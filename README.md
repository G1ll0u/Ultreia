# Ultreïa

**1.12.2 path generator** made for true pilgrims.

Ultreïa is a Minecraft 1.12.2 Forge mod that procedurally generates road networks and bridges connecting Millénaire villages.
The goal is to have "realistic" and useful roads connecting Millénaire villages.

The roads give a speed boost.

## This mod only works on already generated world
I suggest using *Chunk Pregenerator* or my mod  *Jubitus Chunks* to pregenerate your worlds.
**You can generate paths in an existing world without problems but I still recommend generating world.**

## Why pregenerating world ?
1. It will also pregenerate all villages and lone buildings
    - My mod can't guess building placements before village is built

2. It has lots of advantages in 1.12.2
    - It will give a **big performance boost**
    Instead of generating world while you walk, you just load already existing chunks. That totally removes generation lag.
    - It will **prevent village generation** to lag your game
    Since villages are already there, you can disable village generation and get a substancial performance boost
    - It is needed for Distant Horizons to work correctly and not lag your whole game
    Chunk pregenerator and Jubitus Chunks allow you to expand **existing world** without problems.

The only problem of Chunk pregeneration is that it takes **some times to create the world**. But it's **one time**.

## How to use
1. Pregen your world with Chunks pregenerator or Jubitus Chunks
Radius suggested for single player : at least 2000 blocks
If you're a big explorer go 4000+ blocks
2. Ensure you use the latest version of #MillMix
3. If you're generating paths for an existing world, I suggest to **do a backup**
4. Run `/ultreia generate`

**Ensure village generation is ON before pregen**

The mod is not really made to connect new villages to an existing network but it may work.

Ultreïa not only generates paths, it also generates **bridges** using a **template system**.

**Bridge template creation available in pinned messages**


### Commands
All commands need operator rights. `/mpb` works as a short alias.

```
/ultreia generate                 — build the road network between all villages
/ultreia cancel                   — stop the generation in progress
/ultreia status                   — show the generation progress and your pos1/pos2
/ultreia pos1 [x z]               — mark the start of a custom road (your position if no x z)
/ultreia pos2 [x z]               — mark the end of a custom road
/ultreia generatecustom [type]    — build a road from pos1 to pos2; type is VILLAGE_MAIN (default) or LONE_TRAIL (narrow trail)
/ultreia reset                    — clear pos1 and pos2
/ultreia gui [radius]             — open the road map around you (default radius 2000)
/ultreia config                   — open the configuration screen
/ultreia bridge ...               — bridge templates, see below
```

### Config
Go to "mod options" or use `/ultreia config` (same config as travellers)
This mod is highly configurable (way too much)
Some config keys may not work or not act like it should.

## Bridge Template Creation Guide

### What is a template?

A **bridge template** is the pattern of one repeating bridge segment. Ultreïa uses it to place bridges that automatically adapt to the length of any valley.

Default templates (birch, oak, stone, etc.) are copied into `config/ultreia/bridges/` on first server startup. You can edit them or create your own.

---

### Creating a template in-game

**1. Build the model in the world**

Build a complete bridge in your world. Requirements:
- The **deck** (the part you walk on) must be made of **slabs**, exactly **3 blocks wide**.
- The **segment** is the repeating portion — build one clean segment with no tile entities (chests, portals, etc.).
- Minimum segment length is 5 blocks; recommended: 7–12.
- Side rails, pillars, arches — all of these can be part of the template.

**You can import an existing template with `/ultreia bridge import [bridgename]` and edit it directly, it's the simplest way.**

**2. Run the command**

Stand near the bridge and type:
```
/ultreia bridge create my_bridge
```
Ensure your bridge is not surrounded by other blocks, build it high enough above ground.

Ultreïa scans the nearest bridge, converts it into a template, and saves it to `config/ultreia/bridges/my_bridge.json`.

---

### Setting spawn conditions

Open `config/ultreia/bridges/my_bridge.json` in a text editor. At the bottom of the file you will find the condition fields:

| Field                        | Effect                                                                                              |
| ---------------------------- | --------------------------------------------------------------------------------------------------- |
| `biomes`                     | List of biomes where this bridge can appear (e.g. `["minecraft:birch_forest"]`). `null` = anywhere. |
| `biomeTemperatureCategories` | Temperature categories: `COLD`, `MEDIUM`, `WARM`, `OCEAN`. `null` = anywhere.                       |
| `biomeDictionaryTypes`       | Forge biome tags: `FOREST`, `PLAINS`, `SANDY`, etc. `null` = anywhere.                              |
| `waterOnly`                  | `BOTH` (default), `YES` (over water only), `NO` (over land only).                                   |
| `minLength`                  | Minimum bridge length in blocks. `null` = no restriction.                                           |
| `maxLength`                  | Maximum bridge length. `null` = no restriction.                                                     |
| `bridgeCrownHalfBlocks`      | Arc curve height in blocks. Overrides the global config. `null` = use global config.                |
| `bridgeCrownMinLenBlocks`    | Minimum length to apply the arc curve. `null` = global config.                                      |
| `maxSupportDepth`            | Maximum depth of support pillars. `null` = global config (I suggest not touching it).                                           |

**These values are never overwritten by any in-game command.**

---

### Updating geometry without losing your settings

If you tweak the bridge blocks in the world (added a railing, changed a pillar) and want to re-scan its shape while keeping the conditions you set in the JSON:

```
/ultreia bridge update my_bridge
```

Ultreïa re-scans the geometry from the world and rewrites the template, **but preserves** all condition fields (`biomes`, `waterOnly`, `minLength`, etc.).

---

### Setting the default template

```
/ultreia bridge set my_bridge
```

The default template is used when no other template matches the current biome conditions.

---

### Other useful commands

```
/ultreia bridge list           — list all available templates
/ultreia bridge import <name>  — place the template under your feet to visualise it
/ultreia bridge export         — export the nearest bridge in debug format
/ultreia bridge reloadconfig   — reload templates from disk without restarting
```

---

### Typical workflow

1. Build a bridge segment with a slab deck, 3 blocks wide
2. `/ultreia bridge create my_bridge` → generates the JSON
3. Edit `config/ultreia/bridges/my_bridge.json` → set biomes, lengths, etc.
4. `/ultreia bridge validate` → check for issues
5. `/ultreia bridge set my_bridge` → activate as default if desired
6. If you later modify the blocks in-world → `/ultreia bridge update my_bridge` to re-scan

**Note** : Don't hesitate to use `/ultreia bridge import default` to see a valid template example.
