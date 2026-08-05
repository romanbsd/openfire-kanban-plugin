package org.igniterealtime.openfire.plugins.kanban.muc;

import java.util.Locale;

/** Shared board-discussion room / root-message naming. */
public final class BoardDiscussionRooms {
    private BoardDiscussionRooms() {}

    public static String localpart(String boardId) {
        return "board-" + boardId.toLowerCase(Locale.ROOT);
    }

    public static String roomJid(String boardId, String conferenceDomain) {
        return localpart(boardId) + "@" + conferenceDomain;
    }

    public static String rootMessageId(String cardId) {
        return "kanban-card-" + cardId;
    }
}
