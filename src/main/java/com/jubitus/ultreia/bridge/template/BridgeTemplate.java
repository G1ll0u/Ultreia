package com.jubitus.ultreia.bridge.template;

import java.util.ArrayList;
import java.util.List;

public final class BridgeTemplate {

    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String name;
    public int deckWidth = 3;
    public int segmentLength;
    public String canonicalForward = "south";
    public BlockStateRef deckSlab;
    public List<BlockEntry> blocks = new ArrayList<BlockEntry>();
    public List<SupportSeed> supportSeeds = new ArrayList<SupportSeed>();
    public int minLateral;
    public int maxLateral;
    public int minRelY;
    public int maxRelY;
    public long createdAt;

    // Spawn conditions — null or empty list means no restriction
    public List<String> biomes;
    public List<String> biomeTemperatureCategories;
    public List<String> biomeDictionaryTypes;
    /** BOTH (default), YES (water bridges only), NO (non-water bridges only) */
    public String waterOnly = "BOTH";
    /** Minimum bridge length in blocks; 0 = no extra restriction beyond global config */
    public Integer minLength;
    /** Maximum bridge length in blocks; 0 or null = no extra restriction beyond global config */
    public Integer maxLength;

    // Per-template overrides — null means use the global UltreiaConfig value
    public Integer bridgeCrownHalfBlocks;
    public Integer bridgeCrownMinLenBlocks;
    public Integer maxSupportDepth;

    public static final class BlockEntry {
        public int forward;
        public int lateral;
        public int relY;
        public BlockStateRef state;

        public BlockEntry() {
        }

        public BlockEntry(int forward, int lateral, int relY, BlockStateRef state) {
            this.forward = forward;
            this.lateral = lateral;
            this.relY = relY;
            this.state = state;
        }
    }

    public static final class SupportSeed {
        public int forward;
        public int lateral;
        public int relY;
        public BlockStateRef state;

        public SupportSeed() {
        }

        public SupportSeed(int forward, int lateral, int relY, BlockStateRef state) {
            this.forward = forward;
            this.lateral = lateral;
            this.relY = relY;
            this.state = state;
        }
    }
}