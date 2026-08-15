# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

JSettlers is a Java remake of *The Settlers 3* (Gradle multi-module, desktop + Android). Most AI/gameplay logic lives in `jsettlers.logic`. This file focuses on the non-obvious architecture and the workflow traps that waste time if you don't know them.

## Build / test / run

- **JDK 17** (the code targets Java 11 source-compat, but the Gradle toolchain builds with JDK 17). Always pass `--no-daemon` for test/CI-style runs — background daemons from earlier runs otherwise starve later ones and look like regressions.
- Compile logic only (fast feedback): `./gradlew :jsettlers.logic:compileJava :jsettlers.logic:compileTestJava --no-daemon`
- Run a single test: `./gradlew :jsettlers.logic:test --tests 'jsettlers.integration.ai.ColonizationIT' --no-daemon --info`
- Desktop entry point: `jsettlers.main.swing/.../SwingManagedJSettlers.java`. Packaging/running the game is covered in the upstream wiki (linked from README.md); it needs original Settlers 3 `GFX`/`SND` data files to run.
- Clear stuck Gradle daemons with `./gradlew --stop`. **Never `taskkill //IM java.exe`** — a user's running game is a `java` process and will be killed with it.

## Module map (big picture)

- `jsettlers.common` — shared value types: `EBuildingType`, `EMaterialType`, `EMovableType`, `EResourceType`, positions, `CommonConstants` (global flags/toggles).
- `jsettlers.logic` — the simulation: map grid, partitions, buildings, movables (unit behaviour trees), and **all AI** (`jsettlers.ai.*`). This is where nearly all gameplay work happens.
- `jsettlers.graphics` / `go.graphics(.swing)` — rendering; `jsettlers.main.swing` — desktop app + replay tools; `jsettlers.network` — multiplayer; `jsettlers.testutils` — test harness (`TestUtils.setupTempResourceManager()`, `MainGridDataAccessor` test seams).

## The two partition systems (the #1 source of subtle bugs)

Every tile belongs to two *different* partitions; confusing them causes silent failures.

- **WALK / blocked partition** — `landscapeGrid.getBlockedPartitionAt(x,y)` and `LandscapeGrid.isReachable(x1,y1,x2,y2,ship)`. Movement/landmass/sea connectivity. Negative ids are navigable sea.
- **TERRITORY partition** — `partitionsGrid.getPartitionIdAt(x,y)`. Ownership, **material stock**, and **bearer delivery**. Each `PlayerStatistic.partitionIdToBuildOn` is the single home partition the AI builds on.

Consequences that are load-bearing across the AI:
- **Goods move by bearers only WITHIN one territory partition.** Across water they move ONLY by sea-trade: a `HarborBuilding` → `CargoShipMovable` drops materials on a reachable coast, then bearers within that partition. Ferries carry only `IAttackableHumanMovable` (pioneers/soldiers/geologists) — **never plain bearers and never goods**.
- **A building's worker = a jobless BEARER of that building's partition + a tool** (scythe→farmer, hammer→bricklayer, blade→digger, pick→miner; miller/baker/waterworker need none). No spare bearer or missing tool → the building stands finished-but-unworked, or half-built with materials on site.
- **Population grows only via living houses** (`SpawnBuilding` subclasses, e.g. `SmallLivinghouse`): they spawn bearers at their own door into their own territory partition and self-fund beds. This is the *only* way a partition with no home connection (an overseas colony) gains labour.
- **Boxing trap:** partition ids are `short`. `Set<Integer>.contains(getPartitionIdAt())` autoboxes to `Short` and silently never matches — always cast to `int` first. This bug has bitten here before.

## AI architecture

`AiExecutor` ticks each AI player's `WhatToDoAi`, which delegates to two halves:

- **Economy** — `EconomyMinister` (impl `BuildingListEconomyMinister`, `jsettlers.ai.economy`) produces an *ordered* `getBuildingsToBuild()` list each tick: building material (wood/stone) → mana → interleaved food + material + weapons/gold, all scaled by map capacity (`AiMapInformation`) and difficulty factors. `determineFoodBuildings()` encodes the farm→mill→baker→waterworks(+pig/slaughter) ratios. `getLandForPlayer` is the candidate tile set the construction position-finders (`jsettlers.ai.construction.*`) iterate.
- **Army** — `ArmyGeneral` (impl `ModularGeneral`) runs a list of `ArmyModule`s each tick, order-sensitive (later modules can override earlier ones by claiming soldiers into `soldiersWithOrders`): defense, soldier production/upgrade/heal, opponent adaptation, harassment, `SimpleAttack`, naval invasion, colonization, and `Regroup` (runs last — pulls unclaimed soldiers home, which is why in-progress attacks must register their soldiers).
- `AiStatistics` is the shared read-model (rebuilt each tick); most "does the player have/own X" queries and the colonization/suppliability scans live here.

Unit behaviour is behaviour-tree style in `jsettlers.logic.movable.*` (e.g. `SoldierMovable`, `PioneerMovable`, `GeologistMovable`). `EMoveToType.DEFAULT` = work-on-arrival (a geologist surveys, a pioneer claims); `FORCED` suppresses that (needed to board a ferry without surveying/claiming en route).

## Cross-water colonization (`ColonizationModule` + `ColonizationBuildModule`)

The most heavily-developed AI subsystem, and the one where the two-partition rules matter most. Flow: decide/claim a beachhead (`ColonizationModule`, gated to AI_HARD/VERY_HARD + a ready dockyard + a ferry sea route) → build it up (`ColonizationBuildModule`: tower to hold, farm, living house to grow labour, barrack, harbor to export). Established engine limits that shape the design:

- **Landlocked island ore is a dead end:** an overseas mine only produces if its ore's territory partition touches a cargo-ship-reachable coast. Most island ore doesn't, so the AI leads with a **farm** (self-producing on a coastal beachhead) and ships the crop home to be baked there, rather than mining.
- The colony's labour comes from converting ferried pioneers (capped) plus a beachhead **living house**; a barrack turns a local bearer + a *shipped* SWORD into a soldier (no overseas weapon chain needed).

## Testing the AI

- **`AiDifficultiesIT`** — pits difficulty levels against each other on land maps; the balance regression guard. Long and cliff-edge sensitive: run **one battle per fresh JVM** with `--no-daemon`; re-run apparent flakes before trusting them. Any AI change must keep this green.
- **`ColonizationIT`** — end-to-end island-colonization test. **Local-only:** it loads a GOG Settlers 3 island map by absolute path and `assumeTrue`-skips (never fails) when absent, so it does not run in CI. A full run is ~20–70 min of simulation. Its per-step `[ColonizationIT...]` stdout is the primary debugging signal and is captured into the JUnit XML (`build/test-results/test/TEST-*.xml`), not the console.
- **Determinism:** `CommonConstants.DETERMINISTIC_AI` (default `false`). `AiExecutor`/`AiStatistics` normally run players and stat-updaters concurrently, so GUI-task enqueue order — and thus emergent AI outcomes — varies run-to-run. `ColonizationIT` sets `DETERMINISTIC_AI = true` so long emergent pipelines reproduce. When adding AI state that feeds a decision, keep it deterministic: use membership-only sets (never iterate a `HashSet` to pick), give tie-breaks a stable order (by value then x/y), and never touch `MatchConstants.aiRandom()` / `Math.random()` / `Date` in AI decision paths.

## Workflow gotchas

- **Do not commit `gradle.properties` trust-store lines.** Local dev may add trust-store entries to work around a corporate HTTPS proxy; `git checkout -- gradle.properties` before committing. Never import the proxy root CA into the JDK cacerts.
- **Linked git worktrees fail to build on a branch** with `Cannot get property 'name' on null` (a JGit-version task). Work around with `git checkout --detach HEAD` before building in a worktree. Agent worktrees also branch from a *stale cached base*, not current HEAD — `git reset --hard <SHA>` to the intended commit first.
- Behaviour-preserving AI changes (dead-code deletion, gated additions inert on land/single-partition maps) can be verified by compile + argument; anything that changes emergent behaviour needs the relevant IT run. State which you relied on.
