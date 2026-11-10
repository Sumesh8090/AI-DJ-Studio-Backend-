-- AI DJ Studio relational schema (MySQL 8.0.16+; CHECK constraints are enforced from that version on).
-- Notes
--  * Enumerations are VARCHAR + CHECK so the database, not only the API, rejects bad values.
--  * queue_entries.position is a sparse integer (step 1000): a reorder normally rewrites one row.
--  * Hot state (votes, sentiment ticks) is NOT written per event; the Week 2 gateway keeps it in
--    memory and persists batched append-only logs (PRD 9.4).
--  * Timestamps are DATETIME(6) in UTC (hibernate.jdbc.time_zone=UTC).

CREATE TABLE users (
  id            VARCHAR(32)  NOT NULL,
  email         VARCHAR(254) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name  VARCHAR(80)  NOT NULL,
  role          VARCHAR(16)  NOT NULL DEFAULT 'listener',
  is_active     TINYINT(1)   NOT NULL DEFAULT 1,
  created_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT ck_users_role CHECK (role IN ('listener','co_dj','host','admin'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_tokens (
  jti        VARCHAR(64)  NOT NULL,
  user_id    VARCHAR(32)  NOT NULL,
  expires_at DATETIME(6)  NOT NULL,
  revoked    TINYINT(1)   NOT NULL DEFAULT 0,
  created_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (jti),
  KEY ix_refresh_user (user_id),
  CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tracks (
  id              VARCHAR(40)  NOT NULL,
  title           VARCHAR(300) NOT NULL,
  artist          VARCHAR(300) NOT NULL,
  duration_seconds INT         NOT NULL,
  audio_asset_url VARCHAR(500) NULL,
  bpm             DOUBLE       NOT NULL,
  camelot_key     VARCHAR(3)   NOT NULL,
  energy          DOUBLE       NOT NULL,
  valence         DOUBLE       NOT NULL,
  PRIMARY KEY (id),
  -- Acoustic columns are indexed for segue-matching queries.
  KEY ix_tracks_bpm (bpm),
  KEY ix_tracks_camelot_key (camelot_key),
  KEY ix_tracks_bpm_key (bpm, camelot_key),
  CONSTRAINT ck_tracks_bpm CHECK (bpm > 0 AND bpm <= 300),
  CONSTRAINT ck_tracks_energy CHECK (energy >= 0 AND energy <= 1),
  CONSTRAINT ck_tracks_valence CHECK (valence >= 0 AND valence <= 1),
  CONSTRAINT ck_tracks_duration CHECK (duration_seconds > 0),
  CONSTRAINT ck_tracks_camelot_key CHECK (camelot_key IN (
    '1A','2A','3A','4A','5A','6A','7A','8A','9A','10A','11A','12A',
    '1B','2B','3B','4B','5B','6B','7B','8B','9B','10B','11B','12B'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track_timeline_events (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  track_id   VARCHAR(40)  NOT NULL,
  ts_seconds INT          NOT NULL,
  event_type VARCHAR(20)  NOT NULL,
  text       VARCHAR(1000) NOT NULL,   -- RAG grounding text (sanitised on write)
  PRIMARY KEY (id),
  KEY ix_timeline_track (track_id, ts_seconds),
  CONSTRAINT fk_timeline_track FOREIGN KEY (track_id) REFERENCES tracks (id) ON DELETE CASCADE,
  CONSTRAINT ck_timeline_type CHECK (event_type IN ('sample_credit','trivia','glossary')),
  CONSTRAINT ck_timeline_ts CHECK (ts_seconds >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dj_lounges (
  id                 VARCHAR(32)  NOT NULL,
  invite_code        VARCHAR(10)  NOT NULL,
  host_user_id       VARCHAR(32)  NOT NULL,
  vibe_profile       VARCHAR(200) NOT NULL,
  current_track_id   VARCHAR(40)  NULL,
  status             VARCHAR(12)  NOT NULL DEFAULT 'scheduled',
  is_locked          TINYINT(1)   NOT NULL DEFAULT 0,
  max_participants   INT          NOT NULL DEFAULT 40,
  bpm_tolerance_pct  DOUBLE       NOT NULL DEFAULT 8,
  soundclash_enabled TINYINT(1)   NOT NULL DEFAULT 1,
  ai_verbosity       VARCHAR(10)  NOT NULL DEFAULT 'normal',
  created_at         DATETIME(6)  NOT NULL,
  ended_at           DATETIME(6)  NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_lounges_invite UNIQUE (invite_code),
  KEY ix_lounges_host (host_user_id),
  CONSTRAINT fk_lounges_host FOREIGN KEY (host_user_id) REFERENCES users (id),
  CONSTRAINT fk_lounges_track FOREIGN KEY (current_track_id) REFERENCES tracks (id),
  CONSTRAINT ck_lounges_status CHECK (status IN ('scheduled','live','ended')),
  CONSTRAINT ck_lounges_capacity CHECK (max_participants >= 2),
  CONSTRAINT ck_lounges_tolerance CHECK (bpm_tolerance_pct > 0 AND bpm_tolerance_pct <= 20),
  CONSTRAINT ck_lounges_verbosity CHECK (ai_verbosity IN ('quiet','normal','chatty'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Membership + per-lounge role (a user can host one lounge and listen in another).
CREATE TABLE lounge_participants (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  lounge_id   VARCHAR(32) NOT NULL,
  user_id     VARCHAR(32) NOT NULL,
  lounge_role VARCHAR(10) NOT NULL DEFAULT 'listener',
  is_muted    TINYINT(1)  NOT NULL DEFAULT 0,
  joined_at   DATETIME(6) NOT NULL,
  left_at     DATETIME(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_participant_lounge_user UNIQUE (lounge_id, user_id),
  KEY ix_participant_user (user_id),
  CONSTRAINT fk_participant_lounge FOREIGN KEY (lounge_id) REFERENCES dj_lounges (id) ON DELETE CASCADE,
  CONSTRAINT fk_participant_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT ck_participant_role CHECK (lounge_role IN ('listener','co_dj','host'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE queue_entries (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  lounge_id        VARCHAR(32) NOT NULL,
  track_id         VARCHAR(40) NOT NULL,
  position         INT         NOT NULL,   -- sparse: steps of 1000
  vote_count       INT         NOT NULL DEFAULT 0,
  added_by_user_id VARCHAR(32) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_queue_lounge_position UNIQUE (lounge_id, position),
  CONSTRAINT fk_queue_lounge FOREIGN KEY (lounge_id) REFERENCES dj_lounges (id) ON DELETE CASCADE,
  CONSTRAINT fk_queue_track FOREIGN KEY (track_id) REFERENCES tracks (id),
  CONSTRAINT fk_queue_user FOREIGN KEY (added_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE soundclash_battles (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  lounge_id        VARCHAR(32) NOT NULL,
  opened_at        DATETIME(6) NOT NULL,
  closes_at        DATETIME(6) NOT NULL,
  status           VARCHAR(10) NOT NULL DEFAULT 'open',
  winning_track_id VARCHAR(40) NULL,
  PRIMARY KEY (id),
  KEY ix_battles_lounge (lounge_id),
  CONSTRAINT fk_battles_lounge FOREIGN KEY (lounge_id) REFERENCES dj_lounges (id) ON DELETE CASCADE,
  CONSTRAINT fk_battles_track FOREIGN KEY (winning_track_id) REFERENCES tracks (id),
  CONSTRAINT ck_battles_status CHECK (status IN ('open','resolved'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE soundclash_candidates (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  battle_id            BIGINT      NOT NULL,
  track_id             VARCHAR(40) NOT NULL,
  submitted_by_user_id VARCHAR(32) NOT NULL,
  vote_count           INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_candidate_battle_track UNIQUE (battle_id, track_id),
  CONSTRAINT fk_candidates_battle FOREIGN KEY (battle_id) REFERENCES soundclash_battles (id) ON DELETE CASCADE,
  CONSTRAINT fk_candidates_track FOREIGN KEY (track_id) REFERENCES tracks (id),
  CONSTRAINT fk_candidates_user FOREIGN KEY (submitted_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_messages (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  lounge_id       VARCHAR(32) NOT NULL,
  user_id         VARCHAR(32) NULL,        -- NULL for AI / system messages
  msg_type        VARCHAR(16) NOT NULL DEFAULT 'chat',
  body            TEXT        NOT NULL,
  sentiment_score DOUBLE      NULL,
  ts_seconds      INT         NOT NULL DEFAULT 0,
  created_at      DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_chat_lounge_created (lounge_id, created_at),
  CONSTRAINT fk_chat_lounge FOREIGN KEY (lounge_id) REFERENCES dj_lounges (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT ck_chat_type CHECK (msg_type IN ('chat','ai_answer','system','dj_announcement'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Feeds the recommendation model (Epic 3).
CREATE TABLE interactions (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  user_id        VARCHAR(32) NOT NULL,
  track_id       VARCHAR(40) NOT NULL,
  played_seconds INT         NOT NULL DEFAULT 0,
  completed      TINYINT(1)  NOT NULL DEFAULT 0,
  liked          TINYINT(1)  NOT NULL DEFAULT 0,
  created_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_interactions_user (user_id),
  KEY ix_interactions_track (track_id),
  CONSTRAINT fk_interactions_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_interactions_track FOREIGN KEY (track_id) REFERENCES tracks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
