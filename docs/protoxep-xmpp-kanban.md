# ProtoXEP: XMPP Kanban Boards

**Status:** ProtoXEP (Draft)

## 1. Introduction

This document specifies an interoperable Kanban protocol built on top of
existing XMPP standards. It defines data models, discovery,
synchronization, commands, events and permissions while reusing XEP-0060
PubSub as the transport.

Goals:

-   Reuse existing XEPs wherever possible.
-   Allow independent client implementations.
-   Support offline synchronization.
-   Provide optimistic concurrency.
-   Be suitable for both human users and autonomous agents.

## 2. Requirements

### Functional

-   Boards
-   Columns
-   Cards
-   Comments
-   Attachments
-   Activity history
-   Roles
-   Offline sync

### Non-functional

-   Stateless clients
-   Server authoritative writes
-   Horizontal scalability
-   Transactional consistency
-   Efficient mobile synchronization

## 3. Terminology

Board, Column, Card, Activity Event, Component, Revision, Rank,
Snapshot.

## 4. Namespaces

    urn:xmpp:kanban:0
    urn:xmpp:kanban:commands:0
    urn:xmpp:kanban:events:0

## 5. Discovery

Service advertises:

    urn:xmpp:kanban:0
    http://jabber.org/protocol/pubsub

Boards appear through disco#items.

## 6. Architecture

    Clients
       │
    IQ commands
       │
    kanban component
       │
    PostgreSQL
       │
    PubSub notifications

Clients SHALL NOT publish directly into board PubSub nodes.

## 7. Board Model

A board contains:

-   metadata
-   columns
-   cards
-   comments
-   attachments
-   activity

Suggested nodes:

    boards/<id>/cards
    boards/<id>/comments
    boards/<id>/activity
    boards/<id>/attachments

## 8. XML Payloads

### Board

``` xml
<board xmlns="urn:xmpp:kanban:0"
       id="board-1"
       revision="3">
  <name>Engineering</name>
</board>
```

### Column

``` xml
<column id="todo" rank="A">
  <name>Todo</name>
  <wip-limit>10</wip-limit>
</column>
```

### Card

``` xml
<card id="uuid"
      revision="17">
  <title>Implement OAuth</title>
  <column id="doing"/>
  <rank>ABCD</rank>
  <description>...</description>
  <assignee jid="alice@example.com"/>
  <priority>high</priority>
  <labels><label id="label-id"/></labels>
</card>
```

The Openfire reference profile defines priority as `none`, `low`, `normal`,
`high`, or `urgent`. Labels are board-scoped catalog entries with a name and a
closed palette color; cards preserve an ordered set of at most eight label IDs.

## 9. Commands

IQ set commands:

-   create-board
-   update-board
-   delete-board
-   create-card
-   update-card
-   move-card
-   delete-card
-   create-label
-   update-label
-   delete-label
-   add-comment
-   delete-comment
-   attach-file

Each mutation MUST contain:

    expected-revision

## 10. Replies

Success:

``` xml
<result revision="18"/>
```

Conflict:

``` xml
<conflict current-revision="18"/>
```

## 11. Events

Immutable events:

-   BoardCreated
-   CardCreated
-   CardUpdated
-   CardMoved
-   CardDeleted
-   LabelCreated
-   LabelUpdated
-   LabelDeleted
-   CommentAdded
-   AttachmentAdded
-   MemberAdded

## 12. Ordering

Servers MUST NOT derive ordering from PubSub item sequence.

Cards SHALL contain an explicit rank.

Fractional indexing or LexoRank is RECOMMENDED.

## 13. Permissions

Roles:

  Role     Create     Move   Delete   Admin
  -------- ---------- ------ -------- -------
  Owner    Y          Y      Y        Y
  Editor   Y          Y      N        N
  Viewer   N          N      N        N
  Guest    Comments   N      N        N

## 14. Synchronization

Initial:

1.  Discover board
2.  Subscribe
3.  Download snapshots
4.  Receive live events

Reconnect:

Fetch latest snapshots then continue processing notifications.

## 15. Attachments

HTTP Upload (XEP-0363).

Metadata via XEP-0446/0447.

## 16. MUC Integration

Optional.

Discussion SHALL NOT be authoritative.

## 17. Database Projection

Suggested schema:

    boards
    columns
    cards
    members
    comments
    attachments
    activity
    labels

## 18. Error Conditions

-   item-not-found
-   forbidden
-   bad-request
-   revision-conflict
-   invalid-column
-   wip-limit-exceeded
-   duplicate-rank

## 19. Security

Servers MUST verify permissions before mutation.

Servers SHOULD validate payloads.

Clients MUST NOT trust locally cached revisions.

## 20. Implementation Notes

Component performs a single SQL transaction:

1.  Validate revision
2.  Apply mutation
3.  Append activity
4.  Commit
5.  Publish PubSub notifications

## 21. Conformance

A conforming implementation MUST:

-   implement XEP-0060
-   implement discovery
-   support revisions
-   reject stale updates
-   publish immutable events
-   support persistent cards

## 22. Future Work

-   Swimlanes
-   Checklists
-   Automation
-   Dependencies
-   Agent ownership
-   AI summaries
-   CRDT collaborative editing

## Appendix A. Suggested PostgreSQL Tables

``` sql
boards(id,...)
columns(id,...)
cards(id,revision,rank,...)
activity(id,type,...)
comments(id,...)
attachments(id,...)
```

## Appendix B. Example Flow

1.  Client sends move-card IQ.
2.  Component validates revision.
3.  SQL transaction updates card.
4.  Activity event inserted.
5.  PubSub publishes new snapshot.
6.  Subscribers update UI.

## Appendix C. Reference Implementation Profile

This appendix defines the version-zero profile implemented by the Openfire
plugin. It is authoritative for that implementation where the draft above is
more general or leaves behavior open.

### C.1 Commands and authoritative reads

The profile supports IQ `get` commands `list-boards` and `get-board`. It
supports IQ `set` commands `create-board`, `create-column`, `create-card`,
`update-card`, `move-card`, `delete-card`, `create-label`, `update-label`,
`delete-label`, `add-member`, `update-member-role`, and `remove-member`.
Board update/deletion, comments, attachments, and remaining column mutations
are deferred.

`get-board` returns one authoritative SQL snapshot containing board metadata,
ordered columns, live cards, the board label catalog, members, the actual
PubSub service JID, and the cards/activity node IDs. Clients use this command
on board open, reconnect, conflict, or detected synchronization loss.

### C.2 Revision domains and mutation replies

`create-board` requires `expected-revision="0"`. Board structure, label
catalog, membership, and card creation compare the board revision. Card patch,
move, and deletion compare the card revision and do not increment the board
revision.

Deleting a label increments the board revision once. Each affected card can
also receive a new card revision when the deleted label reference is stripped.

Successful mutations return `board-revision`, the affected entity `id`, and
`revision` when the entity is independently revisioned. A revision conflict is
a normal XMPP stanza error with `<revision-conflict
xmlns="urn:xmpp:kanban:0" current-revision="..."/>`.

### C.3 Card priority and board labels

Priority is a closed class-of-service enum: `none`, `low`, `normal`, `high`,
and `urgent`. Omission reads as `none`. Unknown values are `bad-request`.

Labels are board-scoped. Each has a server-generated opaque ID, a trimmed
1–32 character name unique case-insensitively on that board, and one closed
palette token: `slate`, `rose`, `orange`, `amber`, `lime`, `mint`, `sky`,
`violet`, or `pink`.

A card contains an ordered set of at most eight distinct label IDs. Priority
and labels use presence-based `update-card` patches: omission leaves the field
unchanged; empty priority or `none` clears priority; a present `<labels>` fully
replaces the ordered set, including empty `<labels/>` to clear it. Unknown,
deleted, or cross-board label references are `bad-request`.

Label CRUD compares the board revision. Deleting a label transactionally
removes it from every card, increments affected card revisions, publishes each
new card snapshot, and emits `LabelDeleted` activity. Clients refresh
`get-board` after label catalog activity to obtain authoritative names/colors.

### C.4 Ordering and WIP limits

The server owns all rank values. `move-card` accepts at most one of
`before-card` and `after-card`; an anchor must identify a live card in the
destination column. Clients never submit ranks. The implementation uses a
fixed-width base-36 LexoRank and transactionally rebalances the destination
column when no midpoint remains.

A WIP limit of zero is unlimited. Only live cards count. A same-column move
does not add to the count. WIP validation runs while holding the board mutation
lock so concurrent moves cannot overfill a column.

### C.5 Membership and authorization

Owners manage members, columns, labels, and cards, including card deletion.
Editors create columns, manage labels, and create/update/move cards, but cannot
delete cards or manage members. Viewers and guests are read-only while comments
remain deferred. A board must retain at least one owner.

Milestone 0 accepts authenticated local senders only. Actor, member, and
assignee identifiers are canonicalized to bare JIDs. Federation is deferred.

### C.6 PubSub topology, access, and readiness

Snapshots identify the real `pubsub.<domain>` service. Cards and activity
nodes are persistent, whitelist-access, server-published, and retain their most
recent items. Clients never publish directly.

On Openfire 5.2.0+, board members receive the read-only `member` affiliation.
The component remains owner/publisher, uses `publish_model=publishers`, and
uses numeric `pubsub#max_items=2147483647`.

`get-board` is a synchronous readiness barrier: before returning node IDs, the
server creates or reconciles both nodes and applies access for every current
member. Repeating `get-board` repairs missing nodes. Failure returns
`service-unavailable` with `<pubsub-unavailable
xmlns="urn:xmpp:kanban:0"/>`, never a snapshot advertising unusable nodes.

Card create/update/move publishes a canonical card snapshot. Card deletion
publishes a revisioned tombstone plus immutable `CardDeleted` activity.
Non-card changes publish activity. Membership changes asynchronously grant or
revoke access on both nodes. Label deletion additionally republishes every
affected card.

### C.7 Transaction and delivery semantics

SQL is authoritative and PubSub is an asynchronous projection. Each mutation
updates SQL, appends immutable activity, and inserts ordered outbox work in one
transaction. The server replies after commit; a leased worker publishes later.
It preserves per-board order, retries with exponential backoff capped at five
minutes, and reclaims expired publishing leases.

The implementation maintains a monotonic per-board activity sequence and
deterministic outbox order, but does not expose that sequence as a standardized
replay cursor. Delivered outbox rows and activity history are retained for a
configurable period (90 days by default).

### C.8 Deferred protocol work

Comments and attachments still need complete payload, revision,
authorization, notification, and deletion rules. Board lifecycle, column
update/delete, validation limits outside the fields defined above, complete
error-to-stanza mappings, event schemas, PubSub item IDs, time representation,
disco item representation, direct subscription rules, standardized
multi-device replay cursors, and federation also remain outside this
version-zero profile.
