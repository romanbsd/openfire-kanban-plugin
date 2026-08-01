package org.igniterealtime.openfire.plugins.kanban.xml;

import java.util.Locale;

import org.igniterealtime.openfire.plugins.kanban.model.ActivityType;
import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.igniterealtime.openfire.plugins.kanban.model.Card;
import org.igniterealtime.openfire.plugins.kanban.model.KanbanColumn;
import org.igniterealtime.openfire.plugins.kanban.model.Member;

/** XML serialization for database activity payloads and PubSub projections. */
public final class KanbanXml {
    private KanbanXml() {}

    public static String boardXml(Board board) {
        return "<board xmlns='urn:xmpp:kanban:0' id='" + board.id() + "' revision='" + board.revision()
            + "'><name>" + escape(board.name()) + "</name></board>";
    }

    public static String columnXml(KanbanColumn column) {
        return "<column xmlns='urn:xmpp:kanban:0' id='" + column.id() + "' rank='" + column.rank()
            + "'><name>" + escape(column.name()) + "</name><wip-limit>" + column.wipLimit() + "</wip-limit></column>";
    }

    public static String cardXml(Card card) {
        final StringBuilder xml = new StringBuilder("<card xmlns='urn:xmpp:kanban:0' id='").append(card.id())
            .append("' revision='").append(card.revision()).append("'><title>").append(escape(card.title()))
            .append("</title><column id='").append(card.columnId()).append("'/><rank>").append(card.rank()).append("</rank>");
        if (card.description() != null) {
            xml.append("<description>").append(escape(card.description())).append("</description>");
        }
        if (card.assigneeJid() != null) {
            xml.append("<assignee jid='").append(escape(card.assigneeJid())).append("'/>");
        }
        return xml.append("</card>").toString();
    }

    public static String tombstoneXml(Card card) {
        return "<card-tombstone xmlns='urn:xmpp:kanban:0' id='" + card.id() + "' revision='" + card.revision() + "'/>";
    }

    public static String memberXml(Member member) {
        return "<member xmlns='urn:xmpp:kanban:0' id='" + member.id() + "' jid='" + escape(member.bareJid())
            + "' role='" + member.role().name().toLowerCase(Locale.ROOT) + "'/>";
    }

    public static String eventXml(
        String activityId, ActivityType type, String boardId, String actor, long occurredAt, String entityPayload
    ) {
        return "<event xmlns='urn:xmpp:kanban:events:0' id='" + activityId + "' type='" + type.wireName()
            + "' board-id='" + boardId + "' actor='" + escape(actor) + "' occurred-at='" + occurredAt + "'>"
            + entityPayload + "</event>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&apos;").replace("\"", "&quot;");
    }
}
