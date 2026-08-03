package org.igniterealtime.openfire.plugins.kanban.model;

import java.util.Locale;

/** Closed card class-of-service values used on the wire. */
public enum CardPriority {
    NONE,
    LOW,
    NORMAL,
    HIGH,
    URGENT;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CardPriority fromWire(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
