package com.yourserver.packetguard;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.ReflectionHelper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;

import java.util.HashMap;
import java.util.Map;

/**
 * Перехватывает FMLProxyPacket до packet_handler в Netty-пайплайне игрока.
 *
 * Форматы пакетов из Utils.bufWriter (Xenobyte):
 *   Integer → writeInt(4 байта)
 *   Byte    → writeByte(1 байт)
 *   Short   → writeShort(2 байта)
 *   Boolean → writeBoolean(1 байт)
 *   int[]   → writeInt * length
 *   Double  → writeDouble(8 байт)
 *   Float   → writeFloat(4 байта)
 *   byte[]  → writeBytes(length байт)
 */
public class PacketValidatorHandler extends ChannelDuplexHandler {

    private static final double MAX_INTERACTION_RANGE = 6.0;
    // Порог для reach-хаков: ванильный максимум ~5 блоков, 7 — с запасом на лаг
    private static final double MAX_REACH_SQ = 7.0 * 7.0;

    private final EntityPlayerMP player;
    // "channel:disc" → [windowStartMs, count]
    private final Map<String, long[]> rateLimits = new HashMap<>();

    public PacketValidatorHandler(EntityPlayerMP player) {
        this.player = player;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FMLProxyPacket) {
            if (!validate((FMLProxyPacket) msg)) {
                ReferenceCountUtil.release(msg);
                return;
            }
        } else if (ModConfig.reachCheckEnabled) {
            if (msg instanceof C08PacketPlayerBlockPlacement) {
                if (!checkBlockPlace((C08PacketPlayerBlockPlacement) msg)) return;
            } else if (msg instanceof C02PacketUseEntity) {
                if (!checkEntityUse((C02PacketUseEntity) msg)) return;
            }
        }
        super.channelRead(ctx, msg);
    }

    private boolean validate(FMLProxyPacket packet) {
        if (!ModConfig.exploitFilterEnabled) return true;
        String channel = packet.channel();
        ByteBuf buf = packet.payload();
        if (buf.readableBytes() == 0) return true;

        if (ModConfig.globalFmlRateLimit > 0
                && !rateLimit("fml:" + channel, ModConfig.globalFmlRateLimit, 1000)) {
            return blockPacket((byte) 0, channel,
                "global FML rate limit (>" + ModConfig.globalFmlRateLimit + "/s)");
        }

        ByteBuf data = buf.copy();
        try {
            byte disc = data.readByte();
            switch (channel) {
                case "enderio":            return checkEnderIO(disc, data);
                case "cfm":               return checkCFM(disc, data);
                case "MFReloaded":        return checkMFR(disc, data);
                case "GalacticraftCore":  return checkGalacticraft(disc, data);
                case "openmodularturrets":return checkTurret(disc, data);
                case "DragonsRadioMod":   return checkRadio(disc, data);
                case "MEK":               return checkMekanism(disc, data);
                default:                  return true;
            }
        } catch (Exception e) {
            return true;
        } finally {
            data.release();
        }
    }

    // -------------------------------------------------------------------------
    // EnderIO
    // -------------------------------------------------------------------------

    private boolean checkEnderIO(byte disc, ByteBuf data) {
        switch (disc) {
            case 56:
                if (!ModConfig.blockEIOTeleport) return true;
                return kick("EIOTeleport exploit (enderio:56)");

            case 96: {
                if (!ModConfig.blockMachineChaos) return true;
                if (!rateLimit("eio:96", 5, 1000)) {
                    return blockPacket(disc, "enderio", "MachineChaos disc96 rate limit (>5/s)");
                }
                if (data.readableBytes() < 14) return true;
                return checkRange(disc, "enderio", data.readInt(), data.readInt(), data.readInt());
            }
            case 1: {
                if (!ModConfig.blockMachineChaos) return true;
                if (!rateLimit("eio:1", 12, 1000)) {
                    return blockPacket(disc, "enderio", "MachineChaos disc1 rate limit (>12/s)");
                }
                if (data.readableBytes() < 12) return true;
                return checkRange(disc, "enderio", data.readInt(), data.readInt(), data.readInt());
            }
            case 76: {
                if (!ModConfig.blockMachineChaos) return true;
                if (!rateLimit("eio:76", 36, 1000)) {
                    return blockPacket(disc, "enderio", "MachineChaos disc76 rate limit (>36/s)");
                }
                if (data.readableBytes() < 12) return true;
                return checkRange(disc, "enderio", data.readInt(), data.readInt(), data.readInt());
            }
            case 69: {
                if (data.readableBytes() < 14) return true;
                int x = data.readInt();
                int y = data.readInt();
                int z = data.readInt();
                short amount = data.readShort();
                if (ModConfig.blockEIOXpGrab && amount == Short.MAX_VALUE) {
                    return blockPacket(disc, "enderio", "EIOXpGrab (Short.MAX_VALUE amount)");
                }
                return checkRange(disc, "enderio", x, y, z);
            }
            default:
                return true;
        }
    }

    // -------------------------------------------------------------------------
    // CrayfishFurnitureMod
    // -------------------------------------------------------------------------

    private boolean checkCFM(byte disc, ByteBuf data) {
        if (disc == 14 && ModConfig.blockCrayfishNuker) {
            if (!rateLimit("cfm:14", 8, 1000)) {
                return blockPacket(disc, "cfm", "CrayfishNuker rate limit (>8/s)");
            }
            if (data.readableBytes() >= 12) {
                return checkRange(disc, "cfm", data.readInt(), data.readInt(), data.readInt());
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // MineFactory Reloaded
    // -------------------------------------------------------------------------

    private boolean checkMFR(byte disc, ByteBuf data) {
        if (disc == 0) {
            if (data.readableBytes() < 6) return true;
            data.readInt(); // worldId
            short type = data.readShort();

            if (type == 20 && ModConfig.blockFactoryDupe) {
                return blockPacket(disc, "MFReloaded", "FactoryDupe (type=20)");
            }
            if (type == 11 && ModConfig.blockFactoryRocket) {
                if (!rateLimit("mfr:rocket", 2, 1000)) {
                    return blockPacket(disc, "MFReloaded", "FactoryRocket rate limit (>2/s)");
                }
            }
            if (data.readableBytes() >= 12) {
                return checkRange(disc, "MFReloaded", data.readInt(), data.readInt(), data.readInt());
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Galacticraft
    // -------------------------------------------------------------------------

    private boolean checkGalacticraft(byte disc, ByteBuf data) {
        if (disc == 0 && ModConfig.blockGalacticFire && data.readableBytes() >= 8) {
            int type = data.readInt();
            if (type == 7) {
                if (!rateLimit("gc:fire", 3, 1000)) {
                    return blockPacket(disc, "GalacticraftCore", "GalacticFire rate limit (>3/s)");
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Open Modular Turrets
    // -------------------------------------------------------------------------

    private boolean checkTurret(byte disc, ByteBuf data) {
        if (disc == 9 && ModConfig.blockTurretNuker) {
            if (!rateLimit("turret:9", 5, 1000)) {
                return blockPacket(disc, "openmodularturrets", "TurretNuker rate limit (>5/s)");
            }
            if (data.readableBytes() >= 12) {
                return checkRange(disc, "openmodularturrets", data.readInt(), data.readInt(), data.readInt());
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Dragons Radio Mod
    // -------------------------------------------------------------------------

    private boolean checkRadio(byte disc, ByteBuf data) {
        if (disc == 0 && ModConfig.blockRadioHack) {
            if (!rateLimit("radio:0", 2, 10000)) {
                return blockPacket(disc, "DragonsRadioMod", "RadioHack rate limit (>2/10s)");
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Mekanism
    // -------------------------------------------------------------------------

    private boolean checkMekanism(byte disc, ByteBuf data) {
        if (disc == 20 && ModConfig.blockMekFire) {
            if (!rateLimit("mek:20", 3, 1000)) {
                return blockPacket(disc, "MEK", "MekFire rate limit (>3/s)");
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Vanilla packet reach checks (universal — any cheat client)
    // -------------------------------------------------------------------------

    private boolean checkBlockPlace(C08PacketPlayerBlockPlacement packet) {
        try {
            // face=255 означает использование предмета в воздухе, без целевого блока
            int face = ReflectionHelper.getPrivateValue(C08PacketPlayerBlockPlacement.class, packet,
                "field_149562_d", "placedBlockDirection");
            if (face == 255) return true;

            int x = ReflectionHelper.getPrivateValue(C08PacketPlayerBlockPlacement.class, packet,
                "field_149559_a", "xPosition");
            int y = ReflectionHelper.getPrivateValue(C08PacketPlayerBlockPlacement.class, packet,
                "field_149560_b", "yPosition");
            int z = ReflectionHelper.getPrivateValue(C08PacketPlayerBlockPlacement.class, packet,
                "field_149561_c", "zPosition");

            double dx = player.posX - (x + 0.5);
            double dy = player.posY - (y + 0.5);
            double dz = player.posZ - (z + 0.5);
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return blockPacket((byte) 0, "vanilla",
                    String.format("block-place reach [%d,%d,%d] dist=%.1f", x, y, z,
                        Math.sqrt(dx * dx + dy * dy + dz * dz)));
            }
        } catch (Exception e) {
            // reflection fail — не блокируем
        }
        return true;
    }

    private boolean checkEntityUse(C02PacketUseEntity packet) {
        try {
            int entityId = ReflectionHelper.getPrivateValue(C02PacketUseEntity.class, packet,
                "field_149567_a", "entityId");
            Entity target = player.worldObj.getEntityByID(entityId);
            if (target == null) return true; // не загружен — не блокируем

            double dx = player.posX - target.posX;
            double dy = player.posY - target.posY;
            double dz = player.posZ - target.posZ;
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return blockPacket((byte) 0, "vanilla",
                    String.format("entity-use reach entity=%d dist=%.1f", entityId,
                        Math.sqrt(dx * dx + dy * dy + dz * dz)));
            }
        } catch (Exception e) {
            // reflection fail — не блокируем
        }
        return true;
    }

    private boolean checkRange(byte disc, String channel, int x, int y, int z) {
        double dx = player.posX - x;
        double dy = player.posY - y;
        double dz = player.posZ - z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > MAX_INTERACTION_RANGE * MAX_INTERACTION_RANGE) {
            return blockPacket(disc, channel, String.format(
                "out-of-range block at [%d,%d,%d] (dist=%.1f)",
                x, y, z, Math.sqrt(distSq)
            ));
        }
        if (!isWorldGuardAllowed(x, y, z)) {
            return blockPacket(disc, channel, String.format(
                "WorldGuard denied at [%d,%d,%d]", x, y, z
            ));
        }
        return true;
    }

    /**
     * Проверяет через WorldGuard 6.x (Bukkit/Cauldron), может ли игрок взаимодействовать
     * с блоком по координатам. Если WG недоступен — пропускает (fail-open).
     *
     * WG 6.x API: getApplicableRegions принимает com.sk89q.worldedit.Vector,
     * а не org.bukkit.Location как в WG 5.x.
     */
    private boolean isWorldGuardAllowed(int x, int y, int z) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");

            Object pm = bukkitClass.getMethod("getPluginManager").invoke(null);
            Object wg = pm.getClass().getMethod("getPlugin", String.class)
                    .invoke(pm, "WorldGuard");
            if (wg == null) return true;

            Object bWorld = bukkitClass.getMethod("getWorld", String.class)
                    .invoke(null, player.worldObj.getWorldInfo().getWorldName());
            Object bPlayer = bukkitClass.getMethod("getPlayer", String.class)
                    .invoke(null, player.getCommandSenderName());
            if (bWorld == null || bPlayer == null) return true;

            Class<?> worldClass       = Class.forName("org.bukkit.World");
            Class<?> bPlayerClass     = Class.forName("org.bukkit.entity.Player");
            Class<?> localPlayerClass = Class.forName("com.sk89q.worldguard.LocalPlayer");
            // WG 6.x использует WorldEdit Vector, а не Bukkit Location
            Class<?> vecClass         = Class.forName("com.sk89q.worldedit.Vector");

            Object rm = wg.getClass().getMethod("getRegionManager", worldClass)
                    .invoke(wg, bWorld);
            if (rm == null) return true;

            Object vec = vecClass
                    .getConstructor(double.class, double.class, double.class)
                    .newInstance((double) x, (double) y, (double) z);

            Object regionSet = rm.getClass()
                    .getMethod("getApplicableRegions", vecClass).invoke(rm, vec);

            Object localPlayer = wg.getClass()
                    .getMethod("wrapPlayer", bPlayerClass).invoke(wg, bPlayer);

            return (Boolean) regionSet.getClass()
                    .getMethod("canBuild", localPlayerClass).invoke(regionSet, localPlayer);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean blockPacket(byte disc, String channel, String reason) {
        ServerPacketGuard.LOG.warn(
            "[PacketGuard] BLOCKED from '{}': channel={} disc={} reason={}",
            player.getCommandSenderName(), channel, disc & 0xFF, reason
        );
        return false;
    }

    private boolean kick(String reason) {
        ServerPacketGuard.LOG.error(
            "[PacketGuard] KICKING '{}': {}",
            player.getCommandSenderName(), reason
        );
        player.playerNetServerHandler.kickPlayerFromServer("§cЗапрещённый пакет: " + reason);
        return false;
    }

    /**
     * Sliding-window rate limiter.
     * @return true если лимит не превышен (пакет разрешён)
     */
    private boolean rateLimit(String key, int maxCount, long windowMs) {
        long now = System.currentTimeMillis();
        long[] state = rateLimits.computeIfAbsent(key, k -> new long[]{now, 0});
        if (now - state[0] > windowMs) {
            state[0] = now;
            state[1] = 0;
        }
        state[1]++;
        return state[1] <= maxCount;
    }
}
