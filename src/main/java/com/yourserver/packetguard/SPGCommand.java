package com.yourserver.packetguard;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import java.util.List;

public class SPGCommand extends CommandBase {

    @Override
    public String getCommandName() { return "spg"; }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/spg reload"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    @SuppressWarnings("unchecked")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "reload") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equals("reload")) {
            sender.addChatMessage(new ChatComponentText("§eUsage: /spg reload"));
            return;
        }

        ModConfig.load();
        ServerPacketGuard.LOG.info("[PacketGuard] Config reloaded by {}", sender.getCommandSenderName());

        sender.addChatMessage(new ChatComponentText("§a[PacketGuard] Config reloaded."));
        sender.addChatMessage(new ChatComponentText(
            "§7  Anti-XRay: "        + flag(ModConfig.antiXrayEnabled) +
            "  §7Exploit filter: "   + flag(ModConfig.exploitFilterEnabled) +
            "  §7Reach check: "      + flag(ModConfig.reachCheckEnabled) +
            "  §7FML rate: "         + (ModConfig.globalFmlRateLimit > 0
                                        ? "§a" + ModConfig.globalFmlRateLimit + "/s"
                                        : "§cOFF")));
        if (ModConfig.exploitFilterEnabled) {
            sender.addChatMessage(new ChatComponentText(
                "§8  EIOTeleport="   + flag(ModConfig.blockEIOTeleport)   +
                " §8EIOXpGrab="     + flag(ModConfig.blockEIOXpGrab)     +
                " §8MachineChaos="  + flag(ModConfig.blockMachineChaos)));
            sender.addChatMessage(new ChatComponentText(
                "§8  CrayfishNuker=" + flag(ModConfig.blockCrayfishNuker) +
                " §8FactoryDupe="   + flag(ModConfig.blockFactoryDupe)   +
                " §8FactoryRocket=" + flag(ModConfig.blockFactoryRocket)));
            sender.addChatMessage(new ChatComponentText(
                "§8  GalacticFire=" + flag(ModConfig.blockGalacticFire)  +
                " §8TurretNuker="  + flag(ModConfig.blockTurretNuker)   +
                " §8RadioHack="    + flag(ModConfig.blockRadioHack)     +
                " §8MekFire="      + flag(ModConfig.blockMekFire)));
        }
    }

    private static String flag(boolean on) {
        return on ? "§aON" : "§cOFF";
    }
}
