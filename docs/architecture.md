# Architecture & Project Conventions: ExServerMod

## 1. Core Technology Stack
- **Target Minecraft Version**: **Minecraft 26.3+** (compatible forward with 26.4, etc.).
- **Runtime Environment**: Java 25 LTS (OpenJDK 25).
- **Primary Language**: **Kotlin ONLY** (`.kt`, Kotlin 2.4.21). Mod business logic is written strictly in Kotlin; Java is reserved exclusively for low-level Fabric bytecode mixins (`src/main/java/`).
- **Platform / Loader**: [Fabric Loader](https://fabricmc.net/) (0.20.1+) + [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) (`FLK` 1.14.2+).
- **Target Environment**: Dedicated Minecraft Server (FoMe Server) with optional client-side cosmetic features (Roulette GUI, 1st/3rd person disguise rendering).
- **Build System**: Gradle 9.8.2 with Kotlin DSL (`build.gradle.kts`) and `net.fabricmc.fabric-loom` 1.19.2 (unobfuscated toolchain for Minecraft 26.x+).
- **Task Automation**: [Taskfile.yml](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/Taskfile.yml).
- **Gameplay Invariants & Combat Rules**: [docs/gameplay-rules.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/gameplay-rules.md) and [docs/combat-rules-and-troubleshooting.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/combat-rules-and-troubleshooting.md).

## 2. Code Quality & Verification Gates
- **Strict i18n Localization**: No user-facing hardcoded string literals. All messages, tooltips, and buttons must use `Component.translatable` referencing keys. The primary default language is Russian (`ru_ru.json` and default fallback `en_us.json`), with English preserved in `en_backup.json` for Telegram bot language switching (see [ADR 0003](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/decisions/0003-strict-i18n-localization.md)).
- **Code Style & Linting**: `ktlint` (enforced via `task lint` and `task format`).
- **Testing**:
  - Unit tests with JUnit 5 / Kotlin Test for pure game logic, config parsing, commands, and utility algorithms.
  - Integration/Game tests via Fabric Test API where applicable.
- **Pre-commit Gate**: `task pre-commit` (runs `lint` + `test`). Commit or deployment is prohibited if checks fail.
- **Code Comments**: Strictly English only. Purely raw, technical, and concise. No emojis, stickers, or decorative visuals.

## 3. Versioning Strategy
- Follows Semantic Versioning (`MAJOR.MINOR` or `MAJOR.MINOR.PATCH`).
- Managed centrally in [gradle.properties](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/gradle.properties) via `mod_version`.
- Automated bump workflows:
  - **Minor bump (default)**: `task deploy` / `task deploy:minor` (e.g., `1.0` -> `1.1`).
  - **Major bump**: `task deploy:major` (e.g., `1.x` -> `2.0` for breaking architectural changes).
  - **Patch bump**: `task deploy:patch` (e.g., `1.0.0` -> `1.0.1` for hotfixes).

## 4. Exaroton Deployment Architecture
Deployments run on demand via `task deploy` (or `task deploy:major`):
1. **Version Update**: Script [scripts/bump_version.py](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/scripts/bump_version.py) increments version in `gradle.properties`.
2. **Compilation**: Gradle builds the production `.jar` in `build/libs/`.
3. **Exaroton REST API Integration**:
   - Authentication via Bearer token in `.env` (`EXAROTON_API_TOKEN`).
   - Server identification via `EXAROTON_SERVER_ID`.
   - **Atomic File Replacement**: Script [scripts/deploy_exaroton.py](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/scripts/deploy_exaroton.py) queries remote directory `mods/`, deletes previous versions of this mod (`<mod_id>-*.jar`), and uploads the newly built jar to prevent duplicate jar conflicts.
   - Optional automatic server restart if `EXAROTON_AUTO_RESTART=true`.

## 5. Disguise & Network Packet Architecture
- **Server Packet Interception (`ServerEntityMixin.java`)**:
  - Intercepts `sendPairingData` to broadcast `ClientboundAddEntityPacket` with the disguised mob's `EntityType` and dimensions to all observing clients.
  - Remote players see the actual 3D mob with correct bounding box natively without requiring client-side mods.
- **Client Disguise Synchronization (`ClientDisguiseHolder.kt` & `DisguiseSyncPayload.kt`)**:
  - When disguise state changes, server dispatches `DisguiseSyncPayload` to the disguised player.
  - Client holder mirrors position (`xOld, yOld, zOld`), limb swings (`walkAnimation.position`), equipment, and sneaking pose to eliminate client-side ghosting and model flickering.
- **BlueMap Soft Integration (`BlueMapCompat.kt` & `BlueMapBridge.kt`)**:
  - Isolated reflection bridge toggles player visibility on the web map during hiding and seeking phases, restoring visibility upon round conclusion.
- **Operational & Troubleshooting Guide**:
  - Detailed post-mortem, combat invariants, and fixes are maintained in [docs/combat-rules-and-troubleshooting.md](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/docs/combat-rules-and-troubleshooting.md).
