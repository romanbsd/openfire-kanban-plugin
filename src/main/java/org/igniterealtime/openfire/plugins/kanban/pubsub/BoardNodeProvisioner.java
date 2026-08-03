package org.igniterealtime.openfire.plugins.kanban.pubsub;

import org.igniterealtime.openfire.plugins.kanban.model.BoardSnapshot;

/** Makes the PubSub nodes advertised by an authoritative snapshot ready for use. */
@FunctionalInterface
public interface BoardNodeProvisioner {
    BoardNodeProvisioner NOOP = snapshot -> {};

    void ensureReady(BoardSnapshot snapshot);
}
