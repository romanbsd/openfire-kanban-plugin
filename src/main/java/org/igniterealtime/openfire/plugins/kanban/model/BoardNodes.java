package org.igniterealtime.openfire.plugins.kanban.model;

/** Canonical PubSub node identifiers for a board. */
public final class BoardNodes {
    private BoardNodes() {}

    public static String cards(String boardId) {
        return "boards/" + boardId + "/cards";
    }

    public static String activity(String boardId) {
        return "boards/" + boardId + "/activity";
    }
}
