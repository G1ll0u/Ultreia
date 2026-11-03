# CLAUDE.md

## What this mod is

**Ultreïa** (formerly MillPathBuilder) — Minecraft 1.12.2 Forge mod that procedurally generates road networks and bridges between villages. Millénaire is optional: with it, villages come from its world data; without it, the mod uses its own path blocks. Generation runs asynchronously inside a per-tick time budget so the server never lags. Early development; the code was started by weak agents, so expect duplication and a redesign/refactor.

## Build

```bash
./gradlew build                   # jar in build/libs/
./gradlew runClient / runServer
```

`./gradlew build` also runs the tests. Golden snapshots (`src/test/resources/golden/`) are rewritten (`ULTREIA_UPDATE_GOLDEN=1 ./gradlew test`) only for a change the user approved.

## How we work

General rules are in `~/.claude/CLAUDE.md`, rules shared by all mods in `/home/user/IdeaProjects/CLAUDE.md`. Ultreïa specifics:

- **For procedural bugs** whose cause is not certain from the code, build the diagnostic (debug trace) before the fix.
- **`// INVARIANT:` comments** must stay true: fix or delete one whenever you change the code it guards.
- **Travellers** (`/home/user/IdeaProjects/Travellers`) reads `api/MPBRoadAPI` by reflection: never rename a public method there or change its signature (Travellers would break with no compile error).
- **Code style**: split tangled classes, but before adding a class check whether an existing one already owns the concern. Refactor freely, but saved worlds must still load: add new save keys, keep reading the old ones. Comment tunable constants only. Ignore unused imports. Prefer FastUtil (`it.unimi.dsi.fastutil`) collections in generation hot paths.
- **Config fields** are for what a player or server admin would plausibly tune; purely internal tuning stays a named constant in code. A bugfix never gets an on/off toggle. Debug logging (see invariants) is the only exception.
- **README** is for players: no class names, update only for player-visible changes.

## Documentation rules

| Where | What | Budget |
|---|---|---|
| this file | map, cross-cutting invariants, working rules | 20 KB |
| `<package>/CLAUDE.md` | invariants of one subsystem (loaded when working in that folder) | 5 KB each |
| `// INVARIANT:` comment | a rule that concerns one line or method | one line |
| `docs/` | active plans only; finished plans move to `docs/archive/` (never read) | — |
| memory | deferred work, decisions' rationale, external references (never the user profile: see `~/.claude/CLAUDE.md`) | 3 KB per file |

- **Admission test** for any CLAUDE.md line: would an agent who doesn't know it break something, without being able to find it by reading the code where it would naturally look? If not, it doesn't go in.
- **Never**: bug history, measurements, dates, file sizes, "was fixed by", "an earlier version", config key lists, method-by-method descriptions. When a feature changes, rewrite the rule, never append beside it.
- **End of task**: write "Doc: nothing to change" (the default) or propose 1–3 precise lines; write them only after the user agrees. Same for memory.
- **Plan template** (`docs/<name>-plan.md`): status line at the top, goal, decisions taken with the user, steps with a status each. No working-rules preamble. Keep the status current during implementation; when done, extract at most a few invariants into the right `CLAUDE.md` and move the plan to `docs/archive/`.

## Code map

Packages under `src/main/java/com/jubitus/ultreia/`. This map is provisional until the planned redesign.

| Area | Folder |
|---|---|
| Generation jobs, manager, tick budget, checkpoint, manual path selection | `job/` |
| Logical network (MST + RNG, village classes, planned network save) | `planner/` |
| A*, endpoints, village gates, road types, whole-path check | `route/` |
| Terrain sampling, ground and tree rules, protected areas | `terrain/` |
| Road painting, earthworks, headroom clearing, road height profile | `paint/` |
| Bridges (feasibility, geometry, approach) and templates | `bridge/`, `bridge/template/` |
| Placed roads: network, index, navigation, joins | `roads/` |
| Chunk loading | `chunk/` |
| Millénaire data and path blocks | `mill/` |
| Mixins into Millénaire (queued only when Millénaire is present) | `mixin/` |
| Geometry (xz keys, line rasterisation) | `geom/` |
| Map GUI and preview packets; network channel | `map/`, `net/` |
| Config (single jubiconfig class, `Debug` category) | `config/` |
| Commands, proxies, blocks, player speed boost, public API | `command/`, `proxy/`, `block/`, `boost/`, `api/` |

## Cross-cutting invariants

- **Walkability.** Every elevation step between adjacent road or bridge blocks is ≤ 0.5 blocks (half-slab). Players must never be forced to jump — roads, earthwork ramps and bridge decks alike. Verify it whenever touching terrain-painting or bridge-deck code.
- **Bridge realism.** A bridge exists only with real clearance below (valley, river, gorge); never over near-flat terrain at near-ground level. Both ends connect smoothly to the approach road — no floating starts, no abrupt drops. `BridgeFeasibility` is the only gate for placing a bridge (it runs `BridgeClearanceCheck` and the exit corridor); the approach walk in `BridgeApproachCheck` is only an A* cost, and `PathProfileCheck` rejects any found path, bridges included, that is not walkable end to end. Never add another bridge gate elsewhere.
- **Procedural debug visibility.** Every non-trivial decision in generation, pathfinding or painting has a debug log path gated behind a boolean in the `Debug` config category (one switch per subsystem, not one per decision). It must never log per tick or per block.
- **Async safety.** World mutations happen only on the server thread, inside the tick budget (`GenerationManager.onServerTick`); never from another thread.
- **Chunks.** Always go through `chunk/ChunkManager` (force-load before touching, bounded count, per-edge lifecycle, unload pressure); never load chunks directly.
- **Never paint** inside `terrain/ProtectedAreas` (building footprints) or over existing Millénaire path blocks.
- **Bridge moves are part of A\*** — `bridge/BridgeMoveGenerator` offers them during the search and `bridge/BridgeSegmentPainter` builds them while the road is painted; do not add a post-pass that invents bridges.
- **Millénaire is optional.** Nothing outside `mill/` and `mixin/` may require it at class-load time; use `MillPresence`. The late mixin loader queues the mixins only when Millénaire is present; detect it with `getResource()`, never `Class.forName()` (it triggers `MixinTargetAlreadyLoadedException`).
- **Mixin targets are Millénaire internals**: any change to a mixin means re-checking the target's obfuscated names.
- **Road types** (`RoadType`: village main vs lone trail) change A* costs, width and earthworks; any new road behaviour must say which types it applies to.

## References

- `millDecomp/` — decompiled Millénaire and sample world data (untracked, read-only reference).
- Decompiled Minecraft 1.12.2 source for vanilla semantics: `/home/user/IdeaProjects/Travellers/sources/minecraft, forge/minecraft-src/` — read it instead of recalling vanilla behaviour.
