package org.igniterealtime.openfire.plugins.kanban.model;

/** An identified domain entity protected by optimistic concurrency. */
public interface Revisioned extends Identified {
    long revision();
}
