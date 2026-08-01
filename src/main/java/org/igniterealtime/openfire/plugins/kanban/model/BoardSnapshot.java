package org.igniterealtime.openfire.plugins.kanban.model;

import java.util.List;

public record BoardSnapshot(Board board, List<KanbanColumn> columns, List<Card> cards, List<Member> members) {
    public BoardSnapshot {
        columns = List.copyOf(columns);
        cards = List.copyOf(cards);
        members = List.copyOf(members);
    }
}
