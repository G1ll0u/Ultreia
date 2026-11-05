## Ultreïa b0.2.0

### **New features**
- Roads now join and follow existing roads and bridges instead of building a second road alongside them.

### **Improvements**

**Paths**
- Straighter roads on flat ground, and fewer long detours around small hills.
- Better handling of mountains: less tight zigzags where two parts of the road sit side by side at different heights.
- No more steps you have to jump over in tight turns on slopes.
- Village roads now start from the village's own paths, not from the middle of a nearby trail.
- Roads now treat cobblestone cliffs (for example in RTG worlds) like normal rock.

**Bridges**
- Bridges now match their real deck height and clearance: fewer odd or misplaced bridges.
- Roads stop cleanly at an existing bridge and pick up again on the other side (they used to be painted next to it, over the water).
- Small rivers and streams get a bridge too: a few blocks of water are enough now.
- No more long bridges over dry land between two rivers.
- Roads go straight onto and off a bridge, and never pass under their own bridge.

**Performance**
- Generation no longer causes lag spikes: it shares a fixed time budget per tick. Existing config files keep their old value: lower tickBudgetMs to 20 through `/mpb config` if your game lags.
- Fixed long freezes at the start of each road on large worlds.
- Roads no longer give up too early when the server is busy, and no longer get stuck for minutes near some bridges.

**Config**
- Many old or unused options removed; the remaining ones have clearer descriptions.

### Fixes
- Path blocks have their texture again, and worlds from the old "MillPathBuilder" name keep their path blocks.
- No more crash next to Millénaire slab paths; sandstone and ochre roads get matching slabs.
- Works without Millénaire again (no error at startup), but don't use it without Millénaire for now since it does not have path blocks lol.
- Speed boost no longer stays on a mount forever after a crash or a dimension change.
- A second singleplayer world now loads its villages properly.
- Map screen: no freeze on large maps, aligned grid labels; exports are now named `ultreia_export_*.txt`.
- Fixed a half-block hole under the walls of diagonal bridges.
