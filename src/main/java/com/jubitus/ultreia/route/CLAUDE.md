# route/ — A* search

- **Goldens are bit-exact.** `AStarSearch` sums a move's cost as `g + base + slope + roughness + cut + ahead + extra`
  in that order; regrouping the terms changes floats and the goldens. Keep `TerrainMoveCost.MoveCost` components separate.
- **Only `TerrainView`.** A* reads terrain through `TerrainView` so the golden tests run on `SyntheticTerrain` without
  a world; a new terrain query used by A* goes on `TerrainView`.
- **Bridge moves come from `bridge/BridgeMoveGenerator`.** A* only applies them (plus bridge spacing and magnetism).
  The bridge cost's "mostly water" rule intentionally differs from `waterDominant` (user): do not unify them.
- **One whole-path check.** `PathProfileCheck.isAcceptable`; a rejected path's end state is closed and the search
  resumes (`PROFILE_REJECTION_LIMIT`, then the partial fallback).
- **Steep hairpins.** A move is refused when it comes back within a road width of its last 32 blocks of path with
  more than half a block of height per block of gap; the `logPathfinding` trace uses the same rule
  (`TerrainMoveCost.isSteepHairpinGap`).
- **Road reuse settings** (reuse bonus, magnetism, parallel roads, partial path) are frozen until
  `docs/road-reuse-plan.md` is decided with the user.
