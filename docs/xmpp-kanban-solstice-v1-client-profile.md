# XMPP Kanban Client Protocol Profile — Solstice v1

**Status:** Implemented client profile
**Scope:** Openfire Kanban Milestone 0 as consumed by the Solstice Boards UI
**Model namespace:** `urn:xmpp:kanban:0`
**Command namespace:** `urn:xmpp:kanban:commands:0`
**Activity namespace:** `urn:xmpp:kanban:events:0`

This profile records the wire contract implemented by the Openfire Kanban
plugin. Where the broader ProtoXEP is ambiguous, this document is authoritative
for the Solstice v1 client.

Solstice v1 does not expose comments, attachments, board update/deletion,
column update/reordering/deletion, history pagination, automation, swimlanes,
checklists, dependencies, or federation.

## 1. Service discovery and addressing

The service is an internal component at `kanban.<xmpp-domain>`, for example
`kanban.example.org`. Commands MUST be addressed to that component and MUST
come from an authenticated, registered local user. The server canonicalizes
actors, members, and assignees to bare JIDs.

The server domain's `disco#items` response advertises the component:

```xml
<iq type="get" to="example.org" id="domain-disco">
  <query xmlns="http://jabber.org/protocol/disco#items"/>
</iq>
```

```xml
<iq type="result" from="example.org" id="domain-disco">
  <query xmlns="http://jabber.org/protocol/disco#items">
    <item jid="kanban.example.org"/>
  </query>
</iq>
```

The component's `disco#info` identity is:

```xml
<identity category="collaboration" type="kanban" name="Openfire Kanban"/>
<feature var="urn:xmpp:kanban:0"/>
<feature var="urn:xmpp:kanban:commands:0"/>
```

An authenticated user's `disco#items` query to the component lists only boards
visible to that user. Each item uses the opaque board ID directly as its
`node`; clients MUST NOT construct an URN around it:

```xml
<item jid="kanban.example.org"
      node="1a9cfd93-e4c8-4f13-8d1d-237786327ef2"
      name="Engineering"/>
```

The item name is presentation-only. Fetch `get-board` for authoritative data.
`list-boards` is the preferred command-level enumeration mechanism.

## 2. IDs, revisions, and ordering

All IDs are server-generated opaque strings. Clients MUST NOT depend on UUID
version or derive related IDs from them.

There are two revision domains:

- **Board revision:** used by board structure, label catalog, membership, and card creation.
- **Card revision:** used by card patch, move, and deletion.

`expected-revision="0"` is required for `create-board`. Creating a column,
creating a card, changing the label catalog, and changing membership require
the latest board revision.
Updating, moving, and deleting a card require that card's latest revision.

Every successful mutation returns the current board revision. Card mutations
also return the resulting card revision. A card update, move, or deletion does
not increment the board revision.

Columns and cards are ordered by server-owned LexoRank strings. Clients:

- sort ranks using binary/code-point order;
- never generate or persist their own rank;
- never infer order from PubSub publication order;
- move cards using `before-card` or `after-card`, not a rank value.

At most one anchor may be supplied. An omitted anchor appends the card to the
destination column. The server validates anchors, generates the rank, and
rebalances a column when necessary.

## 3. Authoritative reads

### 3.1 List boards

```xml
<iq type="get" to="kanban.example.org" id="boards-1">
  <list-boards xmlns="urn:xmpp:kanban:commands:0"/>
</iq>
```

```xml
<iq type="result" from="kanban.example.org" id="boards-1">
  <boards xmlns="urn:xmpp:kanban:0">
    <board id="1a9c..." revision="4"><name>Engineering</name></board>
  </boards>
</iq>
```

### 3.2 Get board

```xml
<iq type="get" to="kanban.example.org" id="board-1">
  <get-board xmlns="urn:xmpp:kanban:commands:0"
             board-id="1a9cfd93-e4c8-4f13-8d1d-237786327ef2"/>
</iq>
```

```xml
<iq type="result" from="kanban.example.org" id="board-1">
  <snapshot xmlns="urn:xmpp:kanban:0"
            pubsub-service="pubsub.example.org"
            cards-node="boards/1a9cfd93-e4c8-4f13-8d1d-237786327ef2/cards"
            activity-node="boards/1a9cfd93-e4c8-4f13-8d1d-237786327ef2/activity">
    <board id="1a9cfd93-e4c8-4f13-8d1d-237786327ef2" revision="4">
      <name>Engineering</name>
    </board>
    <columns>
      <column id="col-1" rank="hzzzzzzzzz">
        <name>Todo</name>
        <wip-limit>0</wip-limit>
      </column>
    </columns>
    <cards>
      <card id="card-1" revision="2">
        <title>Implement OAuth callback</title>
        <column id="col-1"/>
        <rank>hzzzzzzzzz</rank>
        <description>Handle redirects.</description>
        <assignee jid="alice@example.org"/>
        <priority>urgent</priority>
        <labels>
          <label id="label-docs"/>
          <label id="label-backend"/>
        </labels>
      </card>
    </cards>
    <labels>
      <label id="label-docs" color="mint"><name>docs</name></label>
      <label id="label-backend" color="sky"><name>backend</name></label>
    </labels>
    <members>
      <member id="member-1" jid="alice@example.org" role="owner"/>
    </members>
  </snapshot>
</iq>
```

`wip-limit` is always present; zero means unlimited. `description`, `assignee`,
`priority`, and card `labels` are optional. Assignee JIDs are bare. An omitted
priority means `none`; an omitted or empty card `<labels>` means no labels. The
top-level `<labels>` element is always present and is the authoritative
board-scoped catalog.

There is no `get-card` command in v1. After a conflict or synchronization
loss, fetch the full board snapshot.

### 3.3 PubSub readiness contract

A successful `get-board` is a readiness barrier. Before returning the
snapshot, the server creates or reconciles both advertised nodes and grants
read-only PubSub membership to every current board member. Therefore, a current
member MUST be able to subscribe to the returned node IDs immediately after a
successful `get-board`.

If reconciliation fails, the server returns `service-unavailable` with
`<pubsub-unavailable xmlns="urn:xmpp:kanban:0"/>` instead of a snapshot.

Membership commands commit SQL state before the asynchronous outbox applies
PubSub affiliations. Immediately after `add-member`, a new member can
temporarily receive `not-allowed` while subscribing. The deterministic client
flow is:

1. complete `add-member`;
2. let the new member send `get-board`;
3. subscribe using the returned PubSub service and node IDs.

A client MAY instead retry `not-allowed` with bounded backoff, but MUST NOT
retry indefinitely. After removal, access revocation is likewise eventually
applied; command authorization changes immediately.

## 4. Data model

### Board

```xml
<board id="board-id" revision="4"><name>Engineering</name></board>
```

### Column

```xml
<column id="column-id" rank="hzzzzzzzzz">
  <name>Todo</name>
  <wip-limit>5</wip-limit>
</column>
```

Columns do not have an independent revision in v1.

### Card

```xml
<card id="card-id" revision="3">
  <title>Implement OAuth callback</title>
  <column id="column-id"/>
  <rank>hzzzzzzzzz</rank>
  <description>Handle redirects.</description>
  <assignee jid="alice@example.org"/>
  <priority>urgent</priority>
  <labels>
    <label id="label-docs"/>
    <label id="label-backend"/>
  </labels>
</card>
```

Priority is a closed class-of-service enum: `none`, `low`, `normal`, `high`,
or `urgent`. Unknown values are rejected. The server normally omits
`<priority>` for `none`. Cards reference at most eight distinct labels by ID.
Reference order is display order and is preserved by the server.

### Label

```xml
<label id="label-id" color="rose"><name>bug</name></label>
```

Labels are board-scoped. Names are trimmed, contain 1–32 characters, and are
unique case-insensitively within a board. Colors are closed palette tokens:
`slate`, `rose`, `orange`, `amber`, `lime`, `mint`, `sky`, `violet`, and
`pink`. Label IDs are server-generated opaque strings.

### Member

```xml
<member id="member-id" jid="alice@example.org" role="owner"/>
```

Roles are `owner`, `editor`, `viewer`, and `guest`. Owners manage
members and all current board/card operations. Editors create columns, manage
the label catalog, and create/update/move cards, but cannot delete cards or
manage members. Viewers and guests are read-only. The last owner cannot be
demoted or removed.

## 5. Commands

All mutations use IQ `set`. The implemented v1 commands are:

| Command | Required attributes | Child payload | Revision domain |
|---|---|---|---|
| `create-board` | `expected-revision="0"` | `<name>` | new board |
| `create-column` | `board-id`, `expected-revision` | `<name>`, optional `<wip-limit>` | board |
| `create-card` | `board-id`, `column-id`, `expected-revision` | `<title>`, optional `<description>`, `<assignee jid>`, `<priority>`, `<labels>` | board |
| `update-card` | `card-id`, `expected-revision` | present fields are patched | card |
| `move-card` | `card-id`, `column-id`, `expected-revision`; optional `before-card` or `after-card` | none | card |
| `delete-card` | `card-id`, `expected-revision` | none | card |
| `create-label` | `board-id`, `expected-revision` | `<name>`, `<color>` | board |
| `update-label` | `board-id`, `label-id`, `expected-revision` | present `<name>` and/or `<color>` | board |
| `delete-label` | `board-id`, `label-id`, `expected-revision` | none | board |
| `add-member` | `board-id`, `jid`, `role`, `expected-revision` | none | board |
| `update-member-role` | `board-id`, `jid`, `role`, `expected-revision` | none | board |
| `remove-member` | `board-id`, `jid`, `expected-revision` | none | board |

Board update/deletion and column update/move/deletion are not implemented.

### 5.1 Create a column

```xml
<iq type="set" to="kanban.example.org" id="column-create">
  <create-column xmlns="urn:xmpp:kanban:commands:0"
                 board-id="board-id"
                 expected-revision="1">
    <name>Todo</name>
    <wip-limit>5</wip-limit>
  </create-column>
</iq>
```

### 5.2 Create a card

```xml
<iq type="set" to="kanban.example.org" id="card-create">
  <create-card xmlns="urn:xmpp:kanban:commands:0"
               board-id="board-id"
               column-id="column-id"
               expected-revision="2">
    <title>Implement OAuth callback</title>
    <description>Handle redirects.</description>
    <assignee jid="alice@example.org/phone"/>
    <priority>urgent</priority>
    <labels><label id="label-docs"/><label id="label-backend"/></labels>
  </create-card>
</iq>
```

The assignee is stored and returned as `alice@example.org`.

### 5.3 Patch a card

`update-card` is a presence-based patch, not a full replacement. An absent
element leaves the field unchanged. A present empty `description` clears it.
A present `assignee` supplies its `jid`; clients should
omit the element when not changing the assignee.

Priority and labels use the same presence rule. An omitted `<priority>` or
`<labels>` leaves the current value unchanged. `<priority>none</priority>` or
empty `<priority/>` clears priority. A present `<labels>` fully replaces the
ordered label set; empty `<labels/>` clears it. Unknown label IDs, duplicate
IDs, and sets larger than eight are rejected.

```xml
<iq type="set" to="kanban.example.org" id="card-update">
  <update-card xmlns="urn:xmpp:kanban:commands:0"
               card-id="card-id"
               expected-revision="1">
    <title>Updated title</title>
    <description>Updated details</description>
    <assignee jid="bob@example.org/tablet"/>
    <priority>high</priority>
    <labels><label id="label-backend"/></labels>
  </update-card>
</iq>
```

### 5.4 Manage labels

```xml
<create-label xmlns="urn:xmpp:kanban:commands:0"
              board-id="board-id" expected-revision="3">
  <name>bug</name><color>rose</color>
</create-label>

<update-label xmlns="urn:xmpp:kanban:commands:0"
              board-id="board-id" label-id="label-id" expected-revision="4">
  <color>orange</color>
</update-label>

<delete-label xmlns="urn:xmpp:kanban:commands:0"
              board-id="board-id" label-id="label-id" expected-revision="5"/>
```

Deleting a label removes it from the catalog and from every card on the board.
Affected cards receive a new card revision and are republished on the cards
node.

### 5.5 Move a card

```xml
<iq type="set" to="kanban.example.org" id="card-move">
  <move-card xmlns="urn:xmpp:kanban:commands:0"
             card-id="card-id"
             column-id="done-column"
             before-card="next-card-id"
             expected-revision="2"/>
</iq>
```

### 5.6 Mutation result

Mutation replies are deliberately compact and do not contain a canonical entity
snapshot:

```xml
<iq type="result" from="kanban.example.org" id="card-move">
  <result xmlns="urn:xmpp:kanban:commands:0"
          id="card-id"
          board-revision="3"
          revision="3"/>
</iq>
```

`revision` is present only for revisioned entities (boards and cards).
Columns, labels, and members return `id` and `board-revision`.

After a successful mutation, clients SHOULD optimistically update only fields
whose canonical value is known. Because ranks are server-owned and results are
compact, create/move operations SHOULD be followed by `get-board` unless the
client waits for and consumes the matching cards-node publication.

## 6. PubSub

Use the `pubsub-service`, `cards-node`, and `activity-node` values returned
by `get-board`. Do not derive the service or node IDs.

Both nodes are persistent, whitelist-access, and server-published. Board
members receive Openfire's read-only `member` affiliation. Clients MUST NOT
publish directly.

### Cards node

- Node: `boards/<board-id>/cards`
- Item ID: card ID
- Create/update/move payload: canonical `<card xmlns="urn:xmpp:kanban:0">`
- Delete payload:

```xml
<card-tombstone xmlns="urn:xmpp:kanban:0"
                id="card-id"
                revision="4"/>
```

Apply a card snapshot only when its revision is greater than the cached
revision. A tombstone removes the card when its revision is newer.

### Activity node

- Node: `boards/<board-id>/activity`
- Item ID: activity ID
- Payload:

```xml
<event xmlns="urn:xmpp:kanban:events:0"
       id="activity-id"
       type="CardMoved"
       board-id="board-id"
       actor="alice@example.org"
       occurred-at="1785708000000">
  <card xmlns="urn:xmpp:kanban:0" id="card-id" revision="3">
    ...
  </card>
</event>
```

`occurred-at` is Unix epoch milliseconds, not an ISO-8601 timestamp.
Implemented activity types are `BoardCreated`, `ColumnCreated`,
`CardCreated`, `CardUpdated`, `CardMoved`, `CardDeleted`,
`LabelCreated`, `LabelUpdated`, `LabelDeleted`,
`MemberAdded`, `MemberRoleChanged`, and `MemberRemoved`.

Label catalog changes publish activity but have no separate catalog node.
Clients treat `LabelCreated`, `LabelUpdated`, and `LabelDeleted` as signals to
refresh `get-board`. Label deletion additionally republishes every affected
card snapshot.

Activity delivery is ordered per board but asynchronous relative to the
mutation result. Clients MUST NOT use activity replay as the source of truth.

## 7. Errors

Application conditions are children of the stanza `<error>` in
`urn:xmpp:kanban:0`:

| Application condition | Standard stanza condition |
|---|---|
| `bad-request` | `bad-request` |
| `forbidden` | `forbidden` |
| `final-owner` | `forbidden` |
| `item-not-found` | `item-not-found` |
| `invalid-column` | `item-not-found` |
| `revision-conflict` | `conflict` |
| `invalid-position` | `bad-request` |
| `wip-limit-exceeded` | `bad-request` |
| `duplicate-member` | `bad-request` |
| `pubsub-unavailable` | `service-unavailable` |

A revision conflict includes the authoritative revision:

```xml
<error type="cancel">
  <conflict xmlns="urn:ietf:params:xml:ns:xmpp-stanzas"/>
  <revision-conflict xmlns="urn:xmpp:kanban:0"
                     current-revision="3"/>
</error>
```

The PubSub service independently returns standard XEP-0060 errors. In
particular, a non-member subscription can return `not-allowed`; a client
should not rewrite it into a Kanban `forbidden` condition.

## 8. Solstice synchronization algorithm

On board open or reconnect:

1. Send `get-board`.
2. Atomically replace the local board, columns, cards, labels, and members cache.
3. Subscribe to the returned cards and activity nodes at the returned service.
4. Apply newer card snapshots and tombstones from the cards node.
5. Treat activity items as UI/audit notifications, not authoritative state.

On mutation:

1. Use the correct cached board or card revision.
2. Send the mutation.
3. Store revisions from `<result>`.
4. For create/move operations, reconcile using the cards-node publication or
   `get-board`, because the compact result does not include the server rank.
5. Deduplicate PubSub echoes by card ID and revision.

On `revision-conflict`, missed PubSub events, reconnect, or any detected
inconsistency, fetch `get-board` and replace the local snapshot. Do not attempt
`get-card`; it is not part of this profile.

SQL is authoritative. PubSub is an asynchronous projection. A command result
can arrive before its PubSub echo, and data remains authoritative across an
Openfire restart.

## 9. Minimum Solstice v1 conformance

A compatible client MUST:

- discover or configure the Kanban component JID;
- use `list-boards` and `get-board` for authoritative reads;
- use opaque IDs and server-owned ranks;
- send the correct expected revision;
- support the two advertised PubSub nodes and card tombstones;
- handle compact mutation results and the error mappings above;
- refetch the full board after synchronization loss;
- ignore unknown extension elements.

The Docker E2E suite in
`src/test/java/org/igniterealtime/openfire/plugins/kanban/KanbanDockerE2E.java`
verifies discovery, columns, card creation/update/move/deletion, WIP and
revision errors, roles, PubSub access/events/tombstones, and persistence across
an Openfire restart.
