# Expose the road network to the Travellers worldmap

The Travellers mod (`/home/user/IdeaProjects/Travellers`) will paint Ultreïa's roads onto its worldmap, with a different look for village roads, lone trails and bridges. It reads Ultreïa only by reflection on `com.jubitus.ultreia.api.MPBRoadAPI`, so Ultreïa must expose plain Java types (no Ultreïa classes in signatures).

## What Travellers needs

Two new static methods on `MPBRoadAPI`, called on the server thread:

```java
/** Changes whenever the set of road segments changes (added, removed, cleared, loaded). Never persisted; any value is fine as long as it changes. */
public static long getRoadNetworkRevision(WorldServer world)

/**
 * Every road and bridge segment of this world, one int[] each:
 * [kind, widthBlocks, isBridge, x0, z0, x1, z1, ...]
 * kind: 0 = village road (RoadType.VILLAGE_MAIN), 1 = lone trail (RoadType.LONE_TRAIL).
 * widthBlocks: the width actually painted for this segment (deck width for a bridge).
 * isBridge: 1 for a bridge segment, else 0.
 * Then the centerline waypoints in order (x, z pairs), as stored in RoadSegment.
 */
public static List<int[]> getRoadSegmentsForMap(WorldServer world)
```

Keep every existing `MPBRoadAPI` method and signature unchanged: Travellers calls `isOnRoad`, `findNextRoadWaypointToward`, `findRoadSpawnPos`, `findNearestRoadPos`, `findRoadRoute`, `findRoadRouteLegs`, `getConnectedVillages`, `getRoadPolylinesInBox`.

## What is missing today

- `RoadSegment` does not know its `RoadType`. `RoadPainter` knows it (`roadType`, set by `setRoadType`) when it calls `registerRoadSegment` / `addRoadSegment` / `addBridgeSegment`, but it is not stored or saved in `MPBRoadNetwork`. Store it on the segment and save it in NBT, for road and bridge segments alike (a bridge takes the type of the route it belongs to). Same for `MPBCustomPathGenerationJob`.
- The painted width is not stored either. Store the real painted width per segment. While doing so, check the width logic: `MPBConfig.PAINTING.roadWidth` says "Default 3" while the comment in `RoadPainter.setRoadType` says lone trails are "3-wide vs 5-wide". Find what is actually painted for each type and make code, comments and config text agree.
- **Existing saves**: segments saved before this change have no type or width. Infer them when loading: `MPBWorldData` already saves the planned network (`LogicalEdge.isLoneTrail`) — match each segment to its logical edge (by `fromVillage`/`toVillage` or endpoints; pick the most reliable match you can verify in the code). Width falls back to the width that type paints. If a segment cannot be matched, treat it as a village road. Save the inferred values from then on.

## Rules

- **Never break existing saves**: new NBT keys are optional on read; old worlds must load and keep their roads.
- You may fix other inconsistencies you notice in the code you touch (not only comments), as long as saves stay compatible and the existing API behaves the same.
- Follow this project's `../../CLAUDE.md`.
- Build with `./gradlew build`; do not deploy the jar. At the end, report: the jar path, the final signatures of the two methods, how old saves are inferred, and the width you found for each road type.
