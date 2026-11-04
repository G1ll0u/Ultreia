# Road reuse — status: not started (next, now that the grand nettoyage is done)

## Goal

A new road may join an existing road, follow it as long as that brings it closer to its goal, and leave it.
Existing bridges are ordinary road segments of that network: no special bridge-reuse move. The only special work is
a clean connection at each join and each leave (walkable, aligned, no doubled road).

## Decisions taken with the user

- The special built-bridge reuse move (single-cell snap onto a bridge end, then a jump to its other end) is abandoned:
  it never worked reliably in game.
- Painter (kept, still useful): a village main road stops on an existing road only if it leads to its destination;
  a path over a built bridge paints nothing on the deck (stops at its start, resumes at its end).

## Findings (in-game tests, end of phase 9)

- A* offered the built bridge many times and none of those paths was rejected, yet the found path built a new
  parallel bridge: the cause is in the search itself, not found (the capped bridge log was full of other lines).
- The painter used to stop at the first road met after 20 % of the path, and to paint beside a reused deck
  (only centerline ±1 is marked as deck in `RoadIndex`).
- The whole-path check samples terrain heights: over a reused bridge it works only because the built deck is
  the sampled surface.

## Open questions (to decide with the user)

- How A* walks on an existing road: road cells as cheap moves (existing `existingRoadBonus`), or a dedicated
  "follow the network" move along `RoadNetwork` segments (bridges included, at deck height).
- What becomes of the frozen reuse settings: reuse bonus, magnetism (made twisted or too-wide roads), parallel-road
  avoidance, partial path, the painter's early merge and parallel-gap skip.
- What "as long as it makes sense" means: leave when the network turns away from the goal, cost of the detour.
- Join / leave geometry: angle, height matching on ramps and bridge ends, width difference (lone trail onto main road).
- Road type of a shared stretch (a lone trail following a main road stays a main road).
- First step: a debug trace of why a found path did not follow a nearby existing road.

## Steps

Not planned yet.
