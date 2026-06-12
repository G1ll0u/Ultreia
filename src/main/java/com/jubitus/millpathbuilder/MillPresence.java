package com.jubitus.millpathbuilder;

import net.minecraftforge.fml.common.Loader;

public final class MillPresence {

    private static Boolean present = null;

    private MillPresence() {}

    public static boolean isPresent() {
        if (present == null) {
            present = Loader.isModLoaded("millenaire");
        }
        return present;
    }
}
