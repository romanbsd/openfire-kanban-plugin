INSERT INTO ofVersion (name, version) VALUES ('kanban', 1);

CREATE TABLE ofKanbanBoard (
  boardID VARCHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  revision BIGINT NOT NULL,
  activitySequence BIGINT DEFAULT 0 NOT NULL,
  createdBy VARCHAR(1024) NOT NULL,
  createdAt BIGINT NOT NULL,
  updatedAt BIGINT NOT NULL,
  CONSTRAINT ofKanbanBoard_pk PRIMARY KEY (boardID)
);

CREATE TABLE ofKanbanColumn (
  columnID VARCHAR(36) NOT NULL,
  boardID VARCHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  rank VARCHAR(255) NOT NULL,
  wipLimit INT NOT NULL,
  createdAt BIGINT NOT NULL,
  CONSTRAINT ofKanbanColumn_pk PRIMARY KEY (columnID),
  CONSTRAINT ofKanbanColumn_board_fk FOREIGN KEY (boardID) REFERENCES ofKanbanBoard(boardID),
  CONSTRAINT ofKanbanColumn_rank_uq UNIQUE (boardID, rank)
);

CREATE TABLE ofKanbanCard (
  cardID VARCHAR(36) NOT NULL,
  boardID VARCHAR(36) NOT NULL,
  columnID VARCHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  rank VARCHAR(255) NOT NULL,
  title VARCHAR(255) NOT NULL,
  description LONGVARCHAR NULL,
  assigneeJID VARCHAR(1024) NULL,
  deleted INT NOT NULL,
  createdBy VARCHAR(1024) NOT NULL,
  createdAt BIGINT NOT NULL,
  updatedAt BIGINT NOT NULL,
  deletedAt BIGINT NULL,
  CONSTRAINT ofKanbanCard_pk PRIMARY KEY (cardID),
  CONSTRAINT ofKanbanCard_board_fk FOREIGN KEY (boardID) REFERENCES ofKanbanBoard(boardID),
  CONSTRAINT ofKanbanCard_column_fk FOREIGN KEY (columnID) REFERENCES ofKanbanColumn(columnID),
  CONSTRAINT ofKanbanCard_rank_uq UNIQUE (columnID, rank)
);

CREATE TABLE ofKanbanMember (
  memberID VARCHAR(36) NOT NULL,
  boardID VARCHAR(36) NOT NULL,
  bareJID VARCHAR(1024) NOT NULL,
  role VARCHAR(16) NOT NULL,
  createdAt BIGINT NOT NULL,
  CONSTRAINT ofKanbanMember_pk PRIMARY KEY (memberID),
  CONSTRAINT ofKanbanMember_board_fk FOREIGN KEY (boardID) REFERENCES ofKanbanBoard(boardID),
  CONSTRAINT ofKanbanMember_jid_uq UNIQUE (boardID, bareJID)
);

CREATE TABLE ofKanbanActivity (
  activityID VARCHAR(36) NOT NULL,
  boardID VARCHAR(36) NOT NULL,
  sequence BIGINT NOT NULL,
  eventType VARCHAR(32) NOT NULL,
  actorJID VARCHAR(1024) NOT NULL,
  entityID VARCHAR(36) NOT NULL,
  payload LONGVARCHAR NOT NULL,
  occurredAt BIGINT NOT NULL,
  CONSTRAINT ofKanbanActivity_pk PRIMARY KEY (activityID),
  CONSTRAINT ofKanbanActivity_board_fk FOREIGN KEY (boardID) REFERENCES ofKanbanBoard(boardID),
  CONSTRAINT ofKanbanActivity_sequence_uq UNIQUE (boardID, sequence)
);

CREATE TABLE ofKanbanOutbox (
  outboxID VARCHAR(36) NOT NULL,
  boardID VARCHAR(36) NOT NULL,
  sequence BIGINT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  nodeID VARCHAR(255) NOT NULL,
  itemID VARCHAR(36) NOT NULL,
  payload LONGVARCHAR NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL,
  availableAt BIGINT NOT NULL,
  leaseOwner VARCHAR(255) NULL,
  leaseUntil BIGINT NULL,
  lastError VARCHAR(2000) NULL,
  createdAt BIGINT NOT NULL,
  deliveredAt BIGINT NULL,
  CONSTRAINT ofKanbanOutbox_pk PRIMARY KEY (outboxID),
  CONSTRAINT ofKanbanOutbox_board_fk FOREIGN KEY (boardID) REFERENCES ofKanbanBoard(boardID),
  CONSTRAINT ofKanbanOutbox_sequence_uq UNIQUE (boardID, sequence)
);

CREATE INDEX ofKanbanCard_board_idx ON ofKanbanCard (boardID, deleted);
CREATE INDEX ofKanbanOutbox_ready_idx ON ofKanbanOutbox (status, availableAt);
