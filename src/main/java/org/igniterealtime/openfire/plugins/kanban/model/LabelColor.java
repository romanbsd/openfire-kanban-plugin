package org.igniterealtime.openfire.plugins.kanban.model;

import java.util.Locale;

/** Closed board-label palette. */
public enum LabelColor {
    SLATE,
    ROSE,
    ORANGE,
    AMBER,
    LIME,
    MINT,
    SKY,
    VIOLET,
    PINK;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LabelColor fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Label color is required");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
