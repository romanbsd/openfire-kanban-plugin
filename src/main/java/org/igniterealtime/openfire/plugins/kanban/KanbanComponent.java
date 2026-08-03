package org.igniterealtime.openfire.plugins.kanban;

import org.dom4j.Element;
import org.igniterealtime.openfire.plugins.kanban.pubsub.BoardNodeProvisioner;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.igniterealtime.openfire.plugins.kanban.xml.KanbanProtocol;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.user.UserManager;
import org.xmpp.component.Component;
import org.xmpp.component.ComponentException;
import org.xmpp.component.ComponentManager;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

/** Internal component that owns Kanban protocol routing and discovery. */
public final class KanbanComponent implements Component {
    private final KanbanService service;
    private final KanbanProtocol protocol;
    private ComponentManager componentManager;
    private JID componentJid;

    public KanbanComponent(KanbanService service, String pubsubDomain) {
        this(service, pubsubDomain, BoardNodeProvisioner.NOOP);
    }

    public KanbanComponent(KanbanService service, String pubsubDomain, BoardNodeProvisioner nodeProvisioner) {
        this.service = service;
        protocol = new KanbanProtocol(service, pubsubDomain, nodeProvisioner);
    }

    @Override
    public String getName() {
        return "Openfire Kanban";
    }

    @Override
    public String getDescription() {
        return "Server-authoritative XMPP Kanban boards";
    }

    @Override
    public void initialize(JID jid, ComponentManager manager) {
        componentJid = jid;
        componentManager = manager;
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}

    @Override
    public void processPacket(Packet packet) {
        if (!(packet instanceof IQ iq) || (iq.getType() != IQ.Type.get && iq.getType() != IQ.Type.set)) {
            return;
        }
        final IQ response;
        if (isNamespace(iq, "http://jabber.org/protocol/disco#info")) {
            response = discoInfo(iq);
        } else if (!authenticatedLocalUser(iq.getFrom())) {
            response = IQ.createResultIQ(iq);
            response.setError(PacketError.Condition.forbidden);
        } else if (isNamespace(iq, "http://jabber.org/protocol/disco#items")) {
            response = discoItems(iq);
        } else {
            response = protocol.handle(iq, iq.getFrom().asBareJID().toString());
        }
        try {
            componentManager.sendPacket(this, response);
        } catch (ComponentException exception) {
            throw new IllegalStateException("Unable to route Kanban response", exception);
        }
    }

    private IQ discoInfo(IQ request) {
        final IQ response = IQ.createResultIQ(request);
        final Element query = response.setChildElement("query", "http://jabber.org/protocol/disco#info");
        query.addElement("identity").addAttribute("category", "collaboration").addAttribute("type", "kanban")
            .addAttribute("name", getName());
        query.addElement("feature").addAttribute("var", KanbanProtocol.MODEL_NAMESPACE);
        query.addElement("feature").addAttribute("var", KanbanProtocol.COMMAND_NAMESPACE);
        return response;
    }

    private IQ discoItems(IQ request) {
        final IQ response = IQ.createResultIQ(request);
        final Element query = response.setChildElement("query", "http://jabber.org/protocol/disco#items");
        final String actor = request.getFrom().asBareJID().toString();
        for (var board : service.listBoards(actor)) {
            query.addElement("item").addAttribute("jid", componentJid.toString()).addAttribute("node", board.id())
                .addAttribute("name", board.name());
        }
        return response;
    }

    private static boolean isNamespace(IQ iq, String namespace) {
        return iq.getChildElement() != null && namespace.equals(iq.getChildElement().getNamespaceURI());
    }

    private static boolean authenticatedLocalUser(JID from) {
        if (from == null) {
            return false;
        }
        final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        return domain.equals(from.getDomain()) && UserManager.getInstance().isRegisteredUser(from, false);
    }
}
