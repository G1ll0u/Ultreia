# paint/ — road painting

- **No test covers the painter** (it needs a world): every change here ends with an in-game check.
- **Step order is behaviour.** `RoadPaintSession`: densify → merge decisions → height profile → cell plan →
  prune and reinforce → sweep along the centerline (the edge's bridges are built when the sweep reaches them) →
  merge connector, elbow fills, forced leftovers → talus → register in the network. Reordering changes roads.
- **Grading needs a centerline index.** `RoadCellPainter` grades a cell around the path index it is given; cells
  painted after the sweep (index past the path end) get no earthworks.
- **Merging.** Only what A* followed on the network (`PathResult.getFollowedXZ`, plus holes of up to 8 blocks between
  two followed stretches) is a gap: nothing painted, connectors forced at both ends; the painter never guesses
  reuse from road proximity. A lone trail still stops on the first road it meets (`RoadMergePlanner`).
- **Network registration.** Each painted piece is its own segment, ending on the followed road's first cell (so
  `RoadNavigator` links them); followed stretches are never registered again.
- **Block states.** Which kind to place: `PathPalette`; Millénaire or Ultreïa states only in `mill/MillPathBlocks`.
