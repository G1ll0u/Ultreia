package com.jubitus.millpathbuilder.gen.bridge.template;

import java.util.ArrayList;
import java.util.List;

public final class BridgeTemplate {

    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String name;
    public int deckWidth = 3;
    public int segmentLength;
    public String canonicalForward = "south";
    public MPBBlockStateRef deckSlab;
    public List<BlockEntry> blocks = new ArrayList<BlockEntry>();
    public List<SupportSeed> supportSeeds = new ArrayList<SupportSeed>();
    public int minLateral;
    public int maxLateral;
    public int minRelY;
    public int maxRelY;
    public long createdAt;

    public static final class BlockEntry {
        public int forward;
        public int lateral;
        public int relY;
        public MPBBlockStateRef state;

        public BlockEntry() {
        }

        public BlockEntry(int forward, int lateral, int relY, MPBBlockStateRef state) {
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
        public MPBBlockStateRef state;

        public SupportSeed() {
        }

        public SupportSeed(int forward, int lateral, int relY, MPBBlockStateRef state) {
            this.forward = forward;
            this.lateral = lateral;
            this.relY = relY;
            this.state = state;
        }
    }
}