-- Week 2: append-only log of room activity (votes, queue changes, moderation, AI decisions, chat ids).
-- Written in batches by the gateway (never one INSERT per vote) and used for the Week 3 dashboard and for
-- grounding "sourceEvents" audits. Rows are never updated.
CREATE TABLE queue_events (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  lounge_id  VARCHAR(32) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  user_id    VARCHAR(32) NULL,
  payload    JSON        NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_queue_events_lounge_time (lounge_id, created_at),
  CONSTRAINT fk_queue_events_lounge FOREIGN KEY (lounge_id) REFERENCES dj_lounges (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
