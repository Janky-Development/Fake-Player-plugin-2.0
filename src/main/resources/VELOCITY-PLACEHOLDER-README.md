# velocity-plugin.json - Placeholder File

⚠️ **IMPORTANT NOTICE** ⚠️

This `velocity-plugin.json` file is a **PLACEHOLDER** for documentation purposes only.

## Why This File Exists

This file is included for:
1. **Future development reference** - if we ever port FPP to Velocity
2. **Template documentation** - shows the metadata structure
3. **Build system compatibility** - some build tools expect this for multi-platform projects

## ❌ This Plugin Does NOT Work on Velocity

**FakePlayerPlugin (`fpp.jar`) is a Paper/Spigot plugin** that uses:
- Bukkit API
- Paper API
- NMS (Net Minecraft Server) internal classes
- Server-side entity spawning
- World manipulation
- Player data containers (PDC)

These APIs are **not available on Velocity** because Velocity is a proxy server, not a game server.

## ✅ For Velocity Proxy Support

If you're running a Velocity proxy network, use the **companion plugins**:

1. **Main Plugin** (`fpp.jar`)
   - Install on your **Paper servers** (backend)
   - Platform: Paper/Spigot/Purpur
   - File: `build/fpp.jar`

2. **Velocity Companion** (`fpp-velocity.jar`)
   - Install on your **Velocity proxy**
   - Platform: Velocity 3.3.0+
   - File: `build/fpp-velocity.jar`
   - Purpose: Makes FPP bots appear in the Velocity server list

3. **BungeeCord Companion** (`fpp-bungee.jar`)
   - Install on your **BungeeCord/Waterfall proxy**
   - Platform: BungeeCord/Waterfall
   - File: `build/fpp-bungee.jar`
   - Purpose: Makes FPP bots appear in the BungeeCord server list

## 📖 More Information

- Wiki: https://fpp.wtf
- GitHub: https://github.com/Pepe-tf/fake-player-plugin
- Modrinth: https://modrinth.com/plugin/fake-player-plugin-fpp
- Discord: https://discord.gg/Q9cd9frzRt

---

**TL;DR:** This file doesn't make the plugin work on Velocity. Use `fpp-velocity.jar` for Velocity proxy support.

