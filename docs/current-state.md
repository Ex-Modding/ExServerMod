# Project Current State

## Summary
The project documentation, gameplay rules, testing workflow, and architecture are fully implemented for **ExServerMod** — a Kotlin-only Fabric mod for Minecraft 26.3+ (on Java 25) implementing Hide & Seek, Mob Disguises, Visual Roulette Wheel, AFK bot control, Wave Defense, Halloween Nightmare Event, and BlueMap stealth synchronization.

## Active Status
**Round 3 (October 2026) — Horde Defense Rework: 11 Nightmare Waves, Raid Bossbar, Permadeath, Custom Mobs & Mansion Anti-Turtle.**

### Round 3 changes
- **Horde 11-Wave Nightmare Progression**: Expanded from 8 to 11 intense waves (~1.5h full gauntlet) ending in an apocalyptic multi-boss finale.
- **Raid-Style Bossbar**: Dynamic `ServerBossEvent` at top of screen tracking current wave ("Wave X of 11") and remaining horde count, color-coded by phase (Yellow countdown, Red combat, Purple boss waves, Green intermission).
- **Hardcore Permadeath**: Death of ANY defender immediately terminates the run. All remaining horde mobs dissolve into vapor/steam particles (`POOF`, `CLOUD`, `SOUL`) with phantom sounds.
- **Custom Mobs & Attacks**:
  - **Blaze Champion**: 120 HP, glowing outline, Hellfire Barrage (3 fireballs), Infernal Heat Wave (piercing walls/doors within 24 blocks).
  - **Witch Champion**: 140 HP, Broom Dash (levitation glide), Potion Volley (slowness, harming, poison, weakness), Coven Curse.
  - **Ghost Creeper**: Explosion radius 0 (0 block damage); on detonation spawns lingering spectral cloud (Darkness, Slowness II, Poison).
  - **Spectral Vexes**: 24 HP, natively phase through walls/ceilings to flush out defenders hiding in mansion rooms.
  - **Dread Champion**: 90 HP, full diamond armor and sword, ground slam shockwaves.
- **Anti-Turtle Island Mansion Mechanics**: Stationary defenders camping inside rooms or closets for > 8s trigger wall-piercing `EvokerFangs` erupting through the floor with a 1.0s audio warning.
- **World Integrity Zero-Grief**: `SmallFireballMixin` intercepts block collisions to prevent fire placement; creepers have explosion radius 0; zombies cannot break doors; champions have 0% drop chances.
- **Bugfixes & Commands**: Added missing `start` command to `/waves` dispatcher, registered `WaveCommands` in mod initialization, added landmark arena binding `/waves start [landmark]`.

### Round 2 changes (October 2025)
- **Roulette/game race fixes**: canceling the wheel (ESC) now sends `RouletteCanceledPayload` and aborts the round instead of silently continuing; starting or continuing a round force-closes stale wheels (`CloseRoulettePayload`) and drops pending disguises; `RouletteManager.checkTimeouts` only applies disguises inside an active round for the current hider.
- **AFK bot (Steve)**: excluded from all mini-games (player picking, 2-player requirement, disconnect cancellation); new `afk/` package with `/afk` command family that teleports the bot to landmarks from the shared `/location-add` list.
- **Hint rework**: unified 45 s rotation (distance every other hint, no repeats — `HintEngine` shuffled bag with boundary guarantee); new varied hints: block under hider, open sky / underground, height difference; MOB categorical hints no longer repeat until all categories are used.
- **Echo locator rework**: after 5 minutes every 30 s a glow-ray pulse is drawn from seeker towards the hider, dissolving 3 blocks short of the target; chat aid shows rough distance + compass direction.
- **Wave Defense (`/waves`)**: new co-op mini-game, 8 vanilla-only waves, champions on boss waves, zero world damage (explosion guard, no door breaking, no gear drops). See [docs/features/wave-defense.md](docs/features/wave-defense.md).
- **i18n**: full Russian (`ru_ru.json`) + English (`en_us.json`) catalogs; hardcoded fallback names and roulette screen strings removed.

### Previous status
**All Tasks Completed (100% Ready)**. Tasks 1 through 6 are fully implemented, verified, and tested with 0 errors.

---

## Implemented Components
- [AGENT.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/AGENT.md): Root guidelines, command reference, strict i18n rule, and constraints.
- [docs/combat-rules-and-troubleshooting.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/combat-rules-and-troubleshooting.md): Operational guide, combat invariants, battle-tested mechanics, and troubleshooting post-mortem.
- [docs/features/hide-and-seek.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/features/hide-and-seek.md): Complete game design specification for Hide & Seek, Mob Mode, Roulette Wheel, Landmarks, and BlueMap stealth integration.
- [docs/development-and-testing.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/development-and-testing.md): Fast local testing guide (sandbox `runClient`, Hot-Reload, JUnit 5).
- [docs/architecture.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/architecture.md): Architecture, language exclusivity (Kotlin), and deployment specs.
- [docs/gameplay-rules.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/gameplay-rules.md): Gameplay invariants (zero griefing, no teleportation, anti-stuck only).
- [docs/decisions/0003-strict-i18n-localization.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/decisions/0003-strict-i18n-localization.md): ADR 0003 enforcing strict `Component.translatable` for all user-facing strings with Russian as default.
- [src/main/resources/assets/exservermod/lang/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/resources/assets/exservermod/lang/):
  - `en_us.json`: Primary default language catalog (Russian text out of the box for fallback).
  - `ru_ru.json`: Complete Russian language translation file.
  - `en_backup.json`: Preserved English translation file for Telegram bot multi-language switching.
- [Taskfile.yml](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/Taskfile.yml): Task pipeline (`task pre-commit`, `task test`, `task build`, `task deploy`).
- [scripts/bump_version.py](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/scripts/bump_version.py): Version bumper.
- [scripts/deploy_exaroton.py](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/scripts/deploy_exaroton.py): Exaroton REST deployer.
- [gradle.properties](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/gradle.properties): Configured for Minecraft 26.2+ and Fabric Kotlin on Java 25.
- [settings.gradle.kts](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/settings.gradle.kts) & [build.gradle.kts](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/build.gradle.kts): Operational build pipeline with Fabric Loom 1.19.2 and Kotlin 2.4.21.
- [src/main/kotlin/com/fome/exservermod/game/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/game/):
  - [HideAndSeekGame.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/game/HideAndSeekGame.kt): Core state machine (IDLE, VOTING, HIDING, SEEKING, INTERMISSION).
  - [GameEventHandler.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/game/GameEventHandler.kt): Fabric event integration with strict i18n, damage immunity, block protection, elytra restrictions, periodic landmark proximity hints, 20-block movement warning, seeker hit detection, and BlueMap stealth synchronization.
  - [GameCommands.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/game/GameCommands.kt): Command `/games` (`vote`, `startsearch`, `continue`, `leave`) with clickable translatable buttons and automatic roulette triggering.
- [src/main/kotlin/com/fome/exservermod/landmarks/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/landmarks/):
  - [Landmark.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/landmarks/Landmark.kt): Landmark model with pure distance-to-perimeter calculation.
  - [LandmarkManager.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/landmarks/LandmarkManager.kt): Persistence via `config/exservermod/landmarks.json` and closest landmark lookup.
  - [LandmarkCommands.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/landmarks/LandmarkCommands.kt): Commands `/location-add`, `/location-remove`, `/location-list` with strict i18n.
- [src/main/kotlin/com/fome/exservermod/disguise/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/disguise/):
  - [MobPool.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/disguise/MobPool.kt): Curated pool of 80+ living mobs with 4-axis trait categorization, ambient sound mapping, and scale calculation formula.
  - [DisguiseManager.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/disguise/DisguiseManager.kt): Mob disguise engine managing hitbox scale (`Attributes.SCALE`), infinite particle-free invisibility, environmental immunity for aquatics, safe anti-stuck relocation, and lifecycle cleanup.
- [src/main/kotlin/com/fome/exservermod/network/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/network/):
  - [OpenRoulettePayload.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/network/OpenRoulettePayload.kt): Custom network payload and stream codec for client roulette triggers.
- [src/main/java/com/fome/exservermod/mixin/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/):
  - [ServerEntityMixin.java](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/disguise/ServerEntityMixin.java): Server packet interception replacing entity spawn packets with disguise mob type for remote players natively.
  - [EntityDimensionsMixin.java](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/disguise/EntityDimensionsMixin.java): Hitbox and eye-height dimension scaling hook.
- [src/main/kotlin/com/fome/exservermod/client/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/client/):
  - [ClientDisguiseHolder.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/client/ClientDisguiseHolder.kt): Client-side 3D disguise renderer and state holder synchronizing interpolation, walk animation, crouching, and equipment without jitter.
  - [RouletteAnimation.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/client/RouletteAnimation.kt): Pure cubic deceleration function (`easeOutCubic`).
  - [RouletteScreen.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/client/RouletteScreen.kt): Client GUI screen with slot spinning animation, click sounds on slot change, toast fanfare, and auto-close.
  - [ExServerModClient.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/client/ExServerModClient.kt): Fabric client entrypoint receiving payloads and opening the roulette screen.
- [src/main/kotlin/com/fome/exservermod/compat/bluemap/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/compat/bluemap/):
  - [BlueMapCompat.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/compat/bluemap/BlueMapCompat.kt): Soft dependency wrapper with safe FabricLoader check.
  - [BlueMapBridge.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/compat/bluemap/BlueMapBridge.kt): Isolated reflection bridge for player visibility on BlueMap.
- [src/test/kotlin/](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/test/kotlin/):
  - [HideAndSeekGameTest.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/test/kotlin/com/fome/exservermod/game/HideAndSeekGameTest.kt): Unit tests for game transitions, roles, and timers.
  - [LandmarkTest.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/test/kotlin/com/fome/exservermod/landmarks/LandmarkTest.kt): Unit tests for perimeter distance calculation, closest search, and JSON persistence.
  - [DisguiseTest.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/test/kotlin/com/fome/exservermod/disguise/DisguiseTest.kt): Unit tests for mob pool, trait categorization, hint keys, ambient sound lookups, and scaling math.
  - [RouletteTest.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/test/kotlin/com/fome/exservermod/RouletteTest.kt): Unit tests for roulette animation and boundary math.
  - [BlueMapCompatTest.kt](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/test/kotlin/com/fome/exservermod/BlueMapCompatTest.kt): Unit tests for safe fallback when BlueMap is absent.

---

## Task Backlog

- [x] **Task 1: Initialize Gradle Build Foundation**
  - Create `settings.gradle.kts` and `build.gradle.kts` using Fabric Loom and Kotlin DSL.
  - Setup ktlint Gradle plugin for `task lint` / `task format`.
  - Generate Gradle wrapper (`gradlew`).
  - Add minimal entrypoint `src/main/kotlin/com/fome/exservermod/ExServerMod.kt` and `fabric.mod.json`.
  - Verify that `task pre-commit` and `task build` pass with 0 errors.
- [x] **Task 2: Hide & Seek Core State Machine (Server-side)**
  - Pure Kotlin state machine with phases (IDLE, VOTING, HIDING 120s, SEEKING, INTERMISSION 60s) and modes (CLASSIC, MOB).
  - Fabric events for damage immunity, block break prevention, elytra restriction, and hit detection.
  - Command `/games` registration with interactive chat buttons.
  - Unit tests covering phases, voting, and role swapping.
- [x] **Task 3: Landmarks & Proximity Hint System**
  - Strict i18n refactoring with ADR 0003 (`Component.translatable` only, `en_us.json`).
  - Landmark model with perimeter distance formula (`max(0.0, centerDist - radius)`).
  - LandmarkManager with JSON persistence (`config/exservermod/landmarks.json`).
  - Commands `/location-add`, `/location-remove`, `/location-list`.
  - Proximity hint broadcast (every 50s) and 20-block movement alert in Hide & Seek.
  - Unit tests in `LandmarkTest.kt`.
- [x] **Task 4: Mob Disguise Engine & Scale Hitbox System**
  - Expanded Mob Pool: all living entities in Minecraft 1.21.1 / 26.2 (80+ mobs, from Bee/Silverfish to Warden/Dragon).
  - Trait categorization for hints: SizeClass (Tiny < 0.8m, Medium, Colossal > 2.4m), Origin (Nether, The End, Aquatic, Overworld), Locomotion (Flying, Terrestrial), Disposition (Passive, Hostile).
  - Dynamic scale adjustment via vanilla `Attributes.SCALE` (`max(mobWidth / 0.6, mobHeight / 1.8)`).
  - Infinite invisibility without particles (`MobEffects.INVISIBILITY`).
  - Environmental immunity: permanent water breathing (`MobEffects.WATER_BREATHING`) and air supply maintenance for aquatic mobs.
  - Automated anti-stuck safeguard relocating trapped players to safe air blocks.
  - Two-tier seeking hint engine:
    - Acoustic taunts every 45s (900 ticks) playing native ambient mob sounds audible within 35-40 blocks.
    - Intellectual categorical hints every 90s (1800 ticks) broadcasting clues to chat via strict i18n keys.
  - Strict stealth invariant: only hider knows their disguise during hiding/seeking; revealed at intermission.
  - State tracking, cleanup on disconnect, intermission, idle, and server stop.
  - Comprehensive unit tests in `DisguiseTest.kt` verifying pool, categorization, timers, and scaling math.
- [x] **Task 5: Visual Roulette Screen & Client Animation**
  - Custom network payload `OpenRoulettePayload` and stream codec for client synchronization.
  - Client mod initialization (`ExServerModClient`) registered in `fabric.mod.json`.
  - Pure cubic deceleration function `RouletteAnimation.easeOutCubic`.
  - `RouletteScreen` rendering via 26.2 `extractRenderState`, playing `UI_BUTTON_CLICK` on slot pass and `UI_TOAST_CHALLENGE_COMPLETE` on stop.
  - Automatic trigger on game start and continuation in Mob mode.
  - Unit tests in `RouletteTest.kt`.
- [x] **Task 6: BlueMap Soft Integration & Stealth Invariant**
  - Isolated reflection bridge (`BlueMapBridge`) avoiding any hard dependencies or class loading crashes.
  - Safe loader check in `BlueMapCompat`.
  - Player visibility hidden during `HIDING` and `SEEKING` phases; restored on `INTERMISSION`, `IDLE`, disconnect, leave, and server stop.
  - Safe fallback unit tests in `BlueMapCompatTest.kt`.
