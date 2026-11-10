-- Week 3: the analytics dashboard groups queue_events by type per lounge, and reads chat by lounge in time order.
CREATE INDEX ix_queue_events_lounge_type ON queue_events (lounge_id, event_type);
CREATE INDEX ix_interactions_created ON interactions (created_at);
