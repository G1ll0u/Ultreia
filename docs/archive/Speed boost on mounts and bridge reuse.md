# Task: fix mount speed boost flicker, and fix bridge reuse so paths connect to existing bridges instead of duplicating them

This is a two-part fix. Part 2 (bridge reuse) is the priority; Part 1 (mount speed) is secondary and can be done independently. Read the relevant files fully before editing — the root causes below were found by reading the code, but you should verify them against the current state of the files before changing anything, since this plan may be a bit stale by the time you pick it up.

Follow `../../CLAUDE.md` conventions throughout: FastUtil collections in hot paths, no world mutation off the server thread, every non-trivial pathfinding/painting decision gets a toggleable debug log behind an `MPBConfig` boolean, and the walkability invariant (no elevation step between adjacent road/bridge blocks greater than 0.5 blocks — never force a jump) must hold after your changes, including across the stitch point where a path joins a reused bridge.

Do not implement a "bridges as first-class graph nodes in `PathNetworkBuilder`" architecture — that was considered and explicitly rejected as too large a change for now. Keep bridges as a special-cased move inside the per-edge `AStarPathfinder` session, backed by the persistent `MPBRoadNetwork`, as they are today.

---

## Part 1 — Speed boost does nothing (and flickers) when the player is mounted

File: `../../src/main/java/com/jubitus/ultreia/player/MPBSpeedBoostHandler.java`

### Root cause

The handler applies a `MOVEMENT_SPEED` `AttributeModifier` to the `EntityPlayerMP` itself, every server tick, unconditionally (`removeModifier()` then `applyModifier()` again even when the value hasn't changed). Two consequences:

1. When the player is riding a horse (or any other mount), the player's own movement attribute has no effect on the mount's actual velocity — the mount moves using its own attributes. So the boost is real but invisible: "no boost applied" as reported.
2. The unconditional remove+reapply every tick, combined with `detectOnRoad` sampling the *player's* feet position (`py - 1`) even while mounted — where the player sits noticeably higher above the ground than the mount's own reference point — causes the on/off detection to be less stable while mounted, and every flip causes an attribute removal+reapplication cycle that gets synced to the client. That's the sprint/walk/FOV flicker.

### Required behavior (confirmed with the user)

The boost must apply to the player and, when the player is riding something, to the mount itself — not to arbitrary nearby entities.

### Fix

1. Determine the *effective target* to boost each tick:
   - If `player.isRiding()` and `player.getRidingEntity()` is an `EntityLivingBase` that has a `MOVEMENT_SPEED` attribute instance, target = that ridden entity.
   - Otherwise, target = the player.
   - Entities without the attribute (boats, minecarts) simply get no modifier — don't special-case them, just check for a null `IAttributeInstance` and skip applying.
2. Base `detectOnRoad`'s position sampling on the *target's* own position, not always the player's. When mounted, use the ridden entity's `posX/posY/posZ` — this alone should remove most of the height-offset mismatch causing flicker.
3. Track state keyed by player UUID as today, but store which entity currently holds the applied modifier (`targetUuid`) inside the `State`. When the target changes (mount/dismount, or the ridden entity changes), remove the modifier from the *previous* target before applying to the new one. Keep `boost`/`linger` ramping continuous across a mount/dismount — don't reset the ramp just because the target changed.
4. Stop reapplying the modifier unconditionally. Store `lastAppliedAmount` in `State` and only call `removeModifier` + `applyModifier` on the target when `Math.abs(newAmount - lastAppliedAmount) > 0.001f` (or when crossing the existing `> 0.001f` presence threshold). This is very likely what actually kills the flicker — verify with the fix from point 2 alone first, but implement this regardless since re-syncing an unchanged attribute every tick is wasteful and risky even off-flicker.
5. Clean up properly: on player logout, and whenever a target is swapped out, remove the modifier from whatever entity currently holds it — don't leave a stray modifier on an abandoned mount.
6. Add a debug log line (gated by the existing `cfg.debugLog`) when the target switches (mount/dismount) and when an apply is skipped vs. actually performed, so this is diagnosable in the field like other procedural decisions in the mod.

### Manual test plan

Tame a horse, ride it, sprint across a generated road: confirm the horse visibly speeds up, confirm no FOV stutter, dismount and confirm the player's own boost still works, remount and confirm the old target's modifier is gone (no double-boost, no leak).

---

## Part 2 — Bridges get duplicated instead of reused ("double bridges")

Files primarily involved: `../../src/main/java/com/jubitus/ultreia/gen/AStarPathfinder.java`, `src/main/java/com/jubitus/ultreia/gen/world/MPBRoadNetwork.java`, `src/main/java/com/jubitus/ultreia/gen/world/RoadSegment.java`. You will also need to **read `RoadPainter.java` in full** (not covered in this investigation) before finishing the fix, since it consumes the reconstructed coarse path and must handle a stitch onto an already-built bridge deck without repainting or clearing it.

### What already exists (don't rebuild it, fix it)

There is already a working *intent* for bridge reuse:

- `MPBRoadNetwork` is a persistent `WorldSavedData` (survives across `/mpb generate` runs) that records every bridge ever built as a `RoadSegment` with `isBridge = true`, plus a fast lookup `getBridgeCrossingsNear(x, z, radius)` returning `BridgeCrossing` records (start/end XZ + segment id + length).
- `AStarPathfinder.Session.buildExistingBridgeMoves(...)` (around line 1001) queries nearby crossings and offers them to A* as cheap moves, so that reusing a bridge is normally preferable to `enumerateBridgeMoves(...)` building a brand-new one, via `MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeReuseBonus` / `bridgeReuseSearchRadius`.
- `BridgeMove.span` is set to `null` for a reused-bridge move, with a comment saying "this is an existing bridge, RoadPainter must not rebuild it".

### Root cause of the duplication / bad connections

Two concrete, distinct bugs in that mechanism:

**Bug A — the "reuse" move is an unvalidated teleport, not a real walk.**
`buildExistingBridgeMoves` offers a move from *wherever the current A\* node happens to be* (anywhere within `bridgeReuseSearchRadius`, default 36 blocks, of the crossing's start point) straight to the *far end* of the bridge, in a single A* transition. The "cost" of the walk from `cur` to the bridge's actual start point is approximated as a straight-line Euclidean distance (`walkToStart`) — it is never actually pathed, never validated against terrain, and never turned into real intermediate path points. This is exactly the "path ends and restarts, skipping the bridge" behavior being reported: the connector between wherever the search was and the bridge's true entrance is fictional.

**Bug B — reused bridges lose their identity during path reconstruction.**
In `buildResultFromReconstruction()` (around line 1798), a step is treated as "crossed a bridge" only if `cameBridge.get(curK)` returns a non-null `BridgeSpan`. But reused-bridge moves are stored with `span = null` (`cameBridge.put(nk, mv.span)` where `mv.span` is `null`) — this is indistinguishable from "this step was never a bridge at all" (`Long2ObjectOpenHashMap`'s default return value is also `null`). So a reused-bridge transition silently falls into the plain-walk branch, which does nothing more than append **one single point** — the bridge's far end — using ground `surfaceY` (not deck height), with zero points for the bridge's actual centerline. `RoadPainter` then has to connect the previous point to this one across whatever gap that is, with no idea a bridge exists there. This is very likely what produces a second, nearby structure, or a broken/floating connection.

### Required fix (the "intermediate" option, confirmed with the user — no changes to `PathNetworkBuilder` or the global graph)

**1. Make the reuse transition a real, validated, single-cell snap — not a radius-wide teleport.**

Only allow `buildExistingBridgeMoves` to offer the "enter this bridge" transition when the *current A\* node* is already within one grid step (`step`, the session's adaptive grid size — see field `private final int step` in `Session`) of the crossing's exact start coordinate. In other words: A* must have actually, validly walked there cell-by-cell through normal `expandNormalMoves` transitions (respecting terrain, slope, protected zones, etc.) before it's allowed to "enter" the bridge. Remove the `walkToStart` approximation from the cost entirely — the cost of getting there is already paid for by the normal moves that got A* adjacent to it.

**2. Keep long-range attraction, but move it into cost/heuristic bias, not into the transition itself.**

Without some pull from a distance, A* has no reason to detour to the bridge's exact entrance instead of drifting past it or spawning a new one nearby. The codebase already has exactly this pattern for a different feature: road "magnetism" in `expandNormalMoves` (`AStarPathfinder.java`, ~lines 671–753, gated by `MPBConfig.ROAD_HIERARCHY.magnetismEnabled` / `magnetismRadius` / `magnetismAttractionBonus`), which discounts the cost of neighbor cells near existing roads to pull LONE_TRAIL paths toward them. Add an equivalent bridge-endpoint magnetism: when a candidate neighbor cell in `expandNormalMoves` is within `bridgeReuseSearchRadius` of a known `BridgeCrossing` start point, apply a proportional cost discount (new config fields, e.g. `bridgeEndpointMagnetismBonus`, or reuse the existing magnetism fields — your call, document whichever you pick). This gently steers the search toward the real entrance from a distance; bug-A's fix then makes the actual crossing happen only once genuinely there.

**3. Give reconstruction a way to tell "reused bridge" apart from "no bridge".**

Add a second map alongside `cameBridge`, e.g. `Long2ObjectOpenHashMap<MPBRoadNetwork.BridgeCrossing> cameReusedBridge`, populated in the same place `cameBridge.put(nk, mv.span)` is currently called for reuse moves. In `buildResultFromReconstruction()`, check this map first: if a state has a reused-bridge crossing, stitch the *actual persisted geometry* of that bridge into `out` — same treatment as the existing `span != null` branch already does for freshly-built bridges (append every waypoint with its deck Y, don't duplicate the shared endpoint) — instead of falling into the naive single-point walk branch.

**4. Store bridges at full resolution in `MPBRoadNetwork`, not subsampled.**

`MPBRoadNetwork.addBridgeSegment(...)` currently subsamples the bridge centerline every `SAMPLE_STEP` (4) blocks before persisting it, same as regular road segments. For a freshly-built bridge, `AStarPathfinder` stitches the full, non-subsampled `span.centerline` (every block, with a full deck-height profile from `BridgeGeometry.buildDeckHalfProfile`) — much finer than what gets persisted. For step 3 above to reproduce an equally clean, exact stitch on reuse, the persisted bridge segment needs the same resolution. Change `addBridgeSegment` to keep every centerline point for bridges (skip the `i % SAMPLE_STEP == 0` subsampling when `isBridge`), so `RoadSegment.waypoints` for a bridge segment is the full per-block deck profile. Bridges are short relative to roads, so the extra storage is negligible. Add a `MPBRoadNetwork.getSegment(int id)` accessor (the `segments` list is currently private, only exposed via `allSegments()`) so reconstruction can fetch the exact segment by `BridgeCrossing.segmentId` and read its waypoints in the correct direction (the crossing already tells you whether you're going start→end or end→start).

**5. Verify `RoadPainter` handles the stitched-in reused-bridge points as "already built".**

This file was not read in this investigation session — read it before finishing. It must recognize that these coarse path points sit exactly on an already-placed bridge deck (via `MPBRoadIndexData` bridge-deck marking — see `roadIndex.markBridgeDeck` in `MPBRoadNetwork.addBridgeSegment`) and skip repainting, earthwork, and tree-clearing there, while still painting a clean, smoothly-connected approach on both sides up to the exact deck edge. The existing `dryRun`/"don't rebuild" comment near `BridgeMove.span = null` suggests this was anticipated somewhere — find and confirm how "don't repaint this" is actually communicated to `RoadPainter` today (it may currently rely on `span == null`, which after your change to point 3 will need to be re-checked, since `cameBridge` stays as the source of truth for *newly built* spans while `cameReusedBridge` is the new source for reused ones — make sure both signal "skip painting the bridge deck itself, but do connect to it" correctly to `RoadPainter`).

**6. Keep `minDistanceBetweenBridgesBlocks` as the fallback guard.**

Per the confirmed requirement: if neither endpoint of any nearby existing bridge is reachably close, the path is still allowed to build an independent new bridge elsewhere (current behavior, unchanged) — just make sure the existing minimum-spacing check still prevents that fallback bridge from ending up right next to the one it failed to reuse.

**7. Add debug logging.**

Gate behind `MPBConfig.GENERAL.printBridgeDebug` (already used for bridge decisions elsewhere in this file): log which crossings were considered, why one was rejected (`toGoalDot`, distance, ocean endpoint, etc. — see existing checks in `buildExistingBridgeMoves`), and when a reuse transition is actually taken, with which `segmentId`.

### Manual test plan

Find or set up a seed/area where two villages need to cross the same river/gorge close to each other (or trigger `/mpb generatecustom` twice over paths that should reasonably share a crossing). Confirm: only one bridge structure exists physically in the world; both approach roads meet the bridge exactly at its two physical ends with no floating gap and no visible second structure; walkability holds (no >0.5 block steps) across the stitch on both sides; regenerating/adding more edges later continues to reuse the same bridge rather than building new ones nearby.

### Explicitly out of scope for this task

- Treating bridges as first-class nodes in `PathNetworkBuilder`'s graph (MST/k-NN) — rejected, too invasive for now.
- Detecting or reusing bridges not built by this mod — not applicable, there is no such external-bridge scenario in this project.
- Allowing a new path to join a bridge mid-span (T-junction) when no endpoint is reachable — rejected; the fallback in that case is to build a new, independent bridge (with minimum spacing enforced), not a mid-span ramp.
