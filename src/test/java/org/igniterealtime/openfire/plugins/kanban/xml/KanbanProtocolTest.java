package org.igniterealtime.openfire.plugins.kanban.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.igniterealtime.openfire.plugins.kanban.TestDatabase;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

class KanbanProtocolTest {
    private KanbanProtocol protocol;

    @BeforeEach
    void setUp() throws Exception {
        protocol = new KanbanProtocol(new KanbanService(TestDatabase.repository(), 0), "pubsub.example.org");
    }

    @Test
    void createsListsAndReturnsAuthoritativeSnapshot() {
        final String boardId = createBoard();

        final IQ list = protocol.handle(request(IQ.Type.get, "list-boards"), "owner@example.org");
        assertEquals(boardId, list.getChildElement().element("board").attributeValue("id"));

        final IQ get = request(IQ.Type.get, "get-board");
        get.getChildElement().addAttribute("board-id", boardId);
        final IQ snapshot = protocol.handle(get, "owner@example.org");
        assertEquals("pubsub.example.org", snapshot.getChildElement().attributeValue("pubsub-service"));
        assertNotNull(snapshot.getChildElement().element("members").element("member"));
    }

    @Test
    void mapsMalformedUnknownForbiddenAndConflictRequests() {
        final IQ malformed = request(IQ.Type.set, "create-board");
        assertEquals(PacketError.Condition.bad_request, protocol.handle(malformed, "owner@example.org").getError().getCondition());

        final IQ unknown = request(IQ.Type.set, "unknown");
        assertEquals(PacketError.Condition.bad_request, protocol.handle(unknown, "owner@example.org").getError().getCondition());

        final String boardId = createBoard();
        final IQ stale = request(IQ.Type.set, "create-column");
        stale.getChildElement().addAttribute("board-id", boardId).addAttribute("expected-revision", "9")
            .addElement("name").setText("Column");
        final IQ conflict = protocol.handle(stale, "owner@example.org");
        assertEquals(PacketError.Condition.conflict, conflict.getError().getCondition());
        assertEquals("1", conflict.getError().getElement().element("revision-conflict").attributeValue("current-revision"));
    }

    @Test
    void executesEveryMilestoneMutationAndSerializesOptionalCardFields() {
        final String boardId = createBoard();

        final IQ createTodo = request(IQ.Type.set, "create-column");
        createTodo.getChildElement().addAttribute("board-id", boardId).addAttribute("expected-revision", "1");
        createTodo.getChildElement().addElement("name").setText("Todo");
        createTodo.getChildElement().addElement("wip-limit").setText("2");
        final String todoId = result(createTodo).attributeValue("id");

        final IQ createDoing = request(IQ.Type.set, "create-column");
        createDoing.getChildElement().addAttribute("board-id", boardId).addAttribute("expected-revision", "2");
        createDoing.getChildElement().addElement("name").setText("Doing");
        final String doingId = result(createDoing).attributeValue("id");

        final IQ createCard = request(IQ.Type.set, "create-card");
        createCard.getChildElement().addAttribute("board-id", boardId).addAttribute("column-id", todoId)
            .addAttribute("expected-revision", "3");
        createCard.getChildElement().addElement("title").setText("Card");
        createCard.getChildElement().addElement("description").setText("Initial");
        createCard.getChildElement().addElement("assignee").addAttribute("jid", "owner@example.org/phone");
        final String cardId = result(createCard).attributeValue("id");

        final IQ update = request(IQ.Type.set, "update-card");
        update.getChildElement().addAttribute("card-id", cardId).addAttribute("expected-revision", "1");
        update.getChildElement().addElement("title").setText("Updated");
        update.getChildElement().addElement("description").setText("Details");
        update.getChildElement().addElement("assignee").addAttribute("jid", "owner@example.org/laptop");
        assertEquals("2", result(update).attributeValue("revision"));

        final IQ snapshotRequest = request(IQ.Type.get, "get-board");
        snapshotRequest.getChildElement().addAttribute("board-id", boardId);
        final var card = protocol.handle(snapshotRequest, "owner@example.org").getChildElement()
            .element("cards").element("card");
        assertEquals("Details", card.elementText("description"));
        assertEquals("owner@example.org", card.element("assignee").attributeValue("jid"));

        final IQ move = request(IQ.Type.set, "move-card");
        move.getChildElement().addAttribute("card-id", cardId).addAttribute("column-id", doingId)
            .addAttribute("expected-revision", "2");
        assertEquals("3", result(move).attributeValue("revision"));

        final IQ addMember = request(IQ.Type.set, "add-member");
        addMember.getChildElement().addAttribute("board-id", boardId).addAttribute("jid", "editor@example.org/phone")
            .addAttribute("role", "editor").addAttribute("expected-revision", "4");
        result(addMember);

        final IQ updateMember = request(IQ.Type.set, "update-member-role");
        updateMember.getChildElement().addAttribute("board-id", boardId).addAttribute("jid", "editor@example.org/tablet")
            .addAttribute("role", "viewer").addAttribute("expected-revision", "5");
        result(updateMember);

        final IQ removeMember = request(IQ.Type.set, "remove-member");
        removeMember.getChildElement().addAttribute("board-id", boardId).addAttribute("jid", "editor@example.org/client")
            .addAttribute("expected-revision", "6");
        result(removeMember);

        final IQ delete = request(IQ.Type.set, "delete-card");
        delete.getChildElement().addAttribute("card-id", cardId).addAttribute("expected-revision", "3");
        assertEquals("4", result(delete).attributeValue("revision"));
    }

    @Test
    void rejectsWrongIqTypesAndForeignNamespaces() {
        assertEquals(PacketError.Condition.bad_request,
            protocol.handle(request(IQ.Type.get, "create-board"), "owner@example.org").getError().getCondition());
        final IQ foreign = new IQ(IQ.Type.get);
        foreign.setChildElement("list-boards", "urn:example:foreign");
        assertEquals(PacketError.Condition.bad_request,
            protocol.handle(foreign, "owner@example.org").getError().getCondition());
    }

    private org.dom4j.Element result(IQ request) {
        final IQ response = protocol.handle(request, "owner@example.org");
        assertEquals(IQ.Type.result, response.getType());
        return response.getChildElement();
    }

    private String createBoard() {
        final IQ create = request(IQ.Type.set, "create-board");
        create.getChildElement().addAttribute("expected-revision", "0").addElement("name").setText("Board");
        return result(create).attributeValue("id");
    }

    private static IQ request(IQ.Type type, String command) {
        final IQ iq = new IQ(type);
        iq.setID(command);
        iq.setFrom(new JID("owner@example.org/device"));
        iq.setTo(new JID("kanban.example.org"));
        iq.setChildElement(command, KanbanProtocol.COMMAND_NAMESPACE);
        return iq;
    }
}
