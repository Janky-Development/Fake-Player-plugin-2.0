# Contributing to FakePlayerPlugin

Thanks for your interest in contributing! Bug fixes, performance work, and feature improvements
are welcome. Please read the policy below first - a few capabilities were **deliberately removed**
from this project and pull requests reintroducing them will be declined regardless of code
quality.

## Deliberately Removed Capabilities (do not reintroduce)

FPP's core design promise is: **a bot must always be identifiable as a bot, and can never pose as
a real person.** The following were removed on purpose to uphold that (and a few for scope/safety
reasons), not because nobody got around to building them:

### 1. Player spoofing / impersonation
Removed entirely (formerly the `fpp-spoof` extension). This includes, in any form:
- Assigning a bot a **real account's UUID** or premium identity. Bot UUIDs are deterministic,
  name-derived, and carry the reserved `fb07` prefix (`BotIdentityCache.deterministicBotUuid`) -
  a range no real account can occupy. Keep it that way.
- **Skin/identity lookup by player name.** Skins come exclusively from the pool system
  (`SkinPoolService`) - never from "give this bot player X's skin/name/identity".
- Removing or making optional the **mandatory "ʙᴏᴛ ʙʏ {owner}" nametag row**, the tab-list
  hiding, the server-list ping exclusion, or the advancement block. These are disclosure
  invariants, not preferences.

### 2. Bot chat
Bots are fully silent in chat (no AI chat, fake chat, join/leave/death messages, or chat relays).
The dormant `chat_enabled`/`chat_tier` persistence columns exist only so old databases load
cleanly - do not build new behavior on them.

### 3. Runtime extension/addon loading
The `ExtensionLoader`, addon registries, extension settings tabs, and the extension API surface
were fully removed. External code must not be loaded into FPP's runtime. If you need to integrate,
do it from a **normal Bukkit plugin** using the events FPP fires (`FppBotAttackEvent`,
`FppBotSettingChangeEvent`, `FppBotBlockBreakEvent`, `FppBotTaskEvent`, …) and the read-only
`FppApi` obtained via `FakePlayerPlugin#getFppApi()`.

### 4. License / DRM verification
The plugin is open source and must start unconditionally. No phone-home license gates.

If a PR touches any of the above, expect it to be closed with a pointer to this file. If you
believe you have a legitimate use case, open a discussion first - don't lead with code.

## Development Setup

- **JDK 21+**, Gradle wrapper included.
- Build: `./gradlew clean shadowJar` → `build/libs/fake-player-plugin-<version>-all.jar`
- Formatting: `./gradlew spotlessApply` (CI-enforced style; run it before committing)

## Pull Request Guidelines

1. One logical change per PR; keep diffs reviewable.
2. `./gradlew spotlessApply shadowJar` must pass locally.
3. Player-facing text goes through `language/en.yml` (small-caps style, `{prefix}`, `#A78BFA`
   accent) - no hardcoded chat strings in Java.
4. New config keys need defaults in `config.yml` plus a note in `frontend/wiki/Configuration.md`.
5. Document behavior changes in `frontend/wiki/Changelog.md`.
6. Folia-safety: schedule entity work through `FppScheduler`'s entity-scoped methods, never raw
   Bukkit schedulers.

## Reporting Issues

Use the GitHub issue tracker. For bot-behavior bugs, include the relevant debug topic output
(`/fpp settings` → 🐛 ᴅᴇʙᴜɢ - e.g. `pathfinding` or `skin-pool`) - those traces are designed to
pinpoint failures.
