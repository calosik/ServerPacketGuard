package com.yourserver.packetguard;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = ServerPacketGuard.MODID,
    name = "Server Packet Guard",
    version = "1.0.2",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "1.7.10"
)
public class ServerPacketGuard {

    public static final String MODID = "serverpacketguard";
    public static final Logger LOG = LogManager.getLogger("PacketGuard");

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModConfig.init(event.getSuggestedConfigurationFile());
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        AntiXRayHandler.buildProtectedIds();
        event.registerServerCommand(new SPGCommand());
        LOG.info("=== Server Packet Guard 1.0.2 ===");
        LOG.info("  Exploit filter: {}", ModConfig.exploitFilterEnabled ? "ON" : "OFF");
        LOG.info("  Anti-XRay:      {}", ModConfig.antiXrayEnabled      ? "ON" : "OFF");
        LOG.info("  WorldGuard:     {}", probeWorldGuard());
    }

    private static String probeWorldGuard() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object pm = bukkitClass.getMethod("getPluginManager").invoke(null);
            Object wg = pm.getClass().getMethod("getPlugin", String.class)
                    .invoke(pm, "WorldGuard");
            if (wg == null) return "NOT FOUND (plugin not loaded)";

            // Версия плагина
            Object desc = wg.getClass().getMethod("getDescription").invoke(wg);
            String version = (String) desc.getClass().getMethod("getVersion").invoke(desc);

            // Проверяем наличие WorldEdit Vector (WG 6.x)
            try {
                Class.forName("com.sk89q.worldedit.Vector");
                return "OK v" + version + " (WG 6.x API)";
            } catch (ClassNotFoundException e) {
                return "WARN v" + version + " (WorldEdit Vector не найден — WG 5.x?)";
            }
        } catch (ClassNotFoundException e) {
            return "NOT FOUND (Bukkit API недоступен — не Cauldron/Thermos?)";
        } catch (Exception e) {
            return "ERROR (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        String name = player.getCommandSenderName();

        io.netty.channel.ChannelPipeline pipe =
            player.playerNetServerHandler.netManager.channel().pipeline();

        // Фильтр входящих пакетов-эксплойтов
        String filterName = "spg_filter_" + name;
        if (pipe.get(filterName) == null) {
            pipe.addBefore("packet_handler", filterName, new PacketValidatorHandler(player));
        }

        // Anti-XRay: перехватываем ИСХОДЯЩИЕ чанк-пакеты
        String xrayName = "spg_xray_" + name;
        if (pipe.get(xrayName) == null) {
            // addAfter("packet_handler"): обработчик стоит ПОСЛЕ packet_handler в списке,
            // значит для исходящих (tail→head) он срабатывает ДО encoder — видит объекты Packet
            pipe.addAfter("packet_handler", xrayName, new AntiXRayHandler(player));
        }

        LOG.info("[PacketGuard] Attached to: {}", name);
    }

}
