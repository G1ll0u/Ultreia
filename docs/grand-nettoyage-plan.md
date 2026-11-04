# Grand nettoyage — feature packages, one authority per question, giant classes split

**Status (2026-11-04)**: phases 0–9 done (in-game checks pending). Phase 10 in progress.

## Goal

Make Ultreïa cheap for Claude to explore and change: one system = one folder, one behaviour = one place, no two
copies of the same logic drifting apart. This prepares the later work: faster/leaner network generation, better
roads and bridges, completing an existing network, new villages.

## Decisions taken with the user

- **Feature packages**, as in Travellers (target tree below). Never moved: `Ultreia`, `UltreiaLateLoader`, `api/MPBRoadAPI`
  (Travellers loads `com.jubitus.ultreia.api.MPBRoadAPI` by name), `mixin/` (named in `mixins.ultreia.json`).
- **`MPB` prefix dropped while classes move** (phase 2). Kept: `MPBRoadAPI`, and every save `NAME` string.
- **Config**: delete dead and legacy-only fields; internal tuning becomes named constants, except the fields the user
  actually tunes (picked from a list at phase 5). The three walkability limits become fixed constants. Defaults unchanged.
- **Path blocks**: move models/textures to `assets/ultreia/`, and remap old `millpathbuilder:path_block|path_slab`
  to `ultreia:` on world load. The block names stay `ultreia:` (Travellers reads `ultreia:path_block`).
- **Merging diverging copies**: no default winner. Each merge is listed with both versions and asked at its phase.
- **One generation job** with two edge sources (village network, manual path). Divergences are visible changes,
  confirmed at phase 7.
- **Legacy bridge planner** (`BridgePlanner`, its switch and 11 config fields): deleted.
- **Tests**: kept lean (user: don't spend much time on them): a golden A* test on synthetic terrain before the A*/painter splits, plus a few pure-logic tests where they are cheap.
- **Generation budget**: one budget shared by all dimensions, default 20 ms per tick.

## Rules for every phase

- Each phase (and each numbered step) ends with `./gradlew build` green and the mod playable; one commit per step,
  proposed to the user first.
- Pure moves and splits change no behaviour; the golden test (phase 3) must stay green. Anything a player could see
  is listed under "Visible" and confirmed before it is coded.
- Generation bugs: a debug trace first only when the cause is not certain from the code (user).
- Saves: the `NAME` of `ultreia_data`, `ultreia_roadnetwork`, `ultreia_roadindex`, `ultreia_job_checkpoint` never
  changes; NBT keys stay readable (new ones may be added). Bridge template JSON format unchanged.
- Config keys are Java field names (jubiconfig uses `catField.getName()`, `@Config.Name` is ignored): a surviving
  field never changes name or category, or players lose its value.
- Build files (`build.gradle`, wrapper…) untouched. The jar is `build/libs/`; Ultreïa is never deployed to the test server.

## Target package tree (root `com.jubitus.ultreia`)

| Package | Takes over |
|---|---|
| (root) | `Ultreia`, `UltreiaLateLoader`, `MillPresence`, registration handlers |
| `job/` | `VillageNetworkJob` + `ManualPathJob` → `RoadJob` + `VillageNetworkSource` + `ManualPathSource` (phase 7); `GenerationManager`, `TickedJob`, `JobCheckpoint`, `ManualPathSelection` |
| `planner/` | `NetworkPlanner`, `VillageClassifier`, `LogicalEdge`, `NetworkPlanData` |
| `route/` | `AStarPathfinder` split (phase 9), `EndpointResolver`, `VillageGateField`, `VillageGateTracker`, `RoadType`, `PathProfileCheck`, `PathResult` |
| `terrain/` | `TerrainSampler` split (phase 6), `GroundRules`, `TreeCompat`, `ProtectedAreas`, new `TerrainView` interface |
| `paint/` | `RoadPainter` split (phase 10), `Earthworks`, `HeadroomClearer`, `RoadHeightSolver` + `RoadHeightProfile`, `PathPalette` (path kind and slab support) |
| `bridge/` | `BridgeFeasibility`, `BridgeGeometry`, `BridgeSpan`, `BridgeApproachCheck`; bridge moves and costs taken out of A*, bridge painting taken out of `RoadPainter` |
| `bridge/template/` | template classes (split in phase 8) |
| `roads/` | `RoadNetwork`, `RoadIndex`, `RoadNavigator`, `RoadSegment`, `RoadWaypoint`, `RoadJoins` |
| `chunk/` | `ChunkManager`, `ChunkLoadingCallback` |
| `mill/` | `MillVillageIndex` (split in phase 11), `MillBuildingZones`, `MillPathBlocks`, `MillPathStability`, `MillWorldDataRegistry`, `MillFileNodeLoader`; Millénaire part of `RoadIndex.kindFromState`; new Millénaire-free facade `Villages` |
| `geom/` | `Pos2D`, `RasterLine8` + `SupercoverLine` (→ `LineRaster`, phase 4), half-Y encoding from `BridgeGeometry` (→ `HalfY`) |
| `map/` | `MapScreen` split (phase 11), `PacketOpenMapGui`, `PacketRequestPreview`, `PacketNetworkPreview` |
| `net/` | `NetworkHandler` (channel only) |
| `boost/` | `SpeedBoostHandler` |
| `command/`, `config/`, `block/`, `proxy/`, `api/`, `mixin/` | unchanged folders |

Phase 0–1 tables below use the class names of their time.

## Phases (ordered by gain / risk)

### Phase 0 — Bug fixes — status: done (in-game check pending)

0a: infrastructure bugs. Fixes are obvious and invisible to roads. One commit per bug, unless several fixes share a file.

| # | Bug | Fix | Visible |
|---|---|---|---|
| 0.1 | Blocks registered as `ultreia:` (`block/MPBPathBlock:12`), assets still in `assets/millpathbuilder/` (verified); no lang file | move assets to `assets/ultreia/` (+ `BridgeDefaultTemplates:28` path), missing-mapping remap `millpathbuilder:*` → `ultreia:*`, add `en_us.lang` with the 2 block names | path blocks get their texture; pre-rename worlds get their path blocks back |
| 0.2 | Mixins always queued (`UltreiaLateLoader:11`), `MPBMixinPlugin` not referenced by `mixins.ultreia.json`, `"required": true` (verified) | late loader queues the config only when Millénaire's class resource exists; delete the unused plugin | no mixin error without Millénaire |
| 0.3 | `MPBMillPathPalette.slabBottom(SLABS)` puts `BlockSlab.HALF` on the full `PATHGRAVEL` block → `IllegalArgumentException` (verified, `:53`); SANDSTONE/OCHRE slabs map to dirt | correct slab per kind | no crash next to Millénaire "slabs" paths; sandstone/ochre roads get matching slabs (confirm) |
| 0.4 | `tickBudgetMs = 365` (`MPBConfig:69`), applied per job/dimension (`MPBGenerationManager:162`) (verified) | one budget shared by all jobs, default 20 | server keeps its TPS; generation slower. Existing config files keep their saved value |
| 0.5 | Heavy work outside the budget: job constructors (`PathNetworkBuilder.build`, `findAnchor` for every node, result never read: `anchorByXZ`), `MPBChunkManager.close` waits for disk I/O (`:137`), `saveAllData()` per edge (`:400`) | constructors only queue work; drop the unused anchors; no `waitForFinish`; save at checkpoints only | fewer lag spikes |
| 0.6 | Preview overwrites the saved logical network (`PathNetworkBuilder:126`); preview packet has no op check nor rate limit (`PacketRequestPreview`) | preview never persists; op only, one request at a time | none for ops |
| 0.7 | `/ultreia gui` calls `getTopSolidOrLiquidBlock` for every waypoint (`CommandUltreia:177`): loads/generates chunks, and reads the deck itself | use road Y from the index, drop the meaningless "Terrain ΔY" | no freeze on a big map |
| 0.8 | Direct chunk loads outside `MPBChunkManager`: road-index scan ignores `ensureLoaded == false` and marks the chunk scanned first (`MPBRoadIndexData:220-267`); `MPBEndpointResolver.findMillPathY` scans up to ~58 000 columns with `world.getBlockState` | respect the chunk manager; scan Millénaire paths only in loaded/managed chunks, inside the budget | none expected (gate choice to re-check) |
| 0.9 | Speed-boost modifier saved with the player (no `setSaved(false)`, `MPBSpeedBoostHandler:170`); mount keeps the boost after a dimension change; debug log per tick | not saved; remove from the old mount; log on change only | no permanent boost after a crash |
| 0.10 | `/ultreia bridge set` and `bridge reloadconfig` call `ConfigManager.sync`, a no-op for a jubiconfig mod | save/reload through jubiconfig | `bridge set` survives a restart |
| 0.11 | `MillIntegration.BY_DIM` / `LOAD_TRIED_DIM` never cleared between worlds | clear on server stop | second singleplayer world loads its villages |
| 0.12 | Millénaire imported outside allowed packages: `MPBGenerationJob:19-20` (unguarded), `MPBRoadIndexData:21` | move that code into `mill/` behind `Villages` | none |
| 0.13 | Null use before check `AStarPathfinder:252-254` (also `:1734`, `:1900`) | check first | none |

0a outcomes: 0.1–0.4, 0.7, 0.9–0.12 done as written (0.10 saves through jubiconfig's override file; `bridge reloadconfig` re-reads it).
0.5 done partly: only the never-read anchors removed; `close()` waits for disk once per job (kept), per-edge save writes dirty data only (kept, crash safety);
network build in the constructor and the endpoint scan cost (~58 000 columns per village endpoint) go to phase 7.
0.6: op check added; the preview still persists the logical network (memory: a deliberate decision).
0.8: road-index chunks are rescanned when the chunk manager refused them; `findMillPathY` skips unloaded columns.
0.13: no change (`TerrainSampler.sample` never returns null; the dead null checks vanish in phase 9).

0b: generation-quality bugs. Each one changes roads: a debug trace first, then confirmation with the user, then the fix.

| # | Bug (from inventory; re-verify before fixing) | Visible |
|---|---|---|
| 0.20 | Bridge crown: feasibility and the road network use the global crown (4), the builder uses the template's (6/12) (verified, `BridgeTemplateBridgeBuilder:61`, `RoadPainter:1943`) | clearance and deck heights match the real deck; some bridges accepted/rejected differently |
| 0.21 | Bridge approach check walks inward on the start side (`MPBBridgeApproachFeasibility:30`) → near-constant +120 cost | bridge choice changes |
| 0.22 | Earthworks Y computed then ignored at placement (`RoadPainter:1592/1610`): edge blocks float when fill is refused | no floating edges |
| 0.23 | Half-profile assumes earthworks the painter refuses (lone trails, bridge joins, near buildings) → holes (`MPBRoadHalfProfile:35`, `RoadPainter:1604`) | no holes |
| 0.24 | Slab roads read one half too high (`MPBRoadConnectionHelper.findRoadSurfaceHalfY:159` tests `BlockSlab`, neither path slab extends it) | smoother merges |
| 0.25 | Merge connector can make steps > 1 half (`RoadPainter:427, 844`) | walkable merges |
| 0.26 | Densify skips the rest of a segment when the deadline hits (`RoadPainter:679-685`) | no holes on long edges |
| 0.27 | Junction/gap cells planned outside the sweep are never painted (`RoadPainter:1262, 1298, 1383`) | fuller junctions |
| 0.28 | Lava counts as replaceable in surface scans and clearing (vanilla `MaterialLiquid` is replaceable) | roads avoid lava |
| 0.29 | Planks, fences, stone bricks treated as tree/natural: cut above roads (`MPBTreeCompat:189`, `MPBGround:44-50`) | structures outside footprints survive |
| 0.30 | Earthworks and talus touch Millénaire paths (`Earthworks:104-244`); DT root repaint ignores protections (`RoadPainter:1651`); talus checks the cell only (`:1783`) | invariant "never paint over Millénaire paths" holds |
| 0.31 | Talus pass, DT BFS (600 blocks), `clearTreesAbove` run with no deadline (`RoadPainter:1711-1804`) | fewer lag spikes |
| 0.32 | A*: hop onto a reused bridge not walkability-checked (`:1068-1118`); lone-trail grade check uses the global earthworks flag (`:2235`); lone-trail bridge multiplier also scales turn cost (`:1020`) | lone trails and bridge joins change slightly |
| 0.33 | Network: lone-building fallback edge always dropped (`PathNetworkBuilder:116-120`); snap radius 0 cannot disable snapping (`MPBGenerationJob:247`) | some lone buildings get a trail |
| 0.34 | Navigator: last segment may start at the wrong end (`MPBRoadNavigator:313`); segment length is straight-line | Travellers routes: fewer U-turns |
| 0.35 | Map grid labels misaligned with major lines (`GuiMPBMap:209, 289`) | aligned labels |

0b outcomes (all approved by the user, all done):
- 0.20: `BridgeSpan` carries its template and final crown; feasibility checks with the global crown, picks the
  template, re-checks with the template's crown; every deck profile reads the span. Water bonus +1 everywhere (the
  builder's value). Templates are validated once per loaded instance; the choice is logged at build time only.
- 0.21: start-side approach walks outward. 0.23/0.32: one rule `RoadType.allowsEarthworks()` for A*, profile, approach, painter, talus.
- 0.22: painted cells are graded to road height; a road block gets at most one filled block under it, else the cell is
  skipped. 0.23 partly: holes remain only where >1 block is missing and earthworks are locally off (near buildings, bridge joins).
- 0.25: merge height gap capped by the connector length. 0.26: densify finishes the segment. 0.27: forced leftovers
  painted at a planned neighbour's height.
- 0.28: lava stops scans and clearing. 0.29: logs only by `isWood` or a "log" name; stone bricks, cobble, bricks,
  stairs, walls, slabs are not natural ground (headroom clearing uses the same rule). 0.30: `paintSingle` refuses
  protected cells and Millénaire-path columns before any earthworks; talus skips Millénaire-path columns.
- 0.31: talus applied over several ticks; DT whole-tree removal left bounded per cell.
- 0.32: reused-bridge entrance step checked (`BridgeCrossing.startY`); turn cost no longer scaled by the lone-trail
  bridge multiplier (to ask at phase 5: should that multiplier apply to reusing an existing bridge?).
- 0.33: snap radius 0 disables lone-building spurs; the dead "connect to the first village" fallback removed.
- 0.34: route entry at the nearest waypoint; segment length measured along the waypoints. 0.35 done.
- 0.36 (found on the test server: 10–24 s ticks at each edge start; user: fix now): village buildings listed from
  Millénaire's building map instead of probing a 385×385×193 box (same list, same order); gate and outermost-path
  searches go farthest-first and stop at the first path column (same result); `findMillPathY` scans from surface−3,
  not Y=4; the failure-breakdown message prints the resolved anchors instead of resolving again; the chunk manager
  uses several Forge tickets, each within `maximumChunksPerTicket`. The network build at job start stays in the
  constructor until the server log shows it still costs (phase 7).

### Phase 1 — Dead code — status: done (~770 lines removed)

Outcome: everything below removed after a usage search, except: `BridgeBuildService` kept (it now holds the
missing-template warning and the choice log; phase 8 decides its place); the `dryRun` parameter (97 uses in the painter,
bridge builder, earthworks) goes with the splits of phases 8–10; `force` is a real feature with no command (phase 7
decides); `TerrainSampler.Sample.biome` goes with phase 6; `MPBRoadNavigator.extractFromWaypoint` was wrongly reported dead.
Also removed: the no-op prune loop in the painter, `MPBRoadIndexData.initTransient`, `MPBWorldData.clear`.

Zero visible change; every item re-checked by usage search before deletion.
- `BridgePlanner` and its call/tick in `RoadPainter` (`:166, 283, 314`), `disableLegacyBridgePlannerFallback`, 11 legacy fields
  (`BRIDGES.microBridges/microBridgeMaxLen/exitStableBlocks/exitLookaheadBlocks/exitMaxRiseBlocks/maxOverhangAfterObstacle`,
  `VALLEY_BRIDGING.maxLookahead/minSpanLen/maxLateralDeviation/cooldownBlocks/ridgeRiseBlocks`).
- `AStarPathfinder.probeBridgeMovesInDirection` (~200 lines) + `isMeaningfulValleyRayCell`, `measureWaterWidth`,
  `stateDir`, `getPath`, write-only fields; the two config fields read only there (`narrowCrossing*`).
- 4 dead config fields: `forceSaveEveryNEdges`, `supportSpacing`, `probeLenStepBlocks`, `minBlocksAfterBridgeBeforeMerge`; all `@Config.Name`.
- Whole classes: `MPBMemoryGuard`, `mill/MillPathSnapping`, `BridgeBuildService` (inlined), `PathGenerationService` (inlined).
- `MillVillageIndex`: 12 unused methods and the commented copy (`:365-409`); `MillBuildingObstacles2:69`.
- `RoadPainter`: `resetPaintCursor`, `pointToSegmentDistanceSq`, `isInsidePaintRadius`, no-op `applyGentleBoundaryNoise`,
  `slabByIdx`, always-zero wobble, constant radius arrays.
- `TerrainSampler.invalidate`, never-produced enum values, `Sample.biome`; `Earthworks.Settings.maxGradePerBlock`;
  `MPBRoadColumns.removeMillPathNearby`; `MPBProtectedAreas.addNoPaintSquare`; `BridgeSpan.micro` (if not saved);
  `BridgeFeasibility.debugTryCreateReason` (folded into the debug reason log in phase 8).
- `force` / `dryRun` chain (always false), checkpoint `dryRun` key (still read), `MPBRoadNetwork.bridgeDeckIndex`,
  `GuiMPBMap` colour constants, `IProxy/ClientProxy.registerBlockModels`, navigator/gate/edge unused methods.

### Phase 2 — Feature packages and prefix — status: done

Pure moves and renames into the target tree, one commit per package; nothing else changed. Names chosen with the
user so each says what the class does (e.g. `MPBRoadColumns` → `paint/HeadroomClearer`, `MPBAsyncJob` →
`job/TickedJob`, `integration/MillIntegration` → `mill/MillWorldDataRegistry`); `MPBConfig` → `UltreiaConfig`
(jubiconfig keys come from field names only). Save `NAME`s, NBT keys, config fields, block names, `MPBRoadAPI`,
mixin classes and `mixins.ultreia.json` unchanged. Visible: none.

### Phase 3 — Tests (lean) — status: done

- `terrain/TerrainView`: what A*, `PathProfileCheck`, `BridgeApproachCheck`, `RoadHeightSolver`/`RoadHeightProfile`,
  `BridgeGeometry` and `BridgeFeasibility` ask of the terrain; `TerrainSampler` implements it. The only A* read that
  bypasses it is `BridgeFeasibility.tryCreate` (block reads, Millénaire villages, template by biome).
- Golden A* (`AStarGoldenTest`): flat with obstacle, hills, river valley (lone trail); default config, bridges off.
  The fake terrain subclasses `TerrainSampler` so the real forbidden/cost rules run. A bridge golden comes with phase 8.
- Unit tests: `Pos2D`, `RasterLine8`/`SupercoverLine`, road height solver and deck profile (step limits + snapshot),
  MST + RNG topology (`NetworkPlanner.connect`, extracted from `build`), bundled template JSON round-trip.
- Snapshots in `src/test/resources/golden/`; rewritten only with `ULTREIA_UPDATE_GOLDEN=1` for an approved change.

### Phase 4 — Shared small helpers — status: done

Merged, no behaviour change: xz key (9 copies) and 2D distance → `geom/Pos2D`; `RasterLine8` + `SupercoverLine` →
`geom/LineRaster` (`eight`, `supercover`, `forBridges`); edge key in `NetworkPlanner`; A* `BRIDGE_DIRS` (= `NEIGHBOR_DIRS`)
and open-cell test; light-sample pack/unpack → `TerrainSampler.packLight`/`light*`; template `toLocal`/`project` →
`BridgeTemplateDetector.DetectedDeck`. Decided with the user: both valley tests use `max(2, minValleyDepth)` (was 4 in
`isNearWaterOrValleyEdge`; same at default 6); int squared distances (`RoadNavigator`, `BridgeFeasibility`) now `double`
(int overflowed beyond ~46 000 blocks). Moved: "player only" check ×6 → phase 11; Millénaire path column windows → phase 6.
Kept apart: the two `(lateral, relY)` keys in `BridgeTemplateBridgeBuilder` (not xz); A* free-neighbour counts (16 vs 8 cells).

### Phase 5 — Config cut — status: done

- 188 fields → 39 (user's pick). 137 became named constants beside their reader, same values; the walkability
  limits live in `geom/HalfY`. Kept fields keep name and category; comments rewritten for players.
- Bridges: `BRIDGES.enabled` is the only master switch (`BRIDGE_AWARE_PATHFINDING.enabled`, `PAINTING.buildBridges`
  removed); `VALLEY_BRIDGING.enabled` stays. Debug: 6 switches in `DEBUG` (`logNetwork`, `logPathfinding`, `logBridges`,
  `logPainting`, `logChunks`, `logSpeedBoost`); the failure detail is off by default.
- Visible (approved): reusing an existing bridge no longer pays the lone-trail bridge multiplier.
- Road reuse mechanisms (reuse bonus, parallel-road avoidance, partial path fallback, magnetism) stay unchanged here:
  they are redecided in `docs/road-reuse-plan.md` (user: general road reuse replaces bridge reuse).
- Constant booleans (`ROAD_HIERARCHY_ENABLED`, `BUILDINGS_ENABLED`…) leave dead branches, removed in the splits (6–10).

### Phase 6 — Terrain authorities — status: done (in-game check pending)

Steps (approved): 6.1 `geom/HalfY` (done) · 6.2 `BlockRules` (done) · 6.3 `ColumnSurface` (done) · 6.4 one path-in-column scan (done) ·
6.5 `TerrainSampler` split (done): sampler ~470, `WaterProbe`, `BuildingGuard`, `TreeProbe`,
`route/TerrainCost`; `ProtectedAreas.containsWithin` replaces two neighbour loops. Decided: lone trails keep earthworks. 6.2 (user): tree wood = logs and DT branches only, everywhere (sampler,
bridges, support pillars; `isLikelyTreeWood` dropped); lava never clearable for bridges; the building guard counts
planks and fences as building. 6.3 (user): the road-index scan uses the common surface rule (no longer sees
through planks). 6.4 (user): one path window, ground −3..+4, highest block first.
Open (user, later): `BUILDINGS.hardBlockInPathfinding` has no effect (both branches block footprints except on an
existing path); its comment promises otherwise.


- `terrain/ColumnSurface`: one surface scan replacing `TerrainSampler.sample` loop, `sampleLightIfLoaded`,
  `hasWaterLikeNearColumn`, `RoadIndex.findSurfaceYForScan` (diverge: lava, trees, config).
- `terrain/BlockRules`: tree/plant/water/liquid/natural/clearable/man-made, replacing `GroundRules`, `TreeCompat`
  classification, `isSkippableTop`, `isClearableOrAir`, `isSoftClearable`, `isNaturalSolid`, `isLikelyTreeWood`
  (never narrows), bridge `isReplaceableForBridge`/`isImportReplaceable`/`isBridgeClearable`, `Earthworks` copies.
- Path in a column / its half height: one scan (today 7 windows) and `geom/HalfY` (support-top formula ×3 in the painter).
- `TerrainSampler` split: cache + sample (~350), `WaterProbe`, `BuildingGuard`; the A* cost part moves to `route/`.
- Every divergence is asked before merging; golden tests updated only after approval.

### Phase 7 — One generation job — status: done (in-game check pending)

Outcome: `RoadJob` (context built once in `JobContext`; edge → A* → painter) pulls `EdgeRequest`s from
`VillageNetworkSource` or `ManualPathSource`; `TickedJob`, `VillageNetworkJob`, `ManualPathJob` removed; the manager
has no type checks. `findAnchor` lives in `route/EndpointResolver` next to A*'s grid snap (both kept, user).
Decided (user): manual paths keep their exact ends (no network join rules); `force` and `dryRun` removed from jobs
and checkpoint (old keys ignored on load); the network build stays in the constructor (server log not checked).
Both sources now get the endpoint debug lines; an empty A* path fails the edge instead of painting nothing.


`RoadJob` (context built once, resolve → A* → paint → finish) pulling `EdgeRequest`s from `VillageNetworkSource`
(edge list, gates, built set, checkpoint, lone-building spurs) or `ManualPathSource`. The manager loses its
`instanceof` checks. One "nearest valid ground point" (A*'s `nearestValidGrid` vs job `findAnchor`: asked).
Visible (each confirmed): manual paths merge into existing roads; same scan radius for both.

### Phase 8 — Bridges — status: done (in-game check pending)

Steps: 8.1 `BridgeSite` (world reads of feasibility) + gorge bridge golden (done) · 8.2 `BridgeDeckProfile`,
`BridgeFeasibility` split + rejection log, exit corridor into `BridgeApproachCheck` (done) · 8.3 bridge moves and cost out
of A* (done) · 8.4 `BridgeSegmentPainter` out of `RoadPainter` (done) · 8.5 template side (done) · 8.6 `CLAUDE.md` gatekeeper line (done).
Decided (user): the approach check stays an A* cost; the whole-path profile check is the walkability gate.
Already done earlier: one deck profile (0.20), templates validated once per loaded instance.
The forward-exit check was a subset of the exit-corridor check on the same cell: removed.
8.2 outcome: rejection reason logged through A*'s capped `debugBridge` (user); `isBridgeColumnAcceptable` removed
(repeated checks already made on the same cells, user). The exit corridor only looks past the end side (noted, unchanged).
8.3 outcome: `bridge/BridgeMoveGenerator` (new and reused bridge moves) and `bridge/BridgeMoveCost`; A* keeps only
the move application, bridge spacing and magnetism. `route/GridMoves` (directions, snap, turn cost) created early (user).
Kept apart (user): the cost's own "mostly water" rule differs from feasibility's `waterDominant`.
8.4 outcome: `bridge/BridgeSegmentPainter` builds and registers spans, owns the bridge footprint (one shape for the
no-paint cells and the index volume) and the exit cells; the painter keeps when to build. Kept (user): the exit is
reinforced only when the road continues past the bridge. Dead bridge-join protection (constant 0) removed.
8.5 outcome: `BridgeBuildService` (entry) → `BridgeDeckPlacer` + `BridgeStructurePlacer`, block rules in `BridgeCellRules`,
directions and template-to-world in `BridgeLocalFrame`; deck and road footprints share `BridgeGeometry.forEachFootprintCell`;
one "may a bridge replace this block" rule (3 copies, same effect); `dryRun` removed on the bridge side (always false, user).


- `bridge/BridgeDeckProfile`: the only deck profile, fed by the span's effective crown (follows 0.20).
- `BridgeFeasibility` → `BridgeClearanceCheck` (~250) + `BridgeFeasibility` (~350, logs its rejection reason behind
  the bridge debug switch); `BridgeApproachCheck` merges approach, exit-corridor and forward-exit walks.
- `BridgeMoveGenerator` (~450) and `BridgeMoveCost` (~120) out of A*; `BridgeSegmentPainter` out of `RoadPainter`.
- Template side: `BridgeTemplateBridgeBuilder` → `BridgeDeckPlacer`, `BridgeStructurePlacer`, `BridgeCellRules`;
  `BridgeLocalFrame` absorbs `BridgeTemplateDirection` and the importer's direction parsing; templates validated
  once at load (not at every bridge). JSON classes (`BridgeTemplate`, `BlockStateRef`, `BridgeTemplateIO`) keep their format.
- Fix `CLAUDE.md`: the approach check is a cost (user), the "only gatekeeper" line rewritten (done, with the bridge-moves line).

### Phase 9 — A* split — status: done (in-game checks pending)

Steps: 9.1 in place (dead code, `ax/az/bx/bz` renames, named constants, repeated blocks → one method) (done) · 9.2 `SearchState`,
`GridMoves` completed (adaptive step, endpoint snap, free exits), `SearchBounds` (done) · 9.3 `TerrainMoveCost` (done) · 9.4 `PathReconstructor` (done)
· 9.5 `AStarDiagnostics`, `Session` → `AStarSearch` (done) · 9.6 hairpin debug trace (done) · 9.7 hairpin fix (done).
Decided (user): the bridge-over-own-approach check moves to `PathProfileCheck` (one whole-path check); the per-move
and look-ahead cut costs stay two terms (shared enclosed detection only); the two endpoint debug views become one.
Outcome: `AStarSearch` (~700: search loop, moves, magnetism, partial fallback, stagnation), `SearchState`, `SearchBounds`,
`GridMoves`, `TerrainMoveCost`, `PathReconstructor`, `AStarDiagnostics`; `PathProfileCheck.isAcceptable` is the whole-path check.
A failed reconstruction (absurd jump) now ends as FAILED with its reason instead of FOUND with an empty path.
Open: `RoadType.allowsEarthworks()` is always true (two `true` constants); its false branches sit in A*, profile,
approach check and painter (phase 10).

`AStarPathfinder` (2 650) → `AStarSearch` (~450), `SearchBounds` (~200), `GridMoves` (~120), `TerrainMoveCost` (~450),
`PathReconstructor` (~200), `AStarDiagnostics` (~200), bridge parts already in `bridge/`. Shared search state goes
in one `SearchState` object; the shadowed `ax/az/bx/bz` locals are renamed first. Magic numbers become named
constants. Golden tests must stay identical.
Hairpins (user, after a test): on slopes A* made tight zigzags whose two road parts lay side by side at different
heights, a gap the painter cannot make walkable. Trace (`logPathfinding`): one summary per found path, STEEP hairpins.
Decided (user, option A): A* refuses a move that comes back within a road width of its own last 32 blocks of path
(at least 3 widths further along) with more than half a block of height per block of gap. Golden
`astar_steep_slope_no_hairpin` (0.8-per-block slope) added; the four existing goldens are unchanged.
Built-bridge reuse (in-game test): A* takes the built bridge, but the painter stopped at the first road met after
20 % of the path. Decided (user): a village main road stops on an existing road only if it leads to the destination
(otherwise the parallel-gap skip applies); lone trails still graft onto the first road.
Then the painter painted beside the built deck: it now gets the reused deck cells (`PathResult`) and skips them as a
gap (road stops at the bridge start, resumes at its end, connectors at both ends).

### Phase 10 — Painter split — status: in progress

Steps: 10.1 in place (dead switches and parameters: dry run, early-merge flag, headroom and parallel-skip constants,
always-on earthworks everywhere; profile built once; cursor index passed to the cell painter) (done) · 10.2 `PathPalette`
/ `MillPathBlocks` (done) · 10.3 `RibbonGeometry`, `RoadCenterline` (done) · 10.4 `RoadMergePlanner` · 10.5 `RoadFootprintPlanner`
· 10.6 `RoadCellPainter`, `TalusPass`, `RoadPainter` → `RoadPaintSession`.
Decided (user): `RoadType.allowsEarthworks()` and its two constants deleted (earthworks always on); no new debug
switch, earthworks and clearing log under `logPainting`. No test covers the painter (needs a world): moves keep the
order of operations.

`RoadPainter` (2 200) → `RoadPaintSession` (~250), `RoadCenterline`, `RoadMergePlanner` (~330),
`RoadFootprintPlanner` (~500, owns planned/core/forcePaint/half map), `RibbonGeometry` (~120), `RoadCellPainter`
(~300), `paint/PathPalette` (kind and support choice; Millénaire states in `mill/MillPathBlocks`). The cursor read
inside `paintSingle` becomes an explicit argument. Debug switch for earthworks/terrain clearing.

### Phase 11 — Millénaire index, map GUI, commands — status: not started

- `MillVillageIndex` (950) → `MillSettlements` (~400), `MillBuildings` (~400); `mill/Villages` is the only door
  for code outside `mill/` (checks `MillPresence`, vanilla types only).
- `MapScreen` (760) → `MapScreen` (~230), `MapView`, `MapRenderer` (~330), `MapExporter`.
- Commands: one usage text, one "player only" check, `create`/`update` share one sequence; player text through the lang file (keys listed).
- README fixed (wrong commands, missing ones).

### Phase 12 — Wrap-up — status: not started

Root `CLAUDE.md` code map and authorities written, package `CLAUDE.md` files written, invariants re-checked
(mixin-plugin line, Millénaire-allowed packages become `mill/` and `mixin/`), memory cleaned, plan archived.

## Considered and left out

- Persisted data duplication (planned network in `ultreia_data` and in the checkpoint; road blocks in index and network):
  formats stay, only dead keys stop being written. Low gain, real save risk.
- Speed boost, blocks, proxies: small and clean enough.

## Final code map (to write into `CLAUDE.md` at phase 12)

| Area | Folder | Notes in |
|---|---|---|
| Generation job, manager, budget, checkpoint, manual path state | `job/` | `job/CLAUDE.md` |
| Logical network (MST + RNG, village classes) | `planner/` | `job/CLAUDE.md` |
| A* search, costs, endpoints, gates, whole-path check | `route/` | `route/CLAUDE.md` |
| Terrain questions, sampler, protected areas | `terrain/` | `terrain/CLAUDE.md` |
| Road painting, earthworks, half-slab profile, palette | `paint/` | `paint/CLAUDE.md` |
| Bridges: moves, feasibility, deck, painting, templates | `bridge/`, `bridge/template/` | `bridge/CLAUDE.md` |
| Placed roads: network, index, navigation | `roads/` | this file |
| Chunk loading | `chunk/` | `job/CLAUDE.md` |
| Millénaire data, path blocks, mixins | `mill/`, `mixin/` | `mill/CLAUDE.md` |
| Geometry (xz keys, lines, half-Y) | `geom/` | this file |
| Map GUI and preview packets | `map/`, `net/` | this file |
| Config, commands, blocks, speed boost, public API | `config/`, `command/`, `block/`, `boost/`, `api/` | this file |

## Single authorities (to write into `CLAUDE.md` at phase 12)

| Question | Authority |
|---|---|
| Where is the ground in this column | `terrain/ColumnSurface` |
| What kind of block is this (tree, plant, liquid, natural ground, clearable, man-made) | `terrain/BlockRules` |
| DynamicTrees specifics | `terrain/TreeCompat` |
| Terrain sample for A* and planning | `terrain/TerrainView` (`TerrainSampler` in game) |
| Is this spot protected (building footprint, no-paint zone) | `terrain/ProtectedAreas` |
| Is this a path block / which one to place | `paint/PathPalette` (Millénaire states in `mill/MillPathBlocks`) |
| Block Y ↔ half Y, step limits | `geom/HalfY` |
| xz key, 2D distance | `geom/Pos2D` |
| Line rasterisation | `geom/LineRaster` |
| May a bridge exist here | `bridge/BridgeFeasibility` |
| Deck heights of a bridge | `bridge/BridgeDeckProfile` |
| Endpoints of an edge (gates, anchors) | `route/EndpointResolver` |
| Loading / releasing a chunk | `chunk/ChunkManager` |
| Village list, population, is a point near a settlement | `mill/Villages` (only door to Millénaire data) |
| Is there a road here, its Y, a route over roads | `roads/RoadIndex`, `roads/RoadNavigator` |
| Time spent generating per tick | `job/GenerationManager` |

## Package `CLAUDE.md` files planned (≤ 5 KB each)

`job/` (budget, chunk lifecycle, checkpoint, edge sources), `route/` (costs, road reuse, bridge moves stay A* moves),
`terrain/` (authorities, Millénaire paths never painted), `paint/` (walkability, earthworks, merge rules),
`bridge/` (realism, gatekeeper, deck profile, template format compatibility), `mill/` (Millénaire optional, mixin targets).
