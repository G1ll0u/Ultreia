# Grand nettoyage — feature packages, one authority per question, giant classes split

**Status (2026-11-03)**: phase 0 done (build green, not committed yet, in-game checks pending). Next: phase 1.

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
| `job/` | `MPBGenerationJob` + `MPBCustomPathGenerationJob` → `RoadJob` + `VillageNetworkSource` + `ManualPathSource`; `MPBGenerationManager`, `MPBAsyncJob`, `MPBJobCheckpoint`, `MPBManualPathState`; `PathGenerationService` deleted |
| `planner/` | `PathNetworkBuilder` (→ `NetworkPlanner`), `VillageClassifier`, `LogicalEdge`, `MPBWorldData` (→ `PlanData`) |
| `route/` | `AStarPathfinder` split (phase 9), `MPBEndpointResolver`, `VillageGateField`, `VillageGateTracker`, `RoadType`, `newp/MPBPathProfileFeasibility` (→ `PathProfileCheck`) |
| `terrain/` | `TerrainSampler` split (phase 6), `MPBGround`, `newp/MPBTreeCompat`, `MPBProtectedAreas`, new `TerrainView` interface |
| `paint/` | `RoadPainter` split (phase 10), `world/Earthworks`, `MPBRoadColumns`, `newp/MPBRoadHalfConstraintSolver` + `MPBRoadHalfProfile`, palette choice from `mill/MPBMillPathPalette` |
| `bridge/` | `gen/bridge/*` minus `BridgePlanner`, `newp/MPBBridgeApproachFeasibility` (→ `BridgeApproachCheck`), bridge moves and costs taken out of A*, bridge painting taken out of `RoadPainter` |
| `bridge/template/` | `gen/bridge/template/*` (split in phase 8) |
| `roads/` | `gen/world/{MPBRoadNetwork, MPBRoadIndexData, MPBRoadNavigator, RoadSegment, RoadWaypoint}`, `bridge/MPBRoadConnectionHelper` |
| `chunk/` | `MPBChunkManager`, `world/MPBChunkLoadingCallback` |
| `mill/` | `gen/mill/*`, `integration/*`, Millénaire parts of the palette and of `MPBRoadIndexData.kindFromState`, `newp/MPBMillenairePathCompat`; `MillVillageIndex` split (phase 11); new Millénaire-free facade `Villages` |
| `geom/` | `util/MPBPos2D` (→ `Pos2D`), `RasterLine8` + `SupercoverLine` (→ `LineRaster`), half-Y encoding from `BridgeGeometry` (→ `HalfY`) |
| `map/` | `gui/GuiMPBMap` split (phase 11), `network/Packet{OpenMapGui,RequestPreview,NetworkPreview}` |
| `net/` | `network/MPBNetworkHandler` (channel only) |
| `boost/` | `player/MPBSpeedBoostHandler` |
| `command/`, `config/`, `block/`, `proxy/`, `api/`, `mixin/` | unchanged folders |

`gen/`, `gen/newp/`, `gen/util/`, `gen/world/`, `integration/`, `gui/`, `network/`, `player/` disappear.

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

### Phase 1 — Dead code — status: not started

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

### Phase 2 — Feature packages and prefix — status: not started

Pure moves and renames into the target tree, one commit per package; nothing else changes. `mixins.ultreia.json`
is not touched (mixins stay in `mixin/`). Save `NAME`s and NBT keys are untouched (classes may be renamed:
`WorldSavedData` is looked up by name). Visible: none.

### Phase 3 — Tests (lean) — status: not started

- `terrain/TerrainView`: the read-only questions A* and the painter's planning ask (sample, light sample, block at);
  `TerrainSampler` implements it over the world. Check first which A* reads bypass the sampler (`world` field).
- JUnit (`enable_junit_testing` is already on, `src/test/java`): deck profile, half-profile solver, `LineRaster`,
  `Pos2D`, MST + RNG network topology, template rotation / JSON round-trip.
- Golden tests: A* on 3 synthetic terrains (flat with obstacle, hills, river valley) stores the path; a split must
  keep it identical. A snapshot is updated only for a change the user approved.

### Phase 4 — Shared small helpers — status: not started

Identical copies merged (no behaviour change): xz key ×13 and 2D distance ×10 → `geom/Pos2D`; raster ternary ×4 →
`LineRaster.forBridges()`; edge key ×2; identical direction tables; free-neighbour test ×3; light-sample unpacking ×3;
"player only" check ×6 in commands; template `toLocal/project` ×2. Diverging copies (valley test `max(2,…)` vs
`max(4,…)`, Millénaire path column windows) are listed and asked.

### Phase 5 — Config cut — status: not started

- The user picks, from a list of the ~120 internal fields, the ones they tune (likely `NETWORK.*`, turn/slope/reuse costs).
  The rest become named constants beside their only reader, same values.
- Walkability limits become constants: `maxRoadStepHalfPerBlock`, `maxDeckDeltaHalfPerBlock`, `maxRoadJoinDeltaHalfBlocks`.
- To decide with the user here: overlapping switches (4 bridge on/off, 2 building penalties, 2 no-paint paddings,
  ~6 road-reuse mechanisms); one debug boolean per subsystem in `DEBUG` (moving a debug flag only resets it to off);
  `printFailureBreakdown` off by default; wrong or technical comments rewritten (list in the inventory).
- Visible: removed fields vanish from the config screen; behaviour unchanged at default values.

### Phase 6 — Terrain authorities — status: not started

- `terrain/ColumnSurface`: one surface scan replacing `TerrainSampler.sample` loop, `sampleLightIfLoaded`,
  `hasWaterLikeNearColumn`, `MPBRoadIndexData.findSurfaceYForScan` (diverge: lava, trees, config).
- `terrain/BlockRules`: tree/plant/water/liquid/natural/clearable/man-made, replacing `MPBGround`, `MPBTreeCompat`
  classification, `isSkippableTop`, `isClearableOrAir`, `isSoftClearable`, `isNaturalSolid`, `isLikelyTreeWood`
  (never narrows), bridge `isReplaceableForBridge`/`isImportReplaceable`/`isBridgeClearable`, `Earthworks` copies.
- Path in a column / its half height: one scan (today 7 windows) and `geom/HalfY` (support-top formula ×3 in the painter).
- `TerrainSampler` split: cache + sample (~350), `WaterProbe`, `BuildingGuard`; the A* cost part moves to `route/`.
- Every divergence is asked before merging; golden tests updated only after approval.

### Phase 7 — One generation job — status: not started

`RoadJob` (context built once, resolve → A* → paint → finish) pulling `EdgeRequest`s from `VillageNetworkSource`
(edge list, gates, built set, checkpoint, lone-building spurs) or `ManualPathSource`. The manager loses its
`instanceof` checks. One "nearest valid ground point" (A*'s `nearestValidGrid` vs job `findAnchor`: asked).
Visible (each confirmed): manual paths merge into existing roads; same scan radius for both.

### Phase 8 — Bridges — status: not started

- `bridge/BridgeDeckProfile`: the only deck profile (today two diverging `buildDeckHalfProfile`), fed by the span's
  effective crown (follows 0.20).
- `BridgeFeasibility` → `BridgeClearanceCheck` (~250) + `BridgeFeasibility` (~350, logs its rejection reason behind
  the bridge debug switch); `BridgeApproachCheck` merges approach, exit-corridor and forward-exit walks.
- `BridgeMoveGenerator` (~450) and `BridgeMoveCost` (~120) out of A*; `BridgeSegmentPainter` out of `RoadPainter`.
- Template side: `BridgeTemplateBridgeBuilder` → `BridgeDeckPlacer`, `BridgeStructurePlacer`, `BridgeCellRules`;
  `BridgeLocalFrame` absorbs `BridgeTemplateDirection` and the importer's direction parsing; templates validated
  once at load (not at every bridge). JSON classes (`BridgeTemplate`, `MPBBlockStateRef`, `BridgeTemplateIO`) keep their format.
- Fix `CLAUDE.md`: the approach check is a gate or a cost (decide with the user), the "only gatekeeper" line rewritten.

### Phase 9 — A* split — status: not started

`AStarPathfinder` (2 650) → `AStarSearch` (~450), `SearchBounds` (~200), `GridMoves` (~120), `TerrainMoveCost` (~450),
`PathReconstructor` (~200), `AStarDiagnostics` (~200), bridge parts already in `bridge/`. Shared search state goes
in one `SearchState` object; the shadowed `ax/az/bx/bz` locals are renamed first. Magic numbers become named
constants. Golden tests must stay identical.

### Phase 10 — Painter split — status: not started

`RoadPainter` (2 200) → `RoadPaintSession` (~250), `RoadCenterline`, `RoadMergePlanner` (~330),
`RoadFootprintPlanner` (~500, owns planned/core/forcePaint/half map), `RibbonGeometry` (~120), `RoadCellPainter`
(~300), `paint/PathPalette` (kind and support choice; Millénaire states in `mill/MillPathBlocks`). The cursor read
inside `paintSingle` becomes an explicit argument. Debug switch for earthworks/terrain clearing.

### Phase 11 — Millénaire index, map GUI, commands — status: not started

- `MillVillageIndex` (950) → `MillSettlements` (~400), `MillBuildings` (~400); `mill/Villages` is the only door
  for code outside `mill/` (checks `MillPresence`, vanilla types only).
- `GuiMPBMap` (760) → `MapScreen` (~230), `MapView`, `MapRenderer` (~330), `MapExporter`.
- Commands: one usage text, `create`/`update` share one sequence; player text through the lang file (keys listed).
- README fixed (wrong commands, missing ones).

### Phase 12 — Wrap-up — status: not started

Root `CLAUDE.md` code map and authorities written, package `CLAUDE.md` files written, invariants re-checked
(mixin-plugin line, gatekeeper line, Millénaire-allowed packages become `mill/` and `mixin/`), memory cleaned, plan archived.

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
