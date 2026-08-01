# Openfire Kanban Plugin Design Guide (Revised)

## Purpose

This document describes the implementation of an **Openfire-native**
Kanban plugin.

This project intentionally targets **Openfire only**. Portability to
other XMPP servers is **not** a design goal for the initial
implementation. The objective is to produce the reference implementation
of the XMPP Kanban protocol as quickly as possible while keeping the
codebase clean and maintainable.

------------------------------------------------------------------------

# Design Principles

-   Openfire-first
-   Server-authoritative
-   PostgreSQL recommended for production
-   HSQLDB supported for development/tests
-   Business logic separated from transport
-   PubSub used for synchronization, not as the primary database

------------------------------------------------------------------------

# Repository Layout

    openfire-kanban-plugin/
    ├── pom.xml
    ├── README.md
    ├── LICENSE
    ├── .github/
    ├── src/
    │   ├── main/
    │   │   ├── java/
    │   │   │   └── org/example/openfire/kanban/
    │   │   │       ├── KanbanPlugin.java
    │   │   │       ├── KanbanComponent.java
    │   │   │       ├── iq/
    │   │   │       ├── service/
    │   │   │       ├── repository/
    │   │   │       ├── model/
    │   │   │       ├── pubsub/
    │   │   │       ├── outbox/
    │   │   │       └── xml/
    │   │   ├── resources/
    │   │   │   ├── plugin.xml
    │   │   │   └── database/
    │   │   └── web/
    │   └── test/

Single Maven module.

------------------------------------------------------------------------

# Component Registration

The plugin registers an Internal Component:

    kanban.<domain>

All protocol traffic is addressed to this component.

------------------------------------------------------------------------

# Responsibilities

## KanbanPlugin

-   lifecycle
-   dependency wiring
-   database initialization
-   component registration

## KanbanComponent

-   IQ routing
-   service discovery
-   protocol entry point

## IQ Handlers

-   Parse XML
-   Validate request
-   Invoke services
-   Produce IQ response

## Services

-   Business rules
-   Permissions
-   Revision checks
-   WIP limits
-   Activity generation

## Repository

-   SQL only

------------------------------------------------------------------------

# Openfire APIs

Use directly:

-   Plugin
-   InternalComponent
-   ComponentManager
-   DbConnectionManager
-   PubSubService
-   RoutingTable
-   GroupManager
-   UserManager
-   CacheFactory (optional)
-   JiveGlobals

No unnecessary abstraction layer is required.

------------------------------------------------------------------------

# Database

Plugin-owned tables:

    ofKanbanBoard
    ofKanbanColumn
    ofKanbanCard
    ofKanbanComment
    ofKanbanAttachment
    ofKanbanActivity
    ofKanbanMember
    ofKanbanOutbox

Never modify Openfire core tables.

------------------------------------------------------------------------

# Transaction Flow

1.  Receive IQ
2.  Authenticate sender
3.  Check permissions
4.  Verify expected revision
5.  Execute SQL transaction
6.  Insert activity
7.  Insert outbox row
8.  Commit
9.  Publish PubSub updates
10. Reply to client

------------------------------------------------------------------------

# Outbox

The database is authoritative.

PubSub is eventually synchronized from the outbox.

State machine:

    Pending
      ↓
    Publishing
      ↓
    Delivered

Retry until success.

------------------------------------------------------------------------

# PubSub

Publish only:

-   current card snapshot
-   immutable activity event

Never reconstruct authoritative state from PubSub.

------------------------------------------------------------------------

# Optimistic Concurrency

Every mutation includes:

    expected-revision

SQL pattern:

``` sql
UPDATE ofKanbanCard
SET revision = revision + 1
WHERE cardID = ?
  AND revision = ?;
```

Zero affected rows indicates a conflict.

------------------------------------------------------------------------

# Ranking

Each card stores an explicit sortable rank.

Recommended:

-   LexoRank
-   Fractional indexing

Never infer ordering from publication order.

------------------------------------------------------------------------

# Admin Console

Configuration:

-   default permissions
-   default WIP limit
-   attachment limits
-   activity retention

Diagnostics:

-   pending outbox
-   failed publications
-   board count
-   card count

------------------------------------------------------------------------

# Testing

Unit:

-   services
-   ranking
-   permissions

Integration:

-   embedded Openfire
-   HSQLDB

System:

-   PostgreSQL
-   concurrent clients
-   revision conflict tests

------------------------------------------------------------------------

# Initial Milestone

Implement only:

-   board creation
-   column creation
-   card creation
-   move card
-   update card
-   delete card
-   activity events
-   PubSub notifications

Postpone:

-   automation
-   swimlanes
-   AI
-   federation
-   CRDT editing

------------------------------------------------------------------------

# Coding Guidelines

-   SQL isolated in repositories
-   XML isolated in serializers/parsers

------------------------------------------------------------------------

# Implemented Milestone 0 Profile

The implementation uses `org.igniterealtime.openfire.plugins.kanban` and the
standard Openfire plugin layout (`plugin.xml` at the archive root, database
scripts under `src/main/database`, web assets under `src/main/web`).

Authoritative reads are `list-boards` and `get-board`. Mutations include board
and column creation; card creation, patch, movement, and soft deletion; and
member add, role update, and removal. Board revisions protect board structure
and membership. Card revisions protect card fields, rank, column, and deletion.
The server generates all entity IDs and ranks. Board-row locking serializes
WIP-sensitive writes, while card compare-and-set revisions reject concurrent
edits to the same card.

Ranking is an internal, fixed-width base-36 LexoRank implementation. Ranks are
never accepted from clients. When no midpoint remains, all live cards in the
destination column are transactionally rebalanced before the requested move is
applied.

Every committed mutation writes an immutable activity event and one or more
outbox rows in the same SQL transaction. A leased worker preserves per-board
ordering, retries with bounded exponential backoff, and safely reclaims expired
leases. Card mutations publish a current card snapshot plus activity; board,
column, and membership mutations publish activity. Membership changes also
grant or revoke access to both board nodes. Delivered outbox rows and activity
history are purged daily according to the retention setting; a monotonic board
sequence counter prevents sequence reuse after retention.

Comments and attachments remain outside Milestone 0. The draft protocol areas
that required implementation-specific decisions are recorded in
`docs/protoxep-gap-analysis.md`.
-   IQ handlers remain thin
-   Services own business logic
-   Immutable command objects where practical
-   Versioned database migrations
-   Comprehensive integration tests

------------------------------------------------------------------------

# Future

If a standalone component is ever desired, only the transport layer
should need replacement. The current implementation deliberately
optimizes for Openfire simplicity rather than server portability.
