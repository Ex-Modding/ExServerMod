# Полный реестр ошибок, багов и уязвимостей проекта ExServerMod

> [!IMPORTANT]
> Аудит проведён на базе анализа исходного кода веток `feat/halloween-event-and-fixes` и `ao/minecraft-fome-1` в сравнении с `main` (версия 1.5).
> Все найденные дефекты классифицированы по 5 категориям критичности и снабжены точными ссылками на код, описанием проблемы и готовым решением.

---

## 📑 Оглавление
1. [Сборка и окружение (Build & Environment)](#1-сборка-и-окружение-build--environment)
2. [Безопасность и чит-эксплойты (Security & Exploits)](#2-безопасность-и-чит-эксплойты-security--exploits)
3. [Гриферство мира и целостность (World Griefing & ADR 0002)](#3-гриферство-мира-и-целостность-world-griefing--adr-0002)
4. [Игровая логика, AI и риск крашей (Game Logic, AI & Crashes)](#4-игровая-логика-ai-и-риск-крашей-game-logic-ai--crashes)
5. [Архитектура, миксины и локализация (Architecture & ADR 0003)](#5-архитектура-миксины-и-локализация-architecture--adr-0003)
6. [Производительность и сетевые пакеты (Performance & Networking)](#6-производительность-и-сетевые-пакеты-performance--networking)

---

## 1. Сборка и окружение (Build & Environment)

### 🔴 BUG-01: Захардкоженные абсолютные пути Linux-пользователя в `gradle.properties`
- **Файл:** [`gradle.properties#L7-L8`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/gradle.properties#L7-L8)
- **Суть проблемы:** В коммите `a247b14` добавлены пути:
  ```properties
  org.gradle.java.home = /usr/lib/jvm/java-26-openjdk
  org.gradle.java.installations.paths = /home/bogdan/.local/share/PrismLauncher/java/java-runtime-epsilon
  ```
  Это пути файловой системы Linux конкретного разработчика `bogdan`. На macOS, Windows, CI и машинах других участников запуск `./gradlew test` или `./gradlew build` падает с фатальной ошибкой: `Value '/usr/lib/jvm/java-26-openjdk' given for org.gradle.java.home is invalid`.
- **Исправление:** Удалить эти строки из репозитория. Локальные пути должны настраиваться в персональном `~/.gradle/gradle.properties` или через системную переменную `JAVA_HOME`.

---

## 2. Безопасность и чит-эксплойты (Security & Exploits)

### 🚨 SEC-01: Полное отсутствие проверки прав на чит-команду `/event givecandy`
- **Файл:** [`HalloweenCommands.kt#L94-L137`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenCommands.kt#L94-L137)
- **Суть проблемы:** Команда `/event setcandy` имеет проверку прав оператора (`.requires`), а `/event givecandy` — **нет**. Любой обычный игрок режима выживания может ввести:
  ```
  /event givecandy 1000000
  ```
  и мгновенно выдать себе миллион конфет, сломав всю экономику и скупив у торговца все тотемы, зачарованные яблоки и незерит.
- **Исправление:** Добавить `.requires { it.hasPermission(2) }` для узла команды `givecandy`, а также для `start`, `stop`, `skip` и `spawnmerchant`.

### 🚨 SEC-02: Спуфинг пакета `SubmitGhostNamePayload` (Бесплатный призыв питомца-призрака)
- **Файл:** [`HalloweenEvent.kt#L708-L711`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L708-L711), [`#L788-L827`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L788-L827)
- **Суть проблемы:** Серверный обработчик `handleGhostNaming` вызывается при получении пакета `SubmitGhostNamePayload(petName)`. Сервер **не проверяет**, покупал ли игрок питомца и списались ли конфеты! Любой модифицированный клиент может отправить этот пакет в любой момент и получить питомца бесплатно (обход стоимости 500 конфет).
- **Исправление:** Хранить токен ожидания призыва на сервере (`pendingPetSummons: MutableSet<UUID>`). Добавлять игрока туда только после успешного списания конфет в `handleMerchantPurchase`, и удалять при успешном призыве.

### 🚨 SEC-03: Дистанционная покупка вне торговца и товаров не из пула (`MerchantBuyPayload`)
- **Файл:** [`HalloweenEvent.kt#L703-L738`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L703-L738)
- **Суть проблемы:** 
  1. Нет проверки расстояния до [`HalloweenMerchantEntity`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/entity/HalloweenMerchantEntity.kt): игрок может покупать товары удалённо из любого измерения (даже из Незера/Энда).
  2. Нет проверки фазы ивента: покупка работает во время фазы `IDLE`, когда торговца в мире вообще нет.
  3. Нет проверки `activeMerchantWares.contains(itemKey)`: проверяется только наличие цены в общем конфиге `config.prices[itemKey]`. Игрок может купить незеритовый слиток, даже если торговец в текущей ротации его не продаёт.
- **Исправление:** Валидировать на сервере: фаза $\neq$ `IDLE`, сущность торговца существует в радиусе $\le 8$ блоков от игрока, а `itemKey` входит в `activeMerchantWares`.

### 🚨 SEC-04: Потеря купленного предмета при заполненном инвентаре
- **Файл:** [`HalloweenEvent.kt#L746-L776`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L746-L776)
- **Суть проблемы:** Вызов `player.addItem(stack)` при полностью забитом инвентаре может приводить к исчезновению ценного предмета (незерит, тотем, шалкер), при этом конфеты уже списаны.
- **Исправление:** Проверять свободное место или использовать `if (!player.inventory.add(stack)) { player.drop(stack, false) }`.

---

## 3. Гриферство мира и целостность (World Griefing & ADR 0002)

### 🔴 GRIEF-01: Уничтожение построек и сундуков игрока блоками света (`Blocks.LIGHT`)
- **Файл:** [`GhostPetEntity.kt#L98-L128`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/entity/GhostPetEntity.kt#L98-L128)
- **Суть проблемы:**
  ```kotlin
  val isWater = sLevel.getFluidState(targetPos).type == Fluids.WATER || currentState.block == Blocks.WATER
  if (currentState.isAir || isWater || currentState.`is`(Blocks.LIGHT)) {
      val lightState = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 10).setValue(LightBlock.WATERLOGGED, isWater)
      sLevel.setBlockAndUpdate(targetPos, lightState)
  ```
  Если призрак пролетает сквозь **затопленный сундук, ступени, полублоки, водоросли, морскую траву** (где `isWater == true`), ванильный блок игрока **заменяется на блок света**! При удалении света ставится `Blocks.WATER`, безвозвратно уничтожая постройки и контейнеры игрока. Это прямое нарушение ADR 0002.
- **Исправление:** Призрак не должен изменять физические блоки мира. Модель уже имеет яркость 15 в [`GhostPetRenderer.kt`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/client/GhostPetRenderer.kt#L28). Либо ставить свет **строго** при `currentState.isAir` и никогда в воде/жидкостях.

### 🔴 GRIEF-02: Вечные невидимые блоки света при краше сервера или выгрузке чанка
- **Файл:** [`GhostPetEntity.kt#L118-L128`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/entity/GhostPetEntity.kt#L118-L128), [`CandyEntity.kt#L224-L233`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/entity/CandyEntity.kt#L224-L233)
- **Суть проблемы:** Если сервер выключается аварийно или чанк с призраком/конфетой выгружается без вызова `remove()`, невидимые блоки `Blocks.LIGHT` навечно остаются висеть в воздухе выживания SMP, мешая строительству.
- **Исправление:** Отказаться от физической установки блоков света в пользу клиентского рендеринга частиц или динамического освещения.

---

## 4. Игровая логика, AI и риск крашей (Game Logic, AI & Crashes)

### 🔴 BUG-02: Бесконечный цикл рёва и зависание Вардена на месте («Roar-Lock»)
- **Файл:** [`WaveManager.kt (ветка ao)#L1015-L1019`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1015-L1019)
- **Суть проблемы:** Вызов `mob.increaseAngerAt(nearest, 150, false)` каждую секунду (20 тиков) сбрасывает поведение Вардена в 84-тиковую (4.2 секунды) анимацию рёва. Варден непрерывно ревёт, замирает на месте и не преследует игроков в ближнем бою.
- **Исправление:** Использовать архитектуру из `main` (версия 1.5): [`WardenMixin.java`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/WardenMixin.java) и [`SonicBoomMixin.java`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/SonicBoomMixin.java), отключающие слепоту и звуковой удар, с прямым заданием цели атаки.

### 🔴 BUG-03: Кик игрока с сервера из-за `NaN`-вектора скорости при атаке Dread Slam
- **Файл:** [`WaveManager.kt (ветка ao)#L1288-L1292`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1288-L1292)
- **Суть проблемы:**
  ```kotlin
  val dx = defender.x - champion.x
  val dz = defender.z - champion.z
  val horiz = Vec3(dx, 0.0, dz).normalize().scale(0.8)
  defender.push(horiz.x, 0.55, horiz.z)
  ```
  Если игрок находится на тех же горизонтальных координатах, что и босс (`dx == 0.0, dz == 0.0`), `normalize()` выдаёт вектор `(NaN, NaN, NaN)`. Передача `NaN` в скорость игрока приводит к кику игрока сервером за «аномалию движения» или крашу сетевого трекера.
- **Исправление:** Добавить проверку `if (dx * dx + dz * dz < 1e-4) Vec3.ZERO else Vec3(dx, 0.0, dz).normalize().scale(0.8)`.

### 🔴 BUG-04: Застревание и удушение Рыцаря Погибели в блоках при телепортации (`triggerDreadShadowstep`)
- **Файл:** [`WaveManager.kt (ветка ao)#L1313-L1318`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1313-L1318)
- **Суть проблемы:** Телепортация босса за спину игрока на 1.5 блока не проверяет наличие твёрдых блоков. Если игрок прижался спиной к стене арены/особняка, босс телепортируется внутрь камня/дерева и задыхается насмерть.
- **Исправление:** Проверять коллизию через `level.noCollision(champion, champion.boundingBox.move(behindX - champion.x, ...))`.

### 🔴 BUG-05: Мёртвый таймер анти-кемпинга (`antiTurtleTimer`)
- **Файл:** [`WaveManager.kt (ветка ao)#L1213-L1228`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1213-L1228)
- **Суть проблемы:** Переменная `antiTurtleTimer` объявлена и проверяется (`if (antiTurtleTimer >= 280)`), но нигде в `tickAbilities` или тиковом цикле **не инкрементируется** (`antiTurtleTimer++` отсутствует). Условие никогда не выполняется — механика пробития кемперов челюстями мертва.
- **Исправление:** Добавить `antiTurtleTimer++` в начало `tickAbilities`.

### 🔴 BUG-06: Иммунитет кемпера к челюстям Эвокера из-за подмены владельца
- **Файл:** [`WaveManager.kt (ветка ao)#L1725-L1734`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1725-L1734)
- **Суть проблемы:** Если мобов на арене нет, владельцем `EvokerFangs` назначается `defender`. Ванильные челюсти никогда не наносят урон своему владельцу (`dealer != target`). В итоге защитник получает 100% иммунитет к челюстям.
- **Исправление:** Передавать `null` в качестве владельца челюстей при отсутствии моба.

### 🔴 BUG-07: Инфляция экономики волн (200 конфет за каждого моба на босс-волнах)
- **Файл:** [`WaveManager.kt (ветка halloween)#L485-L491`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L485-L491)
- **Суть проблемы:**
  ```kotlin
  val isBossOrChamp = role == WaveMobRole.CHAMPION || ... || WaveDefenseGame.isBossWave(game.currentWave)
  val candyAmount = if (isBossOrChamp) 200 else HalloweenEvent.config.mobCandyDrop
  ```
  Из-за проверки `isBossWave` **все 25+ рядовых зомби, скелетов и криперов** волн 5, 8 и 11 дают по 200 конфет! За одну волну игрок получает более 5 000 конфет.
- **Исправление:** Удалить `WaveDefenseGame.isBossWave(game.currentWave)`. 200 конфет выдавать строго за `role != WaveMobRole.STANDARD`.

### 🔴 BUG-08: Бесконечное размножение питомцев-призраков при входе на сервер
- **Файл:** [`HalloweenEvent.kt#L847-L865`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L847-L865)
- **Суть проблемы:** При входе игрока вызывается `lvl.getEntity(petUuid)`. Если питомец остался в **непрогруженном чанке**, метод возвращает `null`. Сервер считает, что питомец потерян, и спавнит нового призрака. Когда игрок возвращается на базу, старый чанк прогружается, и питомцы двоятся/троятся.
- **Исправление:** Привязывать питомца к `ServerPlayer` и не спавнить дубликат, если `petUuid` сохранён в `halloween_ghost_pets.json`, а телепортировать сущность при прогрузке чанка.

### 🔴 BUG-09: Сброс игровых дней мира на День 0 при фиксации ночи
- **Файл:** [`HalloweenEvent.kt#L675, #L691`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L675)
- **Суть проблемы:** Вызов `server.clockManager().setTotalTicks(clock, 18000L)` принудительно сбрасывает счётчик тиков мира на 18 000, откатывая день SMP назад на 0-й день и ломая ванильную статистику мира.
- **Исправление:** Продвигать время вперёд до ближайшей ночи, сохраняя номер дня:
  ```kotlin
  val current = server.clockManager().getTotalTicks(clock)
  val dayTime = current % 24000L
  val needed = if (dayTime <= 18000L) 18000L - dayTime else (24000L - dayTime) + 18000L
  server.clockManager().setTotalTicks(clock, current + needed)
  ```

### 🔴 BUG-10: Краш потока тиков от `ArrayIndexOutOfBoundsException` при переполнении `potionIndex`
- **Файл:** [`WaveManager.kt (ветка ao)#L1682-L1685`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1682-L1685)
- **Суть проблемы:** `potions[potionIndex % potions.size]`. При переполнении 32-битного `Int` (после `Int.MAX_VALUE`) остаток от деления в Kotlin становится **отрицательным** (`-1 % 4 == -1`), вызывая падение сервера.
- **Исправление:** Использовать `potionIndex.mod(potions.size)`.

### 🔴 BUG-11: Мгновенный урон сквозь стены без телеграфа у Луча Ведьмы
- **Файл:** [`WaveManager.kt (ветка ao)#L1481-L1505`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1481-L1505)
- **Суть проблемы:** 14 единиц чистого магического урона (7 сердец) наносятся в тот же тик, когда появляется сообщение в Action Bar, сквозь любые стены без проверки видимости. В режиме с одной жизнью (пермадез) это приводит к моментальной смерти игроков без шанса увернуться.
- **Исправление:** Добавить 15–20 тиков звукового и визуального заряда луча перед нанесением урона.

---

## 5. Архитектура, миксины и локализация (Architecture & ADR 0003)

### 🔴 ARCH-01: Массовое нарушение ADR 0003 (Сырые строки и цвета `§` вместо i18n)
- **Файлы:**
  - [`GhostPetEntity.kt#L64, #L68`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/entity/GhostPetEntity.kt#L64) (`§bПризрак ждет вас на этом месте`)
  - [`HalloweenCommands.kt#L53, #L66, #L112`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenCommands.kt#L53) (`§cСбор конфет сейчас не идет!`, `§cУкажите целевого игрока!`)
  - [`WaveManager.kt (ветка ao)#L1265, #L1308, #L1577`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1265) (`§c⚠ [!] Рыцарь Погибели...`, `§6⚠ [!] Испепелитель...`)
- **Суть проблемы:** Прямое нарушение инварианта проекта ADR 0003 ("Strict i18n Localization via Translatable Components"). Все строки должны быть ключами в [`en_us.json`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/resources/assets/exservermod/lang/en_us.json) и [`ru_ru.json`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/resources/assets/exservermod/lang/ru_ru.json).

### 🔴 ARCH-02: Сильное дублирование и конфликт миксинов текстур (Мёртвый код)
- **Файлы:**
  - [`EntityRendererBossTextureMixin.java`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/EntityRendererBossTextureMixin.java)
  - [`LivingEntityRendererExtractStateMixin.java`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/LivingEntityRendererExtractStateMixin.java)
  - [`LivingEntityRendererBossTextureMixin.java`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/LivingEntityRendererBossTextureMixin.java)
  - [`BlazeRendererBossTextureMixin.java`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/java/com/fome/exservermod/mixin/BlazeRendererBossTextureMixin.java), `WardenRenderer...`, `WitchRenderer...`, `ZombieRenderer...`
- **Суть проблемы:**
  1. `EntityRendererBossTextureMixin` и `LivingEntityRendererExtractStateMixin` делают одно и то же в `extractRenderState`, перезаписывая текстуру друг за другом.
  2. `@Redirect` в `LivingEntityRendererBossTextureMixin` полностью перехватывает получение текстуры, из-за чего 4 индивидуальных миксина сущностей вообще никогда не вызываются (мёртвый код).
- **Исправление:** Удалить `EntityRendererBossTextureMixin` и 4 индивидуальных миксина, оставить чистый `LivingEntityRendererExtractStateMixin` + `LivingEntityRendererBossTextureMixin` без агрессивных `@Redirect`.

### 🟠 ARCH-03: Ложное срабатывание текстур боссов на обычных мобах (`BossCustomTextures.kt`)
- **Файл:** [`BossCustomTextures.kt#L19-L48`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/client/BossCustomTextures.kt#L19-L48)
- **Суть проблемы:** Проверка `key.contains("blaze") || fullString.contains("blaze")` срабатывает на любого ифрита или любого моба с биркой "blaze". Обычный моб в выживании получает текстуру Испепелителя.
- **Исправление:** Сравнивать точный translation key: `key == "exservermod.waves.mob.blaze_champion"`.

---

## 6. Производительность и сетевые пакеты (Performance & Networking)

### 🔴 PERF-01: Синхронная запись JSON на диск на главном тиковом потоке
- **Файл:** [`HalloweenBalanceManager.kt#L31, #L44, #L65, #L83`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenBalanceManager.kt#L31)
- **Суть проблемы:** При каждом подборе конфеты игроком и каждом убийстве моба вызывается `save()`, который синхронно делает `storageFile.writeText(...)`. Во время активного фарма десятками игроков это вызывает микрофризы тиков сервера (TPS drop).
- **Исправление:** Добавить флаг `isDirty = true` и сохранять балансы асинхронно раз в 30 секунд и при выключении сервера.

### 🔴 PERF-02: Полное сканирование всех сущностей во всех мирах в `hasActiveGhostPet`
- **Файл:** [`HalloweenEvent.kt#L129-L137`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/HalloweenEvent.kt#L129-L137)
- **Суть проблемы:** При отсутствии питомца в кеше сервер выполняет вложенный цикл:
  ```kotlin
  for (lvl in server.allLevels.toList()) {
      for (entity in lvl.allEntities) { ... }
  }
  ```
  Это перебирает тысячи сущностей при каждом открытии интерфейса магазина каждым игроком.
- **Исправление:** Использовать только мапу `playerPets: ConcurrentHashMap<UUID, UUID>`.

### 🟠 PERF-03: Спам сетевых пакетов частиц при атаках лучом (до 72 пакетов за тик)
- **Файл:** [`WaveManager.kt (ветка ao)#L1493-L1497`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/waves/WaveManager.kt#L1493-L1497)
- **Суть проблемы:** `level.sendParticles` вызывается в цикле с шагом в 1 блок. За один тик создаётся и отправляется по сети до 72 пакетов `ClientboundLevelParticlesPacket`, забивая исходящий канал сервера.
- **Исправление:** Шагать по лучу с интервалом 2.5–3 блока.

### 🟠 PERF-04: Тяжёлые вызовы `getHeight()` и поиск игроков каждый тик у каждого Demon Eye
- **Файл:** [`DemonEyeEntity.kt#L178-L189`](file:///Users/soroqn/me/private-code/Minecraft-FoMe/ExServerMod/src/main/kotlin/com/fome/exservermod/halloween/entity/DemonEyeEntity.kt#L178-L189)
- **Суть проблемы:** Каждый глаз каждый тик опрашивает карту высот `sLevel.getHeight()` и фильтрует список всех игроков мира.
- **Исправление:** Обновлять цель и высоту раз в 10–20 тиков.

---

## 📊 Сводная таблица по приоритетам

| ID | Уязвимость / Баг | Категория | Влияние |
| :--- | :--- | :--- | :--- |
| **BUG-01** | Пути Linux в `gradle.properties` | Build | Билд не собирается на Mac/CI |
| **SEC-01** | `/event givecandy` без проверки прав | Security | Чит на бесконечные конфеты |
| **SEC-02** | Бесплатный призыв призрака через пакет | Security | Обход покупки питомца |
| **SEC-03** | Удалённая покупка в магазине из любого мира | Security | Обход дистанции и ротации товаров |
| **GRIEF-01** | Замена блоков игрока на `Blocks.LIGHT` | Integrity | Разрушение сундуков и построек |
| **BUG-07** | 200 конфет за каждого моба на босс-волнах | Economy | Обесценивание магазина за 1 волну |
| **BUG-02** | Зависание Вардена в рёве (Roar-lock) | Gameplay | Варден стоит на месте |
| **BUG-03** | NaN-вектор в Dread Slam | Crash | Кик игрока с сервера |
| **BUG-08** | Размножение призраков при перезаходе | Gameplay | Двоение сущностей в мире |
| **BUG-09** | Сброс дней мира на День 0 | Gameplay | Сброс статистики SMP |
| **PERF-01** | Синхронный `save()` файла на главном потоке | Performance | Микрофризы и лаги сервера |
| **ARCH-01** | Сырые строки `§` в Action Bar и чате | i18n | Нарушение ADR 0003 |
