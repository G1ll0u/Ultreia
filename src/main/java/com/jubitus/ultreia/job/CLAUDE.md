# job/ — generation job

- **One job, two edge sources.** `RoadJob` runs every edge the same way; `VillageNetworkSource` (Millénaire network)
  and `ManualPathSource` (pos1/pos2) only supply edges and react to results. A behaviour that differs between the two
  belongs in its source, never as a flag inside `RoadJob`.
- **Tick budget.** `GenerationManager` splits one budget (`tickBudgetMs`) across all dimensions' jobs. Every long step
  (A* search, densify, plan, sweep, talus) takes a deadline and resumes on the next tick; nothing may loop to completion.
- **Chunks per edge.** `JobContext.releaseEdge()` runs after every edge, success or failure, and once before painting
  (the path needs far fewer chunks than the search area). Only chunks the mod force-loaded are tracked.
- **Saves.** `JobCheckpoint` and the planned network keep reading old keys; add new keys, never rename one.
