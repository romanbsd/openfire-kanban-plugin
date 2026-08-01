package org.igniterealtime.openfire.plugins.kanban.outbox;

/** Durable operations consumed by the PubSub projection worker. */
public enum OutboxKind {
    CARD_SNAPSHOT,
    ACTIVITY,
    ACCESS_GRANT,
    ACCESS_REVOKE
}
