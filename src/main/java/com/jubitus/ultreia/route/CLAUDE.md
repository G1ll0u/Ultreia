# route/ — A* search

- **Goldens are bit-exact.** `AStarSearch` sums a move's cost as `g + base + slope + roughness + cut + ahead + extra`
  in that order; regrouping the terms changes floats and the goldens. Keep `TerrainMoveCost.MoveCost` components separate.
- **Only `TerrainView`.** A* reads terrain through `TerrainView` so the golden tests run on `SyntheticTerrain` without
  a world; a new terrain query used by A* goes on `TerrainView`.
- **Bridge moves come from `bridge/BridgeMoveGenerator`.** A* only applies them (plus bridge spacing).
  The bridge cost's "mostly water" rule intentionally differs from `waterDominant` (user): do not unify them.
- **One whole-path check.** `PathProfileCheck.isAcceptable`; a rejected path's end state is closed and the search
  resumes (`PROFILE_REJECTION_LIMIT`, then the partial fallback). Closing the end state never removes a bad bridge
  deep in the path, so A* also refuses a bridge move whose deck passes low over its own walked path, with the same
  rule (`PathProfileCheck.lowDeckCell`).
- **Steep hairpins.** A move is refused when it comes back within a road width of its last 32 blocks of path with
  more than half a block of height per block of gap; the `logPathfinding` trace uses the same rule
  (`TerrainMoveCost.isSteepHairpinGap`).
- **Road reuse goes only through `NetworkMoves`** (join, follow stored waypoints, exit; built bridges included):
  no road-cell bonus, attraction or parallel-road penalty, no special bridge jump.
- **Two passes.** With `searchSpeedFactor` > 1 the search runs twice (fast weighted, then exact inside a band around
  that path); the result and the per-path logs come from the last pass.
