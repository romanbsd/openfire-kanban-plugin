package org.igniterealtime.openfire.plugins.kanban.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.igniterealtime.openfire.plugins.kanban.model.Card;
import org.igniterealtime.openfire.plugins.kanban.model.CardPriority;
import org.igniterealtime.openfire.plugins.kanban.model.KanbanColumn;
import org.igniterealtime.openfire.plugins.kanban.model.Label;
import org.igniterealtime.openfire.plugins.kanban.model.LabelColor;
import org.igniterealtime.openfire.plugins.kanban.model.Member;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.igniterealtime.openfire.plugins.kanban.outbox.OutboxKind;

/** JDBC persistence boundary. All statements participating in a mutation share one {@link Transaction}. */
public final class JdbcKanbanRepository {
    public record OutboxEntry(
        String id, String boardId, long sequence, OutboxKind kind, String nodeId, String itemId,
        String payload, int attempts
    ) {}

    public record Diagnostics(long boards, long cards, long pending, long publishing, long retrying) {
        public long getBoards() { return boards; }
        public long getCards() { return cards; }
        public long getPending() { return pending; }
        public long getPublishing() { return publishing; }
        public long getRetrying() { return retrying; }
    }
    public record RetentionResult(int outboxRows, int activityRows) {}
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
        T execute(Transaction transaction) throws SQLException;
    }

    private final ConnectionFactory connectionFactory;

    public JdbcKanbanRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public <T> T transact(Work<T> work) {
        try (Connection connection = connectionFactory.open()) {
            final boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                final T result = work.execute(new Transaction(connection));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Kanban database transaction failed", exception);
        }
    }

    public List<Board> listBoards(String bareJid) {
        return transact(transaction -> transaction.listBoards(bareJid));
    }

    public Optional<OutboxEntry> claimNext(String workerId, long now, long leaseUntil) {
        return transact(transaction -> transaction.claimNext(workerId, now, leaseUntil));
    }

    public void markDelivered(String outboxId, String workerId, long now) {
        transact(transaction -> {
            transaction.markDelivered(outboxId, workerId, now);
            return null;
        });
    }

    public void markFailed(String outboxId, String workerId, long retryAt, String error) {
        transact(transaction -> {
            transaction.markFailed(outboxId, workerId, retryAt, error);
            return null;
        });
    }

    public Diagnostics diagnostics(long now) {
        return transact(transaction -> transaction.diagnostics(now));
    }

    public RetentionResult purgeHistory(long cutoff) {
        return transact(transaction -> transaction.purgeHistory(cutoff));
    }

    public static final class Transaction {
        private static final String BOARD_COLUMNS = "boardID,name,revision,createdBy,createdAt,updatedAt";
        private static final String CARD_COLUMNS =
            "cardID,boardID,columnID,revision,rank,title,description,assigneeJID,priority,deleted,createdBy,createdAt,updatedAt,deletedAt";
        private static final String COLUMN_COLUMNS = "columnID,boardID,name,rank,wipLimit,createdAt";
        private static final String LABEL_COLUMNS = "labelID,boardID,name,color,createdAt";
        private static final String MEMBER_COLUMNS = "memberID,boardID,bareJID,role,createdAt";

        @FunctionalInterface
        private interface RowMapper<T> {
            T read(ResultSet result) throws SQLException;
        }

        private final Connection connection;

        Transaction(Connection connection) {
            this.connection = connection;
        }

        public Optional<Board> board(String boardId) throws SQLException {
            return queryOne("SELECT " + BOARD_COLUMNS + " FROM ofKanbanBoard WHERE boardID=? FOR UPDATE",
                Transaction::readBoard, boardId);
        }

        public Optional<Card> card(String cardId) throws SQLException {
            final Optional<Card> card = queryOne("SELECT " + CARD_COLUMNS + " FROM ofKanbanCard WHERE cardID=?",
                Transaction::readCard, cardId);
            return card.isEmpty() ? card : Optional.of(withLabels(card.get()));
        }

        public Optional<Label> label(String labelId) throws SQLException {
            return queryOne("SELECT " + LABEL_COLUMNS + " FROM ofKanbanLabel WHERE labelID=?",
                Transaction::readLabel, labelId);
        }

        public Optional<KanbanColumn> column(String columnId) throws SQLException {
            return queryOne("SELECT " + COLUMN_COLUMNS + " FROM ofKanbanColumn WHERE columnID=?",
                Transaction::readColumn, columnId);
        }

        public Optional<Member> member(String boardId, String bareJid) throws SQLException {
            return queryOne("SELECT " + MEMBER_COLUMNS + " FROM ofKanbanMember WHERE boardID=? AND bareJID=?",
                Transaction::readMember, boardId, bareJid);
        }

        public List<Board> listBoards(String bareJid) throws SQLException {
            return queryList("SELECT b.boardID,b.name,b.revision,b.createdBy,b.createdAt,b.updatedAt "
                + "FROM ofKanbanBoard b JOIN ofKanbanMember m ON b.boardID=m.boardID "
                + "WHERE m.bareJID=? ORDER BY b.name,b.boardID", Transaction::readBoard, bareJid);
        }

        public List<KanbanColumn> columns(String boardId) throws SQLException {
            return queryList("SELECT " + COLUMN_COLUMNS + " FROM ofKanbanColumn WHERE boardID=? ORDER BY rank",
                Transaction::readColumn, boardId);
        }

        public List<Card> cards(String boardId) throws SQLException {
            return withLabels(queryList("SELECT " + CARD_COLUMNS
                + " FROM ofKanbanCard WHERE boardID=? AND deleted=0 ORDER BY columnID,rank",
                Transaction::readCard, boardId));
        }

        public List<Card> cardsInColumn(String columnId) throws SQLException {
            return withLabels(queryList("SELECT " + CARD_COLUMNS
                + " FROM ofKanbanCard WHERE columnID=? AND deleted=0 ORDER BY rank FOR UPDATE",
                Transaction::readCard, columnId));
        }

        public List<Card> cardsWithLabel(String boardId, String labelId) throws SQLException {
            return withLabels(queryList("SELECT " + prefixedCardColumns("c")
                + " FROM ofKanbanCard c JOIN ofKanbanCardLabel cl ON c.cardID=cl.cardID"
                + " WHERE c.boardID=? AND cl.labelID=? AND c.deleted=0 ORDER BY c.columnID,c.rank FOR UPDATE",
                Transaction::readCard, boardId, labelId));
        }

        public List<Label> labels(String boardId) throws SQLException {
            return queryList("SELECT " + LABEL_COLUMNS + " FROM ofKanbanLabel WHERE boardID=? ORDER BY createdAt,labelID",
                Transaction::readLabel, boardId);
        }

        public List<Member> members(String boardId) throws SQLException {
            return queryList("SELECT " + MEMBER_COLUMNS + " FROM ofKanbanMember WHERE boardID=? ORDER BY bareJID",
                Transaction::readMember, boardId);
        }

        public void insertBoard(Board board) throws SQLException {
            execute("INSERT INTO ofKanbanBoard(boardID,name,revision,createdBy,createdAt,updatedAt) VALUES(?,?,?,?,?,?)",
                board.id(), board.name(), board.revision(), board.createdBy(), board.createdAt(), board.updatedAt());
        }

        public void insertColumn(KanbanColumn column) throws SQLException {
            execute("INSERT INTO ofKanbanColumn(columnID,boardID,name,rank,wipLimit,createdAt) VALUES(?,?,?,?,?,?)",
                column.id(), column.boardId(), column.name(), column.rank(), column.wipLimit(), column.createdAt());
        }

        public void insertCard(Card card) throws SQLException {
            execute("INSERT INTO ofKanbanCard(cardID,boardID,columnID,revision,rank,title,description,assigneeJID,priority,deleted,createdBy,createdAt,updatedAt,deletedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                card.id(), card.boardId(), card.columnId(), card.revision(), card.rank(), card.title(), card.description(),
                card.assigneeJid(), card.priority().name(), card.deleted() ? 1 : 0, card.createdBy(), card.createdAt(),
                card.updatedAt(), card.deletedAt());
            replaceCardLabels(card.id(), card.labelIds());
        }

        public void insertLabel(Label label) throws SQLException {
            execute("INSERT INTO ofKanbanLabel(labelID,boardID,name,color,createdAt) VALUES(?,?,?,?,?)",
                label.id(), label.boardId(), label.name(), label.color().name(), label.createdAt());
        }

        public void insertMember(Member member) throws SQLException {
            execute("INSERT INTO ofKanbanMember(memberID,boardID,bareJID,role,createdAt) VALUES(?,?,?,?,?)",
                member.id(), member.boardId(), member.bareJid(), member.role().name(), member.createdAt());
        }

        public boolean incrementBoardRevision(String boardId, long expected, long now) throws SQLException {
            return execute("UPDATE ofKanbanBoard SET revision=revision+1,updatedAt=? WHERE boardID=? AND revision=?", now, boardId, expected) == 1;
        }

        public boolean updateCard(Card card, long expectedRevision) throws SQLException {
            final boolean updated = execute("UPDATE ofKanbanCard SET columnID=?,revision=revision+1,rank=?,title=?,description=?,assigneeJID=?,priority=?,deleted=?,updatedAt=?,deletedAt=? WHERE cardID=? AND revision=? AND deleted=0",
                card.columnId(), card.rank(), card.title(), card.description(), card.assigneeJid(), card.priority().name(),
                card.deleted() ? 1 : 0, card.updatedAt(), card.deletedAt(), card.id(), expectedRevision) == 1;
            if (updated) {
                replaceCardLabels(card.id(), card.labelIds());
            }
            return updated;
        }

        public void updateLabel(Label label) throws SQLException {
            execute("UPDATE ofKanbanLabel SET name=?,color=? WHERE labelID=?",
                label.name(), label.color().name(), label.id());
        }

        public void deleteLabel(String labelId) throws SQLException {
            execute("DELETE FROM ofKanbanCardLabel WHERE labelID=?", labelId);
            execute("DELETE FROM ofKanbanLabel WHERE labelID=?", labelId);
        }

        public void updateCardRank(String cardId, String rank) throws SQLException {
            execute("UPDATE ofKanbanCard SET rank=? WHERE cardID=?", rank, cardId);
        }

        public void updateMemberRole(String memberId, Role role) throws SQLException {
            execute("UPDATE ofKanbanMember SET role=? WHERE memberID=?", role.name(), memberId);
        }

        public void deleteMember(String memberId) throws SQLException {
            execute("DELETE FROM ofKanbanMember WHERE memberID=?", memberId);
        }

        public int ownerCount(String boardId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ofKanbanMember WHERE boardID=? AND role='OWNER'")) {
                statement.setString(1, boardId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }

        public long appendActivity(
            String activityId, String boardId, String eventType, String actor, String entityId, String payload, long now
        ) throws SQLException {
            final long sequence = nextActivitySequence(boardId);
            execute("INSERT INTO ofKanbanActivity(activityID,boardID,sequence,eventType,actorJID,entityID,payload,occurredAt) VALUES(?,?,?,?,?,?,?,?)",
                activityId, boardId, sequence, eventType, actor, entityId, payload, now);
            return sequence;
        }

        public void appendOutbox(
            String outboxId, String boardId, long sequence, int ordinal, OutboxKind kind, String nodeId,
            String itemId, String payload, long now
        ) throws SQLException {
            execute("INSERT INTO ofKanbanOutbox(outboxID,boardID,sequence,kind,nodeID,itemID,payload,status,attempts,availableAt,createdAt) VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?)",
                outboxId, boardId, sequence * 10 + ordinal, kind.name(), nodeId, itemId, payload, now, now);
        }

        Optional<OutboxEntry> claimNext(String workerId, long now, long leaseUntil) throws SQLException {
            execute("UPDATE ofKanbanOutbox SET status='PENDING',leaseOwner=NULL,leaseUntil=NULL WHERE status='PUBLISHING' AND leaseUntil<?", now);
            try (PreparedStatement select = connection.prepareStatement(
                "SELECT o.outboxID,o.boardID,o.sequence,o.kind,o.nodeID,o.itemID,o.payload,o.attempts FROM ofKanbanOutbox o "
                    + "WHERE o.status='PENDING' AND o.availableAt<=? AND NOT EXISTS (SELECT 1 FROM ofKanbanOutbox p "
                    + "WHERE p.boardID=o.boardID AND p.sequence<o.sequence AND p.status<>'DELIVERED') ORDER BY o.createdAt,o.sequence")) {
                select.setLong(1, now);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        final OutboxEntry entry = new OutboxEntry(result.getString(1), result.getString(2), result.getLong(3),
                            OutboxKind.valueOf(result.getString(4)), result.getString(5), result.getString(6),
                            result.getString(7), result.getInt(8));
                        if (execute("UPDATE ofKanbanOutbox SET status='PUBLISHING',leaseOwner=?,leaseUntil=? WHERE outboxID=? AND status='PENDING'",
                            workerId, leaseUntil, entry.id()) == 1) {
                            return Optional.of(entry);
                        }
                    }
                }
            }
            return Optional.empty();
        }

        void markDelivered(String outboxId, String workerId, long now) throws SQLException {
            execute("UPDATE ofKanbanOutbox SET status='DELIVERED',deliveredAt=?,leaseOwner=NULL,leaseUntil=NULL,lastError=NULL WHERE outboxID=? AND status='PUBLISHING' AND leaseOwner=?",
                now, outboxId, workerId);
        }

        void markFailed(String outboxId, String workerId, long retryAt, String error) throws SQLException {
            execute("UPDATE ofKanbanOutbox SET status='PENDING',attempts=attempts+1,availableAt=?,leaseOwner=NULL,leaseUntil=NULL,lastError=? WHERE outboxID=? AND status='PUBLISHING' AND leaseOwner=?",
                retryAt, error.length() > 2000 ? error.substring(0, 2000) : error, outboxId, workerId);
        }

        Diagnostics diagnostics(long now) throws SQLException {
            return new Diagnostics(count("SELECT COUNT(*) FROM ofKanbanBoard"),
                count("SELECT COUNT(*) FROM ofKanbanCard WHERE deleted=0"),
                count("SELECT COUNT(*) FROM ofKanbanOutbox WHERE status='PENDING'"),
                count("SELECT COUNT(*) FROM ofKanbanOutbox WHERE status='PUBLISHING'"),
                count("SELECT COUNT(*) FROM ofKanbanOutbox WHERE status='PENDING' AND lastError IS NOT NULL AND availableAt>?", now));
        }

        RetentionResult purgeHistory(long cutoff) throws SQLException {
            final int outboxRows = execute(
                "DELETE FROM ofKanbanOutbox WHERE status='DELIVERED' AND deliveredAt<?", cutoff);
            final int activityRows = execute("DELETE FROM ofKanbanActivity WHERE occurredAt<?", cutoff);
            return new RetentionResult(outboxRows, activityRows);
        }

        private long count(String sql, Object... values) throws SQLException {
            return queryOne(sql, result -> result.getLong(1), values).orElse(0L);
        }

        private long nextActivitySequence(String boardId) throws SQLException {
            execute("UPDATE ofKanbanBoard SET activitySequence=activitySequence+1 WHERE boardID=?", boardId);
            return queryOne("SELECT activitySequence FROM ofKanbanBoard WHERE boardID=?",
                result -> result.getLong(1), boardId).orElseThrow();
        }

        private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... values) throws SQLException {
            try (PreparedStatement statement = prepare(sql, values); ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapper.read(result)) : Optional.empty();
            }
        }

        private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... values) throws SQLException {
            final List<T> rows = new ArrayList<>();
            try (PreparedStatement statement = prepare(sql, values); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapper.read(result));
                }
            }
            return List.copyOf(rows);
        }

        private PreparedStatement prepare(String sql, Object... values) throws SQLException {
            final PreparedStatement statement = connection.prepareStatement(sql);
            try {
                for (int index = 0; index < values.length; index++) {
                    statement.setObject(index + 1, values[index]);
                }
                return statement;
            } catch (SQLException | RuntimeException exception) {
                statement.close();
                throw exception;
            }
        }

        private int execute(String sql, Object... values) throws SQLException {
            try (PreparedStatement statement = prepare(sql, values)) {
                return statement.executeUpdate();
            }
        }

        private void replaceCardLabels(String cardId, List<String> labelIds) throws SQLException {
            execute("DELETE FROM ofKanbanCardLabel WHERE cardID=?", cardId);
            for (int index = 0; index < labelIds.size(); index++) {
                execute("INSERT INTO ofKanbanCardLabel(cardID,labelID,position) VALUES(?,?,?)",
                    cardId, labelIds.get(index), index);
            }
        }

        private Card withLabels(Card card) throws SQLException {
            final List<String> labelIds = queryList(
                "SELECT labelID FROM ofKanbanCardLabel WHERE cardID=? ORDER BY position", result -> result.getString(1), card.id());
            return new Card(card.id(), card.boardId(), card.columnId(), card.revision(), card.rank(), card.title(),
                card.description(), card.assigneeJid(), card.priority(), labelIds, card.deleted(), card.createdBy(),
                card.createdAt(), card.updatedAt(), card.deletedAt());
        }

        private List<Card> withLabels(List<Card> cards) throws SQLException {
            final List<Card> hydrated = new ArrayList<>(cards.size());
            for (Card card : cards) {
                hydrated.add(withLabels(card));
            }
            return List.copyOf(hydrated);
        }

        private static String prefixedCardColumns(String alias) {
            return alias + "." + CARD_COLUMNS.replace(",", "," + alias + ".");
        }

        private static Board readBoard(ResultSet result) throws SQLException {
            return new Board(result.getString(1), result.getString(2), result.getLong(3), result.getString(4), result.getLong(5), result.getLong(6));
        }

        private static KanbanColumn readColumn(ResultSet result) throws SQLException {
            return new KanbanColumn(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getInt(5), result.getLong(6));
        }

        private static Card readCard(ResultSet result) throws SQLException {
            final long deletedAt = result.getLong(14);
            final boolean deletedAtWasNull = result.wasNull();
            return new Card(result.getString(1), result.getString(2), result.getString(3), result.getLong(4), result.getString(5),
                result.getString(6), result.getString(7), result.getString(8), CardPriority.valueOf(result.getString(9)),
                List.of(), result.getInt(10) != 0, result.getString(11), result.getLong(12), result.getLong(13),
                deletedAtWasNull ? null : deletedAt);
        }

        private static Label readLabel(ResultSet result) throws SQLException {
            return new Label(result.getString(1), result.getString(2), result.getString(3),
                LabelColor.valueOf(result.getString(4)), result.getLong(5));
        }

        private static Member readMember(ResultSet result) throws SQLException {
            return new Member(result.getString(1), result.getString(2), result.getString(3), Role.valueOf(result.getString(4)), result.getLong(5));
        }
    }
}
