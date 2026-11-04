# paint/ — road painting

- **No test covers the painter** (it needs a world): every change here ends with an in-game check.
- **Step order is behaviour.** `RoadPaintSession`: densify → merge decisions → height profile → cell plan →
  prune and reinforce → sweep along the centerline (the edge's bridges are built when the sweep reaches them) →
  merge connector, elbow fills, forced leftovers → talus → register in the network. Reordering changes roads.
- **Grading needs a centerline index.** `RoadCellPainter` grades a cell around the path index it is given; cells
  painted after the sweep (index past the path end) get no earthworks.
- **Merging.** A village main road stops on an existing road only if that road leads to its destination; a lone trail
  joins the first road. Stretches along an existing road and over a reused bridge deck are gaps: nothing is painted
  there, connectors are forced at both ends (`RoadMergePlanner`).
- **Block states.** Which kind to place: `PathPalette`; Millénaire or Ultreïa states only in `mill/MillPathBlocks`.
