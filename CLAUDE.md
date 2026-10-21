# CLAUDE.md

## What this mod is

**Ultreïa** (formerly MillPathBuilder) — Minecraft 1.12.2 Forge mod that procedurally generates road networks and bridges between villages. Millénaire is optional: with it, villages come from its world data; without it, the mod uses its own path blocks. Generation runs asynchronously inside a per-tick time budget so the server never lags. Early development; the code was started by weak agents, so expect duplication and a redesign/refactor.

## Build

```bash
./gradlew build                   # jar in build/libs/
./gradlew runClient / runServer
```

The shell's default JDK (Java 25) works as is; never use java-27-temurin. There is no `src/test` yet.

## How we work

General rules are in `~/.claude/CLAUDE.md`, rules shared by all mods in `/home/user/IdeaProjects/CLAUDE.md`. Ultreïa specifics:

- **For procedural bugs**, build the diagnostic (debug trace) before the fix.
- **`// INVARIANT:` comments** are actively maintained and trustworthy.
- **Travellers** (`/home/user/IdeaProjects/Travellers`) is a sibling mod: Ultreïa may be edited and built here, never deployed.
- **Code style**: split tangled classes, but before adding a class check whether an existing one already owns the concern. Refactor fearlessly: the mod is unreleased alpha, so backwards compatibility and legacy code are not worth preserving. Comment tunable constants only. Ignore unused imports. Prefer FastUtil (`it.unimi.dsi.fastutil`) collections in generation hot paths.
- **Config fields** are for what a player or server admin would plausibly tune; purely internal tuning stays a named constant in code. A bugfix never gets an on/off toggle. Debug logging (see invariants) is the only exception.
- **README** (if any) is for players: no class names, update only for player-visible changes.

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
| Generation pipeline: jobs, manager, A*, painter, terrain, chunk management, protected areas, network building, village classification | `gen/` |
| Bridges (legacy procedural + template-based) | `gen/bridge/`, `gen/bridge/template/` |
| Persisted road data, road index, navigation over placed roads | `gen/world/` |
| Millénaire village/building data | `gen/mill/`, `integration/` |
| Tree handling and other terrain compat | `gen/newp/` |
| Mixins into Millénaire (optional, conditional plugin) | `mixin/` |
| Config (single Forge `@Config` class, `Debug` category) | `config/` |
| Commands, network packets, GUI, proxies, blocks, player speed boost, public API | `command/`, `network/`, `gui/`, `proxy/`, `block/`, `player/`, `api/` |

## Cross-cutting invariants

- **Walkability.** Every elevation step between adjacent road or bridge blocks is ≤ 0.5 blocks (half-slab). Players must never be forced to jump — roads, earthwork ramps and bridge decks alike. Verify it whenever touching terrain-painting or bridge-deck code.
- **Bridge realism.** A bridge exists only with real clearance below (valley, river, gorge); never over near-flat terrain at near-ground level. Both ends connect smoothly to the approach road — no floating starts, no abrupt drops. `BridgeFeasibility` → `MPBBridgeApproachFeasibility` → `MPBPathProfileFeasibility` is the only gatekeeper for bridge placement.
- **Procedural debug visibility.** Every non-trivial decision in generation, pathfinding or painting has a debug log path gated behind a boolean in the `Debug` config category (one switch per subsystem, not one per decision). It must never log per tick or per block.
- **Async safety.** World mutations happen only on the server thread, inside the tick budget (`MPBGenerationManager.onServerTick`); never from another thread.
- **Chunks.** Always go through `MPBChunkManager` (force-load before touching, bounded count, per-edge lifecycle, unload pressure); never load chunks directly.
- **Never paint** inside `MPBProtectedAreas` (building footprints) or over existing Millénaire path blocks.
- **Bridge moves are part of A\*** — the pathfinder emits bridge nodes that `RoadPainter` later materialises; do not add a post-pass that invents bridges.
- **Millénaire is optional.** Nothing outside `integration/`, `gen/mill/` and `mixin/` may require it at class-load time; use `MillPresence`. In the mixin plugin, detect classes with `getResource()`, never `Class.forName()` (it triggers `MixinTargetAlreadyLoadedException`).
- **Mixin targets are Millénaire internals**: any change to a mixin means re-checking the target's obfuscated names.
- **Road types** (`RoadType`: village main vs lone trail) change A* costs, width and earthworks; any new road behaviour must say which types it applies to.

## References

- `millDecomp/` — decompiled Millénaire and sample world data (untracked, read-only reference).
- Decompiled Minecraft 1.12.2 source for vanilla semantics: `/home/user/IdeaProjects/Travellers/sources/minecraft, forge/minecraft-src/` — read it instead of recalling vanilla behaviour.
