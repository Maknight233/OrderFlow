-- Nullable for existing events; new events preserve W3C context across retries and restarts.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(255);
