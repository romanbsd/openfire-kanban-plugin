package org.igniterealtime.openfire.plugins.kanban.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.igniterealtime.openfire.plugins.kanban.TestDatabase;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.igniterealtime.openfire.plugins.kanban.model.CardPriority;
import org.igniterealtime.openfire.plugins.kanban.model.LabelColor;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanException.Code;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService.CardPatch;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService.FieldPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KanbanServiceTest {
    private JdbcKanbanRepository repository;
    private KanbanService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = TestDatabase.repository();
        final AtomicInteger ids = new AtomicInteger();
        service = new KanbanService(repository, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
            () -> String.format("00000000-0000-0000-0000-%012d", ids.incrementAndGet()), () -> 2, actor -> true);
    }

    @Test
    void completeBoardCardAndMembershipFlow() {
        final var board = service.createBoard("owner@example.org", " Engineering ", 0);
        assertEquals("Engineering", board.value().name());
        assertEquals(1, board.boardRevision());

        final var todo = service.createColumn("owner@example.org", board.value().id(), "Todo", null, 1);
        final var doing = service.createColumn("owner@example.org", board.value().id(), "Doing", 1, 2);
        final var first = service.createCard("owner@example.org", board.value().id(), todo.value().id(),
            "First", "description", "owner@example.org", 3);
        final var second = service.createCard("owner@example.org", board.value().id(), todo.value().id(),
            "Second", null, null, 4);

        final var updated = service.updateCard("owner@example.org", first.value().id(), 1,
            new CardPatch(FieldPatch.set("Updated"), FieldPatch.set(""), FieldPatch.set("")));
        assertEquals("Updated", updated.value().title());
        assertNull(updated.value().description());
        assertNull(updated.value().assigneeJid());

        final var moved = service.moveCard("owner@example.org", second.value().id(), doing.value().id(), null, null, 1);
        assertEquals(doing.value().id(), moved.value().columnId());
        final var deleted = service.deleteCard("owner@example.org", first.value().id(), 2);
        assertTrue(deleted.value().deleted());
        assertEquals(1, service.snapshot("owner@example.org", board.value().id()).cards().size());

        final var editor = service.addMember("owner@example.org", board.value().id(), "editor@example.org", Role.EDITOR, 5);
        assertEquals(Role.EDITOR, editor.value().role());
        service.updateMemberRole("owner@example.org", board.value().id(), "editor@example.org", Role.VIEWER, 6);
        service.removeMember("owner@example.org", board.value().id(), "editor@example.org", 7);
        assertEquals(1, service.listBoards("owner@example.org").size());
        assertEquals(0, service.listBoards("editor@example.org").size());
        assertTrue(repository.diagnostics(1234).pending() > 0);
    }

    @Test
    void persistsPriorityAndOrderedLabelsAndRepublishesCardsWhenDeletingLabels() {
        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var column = service.createColumn("owner@example.org", board.value().id(), "Todo", 0, 1);
        final var bug = service.createLabel("owner@example.org", board.value().id(), " Bug ", LabelColor.ROSE, 2);
        final var docs = service.createLabel("owner@example.org", board.value().id(), "docs", LabelColor.MINT, 3);

        final var card = service.createCard("owner@example.org", board.value().id(), column.value().id(),
            "Card", null, null, CardPriority.URGENT, List.of(docs.value().id(), bug.value().id()), 4);
        assertEquals(CardPriority.URGENT, card.value().priority());
        assertEquals(List.of(docs.value().id(), bug.value().id()), card.value().labelIds());
        assertEquals(List.of("Bug", "docs"), service.snapshot("owner@example.org", board.value().id()).labels().stream()
            .map(label -> label.name()).toList());

        service.deleteLabel("owner@example.org", board.value().id(), bug.value().id(), 5);
        final var afterDelete = service.snapshot("owner@example.org", board.value().id());
        assertEquals(List.of(docs.value().id()), afterDelete.cards().get(0).labelIds());
        assertEquals(2, afterDelete.cards().get(0).revision());
        assertEquals(List.of("docs"), afterDelete.labels().stream().map(label -> label.name()).toList());

        final var cleared = service.updateCard("owner@example.org", card.value().id(), 2,
            new CardPatch(FieldPatch.absent(), FieldPatch.absent(), FieldPatch.absent(),
                FieldPatch.set(CardPriority.NONE), FieldPatch.set(List.of())));
        assertEquals(CardPriority.NONE, cleared.value().priority());
        assertTrue(cleared.value().labelIds().isEmpty());
        assertTrue(repository.diagnostics(1234).pending() > 0);
    }

    @Test
    void rejectsInvalidLabelCatalogAndCardReferences() {
        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var column = service.createColumn("owner@example.org", board.value().id(), "Todo", 0, 1);
        final var label = service.createLabel("owner@example.org", board.value().id(), "Bug", LabelColor.ROSE, 2);
        final var updated = service.updateLabel("owner@example.org", board.value().id(), label.value().id(),
            FieldPatch.set("Defect"), FieldPatch.set(LabelColor.ORANGE), 3);
        assertEquals("Defect", updated.value().name());
        assertEquals(LabelColor.ORANGE, updated.value().color());

        assertCode(Code.BAD_REQUEST,
            () -> service.createLabel("owner@example.org", board.value().id(), " defect ", LabelColor.MINT, 4));
        assertCode(Code.BAD_REQUEST, () -> service.createCard("owner@example.org", board.value().id(),
            column.value().id(), "Card", null, null, CardPriority.HIGH, List.of("unknown"), 4));
        assertCode(Code.BAD_REQUEST, () -> service.createCard("owner@example.org", board.value().id(),
            column.value().id(), "Card", null, null, CardPriority.HIGH,
            List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), 4));
    }

    @Test
    void enforcesRevisionPermissionsWipAndFinalOwner() {
        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var column = service.createColumn("owner@example.org", board.value().id(), "One", 1, 1);
        service.addMember("owner@example.org", board.value().id(), "viewer@example.org", Role.VIEWER, 2);

        assertCode(Code.REVISION_CONFLICT, () -> service.createColumn("owner@example.org", board.value().id(), "Stale", 0, 1));
        assertCode(Code.FORBIDDEN, () -> service.createCard("viewer@example.org", board.value().id(), column.value().id(), "No", null, null, 3));
        final var card = service.createCard("owner@example.org", board.value().id(), column.value().id(), "Allowed", null, null, 3);
        assertCode(Code.WIP_LIMIT_EXCEEDED, () -> service.createCard("owner@example.org", board.value().id(), column.value().id(), "Full", null, null, 4));
        assertCode(Code.FINAL_OWNER, () -> service.updateMemberRole("owner@example.org", board.value().id(), "owner@example.org", Role.EDITOR, 4));
        assertCode(Code.FORBIDDEN, () -> service.deleteCard("viewer@example.org", card.value().id(), 1));
        assertCode(Code.REVISION_CONFLICT, () -> service.updateCard("owner@example.org", card.value().id(), 9,
            new CardPatch(FieldPatch.set("x"), FieldPatch.absent(), FieldPatch.absent())));
    }

    @Test
    void rejectsInvalidInputsAndPositions() {
        assertCode(Code.BAD_REQUEST, () -> service.createBoard("owner@example.org", "", 0));
        assertCode(Code.BAD_REQUEST, () -> service.createBoard("owner@example.org", "x", 1));
        final var board = service.createBoard("owner@example.org", "Board", 0);
        assertCode(Code.BAD_REQUEST, () -> service.createColumn("owner@example.org", board.value().id(), "Bad", -1, 1));
        final var column = service.createColumn("owner@example.org", board.value().id(), "One", 0, 1);
        final var card = service.createCard("owner@example.org", board.value().id(), column.value().id(), "Card", null, null, 2);
        assertCode(Code.INVALID_POSITION, () -> service.moveCard("owner@example.org", card.value().id(), column.value().id(), "a", "b", 1));
        assertCode(Code.INVALID_POSITION, () -> service.moveCard("owner@example.org", card.value().id(), column.value().id(), "missing", null, 1));
        assertCode(Code.BAD_REQUEST, () -> service.updateCard("owner@example.org", card.value().id(), 1,
            new CardPatch(FieldPatch.absent(), FieldPatch.absent(), FieldPatch.absent())));
        assertCode(Code.DUPLICATE_MEMBER, () -> service.addMember("owner@example.org", board.value().id(), "owner@example.org", Role.OWNER, 3));
        assertCode(Code.FORBIDDEN, () -> service.snapshot("nobody@example.org", board.value().id()));
        assertFalse(service.snapshot("owner@example.org", board.value().id()).cards().isEmpty());
    }

    @Test
    void honorsAdministratorsOnlyCreationPolicy() throws Exception {
        final AtomicInteger ids = new AtomicInteger();
        final KanbanService restricted = new KanbanService(TestDatabase.repository(), Clock.systemUTC(),
            () -> String.format("00000000-0000-0000-0000-%012d", ids.incrementAndGet()),
            () -> 0, "admin@example.org"::equals);
        assertCode(Code.FORBIDDEN, () -> restricted.createBoard("user@example.org", "No", 0));
        assertEquals("Yes", restricted.createBoard("admin@example.org", "Yes", 0).value().name());
    }

    @Test
    void ordersCardsUsingBeforeAndAfterAnchors() {
        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var column = service.createColumn("owner@example.org", board.value().id(), "Column", 3, 1);
        final var first = service.createCard("owner@example.org", board.value().id(), column.value().id(),
            "First", null, null, 2);
        final var second = service.createCard("owner@example.org", board.value().id(), column.value().id(),
            "Second", null, null, 3);
        final var third = service.createCard("owner@example.org", board.value().id(), column.value().id(),
            "Third", null, null, 4);

        service.moveCard("owner@example.org", third.value().id(), column.value().id(), first.value().id(), null, 1);
        assertEquals(third.value().id(), service.snapshot("owner@example.org", board.value().id()).cards().get(0).id());
        service.moveCard("owner@example.org", third.value().id(), column.value().id(), null, second.value().id(), 2);
        assertEquals(third.value().id(), service.snapshot("owner@example.org", board.value().id()).cards().get(2).id());
    }

    @Test
    void ensureCardDiscussionIsIdempotentAndLinksRoomAndThread() {
        final var provisioner = new org.igniterealtime.openfire.plugins.kanban.muc.InMemoryBoardDiscussionProvisioner("example.org");
        final AtomicInteger ids = new AtomicInteger();
        service = new KanbanService(repository, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
            () -> String.format("00000000-0000-0000-0000-%012d", ids.incrementAndGet()), () -> 0, actor -> true,
            provisioner);

        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var column = service.createColumn("owner@example.org", board.value().id(), "Todo", 0, 1);
        final var card = service.createCard("owner@example.org", board.value().id(), column.value().id(),
            "Card", null, null, 2);

        final var first = service.ensureCardDiscussion("owner@example.org", board.value().id(), card.value().id());
        assertEquals("board-" + board.value().id() + "@conference.example.org", first.value().discussionRoomJid());
        assertEquals("kanban-card-" + card.value().id(), first.value().discussionThreadId());
        assertEquals(1, provisioner.postedRoots().size());

        final var second = service.ensureCardDiscussion("owner@example.org", board.value().id(), card.value().id());
        assertEquals(first.value().discussionRoomJid(), second.value().discussionRoomJid());
        assertEquals(first.value().discussionThreadId(), second.value().discussionThreadId());
        assertEquals(1, provisioner.postedRoots().size());

        final var snapshot = service.snapshot("owner@example.org", board.value().id());
        assertEquals(first.value().discussionRoomJid(), snapshot.board().discussionRoomJid());
        assertEquals(first.value().discussionThreadId(), snapshot.cards().get(0).discussionThreadId());
        assertEquals(2, provisioner.syncedRooms().size()); // ensureRoom + re-sync on second ensure
    }

    @Test
    void rejectsViewerEnsureCardDiscussion() {
        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var column = service.createColumn("owner@example.org", board.value().id(), "Todo", 0, 1);
        final var card = service.createCard("owner@example.org", board.value().id(), column.value().id(),
            "Card", null, null, 2);
        service.addMember("owner@example.org", board.value().id(), "viewer@example.org", Role.VIEWER, 3);
        assertCode(Code.FORBIDDEN,
            () -> service.ensureCardDiscussion("viewer@example.org", board.value().id(), card.value().id()));
    }

    @Test
    void serializesConcurrentMovesWhenEnforcingWip() throws Exception {
        final var board = service.createBoard("owner@example.org", "Board", 0);
        final var target = service.createColumn("owner@example.org", board.value().id(), "Target", 1, 1);
        final var sourceA = service.createColumn("owner@example.org", board.value().id(), "A", 0, 2);
        final var sourceB = service.createColumn("owner@example.org", board.value().id(), "B", 0, 3);
        final var cardA = service.createCard("owner@example.org", board.value().id(), sourceA.value().id(),
            "A", null, null, 4);
        final var cardB = service.createCard("owner@example.org", board.value().id(), sourceB.value().id(),
            "B", null, null, 5);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<Code> first = executor.submit(() -> moveAfter(start, cardA.value().id(), target.value().id()));
            final Future<Code> second = executor.submit(() -> moveAfter(start, cardB.value().id(), target.value().id()));
            start.countDown();
            final Code firstCode = first.get();
            final Code secondCode = second.get();
            assertTrue((firstCode == null && secondCode == Code.WIP_LIMIT_EXCEEDED)
                || (secondCode == null && firstCode == Code.WIP_LIMIT_EXCEEDED));
            assertEquals(1, service.snapshot("owner@example.org", board.value().id()).cards().stream()
                .filter(card -> card.columnId().equals(target.value().id())).count());
        } finally {
            executor.shutdown();
        }
    }

    private Code moveAfter(CountDownLatch start, String cardId, String columnId) throws InterruptedException {
        start.await();
        try {
            service.moveCard("owner@example.org", cardId, columnId, null, null, 1);
            return null;
        } catch (KanbanException exception) {
            return exception.code();
        }
    }

    private static void assertCode(Code expected, Runnable operation) {
        assertEquals(expected, assertThrows(KanbanException.class, operation::run).code());
    }
}
