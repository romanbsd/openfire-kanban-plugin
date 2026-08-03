package org.igniterealtime.openfire.plugins.kanban.pubsub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.dom4j.Element;
import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.igniterealtime.openfire.plugins.kanban.model.BoardNodes;
import org.igniterealtime.openfire.plugins.kanban.model.BoardSnapshot;
import org.igniterealtime.openfire.plugins.kanban.model.Member;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.pubsub.LeafNode;
import org.jivesoftware.openfire.pubsub.NodeAffiliate;
import org.jivesoftware.openfire.pubsub.PubSubEngine;
import org.jivesoftware.openfire.pubsub.PubSubEngine.CreateNodeResponse;
import org.jivesoftware.openfire.pubsub.PubSubModule;
import org.jivesoftware.openfire.pubsub.PubSubPersistenceProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.xmpp.packet.JID;

class OpenfirePubSubPublisherTest {
    @Test
    void createsMissingSnapshotNodesAndGrantsEveryMemberAccess() {
        final String boardId = "board-1";
        final JID owner = new JID("owner@example.org");
        final BoardSnapshot snapshot = new BoardSnapshot(
            new Board(boardId, "Board", 1, owner.toBareJID(), 0, 0),
            List.of(),
            List.of(),
            List.of(new Member("member-1", boardId, owner.toBareJID(), Role.OWNER, 0))
        );
        final XMPPServer server = mock(XMPPServer.class);
        final PubSubModule module = mock(PubSubModule.class);
        final PubSubPersistenceProvider persistence = mock(PubSubPersistenceProvider.class);
        final LeafNode cards = mock(LeafNode.class);
        final LeafNode activity = mock(LeafNode.class);
        final NodeAffiliate cardsAffiliate = mock(NodeAffiliate.class);
        final NodeAffiliate activityAffiliate = mock(NodeAffiliate.class);
        when(server.getPubSubModule()).thenReturn(module);
        when(module.getPersistenceProvider()).thenReturn(persistence);
        when(module.getNode(BoardNodes.cards(boardId))).thenReturn(null, cards);
        when(module.getNode(BoardNodes.activity(boardId))).thenReturn(null, activity);
        when(cards.getAffiliate(owner)).thenReturn(cardsAffiliate);
        when(activity.getAffiliate(owner)).thenReturn(activityAffiliate);

        final JID componentJid = new JID("kanban.example.org");
        try (MockedStatic<XMPPServer> servers = mockStatic(XMPPServer.class);
             MockedStatic<PubSubEngine> engine = mockStatic(PubSubEngine.class)) {
            servers.when(XMPPServer::getInstance).thenReturn(server);
            engine.when(() -> PubSubEngine.createNodeHelper(
                eq(module), eq(componentJid), any(), eq(BoardNodes.cards(boardId)), isNull()))
                .thenReturn(new CreateNodeResponse(null, null, cards));
            engine.when(() -> PubSubEngine.createNodeHelper(
                eq(module), eq(componentJid), any(), eq(BoardNodes.activity(boardId)), isNull()))
                .thenReturn(new CreateNodeResponse(null, null, activity));

            new OpenfirePubSubPublisher(componentJid).ensureReady(snapshot);

            engine.verify(() -> PubSubEngine.createNodeHelper(
                eq(module), eq(componentJid), argThat(OpenfirePubSubPublisherTest::isCompatibleConfiguration),
                eq(BoardNodes.cards(boardId)), isNull()));
            engine.verify(() -> PubSubEngine.createNodeHelper(
                eq(module), eq(componentJid), argThat(OpenfirePubSubPublisherTest::isCompatibleConfiguration),
                eq(BoardNodes.activity(boardId)), isNull()));
        }

        verify(cardsAffiliate).setAffiliation(NodeAffiliate.Affiliation.member);
        verify(activityAffiliate).setAffiliation(NodeAffiliate.Affiliation.member);
        verify(persistence).updateAffiliation(cards, cardsAffiliate);
        verify(persistence).updateAffiliation(activity, activityAffiliate);
    }

    private static boolean isCompatibleConfiguration(Element configure) {
        final Element form = configure.element("x");
        final String publishModel = fieldValue(form, "pubsub#publish_model");
        final String maxItems = fieldValue(form, "pubsub#max_items");
        try {
            return "publishers".equals(publishModel) && Integer.parseInt(maxItems) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String fieldValue(Element form, String variable) {
        return form.elements("field").stream()
            .filter(field -> variable.equals(field.attributeValue("var")))
            .findFirst()
            .orElseThrow()
            .elementText("value");
    }
}
