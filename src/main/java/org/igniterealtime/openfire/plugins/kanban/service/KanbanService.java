package org.igniterealtime.openfire.plugins.kanban.service;

import static org.igniterealtime.openfire.plugins.kanban.xml.KanbanXml.boardXml;
import static org.igniterealtime.openfire.plugins.kanban.xml.KanbanXml.cardXml;
import static org.igniterealtime.openfire.plugins.kanban.xml.KanbanXml.columnXml;
import static org.igniterealtime.openfire.plugins.kanban.xml.KanbanXml.eventXml;
import static org.igniterealtime.openfire.plugins.kanban.xml.KanbanXml.memberXml;
import static org.igniterealtime.openfire.plugins.kanban.xml.KanbanXml.tombstoneXml;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.IntSupplier;

import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.igniterealtime.openfire.plugins.kanban.model.ActivityType;
import org.igniterealtime.openfire.plugins.kanban.model.BoardNodes;
import org.igniterealtime.openfire.plugins.kanban.model.BoardSnapshot;
import org.igniterealtime.openfire.plugins.kanban.model.Card;
import org.igniterealtime.openfire.plugins.kanban.model.KanbanColumn;
import org.igniterealtime.openfire.plugins.kanban.model.Member;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.igniterealtime.openfire.plugins.kanban.outbox.OutboxKind;
import org.igniterealtime.openfire.plugins.kanban.rank.LexoRank;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.Transaction;

/** Server-authoritative Kanban business rules. */
public final class KanbanService {
    public record Result<T>(T value, long boardRevision) {}

    public record FieldPatch<T>(boolean present, T value) {
        public static <T> FieldPatch<T> absent() {
            return new FieldPatch<>(false, null);
        }

        public static <T> FieldPatch<T> set(T value) {
            return new FieldPatch<>(true, value);
        }
    }

    public record CardPatch(FieldPatch<String> title, FieldPatch<String> description, FieldPatch<String> assigneeJid) {
        public CardPatch {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(assigneeJid, "assigneeJid");
        }
    }

    private final JdbcKanbanRepository repository;
    private final Clock clock;
    private final Supplier<String> idGenerator;
    private final IntSupplier defaultWipLimit;
    private final Predicate<String> boardCreationAllowed;

    public KanbanService(
        JdbcKanbanRepository repository, Clock clock, Supplier<String> idGenerator,
        IntSupplier defaultWipLimit, Predicate<String> boardCreationAllowed
    ) {
        this.repository = repository;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.defaultWipLimit = Objects.requireNonNull(defaultWipLimit);
        this.boardCreationAllowed = boardCreationAllowed;
    }

    public KanbanService(JdbcKanbanRepository repository, int defaultWipLimit) {
        this(repository, Clock.systemUTC(), () -> UUID.randomUUID().toString(), () -> defaultWipLimit, actor -> true);
    }

    public Result<Board> createBoard(String actor, String name, long expectedRevision) {
        requireExpected(expectedRevision, 0);
        if (!boardCreationAllowed.test(actor)) {
            throw error(KanbanException.Code.FORBIDDEN, "Board creation is restricted to administrators");
        }
        final String normalizedName = requireText(name, "Board name", 255);
        return repository.transact(transaction -> {
            final long now = clock.millis();
            final Board board = new Board(idGenerator.get(), normalizedName, 1, actor, now, now);
            transaction.insertBoard(board);
            transaction.insertMember(new Member(idGenerator.get(), board.id(), actor, Role.OWNER, now));
            final long sequence = appendEvent(transaction, board.id(), ActivityType.BOARD_CREATED,
                actor, board.id(), boardXml(board), now, false);
            appendAccess(transaction, board.id(), sequence, actor, OutboxKind.ACCESS_GRANT, now);
            return new Result<>(board, board.revision());
        });
    }

    public Result<KanbanColumn> createColumn(
        String actor, String boardId, String name, Integer requestedWipLimit, long expectedRevision
    ) {
        return repository.transact(transaction -> {
            final Board board = requireBoardMutation(transaction, boardId, actor, Permission.CREATE, expectedRevision);
            final List<KanbanColumn> columns = transaction.columns(boardId);
            final LexoRank lower = columns.isEmpty() ? null : LexoRank.parse(columns.get(columns.size() - 1).rank());
            final long now = clock.millis();
            final KanbanColumn column = new KanbanColumn(idGenerator.get(), boardId,
                requireText(name, "Column name", 255), LexoRank.between(lower, null).toString(),
                validWipLimit(requestedWipLimit == null ? defaultWipLimit.getAsInt() : requestedWipLimit), now);
            incrementBoard(transaction, board, expectedRevision, now);
            transaction.insertColumn(column);
            appendEvent(transaction, boardId, ActivityType.COLUMN_CREATED,
                actor, column.id(), columnXml(column), now, false);
            return new Result<>(column, expectedRevision + 1);
        });
    }

    public Result<Card> createCard(
        String actor, String boardId, String columnId, String title, String description,
        String assigneeJid, long expectedRevision
    ) {
        return repository.transact(transaction -> {
            final Board board = requireBoardMutation(transaction, boardId, actor, Permission.CREATE, expectedRevision);
            final KanbanColumn column = requireColumn(transaction, boardId, columnId);
            final List<Card> cards = transaction.cardsInColumn(columnId);
            enforceWip(column, cards.size() + 1);
            final String rank = rankBetween(transaction, cards, null, null);
            final long now = clock.millis();
            final Card card = new Card(idGenerator.get(), boardId, columnId, 1, rank,
                requireText(title, "Card title", 255), normalizeNullable(description, 100_000),
                normalizeNullable(assigneeJid, 1024), false, actor, now, now, null);
            incrementBoard(transaction, board, expectedRevision, now);
            transaction.insertCard(card);
            appendEvent(transaction, boardId, ActivityType.CARD_CREATED, actor, card.id(), cardXml(card), now, true);
            return new Result<>(card, expectedRevision + 1);
        });
    }

    public Result<Card> updateCard(String actor, String cardId, long expectedRevision, CardPatch patch) {
        if (!patch.title().present() && !patch.description().present() && !patch.assigneeJid().present()) {
            throw error(KanbanException.Code.BAD_REQUEST, "At least one card field must be supplied");
        }
        return repository.transact(transaction -> {
            final CardMutation mutation = requireCardMutation(
                transaction, cardId, actor, Permission.EDIT, expectedRevision);
            final Card current = mutation.card();
            final Card updated = current.update(
                patch.title().present() ? requireText(patch.title().value(), "Card title", 255) : current.title(),
                patch.description().present() ? normalizeNullable(patch.description().value(), 100_000) : current.description(),
                patch.assigneeJid().present() ? normalizeNullable(patch.assigneeJid().value(), 1024) : current.assigneeJid(),
                clock.millis());
            updateCard(transaction, updated, expectedRevision);
            appendEvent(transaction, current.boardId(), ActivityType.CARD_UPDATED,
                actor, cardId, cardXml(updated), updated.updatedAt(), true);
            return new Result<>(updated, mutation.board().revision());
        });
    }

    public Result<Card> moveCard(
        String actor, String cardId, String targetColumnId, String beforeCardId, String afterCardId, long expectedRevision
    ) {
        if (beforeCardId != null && afterCardId != null) {
            throw error(KanbanException.Code.INVALID_POSITION, "Specify either before-card or after-card, not both");
        }
        return repository.transact(transaction -> {
            final CardMutation mutation = requireCardMutation(
                transaction, cardId, actor, Permission.EDIT, expectedRevision);
            final Card current = mutation.card();
            final KanbanColumn target = requireColumn(transaction, current.boardId(), targetColumnId);
            final List<Card> targetCards = new ArrayList<>(transaction.cardsInColumn(targetColumnId));
            targetCards.removeIf(card -> card.id().equals(cardId));
            if (!current.columnId().equals(targetColumnId)) {
                enforceWip(target, targetCards.size() + 1);
            }
            final String rank = rankBetween(transaction, targetCards, beforeCardId, afterCardId);
            final Card moved = current.moveTo(targetColumnId, rank, clock.millis());
            updateCard(transaction, moved, expectedRevision);
            appendEvent(transaction, current.boardId(), ActivityType.CARD_MOVED,
                actor, cardId, cardXml(moved), moved.updatedAt(), true);
            return new Result<>(moved, mutation.board().revision());
        });
    }

    public Result<Card> deleteCard(String actor, String cardId, long expectedRevision) {
        return repository.transact(transaction -> {
            final CardMutation mutation = requireCardMutation(
                transaction, cardId, actor, Permission.DELETE, expectedRevision);
            final Card current = mutation.card();
            final long now = clock.millis();
            final Card deleted = current.tombstone(now);
            updateCard(transaction, deleted, expectedRevision);
            appendEvent(transaction, current.boardId(), ActivityType.CARD_DELETED,
                actor, cardId, tombstoneXml(deleted), now, true);
            return new Result<>(deleted, mutation.board().revision());
        });
    }

    public Result<Member> addMember(String actor, String boardId, String bareJid, Role role, long expectedRevision) {
        return repository.transact(transaction -> {
            final Board board = requireBoardMutation(transaction, boardId, actor, Permission.MEMBERS, expectedRevision);
            if (transaction.member(boardId, bareJid).isPresent()) {
                throw error(KanbanException.Code.DUPLICATE_MEMBER, "The JID is already a board member");
            }
            final long now = clock.millis();
            final Member member = new Member(idGenerator.get(), boardId, bareJid, Objects.requireNonNull(role), now);
            incrementBoard(transaction, board, expectedRevision, now);
            transaction.insertMember(member);
            final long sequence = appendEvent(transaction, boardId, ActivityType.MEMBER_ADDED,
                actor, member.id(), memberXml(member), now, false);
            appendAccess(transaction, boardId, sequence, bareJid, OutboxKind.ACCESS_GRANT, now);
            return new Result<>(member, expectedRevision + 1);
        });
    }

    public Result<Member> updateMemberRole(
        String actor, String boardId, String bareJid, Role role, long expectedRevision
    ) {
        return repository.transact(transaction -> {
            final Board board = requireBoardMutation(transaction, boardId, actor, Permission.MEMBERS, expectedRevision);
            final Member member = transaction.member(boardId, bareJid)
                .orElseThrow(() -> error(KanbanException.Code.ITEM_NOT_FOUND, "Member not found"));
            preventFinalOwner(transaction, member, role);
            final Member updated = new Member(member.id(), boardId, bareJid, Objects.requireNonNull(role), member.createdAt());
            final long now = clock.millis();
            incrementBoard(transaction, board, expectedRevision, now);
            transaction.updateMemberRole(member.id(), role);
            appendEvent(transaction, boardId, ActivityType.MEMBER_ROLE_CHANGED,
                actor, member.id(), memberXml(updated), now, false);
            return new Result<>(updated, expectedRevision + 1);
        });
    }

    public Result<Member> removeMember(String actor, String boardId, String bareJid, long expectedRevision) {
        return repository.transact(transaction -> {
            final Board board = requireBoardMutation(transaction, boardId, actor, Permission.MEMBERS, expectedRevision);
            final Member member = transaction.member(boardId, bareJid)
                .orElseThrow(() -> error(KanbanException.Code.ITEM_NOT_FOUND, "Member not found"));
            preventFinalOwner(transaction, member, null);
            final long now = clock.millis();
            incrementBoard(transaction, board, expectedRevision, now);
            transaction.deleteMember(member.id());
            final long sequence = appendEvent(transaction, boardId, ActivityType.MEMBER_REMOVED,
                actor, member.id(), memberXml(member), now, false);
            appendAccess(transaction, boardId, sequence, bareJid, OutboxKind.ACCESS_REVOKE, now);
            return new Result<>(member, expectedRevision + 1);
        });
    }

    public List<Board> listBoards(String actor) {
        return repository.listBoards(actor);
    }

    public BoardSnapshot snapshot(String actor, String boardId) {
        return repository.transact(transaction -> {
            requireRole(transaction, boardId, actor);
            return new BoardSnapshot(requireBoard(transaction, boardId), transaction.columns(boardId),
                transaction.cards(boardId), transaction.members(boardId));
        });
    }

    private String rankBetween(
        Transaction transaction, List<Card> cards, String beforeCardId, String afterCardId
    ) throws SQLException {
        int insertion = cards.size();
        if (beforeCardId != null) {
            insertion = indexOf(cards, beforeCardId);
        } else if (afterCardId != null) {
            insertion = indexOf(cards, afterCardId) + 1;
        }
        LexoRank lower = insertion == 0 ? null : LexoRank.parse(cards.get(insertion - 1).rank());
        LexoRank upper = insertion == cards.size() ? null : LexoRank.parse(cards.get(insertion).rank());
        try {
            return LexoRank.between(lower, upper).toString();
        } catch (LexoRank.ExhaustedException exhausted) {
            final int bucket = lower != null ? lower.bucket() : Objects.requireNonNull(upper).bucket();
            final List<LexoRank> ranks = LexoRank.rebalance(cards.size(), bucket);
            for (int index = 0; index < cards.size(); index++) {
                transaction.updateCardRank(cards.get(index).id(), ranks.get(index).toString());
            }
            lower = insertion == 0 ? null : ranks.get(insertion - 1);
            upper = insertion == ranks.size() ? null : ranks.get(insertion);
            return LexoRank.between(lower, upper).toString();
        }
    }

    private static int indexOf(List<Card> cards, String id) {
        for (int index = 0; index < cards.size(); index++) {
            if (cards.get(index).id().equals(id)) {
                return index;
            }
        }
        throw error(KanbanException.Code.INVALID_POSITION, "Position anchor is not in the target column");
    }

    private long appendEvent(
        Transaction transaction, String boardId, ActivityType type, String actor, String entityId,
        String entityPayload, long now, boolean publishCard
    ) throws SQLException {
        final String activityId = idGenerator.get();
        final String event = eventXml(activityId, type, boardId, actor, now, entityPayload);
        final long sequence = transaction.appendActivity(
            activityId, boardId, type.wireName(), actor, entityId, event, now);
        if (publishCard) {
            transaction.appendOutbox(idGenerator.get(), boardId, sequence, 1, OutboxKind.CARD_SNAPSHOT,
                BoardNodes.cards(boardId), entityId, entityPayload, now);
        }
        transaction.appendOutbox(idGenerator.get(), boardId, sequence, 2, OutboxKind.ACTIVITY,
            BoardNodes.activity(boardId), activityId, event, now);
        return sequence;
    }

    private void appendAccess(
        Transaction transaction, String boardId, long sequence, String bareJid, OutboxKind kind, long now
    ) throws SQLException {
        transaction.appendOutbox(idGenerator.get(), boardId, sequence, 3, kind,
            BoardNodes.cards(boardId), idGenerator.get(), bareJid, now);
        transaction.appendOutbox(idGenerator.get(), boardId, sequence, 4, kind,
            BoardNodes.activity(boardId), idGenerator.get(), bareJid, now);
    }

    private static Board requireBoardMutation(
        Transaction transaction, String boardId, String actor, Permission permission, long expectedRevision
    ) throws SQLException {
        final Board board = requireBoard(transaction, boardId);
        requirePermission(transaction, boardId, actor, permission);
        requireRevision(board.revision(), expectedRevision);
        return board;
    }

    private static CardMutation requireCardMutation(
        Transaction transaction, String cardId, String actor, Permission permission, long expectedRevision
    ) throws SQLException {
        final Card card = requireActiveCard(transaction, cardId);
        final Board board = requireBoard(transaction, card.boardId());
        requirePermission(transaction, card.boardId(), actor, permission);
        requireRevision(card.revision(), expectedRevision);
        return new CardMutation(card, board);
    }

    private static Board requireBoard(Transaction transaction, String boardId) throws SQLException {
        return transaction.board(boardId).orElseThrow(() -> error(KanbanException.Code.ITEM_NOT_FOUND, "Board not found"));
    }

    private static Card requireActiveCard(Transaction transaction, String cardId) throws SQLException {
        final Card card = transaction.card(cardId).orElseThrow(() -> error(KanbanException.Code.ITEM_NOT_FOUND, "Card not found"));
        if (card.deleted()) {
            throw error(KanbanException.Code.ITEM_NOT_FOUND, "Card not found");
        }
        return card;
    }

    private static KanbanColumn requireColumn(Transaction transaction, String boardId, String columnId) throws SQLException {
        final KanbanColumn column = transaction.column(columnId)
            .orElseThrow(() -> error(KanbanException.Code.INVALID_COLUMN, "Column not found"));
        if (!column.boardId().equals(boardId)) {
            throw error(KanbanException.Code.INVALID_COLUMN, "Column belongs to another board");
        }
        return column;
    }

    private static Role requireRole(Transaction transaction, String boardId, String actor) throws SQLException {
        return transaction.member(boardId, actor).map(Member::role)
            .orElseThrow(() -> error(KanbanException.Code.FORBIDDEN, "Board membership is required"));
    }

    private static void requirePermission(Transaction transaction, String boardId, String actor, Permission permission)
        throws SQLException {
        final Role role = requireRole(transaction, boardId, actor);
        final boolean allowed = switch (permission) {
            case CREATE -> role.canCreate();
            case EDIT -> role.canEditCards();
            case DELETE -> role.canDeleteCards();
            case MEMBERS -> role.canManageMembers();
        };
        if (!allowed) {
            throw error(KanbanException.Code.FORBIDDEN, "The board role does not permit this operation");
        }
    }

    private static void preventFinalOwner(Transaction transaction, Member member, Role nextRole) throws SQLException {
        if (member.role() == Role.OWNER && nextRole != Role.OWNER && transaction.ownerCount(member.boardId()) == 1) {
            throw error(KanbanException.Code.FINAL_OWNER, "A board must retain at least one owner");
        }
    }

    private static void enforceWip(KanbanColumn column, int count) {
        if (column.wipLimit() > 0 && count > column.wipLimit()) {
            throw error(KanbanException.Code.WIP_LIMIT_EXCEEDED, "Column WIP limit exceeded");
        }
    }

    private static void incrementBoard(Transaction transaction, Board board, long expected, long now) throws SQLException {
        if (!transaction.incrementBoardRevision(board.id(), expected, now)) {
            throw new KanbanException(KanbanException.Code.REVISION_CONFLICT, "Board revision conflict", board.revision());
        }
    }

    private static void updateCard(Transaction transaction, Card card, long expected) throws SQLException {
        if (!transaction.updateCard(card, expected)) {
            final long currentRevision = transaction.card(card.id()).map(Card::revision).orElse(expected);
            throw new KanbanException(
                KanbanException.Code.REVISION_CONFLICT, "Card revision conflict", currentRevision);
        }
    }

    private static void requireRevision(long current, long expected) {
        if (current != expected) {
            throw new KanbanException(KanbanException.Code.REVISION_CONFLICT, "Revision conflict", current);
        }
    }

    private static void requireExpected(long actual, long expected) {
        if (actual != expected) {
            throw error(KanbanException.Code.BAD_REQUEST, "Creation expected-revision must be zero");
        }
    }

    private static int validWipLimit(int value) {
        if (value < 0) {
            throw error(KanbanException.Code.BAD_REQUEST, "WIP limit must not be negative");
        }
        return value;
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw error(KanbanException.Code.BAD_REQUEST, label + " must contain 1 to " + max + " characters");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw error(KanbanException.Code.BAD_REQUEST, "Value exceeds " + max + " characters");
        }
        return value;
    }

    private static KanbanException error(KanbanException.Code code, String message) {
        return new KanbanException(code, message);
    }

    private record CardMutation(Card card, Board board) {}

    private enum Permission { CREATE, EDIT, DELETE, MEMBERS }
}
