# route/ — A* search

- **Goldens are bit-exact.** `AStarSearch` sums a move's cost as `g + base + slope + roughness + cut + ahead + extra`
  in that order; regrouping the terms changes floats and the goldens. Keep `TerrainMoveCost.MoveCost` components separate.
- **Only `TerrainView`.** A* reads terrain through `TerrainView` so the golden tests run on `SyntheticTerrain` without
  a world; a new terrain query used by A* goes on `TerrainView`.
- **Bridge moves come from `bridge/BridgeMoveGenerator`.** A* only applies them (plus bridge spacing).
  The bridge cost's "mostly water" rule intentionally differs from `waterDominant` (user): do not unify them.
- **One whole-path check.** `PathProfileCheck.isAcceptable`; a rejected path's end state is closed and the search
  resumes (`PROFILE_REJECTION_LIMIT`, then the partial fallback). Closing the end state never removes a bad bridge
  deep in the path, so A* applies the same rules itself.
- **Own bridges.** A road never runs under a bridge of its own path, whatever the deck height
  (`PathProfileCheck.underOwnBridge`): A* refuses a bridge over the walked path and steps back under its last bridge;
  the whole-path check covers every bridge. A road enters and leaves a new bridge in its axis
  (`BridgeMoveGenerator.BRIDGE_END_MIN_DOT`, both ends).
- **Steep hairpins.** A move is refused when it comes back within a road width of its last 32 blocks of path with
  more than half a block of height per block of gap; the `logPathfinding` trace uses the same rule
  (`TerrainMoveCost.isSteepHairpinGap`).
- **Road reuse goes only through `NetworkMoves`** (join, follow stored waypoints, exit; built bridges included):
  no road-cell bonus, attraction or parallel-road penalty, no special bridge jump.
- **Two passes.** With `searchSpeedFactor` > 1 the search runs twice (fast weighted, then exact inside a band around
  that path); the result and the per-path logs come from the last pass.
- **Village entries are Millénaire paths only** (`EndpointResolver`): Ultreïa roads use the same blocks, so they are
  told apart by `RoadIndex`; otherwise a village road starts in the middle of a lone trail.
