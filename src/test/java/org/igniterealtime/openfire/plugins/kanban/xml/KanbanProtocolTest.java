package org.igniterealtime.openfire.plugins.kanban.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.igniterealtime.openfire.plugins.kanban.TestDatabase;
import org.igniterealtime.openfire.plugins.kanban.model.BoardSnapshot;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

class KanbanProtocolTest {
    private KanbanProtocol protocol;
    private KanbanService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new KanbanService(TestDatabase.repository(), 0);
        protocol = new KanbanProtocol(service, "pubsub.example.org");
    }

    @Test
    void createsListsAndReturnsAuthoritativeSnapshot() {
        final String boardId = createBoard();
        final AtomicReference<BoardSnapshot> provisioned = new AtomicReference<>();
        protocol = new KanbanProtocol(service, "pubsub.example.org", provisioned::set);

        final IQ list = protocol.handle(request(IQ.Type.get, "list-boards"), "owner@example.org");
        assertEquals(boardId, list.getChildElement().element("board").attributeValue("id"));

        final IQ get = request(IQ.Type.get, "get-board");
        get.getChildElement().addAttribute("board-id", boardId);
        final IQ snapshot = protocol.handle(get, "owner@example.org");
        assertEquals("pubsub.example.org", snapshot.getChildElement().attributeValue("pubsub-service"));
        assertNotNull(snapshot.getChildElement().element("members").element("member"));
        assertEquals(boardId, provisioned.get().board().id());
    }

    @Test
    void doesNotAdvertisePubSubNodesWhenProvisioningFails() {
        final String boardId = createBoard();
        protocol = new KanbanProtocol(service, "pubsub.example.org", snapshot -> {
            throw new IllegalStateException("PubSub unavailable");
        });

        final IQ get = request(IQ.Type.get, "get-board");
        get.getChildElement().addAttribute("board-id", boardId);
        final IQ response = protocol.handle(get, "owner@example.org");

        assertEquals(PacketError.Condition.service_unavailable, response.getError().getCondition());
        assertNotNull(response.getError().getElement().element("pubsub-unavailable"));
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
    void roundTripsPriorityAndLabelsAndRejectsUnknownWireTokens() {
        final String boardId = createBoard();
        final IQ createColumn = request(IQ.Type.set, "create-column");
        createColumn.getChildElement().addAttribute("board-id", boardId).addAttribute("expected-revision", "1");
        createColumn.getChildElement().addElement("name").setText("Todo");
        final String columnId = result(createColumn).attributeValue("id");

        final String bugId = createLabel(boardId, "Bug", "rose", 2);
        final String docsId = createLabel(boardId, "Docs", "mint", 3);
        final IQ updateLabel = request(IQ.Type.set, "update-label");
        updateLabel.getChildElement().addAttribute("board-id", boardId).addAttribute("label-id", docsId)
            .addAttribute("expected-revision", "4");
        updateLabel.getChildElement().addElement("name").setText("Guides");
        updateLabel.getChildElement().addElement("color").setText("sky");
        assertEquals("5", result(updateLabel).attributeValue("board-revision"));

        final IQ createCard = request(IQ.Type.set, "create-card");
        createCard.getChildElement().addAttribute("board-id", boardId).addAttribute("column-id", columnId)
            .addAttribute("expected-revision", "5");
        createCard.getChildElement().addElement("title").setText("Metadata");
        createCard.getChildElement().addElement("priority").setText("urgent");
        final var requestedLabels = createCard.getChildElement().addElement("labels");
        requestedLabels.addElement("label").addAttribute("id", docsId);
        requestedLabels.addElement("label").addAttribute("id", bugId);
        final String cardId = result(createCard).attributeValue("id");

        var snapshot = snapshot(boardId);
        var card = snapshot.element("cards").element("card");
        assertEquals("urgent", card.elementText("priority"));
        assertEquals(docsId, card.element("labels").elements("label").get(0).attributeValue("id"));
        final var catalog = snapshot.element("labels").elements("label");
        final var bug = catalog.stream().filter(label -> bugId.equals(label.attributeValue("id"))).findFirst().orElseThrow();
        final var docs = catalog.stream().filter(label -> docsId.equals(label.attributeValue("id"))).findFirst().orElseThrow();
        assertEquals("rose", bug.attributeValue("color"));
        assertEquals("Guides", docs.elementText("name"));

        final IQ clear = request(IQ.Type.set, "update-card");
        clear.getChildElement().addAttribute("card-id", cardId).addAttribute("expected-revision", "1");
        clear.getChildElement().addElement("priority");
        clear.getChildElement().addElement("labels");
        assertEquals("2", result(clear).attributeValue("revision"));
        card = snapshot(boardId).element("cards").element("card");
        assertNull(card.element("priority"));
        assertNull(card.element("labels"));

        final IQ unknownPriority = request(IQ.Type.set, "update-card");
        unknownPriority.getChildElement().addAttribute("card-id", cardId).addAttribute("expected-revision", "2")
            .addElement("priority").setText("critical");
        assertEquals(PacketError.Condition.bad_request,
            protocol.handle(unknownPriority, "owner@example.org").getError().getCondition());

        final IQ duplicatePriority = request(IQ.Type.set, "update-card");
        duplicatePriority.getChildElement().addAttribute("card-id", cardId).addAttribute("expected-revision", "2");
        duplicatePriority.getChildElement().addElement("priority").setText("low");
        duplicatePriority.getChildElement().addElement("priority").setText("high");
        assertEquals(PacketError.Condition.bad_request,
            protocol.handle(duplicatePriority, "owner@example.org").getError().getCondition());

        final IQ unknownColor = request(IQ.Type.set, "create-label");
        unknownColor.getChildElement().addAttribute("board-id", boardId).addAttribute("expected-revision", "6");
        unknownColor.getChildElement().addElement("name").setText("Invalid");
        unknownColor.getChildElement().addElement("color").setText("teal");
        assertEquals(PacketError.Condition.bad_request,
            protocol.handle(unknownColor, "owner@example.org").getError().getCondition());

        final IQ unknownLabel = request(IQ.Type.set, "update-card");
        unknownLabel.getChildElement().addAttribute("card-id", cardId).addAttribute("expected-revision", "2");
        unknownLabel.getChildElement().addElement("labels").addElement("label").addAttribute("id", "unknown");
        assertEquals(PacketError.Condition.bad_request,
            protocol.handle(unknownLabel, "owner@example.org").getError().getCondition());

        final IQ delete = request(IQ.Type.set, "delete-label");
        delete.getChildElement().addAttribute("board-id", boardId).addAttribute("label-id", bugId)
            .addAttribute("expected-revision", "6");
        assertEquals("7", result(delete).attributeValue("board-revision"));
        assertEquals(1, snapshot(boardId).element("labels").elements("label").size());
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

    private String createLabel(String boardId, String name, String color, long expectedRevision) {
        final IQ create = request(IQ.Type.set, "create-label");
        create.getChildElement().addAttribute("board-id", boardId)
            .addAttribute("expected-revision", Long.toString(expectedRevision));
        create.getChildElement().addElement("name").setText(name);
        create.getChildElement().addElement("color").setText(color);
        return result(create).attributeValue("id");
    }

    private org.dom4j.Element snapshot(String boardId) {
        final IQ get = request(IQ.Type.get, "get-board");
        get.getChildElement().addAttribute("board-id", boardId);
        return protocol.handle(get, "owner@example.org").getChildElement();
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
