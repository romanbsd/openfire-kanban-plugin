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
</card>
```

## 9. Commands

IQ set commands:

-   create-board
-   update-board
-   delete-board
-   create-card
-   update-card
-   move-card
-   delete-card
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

The Openfire reference implementation defines the following version-zero
extensions while the draft is refined:

- IQ `get`: `list-boards`, `get-board`.
- IQ `set`: `create-board`, `create-column`, `create-card`, `update-card`,
  `move-card`, `delete-card`, `add-member`, `update-member-role`,
  `remove-member`.
- `expected-revision="0"` is used when creating a board. Board-structure and
  membership mutations compare the board revision; card mutations compare the
  card revision.
- Successful mutations return `board-revision`, affected `id`, and an entity
  `revision` when that entity is revisioned.
- `get-board` returns the authoritative snapshot plus the actual PubSub service
  and node identifiers.
- Move requests use optional, mutually exclusive `before-card` and
  `after-card` anchors. Clients never submit ranks.
- Deletes are soft tombstones in SQL. `CardDeleted` is published as immutable
  activity and a card tombstone is published on the cards node.

Known underspecified areas and the choices above are detailed in
`docs/protoxep-gap-analysis.md`.
