package com.yourserver.packetguard;

import net.minecraftforge.common.config.Configuration;
import java.io.File;

public class ModConfig {

    public static boolean antiXrayEnabled     = true;
    public static boolean exploitFilterEnabled = true;
    public static boolean reachCheckEnabled   = true;
    public static int     globalFmlRateLimit  = 100; // пакетов/сек на канал, 0 = выключено
    public static boolean strictMode          = false;
    public static int     antiXrayThreads     = 2;

    public static boolean blockEIOTeleport    = true;
    public static boolean blockEIOXpGrab      = true;
    public static boolean blockMachineChaos   = true;
    public static boolean blockCrayfishNuker  = true;
    public static boolean blockFactoryDupe    = true;
    public static boolean blockFactoryRocket  = true;
    public static boolean blockGalacticFire   = true;
    public static boolean blockTurretNuker    = true;
    public static boolean blockRadioHack      = true;
    public static boolean blockMekFire        = true;

    private static Configuration config;

    static void init(File file) {
        config = new Configuration(file);
        load();
    }

    static void load() {
        config.load();
        final String G = "general";
        final String E = "exploit_filter";

        antiXrayEnabled      = config.getBoolean("antiXrayEnabled",      G, true, "Chunk packet obfuscation (anti-xray)");
        exploitFilterEnabled = config.getBoolean("exploitFilterEnabled",  G, true, "Exploit packet filter (master switch for all checks below)");
        reachCheckEnabled    = config.getBoolean("reachCheckEnabled",     G, true, "Block vanilla reach hacks (KillAura, reach cheats) — checks C02/C08 packets");
        globalFmlRateLimit   = config.getInt("globalFmlRateLimit",        G, 100, 0, 10000, "Max FML packets per second per channel (0 = disabled). Catches unknown exploits.");
        strictMode           = config.getBoolean("strictMode",            G, false, "Fail-closed: block packets that fail to parse or trigger a reflection/integration error, instead of letting them through. Off by default to avoid false positives — enable once you've verified the filter behaves on your modpack.");
        antiXrayThreads      = config.getInt("antiXrayThreads",           G, 2, 1, 8, "Worker threads for Anti-XRay chunk (de)compression, kept off the Netty I/O threads.");

        blockEIOTeleport   = config.getBoolean("blockEIOTeleport",   E, true, "Block EIOTeleport (enderio disc=56)");
        blockEIOXpGrab     = config.getBoolean("blockEIOXpGrab",     E, true, "Block EIOXpGrab Short.MAX_VALUE spam (enderio disc=69)");
        blockMachineChaos  = config.getBoolean("blockMachineChaos",  E, true, "Block MachineChaos rate-limit (enderio disc=1/76/96)");
        blockCrayfishNuker = config.getBoolean("blockCrayfishNuker", E, true, "Block CrayfishNuker rate-limit (cfm disc=14)");
        blockFactoryDupe   = config.getBoolean("blockFactoryDupe",   E, true, "Block FactoryDupe (MFReloaded type=20)");
        blockFactoryRocket = config.getBoolean("blockFactoryRocket", E, true, "Block FactoryRocket rate-limit (MFReloaded type=11)");
        blockGalacticFire  = config.getBoolean("blockGalacticFire",  E, true, "Block GalacticFire rate-limit (GalacticraftCore type=7)");
        blockTurretNuker   = config.getBoolean("blockTurretNuker",   E, true, "Block TurretNuker rate-limit (openmodularturrets disc=9)");
        blockRadioHack     = config.getBoolean("blockRadioHack",     E, true, "Block RadioHack rate-limit (DragonsRadioMod disc=0)");
        blockMekFire       = config.getBoolean("blockMekFire",       E, true, "Block MekFire rate-limit (MEK disc=20)");

        if (config.hasChanged()) config.save();
    }
}
