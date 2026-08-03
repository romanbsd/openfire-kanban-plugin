package org.igniterealtime.openfire.plugins.kanban.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.dom4j.Element;
import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.igniterealtime.openfire.plugins.kanban.model.BoardNodes;
import org.igniterealtime.openfire.plugins.kanban.model.BoardSnapshot;
import org.igniterealtime.openfire.plugins.kanban.model.Card;
import org.igniterealtime.openfire.plugins.kanban.model.CardPriority;
import org.igniterealtime.openfire.plugins.kanban.model.KanbanColumn;
import org.igniterealtime.openfire.plugins.kanban.model.Label;
import org.igniterealtime.openfire.plugins.kanban.model.LabelColor;
import org.igniterealtime.openfire.plugins.kanban.model.Member;
import org.igniterealtime.openfire.plugins.kanban.model.Identified;
import org.igniterealtime.openfire.plugins.kanban.model.Revisioned;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.igniterealtime.openfire.plugins.kanban.pubsub.BoardNodeProvisioner;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanException;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService.CardPatch;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService.FieldPatch;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService.Result;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses and serializes the version-zero Kanban IQ contract. */
public final class KanbanProtocol {
    private static final Logger Log = LoggerFactory.getLogger(KanbanProtocol.class);
    public static final String MODEL_NAMESPACE = "urn:xmpp:kanban:0";
    public static final String COMMAND_NAMESPACE = "urn:xmpp:kanban:commands:0";

    private final KanbanService service;
    private final String pubsubDomain;
    private final BoardNodeProvisioner nodeProvisioner;

    public KanbanProtocol(KanbanService service, String pubsubDomain) {
        this(service, pubsubDomain, BoardNodeProvisioner.NOOP);
    }

    public KanbanProtocol(KanbanService service, String pubsubDomain, BoardNodeProvisioner nodeProvisioner) {
        this.service = Objects.requireNonNull(service);
        this.pubsubDomain = Objects.requireNonNull(pubsubDomain);
        this.nodeProvisioner = Objects.requireNonNull(nodeProvisioner);
    }

    public IQ handle(IQ request, String actor) {
        try {
            final Element command = request.getChildElement();
            if (command == null || !COMMAND_NAMESPACE.equals(command.getNamespaceURI())) {
                return error(request, PacketError.Condition.bad_request, "bad-request", null);
            }
            return switch (command.getName()) {
                case "list-boards" -> listBoards(request, actor);
                case "get-board" -> getBoard(request, actor, command);
                case "create-board" -> createBoard(request, actor, command);
                case "create-column" -> createColumn(request, actor, command);
                case "create-card" -> createCard(request, actor, command);
                case "update-card" -> updateCard(request, actor, command);
                case "move-card" -> moveCard(request, actor, command);
                case "delete-card" -> deleteCard(request, actor, command);
                case "create-label" -> createLabel(request, actor, command);
                case "update-label" -> updateLabel(request, actor, command);
                case "delete-label" -> deleteLabel(request, actor, command);
                case "add-member" -> addMember(request, actor, command);
                case "update-member-role" -> updateMember(request, actor, command);
                case "remove-member" -> removeMember(request, actor, command);
                default -> error(request, PacketError.Condition.bad_request, "bad-request", null);
            };
        } catch (IllegalArgumentException exception) {
            return error(request, PacketError.Condition.bad_request, "bad-request", null);
        } catch (KanbanException exception) {
            return mapError(request, exception);
        }
    }

    private IQ listBoards(IQ request, String actor) {
        requireType(request, IQ.Type.get);
        final IQ response = IQ.createResultIQ(request);
        final Element boards = response.setChildElement("boards", MODEL_NAMESPACE);
        for (Board board : service.listBoards(actor)) {
            addBoard(boards.addElement("board"), board);
        }
        return response;
    }

    private IQ getBoard(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.get);
        final BoardSnapshot snapshot = service.snapshot(actor, required(command, "board-id"));
        try {
            nodeProvisioner.ensureReady(snapshot);
        } catch (RuntimeException exception) {
            Log.warn("Unable to prepare PubSub nodes for board {}", snapshot.board().id(), exception);
            return error(request, PacketError.Condition.service_unavailable, "pubsub-unavailable", null);
        }
        final IQ response = IQ.createResultIQ(request);
        final Element root = response.setChildElement("snapshot", MODEL_NAMESPACE);
        root.addAttribute("pubsub-service", pubsubDomain);
        root.addAttribute("cards-node", BoardNodes.cards(snapshot.board().id()));
        root.addAttribute("activity-node", BoardNodes.activity(snapshot.board().id()));
        addBoard(root.addElement("board"), snapshot.board());
        final Element columns = root.addElement("columns");
        snapshot.columns().forEach(column -> addColumn(columns.addElement("column"), column));
        final Element cards = root.addElement("cards");
        snapshot.cards().forEach(card -> addCard(cards.addElement("card"), card));
        final Element labels = root.addElement("labels");
        snapshot.labels().forEach(label -> addLabel(labels.addElement("label"), label));
        final Element members = root.addElement("members");
        snapshot.members().forEach(member -> addMember(members.addElement("member"), member));
        return response;
    }

    private IQ createBoard(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.createBoard(actor, childText(command, "name"), revision(command)));
    }

    private IQ createColumn(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        final String limit = childTextOptional(command, "wip-limit");
        return result(request, service.createColumn(actor, required(command, "board-id"), childText(command, "name"),
            limit == null ? null : Integer.valueOf(limit), revision(command)));
    }

    private IQ createCard(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.createCard(actor, required(command, "board-id"), required(command, "column-id"),
            childText(command, "title"), childTextOptional(command, "description"), childBareJid(command, "assignee", "jid"),
            cardPriority(command), labelIds(command), revision(command)));
    }

    private IQ updateCard(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        final CardPatch patch = new CardPatch(textPatch(command, "title"), textPatch(command, "description"),
            bareJidPatch(command, "assignee", "jid"), priorityPatch(command), labelIdsPatch(command));
        return result(request, service.updateCard(actor, required(command, "card-id"), revision(command), patch));
    }

    private IQ moveCard(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.moveCard(actor, required(command, "card-id"), required(command, "column-id"),
            optional(command, "before-card"), optional(command, "after-card"), revision(command)));
    }

    private IQ deleteCard(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.deleteCard(actor, required(command, "card-id"), revision(command)));
    }

    private IQ createLabel(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.createLabel(actor, required(command, "board-id"), childText(command, "name"),
            LabelColor.fromWire(childText(command, "color")), revision(command)));
    }

    private IQ updateLabel(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.updateLabel(actor, required(command, "board-id"), required(command, "label-id"),
            textPatch(command, "name"), labelColorPatch(command), revision(command)));
    }

    private IQ deleteLabel(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.deleteLabel(actor, required(command, "board-id"),
            required(command, "label-id"), revision(command)));
    }

    private IQ addMember(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.addMember(actor, required(command, "board-id"), requiredBareJid(command, "jid"),
            role(command), revision(command)));
    }

    private IQ updateMember(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.updateMemberRole(actor, required(command, "board-id"), requiredBareJid(command, "jid"),
            role(command), revision(command)));
    }

    private IQ removeMember(IQ request, String actor, Element command) {
        requireType(request, IQ.Type.set);
        return result(request, service.removeMember(actor, required(command, "board-id"), requiredBareJid(command, "jid"),
            revision(command)));
    }

    private static IQ result(IQ request, Result<? extends Identified> result) {
        final IQ response = IQ.createResultIQ(request);
        final Element element = response.setChildElement("result", COMMAND_NAMESPACE)
            .addAttribute("board-revision", Long.toString(result.boardRevision()));
        final Identified value = result.value();
        element.addAttribute("id", value.id());
        if (value instanceof Revisioned revisioned) {
            element.addAttribute("revision", Long.toString(revisioned.revision()));
        }
        return response;
    }

    private static IQ mapError(IQ request, KanbanException exception) {
        final PacketError.Condition condition = switch (exception.code()) {
            case FORBIDDEN, FINAL_OWNER -> PacketError.Condition.forbidden;
            case ITEM_NOT_FOUND, INVALID_COLUMN -> PacketError.Condition.item_not_found;
            case REVISION_CONFLICT -> PacketError.Condition.conflict;
            case BAD_REQUEST, INVALID_POSITION, WIP_LIMIT_EXCEEDED, DUPLICATE_MEMBER -> PacketError.Condition.bad_request;
        };
        return error(request, condition, exception.code().name().toLowerCase(Locale.ROOT).replace('_', '-'),
            exception.currentRevision());
    }

    private static IQ error(IQ request, PacketError.Condition condition, String applicationCondition, Long currentRevision) {
        final IQ response = IQ.createResultIQ(request);
        response.setError(condition);
        final Element detail = response.getError().getElement().addElement(applicationCondition, MODEL_NAMESPACE);
        if (currentRevision != null) {
            detail.addAttribute("current-revision", Long.toString(currentRevision));
        }
        return response;
    }

    private static void requireType(IQ request, IQ.Type expected) {
        if (request.getType() != expected) {
            throw new IllegalArgumentException("Wrong IQ type");
        }
    }

    private static long revision(Element command) {
        return Long.parseLong(required(command, "expected-revision"));
    }

    private static Role role(Element command) {
        return Role.valueOf(required(command, "role").toUpperCase(Locale.ROOT));
    }

    private static String required(Element element, String attribute) {
        final String value = element.attributeValue(attribute);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing attribute " + attribute);
        }
        return value;
    }

    private static String optional(Element element, String attribute) {
        final String value = element.attributeValue(attribute);
        return value == null || value.isBlank() ? null : value;
    }

    private static String childText(Element element, String name) {
        final String value = childTextOptional(element, name);
        if (value == null) {
            throw new IllegalArgumentException("Missing element " + name);
        }
        return value;
    }

    private static String childTextOptional(Element element, String name) {
        final Element child = element.element(name);
        return child == null ? null : child.getText();
    }

    private static String childAttribute(Element element, String childName, String attribute) {
        final Element child = element.element(childName);
        return child == null ? null : child.attributeValue(attribute);
    }

    private static String childBareJid(Element element, String childName, String attribute) {
        final String value = childAttribute(element, childName, attribute);
        return value == null ? null : new JID(value).toBareJID();
    }

    private static FieldPatch<String> textPatch(Element element, String name) {
        final Element child = element.element(name);
        return child == null ? FieldPatch.absent() : FieldPatch.set(child.getText());
    }

    private static FieldPatch<String> attributePatch(Element element, String childName, String attribute) {
        final Element child = element.element(childName);
        return child == null ? FieldPatch.absent() : FieldPatch.set(child.attributeValue(attribute));
    }

    private static FieldPatch<String> bareJidPatch(Element element, String childName, String attribute) {
        final FieldPatch<String> patch = attributePatch(element, childName, attribute);
        return !patch.present() || patch.value() == null ? patch : FieldPatch.set(new JID(patch.value()).toBareJID());
    }

    private static FieldPatch<CardPriority> priorityPatch(Element element) {
        final Element child = singleChild(element, "priority");
        return child == null ? FieldPatch.absent() : FieldPatch.set(CardPriority.fromWire(child.getText()));
    }

    private static CardPriority cardPriority(Element element) {
        final Element child = singleChild(element, "priority");
        return CardPriority.fromWire(child == null ? null : child.getText());
    }

    private static FieldPatch<LabelColor> labelColorPatch(Element element) {
        final Element child = element.element("color");
        return child == null ? FieldPatch.absent() : FieldPatch.set(LabelColor.fromWire(child.getText()));
    }

    private static List<String> labelIds(Element element) {
        final Element labels = singleChild(element, "labels");
        if (labels == null) {
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        for (Element label : labels.elements("label")) {
            ids.add(required(label, "id"));
        }
        return List.copyOf(ids);
    }

    private static FieldPatch<List<String>> labelIdsPatch(Element element) {
        return singleChild(element, "labels") == null ? FieldPatch.absent() : FieldPatch.set(labelIds(element));
    }

    private static Element singleChild(Element element, String name) {
        final List<Element> children = element.elements(name);
        if (children.size() > 1) {
            throw new IllegalArgumentException("Duplicate element " + name);
        }
        return children.isEmpty() ? null : children.get(0);
    }

    private static String requiredBareJid(Element element, String attribute) {
        return new JID(required(element, attribute)).toBareJID();
    }

    private static void addBoard(Element element, Board board) {
        element.addAttribute("id", board.id()).addAttribute("revision", Long.toString(board.revision()));
        element.addElement("name").setText(board.name());
    }

    private static void addColumn(Element element, KanbanColumn column) {
        element.addAttribute("id", column.id()).addAttribute("rank", column.rank());
        element.addElement("name").setText(column.name());
        element.addElement("wip-limit").setText(Integer.toString(column.wipLimit()));
    }

    private static void addCard(Element element, Card card) {
        element.addAttribute("id", card.id()).addAttribute("revision", Long.toString(card.revision()));
        element.addElement("title").setText(card.title());
        element.addElement("column").addAttribute("id", card.columnId());
        element.addElement("rank").setText(card.rank());
        if (card.description() != null) {
            element.addElement("description").setText(card.description());
        }
        if (card.assigneeJid() != null) {
            element.addElement("assignee").addAttribute("jid", card.assigneeJid());
        }
        if (card.priority() != CardPriority.NONE) {
            element.addElement("priority").setText(card.priority().wireName());
        }
        if (!card.labelIds().isEmpty()) {
            final Element labels = element.addElement("labels");
            card.labelIds().forEach(labelId -> labels.addElement("label").addAttribute("id", labelId));
        }
    }

    private static void addLabel(Element element, Label label) {
        element.addAttribute("id", label.id()).addAttribute("color", label.color().wireName());
        element.addElement("name").setText(label.name());
    }

    private static void addMember(Element element, Member member) {
        element.addAttribute("id", member.id()).addAttribute("jid", member.bareJid())
            .addAttribute("role", member.role().name().toLowerCase(Locale.ROOT));
    }
}
