package org.igniterealtime.openfire.plugins.kanban.pubsub;

import java.util.List;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.igniterealtime.openfire.plugins.kanban.model.BoardNodes;
import org.igniterealtime.openfire.plugins.kanban.model.BoardSnapshot;
import org.igniterealtime.openfire.plugins.kanban.outbox.PubSubPublisher;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.OutboxEntry;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.pubsub.LeafNode;
import org.jivesoftware.openfire.pubsub.Node;
import org.jivesoftware.openfire.pubsub.NodeAffiliate;
import org.jivesoftware.openfire.pubsub.PubSubEngine;
import org.jivesoftware.openfire.pubsub.PubSubEngine.CreateNodeResponse;
import org.jivesoftware.openfire.pubsub.PubSubModule;
import org.xmpp.packet.JID;

/** Publishes the outbox into Openfire's existing PubSub service. */
public final class OpenfirePubSubPublisher implements PubSubPublisher, BoardNodeProvisioner {
    private final JID componentJid;

    public OpenfirePubSubPublisher(JID componentJid) {
        this.componentJid = componentJid;
    }

    @Override
    public void ensureReady(BoardSnapshot snapshot) {
        final String boardId = snapshot.board().id();
        final LeafNode cards = ensureNode(BoardNodes.cards(boardId));
        final LeafNode activity = ensureNode(BoardNodes.activity(boardId));
        snapshot.members().forEach(member -> {
            final JID jid = new JID(member.bareJid());
            setAffiliation(cards, jid, NodeAffiliate.Affiliation.member);
            setAffiliation(activity, jid, NodeAffiliate.Affiliation.member);
        });
    }

    @Override
    public void publish(OutboxEntry entry) throws Exception {
        final LeafNode node = ensureNode(entry.nodeId());
        switch (entry.kind()) {
            case ACCESS_GRANT -> setAffiliation(node, new JID(entry.payload()), NodeAffiliate.Affiliation.member);
            case ACCESS_REVOKE -> setAffiliation(node, new JID(entry.payload()), NodeAffiliate.Affiliation.none);
            case CARD_SNAPSHOT, ACTIVITY -> publishItem(node, entry.itemId(), entry.payload());
        }
    }

    private LeafNode ensureNode(String nodeId) {
        final PubSubModule module = XMPPServer.getInstance().getPubSubModule();
        Node node = module.getNode(nodeId);
        if (node == null) {
            final CreateNodeResponse creation =
                PubSubEngine.createNodeHelper(module, componentJid, nodeConfiguration(), nodeId, null);
            if (creation.creationStatus != null) {
                throw new IllegalStateException(
                    "Unable to create PubSub node " + nodeId + ": " + creation.creationStatus);
            }
            node = creation.newNode != null ? creation.newNode : module.getNode(nodeId);
            if (node == null) {
                throw new IllegalStateException("Unable to create PubSub node " + nodeId);
            }
        }
        if (!(node instanceof LeafNode leaf)) {
            throw new IllegalStateException("Kanban PubSub node is not a leaf node: " + nodeId);
        }
        return leaf;
    }

    private Element nodeConfiguration() {
        final Element configure = DocumentHelper.createElement("configure");
        final Element form = configure.addElement("x", "jabber:x:data").addAttribute("type", "submit");
        addField(form, "FORM_TYPE", "http://jabber.org/protocol/pubsub#node_config", "hidden");
        addField(form, "pubsub#access_model", "whitelist", null);
        addField(form, "pubsub#publish_model", "publishers", null);
        addField(form, "pubsub#persist_items", "1", null);
        addField(form, "pubsub#max_items", Integer.toString(Integer.MAX_VALUE), null);
        return configure;
    }

    private static void addField(Element form, String variable, String value, String type) {
        final Element field = form.addElement("field").addAttribute("var", variable);
        if (type != null) {
            field.addAttribute("type", type);
        }
        field.addElement("value").setText(value);
    }

    private static void setAffiliation(LeafNode node, JID jid, NodeAffiliate.Affiliation affiliation) {
        final PubSubModule module = XMPPServer.getInstance().getPubSubModule();
        NodeAffiliate affiliate = node.getAffiliate(jid);
        if (affiliate == null) {
            affiliate = new NodeAffiliate(node, jid);
            affiliate.setAffiliation(affiliation);
            node.addAffiliate(affiliate);
            module.getPersistenceProvider().createAffiliation(node, affiliate);
        } else {
            affiliate.setAffiliation(affiliation);
            module.getPersistenceProvider().updateAffiliation(node, affiliate);
        }
    }

    private void publishItem(LeafNode node, String itemId, String payload) throws Exception {
        final Element item = DocumentHelper.createElement("item").addAttribute("id", itemId);
        item.add(DocumentHelper.parseText(payload).getRootElement().detach());
        node.publishItems(componentJid, List.of(item));
    }
}
