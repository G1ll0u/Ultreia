package com.jubitus.millpathbuilder.gui;

import java.util.*;

public final class MPBGuiState {

    public static String lastSearch = "";
    public static int lastScrollOffset = 0;
    public static String lastSelectedPath = null;
    public static boolean sortByModification = false;
    public static int historyMaxSize = 16;

    // path → previous values, oldest first; currentValue is NOT stored here
    private static final Map<String, ArrayDeque<String>> history = new LinkedHashMap<>();
    // modification order: most recently modified path at the end
    private static final List<String> modOrder = new ArrayList<>();

    public static void recordChange(String path, String oldValue, String newValue) {
        if (oldValue.equals(newValue)) return;
        ArrayDeque<String> h = history.computeIfAbsent(path, k -> new ArrayDeque<>());
        h.addLast(oldValue);
        while (h.size() > historyMaxSize) h.removeFirst();
        modOrder.remove(path);
        modOrder.add(path);
    }

    public static List<String> getHistory(String path) {
        ArrayDeque<String> h = history.get(path);
        return h == null ? Collections.emptyList() : new ArrayList<>(h);
    }

    public static int getHistoryCount(String path) {
        ArrayDeque<String> h = history.get(path);
        return h == null ? 0 : h.size();
    }

    public static List<String> getModificationOrder() {
        return Collections.unmodifiableList(modOrder);
    }

    public static void trimHistoryToMax() {
        for (ArrayDeque<String> h : history.values()) {
            while (h.size() > historyMaxSize) h.removeFirst();
        }
    }
}
