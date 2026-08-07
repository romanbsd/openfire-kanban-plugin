package org.igniterealtime.openfire.plugins.kanban;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.igniterealtime.openfire.plugins.kanban.xml.KanbanProtocol;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.XMPPServerInfo;
import org.jivesoftware.openfire.user.UserManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.xmpp.component.Component;
import org.xmpp.component.ComponentManager;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

class KanbanComponentTest {
    @Test
    void ignoresNonIqPacketsAndDescribesItself() throws Exception {
        final KanbanComponent component = new KanbanComponent(mock(KanbanService.class), "pubsub.example.org");
        final ComponentManager manager = mock(ComponentManager.class);
        component.initialize(new JID("kanban.example.org"), manager);
        component.start();
        component.processPacket(new Message());
        component.shutdown();

        assertEquals("Openfire Kanban", component.getName());
        assertEquals("Server-authoritative XMPP Kanban boards", component.getDescription());
        verify(manager, never()).sendPacket(any(Component.class), any(Packet.class));
    }

    @Test
    void answersOpenfireRegistrationDiscoProbeWithoutAUserIdentity() throws Exception {
        final CapturingComponent capture = capturingComponent(mock(KanbanService.class));

        final IQ probe = command(IQ.Type.get, "query", "example.org");
        probe.setChildElement("query", "http://jabber.org/protocol/disco#info");
        capture.component.processPacket(probe);

        final IQ response = (IQ) capture.sent.get();
        assertEquals(IQ.Type.result, response.getType());
        assertNotNull(response.getChildElement().element("identity"));
        assertEquals("collaboration", response.getChildElement().element("identity").attributeValue("category"));
    }

    @Test
    void rejectsUnknownSendersAndServesAuthorizedDiscoveryAndCommands() throws Exception {
        final CapturingComponent capture = capturingComponent(new KanbanService(TestDatabase.repository(), 0));
        final KanbanComponent component = capture.component;
        final AtomicReference<Packet> sent = capture.sent;

        final XMPPServer server = mock(XMPPServer.class);
        final XMPPServerInfo info = mock(XMPPServerInfo.class);
        final UserManager users = mock(UserManager.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getXMPPDomain()).thenReturn("example.org");
        try (MockedStatic<XMPPServer> servers = mockStatic(XMPPServer.class);
             MockedStatic<UserManager> userManagers = mockStatic(UserManager.class)) {
            servers.when(XMPPServer::getInstance).thenReturn(server);
            userManagers.when(UserManager::getInstance).thenReturn(users);

            final IQ forbidden = command(IQ.Type.get, "list-boards", "unknown@example.org/device");
            component.processPacket(forbidden);
            assertEquals(PacketError.Condition.forbidden, sent.get().getError().getCondition());

            when(users.isRegisteredUser(any(JID.class), eq(false))).thenReturn(true);
            final IQ create = command(IQ.Type.set, "create-board", "owner@example.org/device");
            create.getChildElement().addAttribute("expected-revision", "0").addElement("name").setText("Board");
            component.processPacket(create);
            assertEquals(IQ.Type.result, ((IQ) sent.get()).getType());

            final IQ infoRequest = command(IQ.Type.get, "query", "owner@example.org/device");
            infoRequest.setChildElement("query", "http://jabber.org/protocol/disco#info");
            component.processPacket(infoRequest);
            assertNotNull(((IQ) sent.get()).getChildElement().element("identity"));

            final IQ itemsRequest = command(IQ.Type.get, "query", "owner@example.org/device");
            itemsRequest.setChildElement("query", "http://jabber.org/protocol/disco#items");
            component.processPacket(itemsRequest);
            assertEquals("Board", ((IQ) sent.get()).getChildElement().element("item").attributeValue("name"));
        }
    }

    private static CapturingComponent capturingComponent(KanbanService service) throws Exception {
        final KanbanComponent component = new KanbanComponent(service, "pubsub.example.org");
        final ComponentManager manager = mock(ComponentManager.class);
        final AtomicReference<Packet> sent = new AtomicReference<>();
        component.initialize(new JID("kanban.example.org"), manager);
        doAnswer(invocation -> {
            sent.set(invocation.getArgument(1));
            return null;
        }).when(manager).sendPacket(eq(component), any(Packet.class));
        return new CapturingComponent(component, sent);
    }

    private static IQ command(IQ.Type type, String name, String from) {
        final IQ iq = new IQ(type);
        iq.setID(name);
        iq.setFrom(new JID(from));
        iq.setTo(new JID("kanban.example.org"));
        iq.setChildElement(name, KanbanProtocol.COMMAND_NAMESPACE);
        return iq;
    }

    private record CapturingComponent(KanbanComponent component, AtomicReference<Packet> sent) {}
}
