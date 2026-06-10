package com.yourserver.packetguard;

import cpw.mods.fml.relauncher.ReflectionHelper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraftforge.oredict.OreDictionary;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Исходящий Netty-обработчик: подменяет руды на камень в чанк-пакетах.
 *
 * Формат чанк-данных MC 1.7.10 (COLUMNAR layout):
 *   Для numSections секций из primaryBitMap:
 *     [numSections * 4096]  — все block IDs (нижние 8 бит)
 *     [numSections * 2048]  — все metadata (nibble array)
 *     [numSections * 2048]  — все block light (nibble array)
 *     [numSections * 2048]  — все sky light (nibble array, только hasSkyLight=true)
 *   Для numAdds секций из addBitMap:
 *     [numAdds * 2048]      — add nibble — верхние 4 бита block ID (для ID > 255)
 *   [256]                   — biome data
 *
 * ВАЖНО: данные НЕ per-section (не [IDs, meta, light, sky] на секцию подряд).
 * Все IDs идут вместе, затем все metadata, и т.д.
 */
public class AntiXRayHandler extends ChannelOutboundHandlerAdapter {

    static final boolean[] PROTECTED = new boolean[4096];

    private static final int SECTION_BYTES_SKY   = 4096 + 2048 + 2048 + 2048; // 10240 — Overworld
    private static final int SECTION_BYTES_NOSKY = 4096 + 2048 + 2048;         //  8192 — Nether/End

    private final EntityPlayerMP player;

    // -------------------------------------------------------------------------
    // S26 field discovery — сканируем по типу, независимо от SRG-имён
    // -------------------------------------------------------------------------

    private static final Field S26_COMPRESSED;   // byte[]
    private static final Field S26_PRIMARIES;    // int[]
    private static final Field S26_ADDS;         // int[]
    private static final Field S26_LENGTH;       // int (может быть null)

    static {
        Field compressed = null, length = null;
        List<Field> intArrays = new ArrayList<>();

        for (Class<?> cls = S26PacketMapChunkBulk.class; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); } catch (Exception ignored) { continue; }
                Class<?> t = f.getType();
                if (t == byte[].class) {
                    compressed = f;
                } else if (t == int[].class) {
                    intArrays.add(f);
                } else if (t == int.class) {
                    length = f;
                }
            }
        }

        // Порядок int[] в MC 1.7.10: [xCoords, zCoords, primaryBitmaps, addBitmaps]
        int n = intArrays.size();
        S26_COMPRESSED = compressed;
        S26_LENGTH     = length;
        S26_PRIMARIES  = n >= 2 ? intArrays.get(n - 2) : null;
        S26_ADDS       = n >= 2 ? intArrays.get(n - 1) : null;
    }

    AntiXRayHandler(EntityPlayerMP player) {
        this.player = player;
    }

    static void buildProtectedIds() {
        for (int id : new int[]{ 14, 15, 16, 21, 56, 73, 74, 129, 153 }) {
            PROTECTED[id] = true;
        }

        int modded = 0;
        for (String name : OreDictionary.getOreNames()) {
            if (!name.startsWith("ore")) continue;
            for (ItemStack stack : OreDictionary.getOres(name)) {
                if (!(stack.getItem() instanceof ItemBlock)) continue;
                Block block = Block.getBlockFromItem(stack.getItem());
                int id = Block.getIdFromBlock(block);
                if (id > 0 && id < 4096 && !PROTECTED[id]) {
                    PROTECTED[id] = true;
                    modded++;
                    ServerPacketGuard.LOG.info("[AntiXRay] Protected ore: {} id={}", name, id);
                }
            }
        }
        ServerPacketGuard.LOG.info("[AntiXRay] Total modded ores registered: {}", modded);

        if (S26_COMPRESSED != null) {
            ServerPacketGuard.LOG.info("[AntiXRay] S26 fields found — compressed={}.{}, primaries={}, adds={}, length={}",
                S26_COMPRESSED.getDeclaringClass().getSimpleName(), S26_COMPRESSED.getName(),
                S26_PRIMARIES != null ? S26_PRIMARIES.getName() : "NOT FOUND",
                S26_ADDS      != null ? S26_ADDS.getName()      : "NOT FOUND",
                S26_LENGTH    != null ? S26_LENGTH.getName()    : "null (ok)");
        } else {
            ServerPacketGuard.LOG.warn("[AntiXRay] S26 compressed byte[] field NOT FOUND — bulk chunk obfuscation DISABLED");
            ServerPacketGuard.LOG.warn("[AntiXRay] S26PacketMapChunkBulk declared fields:");
            for (Field f : S26PacketMapChunkBulk.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    ServerPacketGuard.LOG.warn("[AntiXRay]   {} {}", f.getType().getSimpleName(), f.getName());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Netty write
    // -------------------------------------------------------------------------

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (ModConfig.antiXrayEnabled) {
            try {
                int sectionBytes = player.worldObj.provider.hasNoSky ? SECTION_BYTES_NOSKY : SECTION_BYTES_SKY;
                if (msg instanceof S21PacketChunkData) {
                    obfuscateS21((S21PacketChunkData) msg, sectionBytes);
                } else if (msg instanceof S26PacketMapChunkBulk) {
                    obfuscateS26((S26PacketMapChunkBulk) msg, sectionBytes);
                }
            } catch (Exception e) {
                ServerPacketGuard.LOG.warn("[AntiXRay] write error for {}: {}", player.getCommandSenderName(), e.toString(), e);
            }
        }
        super.write(ctx, msg, promise);
    }

    // -------------------------------------------------------------------------
    // S21PacketChunkData (один чанк)
    // -------------------------------------------------------------------------

    private void obfuscateS21(S21PacketChunkData packet, int sectionBytes) throws Exception {
        byte[] compressed = ReflectionHelper.getPrivateValue(
                S21PacketChunkData.class, packet, "field_149276_d");
        short primary = ReflectionHelper.getPrivateValue(
                S21PacketChunkData.class, packet, "field_149277_e");
        short add = ReflectionHelper.getPrivateValue(
                S21PacketChunkData.class, packet, "field_149278_f");

        byte[] raw = inflate(compressed);
        obfuscateChunk(raw, 0, primary, add, sectionBytes);
        ReflectionHelper.setPrivateValue(
                S21PacketChunkData.class, packet, deflate(raw), "field_149276_d");
    }

    // -------------------------------------------------------------------------
    // S26PacketMapChunkBulk (пачка чанков при первой загрузке)
    // -------------------------------------------------------------------------

    private void obfuscateS26(S26PacketMapChunkBulk packet, int sectionBytes) throws Exception {
        if (S26_COMPRESSED == null || S26_PRIMARIES == null || S26_ADDS == null) return;

        byte[] data      = (byte[]) S26_COMPRESSED.get(packet);
        int[]  primaries = (int[])  S26_PRIMARIES.get(packet);
        int[]  adds      = (int[])  S26_ADDS.get(packet);

        if (data == null || data.length == 0 || primaries == null || adds == null) return;

        int expectedRaw = 0;
        for (int i = 0; i < primaries.length; i++) {
            expectedRaw += Integer.bitCount(primaries[i]) * sectionBytes
                         + Integer.bitCount(adds[i]) * 2048 + 256;
        }
        if (expectedRaw == 0) return;

        byte[] raw;
        boolean wasCompressed;
        try {
            raw = inflate(data);
            wasCompressed = true;
        } catch (Exception inflateEx) {
            // Crucible хранит S26 несжатым — writePacketData сожмёт сам
            if (data.length != expectedRaw) {
                ServerPacketGuard.LOG.warn("[AntiXRay] S26 size mismatch: data={} expected={} — возможно неверные int[] поля. Пропускаем.", data.length, expectedRaw);
                return;
            }
            raw = data.clone();
            wasCompressed = false;
        }

        if (raw.length != expectedRaw) {
            ServerPacketGuard.LOG.warn("[AntiXRay] S26 inflated size mismatch: raw={} expected={} — пропускаем.", raw.length, expectedRaw);
            return;
        }

        int globalOffset = 0;
        for (int i = 0; i < primaries.length; i++) {
            int sectionCount = Integer.bitCount(primaries[i]);
            int addCount     = Integer.bitCount(adds[i]);
            int chunkRawLen  = sectionCount * sectionBytes + addCount * 2048 + 256;
            obfuscateChunk(raw, globalOffset, (short) primaries[i], (short) adds[i], sectionBytes);
            globalOffset += chunkRawLen;
        }

        if (wasCompressed) {
            byte[] newCompressed = deflate(raw);
            S26_COMPRESSED.set(packet, newCompressed);
            if (S26_LENGTH != null) S26_LENGTH.set(packet, newCompressed.length);
        } else {
            S26_COMPRESSED.set(packet, raw);
        }
    }

    // -------------------------------------------------------------------------
    // Обфускация секций чанка — COLUMNAR layout
    // -------------------------------------------------------------------------

    /**
     * Обфускирует один чанк в буфере raw начиная с chunkBase.
     *
     * MC 1.7.10 хранит данные в columnar layout:
     *   [chunkBase + 0]                              — block IDs (numSections * 4096)
     *   [chunkBase + numSections*4096]               — metadata  (numSections * 2048)
     *   [chunkBase + numSections*(4096+2048)]        — block light
     *   [chunkBase + numSections*(4096+2048+2048)]   — sky light (если hasSkyLight)
     *   [chunkBase + numSections*sectionBytes]       — add nibbles (numAdds * 2048)
     */
    private void obfuscateChunk(byte[] raw, int chunkBase, short primaryBitMap, short addBitMap, int sectionBytes) {
        int numSections = Integer.bitCount(primaryBitMap & 0xFFFF);

        int idsBase       = chunkBase;
        int metaBase      = chunkBase + numSections * 4096;
        // add nibble data начинается сразу после всех primary секций
        int addNibbleBase = chunkBase + numSections * sectionBytes;

        // Для каждого бита addBitMap назначаем последовательный индекс
        int[] addIdxForSection = new int[16];
        int aIdx = 0;
        for (int s = 0; s < 16; s++) {
            addIdxForSection[s] = ((addBitMap & (1 << s)) != 0) ? aIdx++ : -1;
        }

        int sIdx = 0;
        for (int s = 0; s < 16; s++) {
            if ((primaryBitMap & (1 << s)) == 0) continue;

            int blockIdOffset = idsBase  + sIdx * 4096;
            int metaOffset    = metaBase + sIdx * 2048;
            int addOffset     = addIdxForSection[s] >= 0
                                ? addNibbleBase + addIdxForSection[s] * 2048
                                : -1;

            replaceSection(raw, blockIdOffset, metaOffset, addOffset);
            sIdx++;
        }
    }

    /**
     * Заменяет PROTECTED блоки в одной секции на камень (ID=1).
     *
     * @param idBase   смещение block ID массива секции в raw[]
     * @param metaBase смещение nibble-массива metadata секции в raw[]
     * @param addBase  смещение add-nibble массива секции в raw[], или -1 если нет
     */
    private void replaceSection(byte[] raw, int idBase, int metaBase, int addBase) {
        for (int i = 0; i < 4096; i++) {
            int lowByte = raw[idBase + i] & 0xFF;

            int highNibble = 0;
            if (addBase >= 0) {
                int addIdx = addBase + (i >> 1);
                highNibble = (i & 1) == 0
                        ? (raw[addIdx] & 0x0F)
                        : ((raw[addIdx] >> 4) & 0x0F);
            }

            int fullId = (highNibble << 8) | lowByte;

            if (fullId > 0 && fullId < 4096 && PROTECTED[fullId]) {
                raw[idBase + i] = 1;

                // Обнуляем metadata nibble
                int nibbleIdx = metaBase + (i >> 1);
                if ((i & 1) == 0) {
                    raw[nibbleIdx] = (byte)(raw[nibbleIdx] & 0xF0);
                } else {
                    raw[nibbleIdx] = (byte)(raw[nibbleIdx] & 0x0F);
                }

                // Обнуляем add nibble
                if (addBase >= 0) {
                    int addIdx = addBase + (i >> 1);
                    if ((i & 1) == 0) {
                        raw[addIdx] = (byte)(raw[addIdx] & 0xF0);
                    } else {
                        raw[addIdx] = (byte)(raw[addIdx] & 0x0F);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // zlib
    // -------------------------------------------------------------------------

    private static byte[] inflate(byte[] input) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 4);
        byte[] buf = new byte[65536];
        int n;
        while ((n = inf.inflate(buf)) > 0) {
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] input) {
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        def.setInput(input);
        def.finish();
        byte[] buf = new byte[input.length + 64];
        int len = def.deflate(buf);
        def.end();
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }
}
