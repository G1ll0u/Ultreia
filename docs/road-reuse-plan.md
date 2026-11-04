# Road reuse — status: step 3 coded and tested, awaiting the in-game check

## Goal

A new road may join an existing road, follow it as long as that is cheaper than building its own way, and leave it.
While it follows, nothing is painted. Existing bridges are ordinary road segments: no special bridge move. The only
special work is a clean physical connection at each join and each leave (walkable for players and Travellers walkers,
aligned, no doubled road).

## Decisions taken with the user

- The old reuse settings and their logic go: `preferExistingRoads`/`existingRoadBonus`, `avoidParallelToExistingRoads`
  and its four tuning keys, road magnetism (four keys), bridge-endpoint magnetism. None worked as hoped.
- **Network move.** A* follows a road with a dedicated move along the recorded `RoadNetwork` segments (bridges
  included, at their stored heights), generalising the built-bridge move; no "cheap road cells" on the grid.
- **Cost decides when to leave.** Following costs `existingRoadCost` per block (half a flat new block by default);
  no explicit "turns away from the goal" rule.
- **Config:** `existingRoadCost` in Pathfinding, default 0.5, range 0.1–1.0. Comment: "Cost of walking along an
  existing road compared with building a new one; lower makes roads share the network more, even with detours."
- **Join radius 8 blocks.** A* estimates the remaining cost as straight-line distance, so it never detours toward a
  cheaper road; instead a node joins any road point within 8 blocks by a straight connector (cells checked: no water,
  nothing forbidden; terrain cost paid as normal moves). Roads farther away are used only if the path passes near.
- **Road types:** a village main road follows only main roads (and their bridges); a lone trail follows any road.
  Nothing is ever widened or repainted.
- Painter (kept): a path over a followed stretch paints nothing there (stops at the join, resumes at the leave).
- The bridge cost's "mostly water" rule stays different from `waterDominant`.

## Findings

- Built-bridge reuse costs at least 700 because its landing cell is in the bridge volume
  (`EXISTING_BRIDGE_VOLUME_AVOIDANCE_COST`); a new water bridge beside it costs about 650 and wins. Cause of the
  doubled bridge (trace, in game).
- With no reuse setting on, a road cell costs the same as the grass beside it: roads ran 1–8 blocks beside an
  existing road for ~45 blocks around a junction, and the painter hid it with a 52-block parallel gap (trace).
- The painter registers the whole path as one network segment, followed and skipped stretches included.
- `RoadNavigator` (Travellers) already links a segment whose end lies within 12 blocks of another segment's waypoint,
  so registering only the painted pieces keeps routes navigable.
- `TerrainSampler.nearestRoadDistManhattan` caches by cell regardless of radius (wrong when magnetism and parallel
  avoidance were both on); goes away with them.
- Separate issue, not this plan: the server lags 2–5 s around the end of each generated edge.

## Steps

1. **Debug trace** (`ReuseTrace`: roads alongside, bridges offered vs built) — done.
2. **Remove the old reuse settings and logic** (config keys, `TerrainCost` bonus/parallel penalty, road magnetism,
   the radius-blind distance cache). All were off by default: no behaviour change; goldens unchanged — done.
3. **Network move in A\*** (also removes the built-bridge jump and its always-on bridge-endpoint magnetism): join (within 8 blocks of a waypoint of an allowed segment, walkable height; a bridge
   only at its ends), follow along the segment to its ends and periodic exit points, leave by normal moves; cost
   `existingRoadCost` × length, no terrain extra at the landing; replaces the built-bridge jump and its +700 trap.
   Reconstruction splices the stored waypoints and reports followed stretches; whole-path check over them.
   New golden test with a synthetic road; `ReuseTrace` updated — done (existing goldens unchanged, new golden
   `astar_follows_main_road` approved); in-game check pending.
4. **Painter:** followed stretches are gaps with forced, walkable connectors at join and leave; the parallel-gap
   skip and the main-road early merge go; only painted pieces are registered in the network. In-game checks — not
   started.
5. **Travellers:** check routes across a join and a leave (code + in game) — not started.
6. **Close:** invariants into `route/` and `paint/` notes, remove the frozen-settings rule, archive this plan — not
   started.
