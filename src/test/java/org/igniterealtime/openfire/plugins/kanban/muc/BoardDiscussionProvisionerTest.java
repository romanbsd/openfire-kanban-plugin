package org.igniterealtime.openfire.plugins.kanban.muc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.igniterealtime.openfire.plugins.kanban.model.Member;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.junit.jupiter.api.Test;

class BoardDiscussionProvisionerTest {
    @Test
    void namesRoomsAndRootMessagesConsistently() {
        assertEquals("board-abc", BoardDiscussionRooms.localpart("AbC"));
        assertEquals("board-abc@conference.example.org",
            BoardDiscussionRooms.roomJid("AbC", "conference.example.org"));
        assertEquals("kanban-card-card-1", BoardDiscussionRooms.rootMessageId("card-1"));
    }

    @Test
    void inMemoryProvisionerIsIdempotentAndRecordsPosts() {
        final InMemoryBoardDiscussionProvisioner provisioner = new InMemoryBoardDiscussionProvisioner("example.org");
        final List<Member> members = List.of(new Member("m1", "b1", "owner@example.org", Role.OWNER, 1));
        final String room = provisioner.ensureRoom("b1", "Board", members);
        assertEquals("board-b1@conference.example.org", room);
        assertEquals("kanban-card-c1", provisioner.postCardRootMessage(room, "c1", "Title"));
        assertEquals(1, provisioner.postedRoots().size());
        assertTrue(provisioner.syncedRooms().contains(room));
    }

    @Test
    void noopProvisionerRejectsEnsureAndPost() {
        assertThrows(UnsupportedOperationException.class,
            () -> BoardDiscussionProvisioner.NOOP.ensureRoom("b", "n", List.of()));
        assertThrows(UnsupportedOperationException.class,
            () -> BoardDiscussionProvisioner.NOOP.postCardRootMessage("room@conf", "c", "t"));
        BoardDiscussionProvisioner.NOOP.syncAffiliations("room@conf", List.of());
    }
}
