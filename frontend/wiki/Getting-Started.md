# Getting Started

FPP spawns server-side bot entities that behave like players - useful for **AFK farms, automated tasks, testing, and NPC simulations**. It is **not** a fake-online-count or player-spoofing tool. Supports both Paper/Purpur and Folia.

## Requirements

- **Server:** Paper/Purpur 1.21+ (up to `1.21.11` and the year-based `26.1.x`–`26.3.x` releases) or Folia
- **Java:** JDK 21+
- **RAM:** 2GB+ recommended for optimal performance
- **Optional soft-depends:** PlaceholderAPI (placeholders), WorldEdit (selection helpers), Spark (`/fpp perf`)
- **Permissions:** any permission manager (e.g. LuckPerms) works with FPP's nodes - there is no built-in LuckPerms integration to configure

## Installation

1. Download `fpp.jar` from [Modrinth](https://modrinth.com/plugin/fake-player-plugin-fpp) or build from source.
2. Drop the JAR into your server's `plugins/` folder.
3. Restart the server.
4. The plugin creates `plugins/FakePlayerPlugin/` with:
   - `config.yml` - main configuration
   - `debug.yml` - debug logging control (all categories disabled by default)
   - `language/en.yml` - messages and translations
   - `bot-names.yml` - reserved-name configuration
   - `bad-words.yml` - profanity filter word list
   - `data/` - SQLite database and persistence files
   - `skins/` - bot skin pools (`main_skin.txt` + `1-<N>%.txt` rarity tiers)
5. Configure permissions (see [Permissions](Permissions)).
6. Run `/fpp reload` to apply most changes without restarting.

## First Steps

1. Spawn a bot: `/fpp spawn` (or `/fpp spawn --name Guard attack --chase` to give it a job immediately).
2. Open the hub: `/fpp menu` - bots, settings, chunk loading, rent, help.
3. Give it work: `/fpp attack <bot>` (AFK mob farm), `/fpp find <bot> ores`, `/fpp clear <bot> --chunk`,
   `/fpp move <bot> --to <player>`, `/fpp left-click <bot> --hold`.
4. Stop everything for a bot with `/fpp stop <bot>`; manage it in `/fpp settings <bot>`.

## Building from Source

Requires JDK 21+ and the Gradle wrapper.

```bash
./gradlew clean shadowJar
```

- Output: `build/libs/fake-player-plugin-<version>-all.jar`
- The runnable plugin task is `shadowJar`; plain `jar` is not the final server plugin.

The build depends on the **paperweight** Paper dev bundle (`26.1.2.build.65-stable`). Gradle downloads this automatically via the Paperweight plugin.

## First Steps

1. Grant yourself admin access: `/lp user <you> permission set fpp.admin true`
2. Spawn a bot: `/fpp spawn`
3. Open its settings: shift+right-click the bot entity
4. Teleport it to you: `/fpp tph <bot>`
5. Make it move: `/fpp move <bot> --coords ~ ~ ~` or follow you: `/fpp move <bot> --to <your name>`
