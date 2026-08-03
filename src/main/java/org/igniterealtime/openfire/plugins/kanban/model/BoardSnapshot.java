package org.igniterealtime.openfire.plugins.kanban.model;

import java.util.List;

public record BoardSnapshot(
    Board board, List<KanbanColumn> columns, List<Card> cards, List<Label> labels, List<Member> members
) {
    public BoardSnapshot {
        columns = List.copyOf(columns);
        cards = List.copyOf(cards);
        labels = List.copyOf(labels);
        members = List.copyOf(members);
    }

    public BoardSnapshot(Board board, List<KanbanColumn> columns, List<Card> cards, List<Member> members) {
        this(board, columns, cards, List.of(), members);
    }
}
