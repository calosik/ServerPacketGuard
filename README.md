# ServerPacketGuard

A server-side Forge mod for Minecraft 1.7.10 that protects modded servers against cheat clients. Blocks X-ray exploits via chunk packet obfuscation and filters known exploit packets from malicious mods.

**Mod ID:** `serverpacketguard` | **Version:** 1.0.2 | **MC:** 1.7.10 | **Side:** Server only

---

## Features

### Anti-XRay
Intercepts outgoing chunk packets at the Netty layer and replaces ore block IDs with stone before the data reaches the client. Since cheat clients like Xenobyte use `world.getBlock()` on the locally stored chunk data, they only see stone — the X-ray module shows nothing useful.

- Protects vanilla ores: Diamond, Gold, Iron, Coal, Redstone, Lapis Lazuli, Emerald
- Auto-discovers modded ores via `OreDictionary` (any registered entry with name starting with `"ore"`)
- Handles both `S21PacketChunkData` (single chunk) and `S26PacketMapChunkBulk` (bulk load)
- Compatible with Crucible servers (handles uncompressed chunk data)

### Exploit Packet Filter
Intercepts incoming `FMLProxyPacket` messages before the game processes them. Each check can be individually toggled in config.

| Exploit | Channel | Packet | Action |
|---|---|---|---|
| EIO Teleport | `enderio` | disc=56 | Kick |
| EIO XP Grab | `enderio` | disc=69 | Block + range check |
| Machine Chaos | `enderio` | disc=1/76/96 | Rate limit (5–36/s) |
| Crayfish Nuker | `cfm` | disc=14 | Rate limit (8/s) |
| MFR Duplication | `MFReloaded` | type=20 | Kick |
| MFR Rocket Spam | `MFReloaded` | type=11 | Rate limit (2/s) |
| Galacticraft Fire | `GalacticraftCore` | type=7 | Rate limit (3/s) |
| Turret Nuker | `openmodularturrets` | disc=9 | Rate limit (5/s) |
| Dragons Radio Hack | `DragonsRadioMod` | disc=0 | Rate limit (2/10s) |
| Mekanism Fire | `MEK` | disc=20 | Rate limit (3/s) |

### Reach Check
Validates `C02PacketUseEntity` and `C08PacketPlayerBlockPlacement` packets against the player's actual position. Blocks interactions beyond 7.0 blocks (vanilla max is ~5 blocks; the margin accounts for lag).

### Global FML Rate Limit
Optional per-channel packet rate limiter applied to all incoming FML packets. Default: 100 packets/second. Set to `0` to disable.

### WorldGuard Integration
When running on Cauldron/Thermos with WorldGuard 6.x present, the exploit filter additionally checks whether the interacting player has build permission at the target location before passing packets through. Uses reflection — fails open if WorldGuard is unavailable.

### Admin Command
```
/spg reload
```
Hot-reloads the configuration file without restarting the server. Requires operator level 2. Prints the current state of all feature flags to chat.

---

## Installation

1. Install [Cauldron](https://sourceforge.net/projects/cauldron-unofficial/files/) or [Thermos](https://github.com/CyberdyneCC/Thermos) for Minecraft 1.7.10.
2. Drop `ServerPacketGuard-1.0.2.jar` into the `mods/` folder.
3. Start the server — a config file is generated at `config/serverpacketguard.cfg`.

> **Server-side only.** Clients do not need this mod installed. `acceptableRemoteVersions = "*"` is set so clients without the mod can connect normally.

---

## Configuration

Config file: `config/serverpacketguard.cfg`

```properties
# General
antiXrayEnabled         = true   # Chunk packet ore obfuscation
exploitFilterEnabled    = true   # Master switch for all exploit checks
reachCheckEnabled       = true   # C02/C08 reach validation
globalFmlRateLimit      = 100    # Max FML packets/s per channel (0 = off)

# Exploit-specific (all default true)
blockEIOTeleport        = true
blockEIOXpGrab          = true
blockMachineChaos       = true
blockCrayfishNuker      = true
blockFactoryDupe        = true
blockFactoryRocket      = true
blockGalacticFire       = true
blockTurretNuker        = true
blockRadioHack          = true
blockMekFire            = true
```

After editing, run `/spg reload` to apply changes without a restart.

---

## How It Works

### Anti-XRay Pipeline

```
Client ← [AntiXRayHandler] ← [packet_handler] ← Server
```

`AntiXRayHandler` is a `ChannelOutboundHandlerAdapter` injected **after** `packet_handler` in the Netty pipeline. It sees fully encoded `S21`/`S26` packets, decompresses the chunk data with `Inflater`, iterates every block position in each active section, replaces protected ore IDs with stone (`ID=1`), zeroes out their metadata and add-nibbles, then recompresses with `Deflater` at `BEST_SPEED` level.

### Exploit Filter Pipeline

```
[PacketValidatorHandler] → [packet_handler] → Server
Client →
```

`PacketValidatorHandler` is a `ChannelDuplexHandler` injected **before** `packet_handler`. It reads the `FMLProxyPacket` channel tag and `discriminator` byte, applies the relevant check, and either calls `super.channelRead()` (pass) or swallows the message and optionally disconnects the player.

### Per-Player Injection

Both handlers are injected into each player's individual Netty channel on `PlayerLoggedInEvent`:

```
[spg_filter_<player>] → [packet_handler] → [spg_xray_<player>]
```

---

## Building

Requires Java 8 and Gradle.

```bash
./gradlew build
```

Output jar: `build/libs/ServerPacketGuard-1.0.2.jar`

Uses [anatawa12's ForgeGradle](https://github.com/anatawa12/ForgeGradle-1.2) fork for Forge 1.7.10 compatibility.

---

## Compatibility

| Software | Status |
|---|---|
| Cauldron 1.7.10 | Supported |
| Thermos 1.7.10 | Supported |
| Crucible 1.7.10 | Supported (uncompressed chunk data handled) |
| WorldGuard 5.x | Detected, WG checks skipped (API mismatch) |
| WorldGuard 6.x | Full integration |
| Vanilla Forge server | Supported (no WorldGuard features) |
