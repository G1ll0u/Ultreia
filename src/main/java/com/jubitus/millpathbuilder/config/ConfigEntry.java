package com.jubitus.millpathbuilder.config;

import java.lang.reflect.Field;

public final class ConfigEntry {
    public final String category;
    public final String name;
    public final String path;
    public final String comment;
    public final Class<?> type;
    public final double rangeMin;
    public final double rangeMax;
    public final boolean hasRange;
    public final Field field;
    public final Object owner; // null for static fields

    public String currentValue;

    ConfigEntry(String category, String name, String comment,
                Class<?> type, double rangeMin, double rangeMax, boolean hasRange,
                Field field, Object owner, String currentValue) {
        this.category = category;
        this.name = name;
        this.path = category + "." + name;
        this.comment = comment;
        this.type = type;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.hasRange = hasRange;
        this.field = field;
        this.owner = owner;
        this.currentValue = currentValue;
    }

    public boolean isBoolean() {
        return type == boolean.class || type == Boolean.class;
    }
}
