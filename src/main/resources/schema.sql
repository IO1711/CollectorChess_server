CREATE TABLE IF NOT EXISTS matches (
    id UUID PRIMARY KEY,
    room_code VARCHAR(16) NOT NULL UNIQUE,
    phase VARCHAR(32) NOT NULL,
    revision INTEGER NOT NULL,
    white_guest_id UUID NOT NULL,
    black_guest_id UUID,
    white_last_connected_at TIMESTAMP WITH TIME ZONE,
    white_last_disconnected_at TIMESTAMP WITH TIME ZONE,
    black_last_connected_at TIMESTAMP WITH TIME ZONE,
    black_last_disconnected_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    snapshot_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_matches_room_code ON matches(room_code);
CREATE INDEX IF NOT EXISTS idx_matches_white_guest_id ON matches(white_guest_id);
CREATE INDEX IF NOT EXISTS idx_matches_black_guest_id ON matches(black_guest_id);

CREATE TABLE IF NOT EXISTS action_logs (
    action_id UUID PRIMARY KEY,
    match_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    base_revision INTEGER NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    accepted BOOLEAN NOT NULL,
    event_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_action_logs_match FOREIGN KEY (match_id) REFERENCES matches(id)
);

CREATE INDEX IF NOT EXISTS idx_action_logs_match_id ON action_logs(match_id);
