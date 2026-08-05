package org.igniterealtime.openfire.plugins.kanban.xml;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.igniterealtime.openfire.plugins.kanban.model.ActivityType;
import org.igniterealtime.openfire.plugins.kanban.model.Card;
import org.igniterealtime.openfire.plugins.kanban.model.CardPriority;
import java.util.List;
import org.junit.jupiter.api.Test;

class KanbanXmlTest {
    @Test
    void escapesPayloadTextAndSerializesOptionalFields() {
        final Board board = new Board("board", "A&B <board>", 1, "owner@example.org", 1, 1);
        assertTrue(KanbanXml.boardXml(board).contains("A&amp;B &lt;board>"));
        assertFalse(KanbanXml.boardXml(board).contains("discussion-room"));

        final Board withRoom = board.withDiscussionRoom("board-board@conference.example.org");
        assertTrue(KanbanXml.boardXml(withRoom).contains("discussion-room='board-board@conference.example.org'"));

        final Card full = new Card("card", "board", "column", 2, "0|100000000000:", "'Title'",
            "\"Details\"", "owner&team@example.org", CardPriority.HIGH, List.of("label&amp"),
            "kanban-card-card", false, "owner@example.org", 1, 2, null);
        final String cardXml = KanbanXml.cardXml(full);
        assertTrue(cardXml.contains("&apos;Title&apos;"));
        assertTrue(cardXml.contains("&quot;Details&quot;"));
        assertTrue(cardXml.contains("owner&amp;team@example.org"));
        assertTrue(cardXml.contains("<priority>high</priority>"));
        assertTrue(cardXml.contains("<labels><label id='label&amp;amp'/></labels>"));
        assertTrue(cardXml.contains("<discussion-thread>kanban-card-card</discussion-thread>"));

        final Card minimal = new Card("card", "board", "column", 2, "0|100000000000:", "Title",
            null, null, false, "owner@example.org", 1, 2, null);
        assertFalse(KanbanXml.cardXml(minimal).contains("description"));
        assertFalse(KanbanXml.cardXml(minimal).contains("assignee"));
        assertFalse(KanbanXml.cardXml(minimal).contains("priority"));
        assertFalse(KanbanXml.cardXml(minimal).contains("labels"));
        assertFalse(KanbanXml.cardXml(minimal).contains("discussion-thread"));
        assertTrue(KanbanXml.eventXml("activity", ActivityType.CARD_UPDATED, "board", "a&b@example.org", 3, cardXml)
            .contains("actor='a&amp;b@example.org'"));
        assertTrue(KanbanXml.tombstoneXml(full).contains("revision='2'"));
    }
}
