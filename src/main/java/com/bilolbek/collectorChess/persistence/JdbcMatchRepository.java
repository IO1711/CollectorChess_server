package com.bilolbek.collectorChess.persistence;

import com.bilolbek.collectorChess.domain.model.Contracts;
import com.bilolbek.collectorChess.domain.model.MatchAggregate;
import com.bilolbek.collectorChess.domain.model.MatchAggregate.SeatState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMatchRepository implements MatchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMatchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MatchAggregate> findById(UUID matchId, boolean forUpdate) {
        String sql = """
                SELECT snapshot_json,
                       white_last_connected_at,
                       white_last_disconnected_at,
                       black_last_connected_at,
                       black_last_disconnected_at
                FROM matches
                WHERE id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, matchRowMapper(), matchId).stream().findFirst();
    }

    @Override
    public Optional<MatchAggregate> findByRoomCode(String roomCode, boolean forUpdate) {
        String sql = """
                SELECT snapshot_json,
                       white_last_connected_at,
                       white_last_disconnected_at,
                       black_last_connected_at,
                       black_last_disconnected_at
                FROM matches
                WHERE room_code = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, matchRowMapper(), roomCode).stream().findFirst();
    }

    @Override
    public boolean roomCodeExists(String roomCode) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM matches WHERE room_code = ?", Integer.class, roomCode);
        return count != null && count > 0;
    }

    @Override
    public void insert(MatchAggregate aggregate) {
        SeatState white = aggregate.seatForColor(Contracts.PieceColor.WHITE)
                .orElseThrow(() -> new IllegalStateException("White seat must exist."));
        SeatState black = aggregate.seatForColor(Contracts.PieceColor.BLACK).orElse(null);
        jdbcTemplate.update("""
                        INSERT INTO matches(
                            id,
                            room_code,
                            phase,
                            revision,
                            white_guest_id,
                            black_guest_id,
                            white_last_connected_at,
                            white_last_disconnected_at,
                            black_last_connected_at,
                            black_last_disconnected_at,
                            created_at,
                            updated_at,
                            snapshot_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                aggregate.id(),
                aggregate.roomCode(),
                aggregate.phase().wireValue(),
                aggregate.revision(),
                white.guestId(),
                black == null ? null : black.guestId(),
                timestamp(white.lastConnectedAt()),
                timestamp(white.lastDisconnectedAt()),
                black == null ? null : timestamp(black.lastConnectedAt()),
                black == null ? null : timestamp(black.lastDisconnectedAt()),
                timestamp(aggregate.createdAt()),
                timestamp(aggregate.updatedAt()),
                writeJson(aggregate.toSnapshot())
        );
    }

    @Override
    public void update(MatchAggregate aggregate) {
        SeatState white = aggregate.seatForColor(Contracts.PieceColor.WHITE)
                .orElseThrow(() -> new IllegalStateException("White seat must exist."));
        SeatState black = aggregate.seatForColor(Contracts.PieceColor.BLACK).orElse(null);
        jdbcTemplate.update("""
                        UPDATE matches
                        SET phase = ?,
                            revision = ?,
                            white_guest_id = ?,
                            black_guest_id = ?,
                            white_last_connected_at = ?,
                            white_last_disconnected_at = ?,
                            black_last_connected_at = ?,
                            black_last_disconnected_at = ?,
                            updated_at = ?,
                            snapshot_json = ?
                        WHERE id = ?
                        """,
                aggregate.phase().wireValue(),
                aggregate.revision(),
                white.guestId(),
                black == null ? null : black.guestId(),
                timestamp(white.lastConnectedAt()),
                timestamp(white.lastDisconnectedAt()),
                black == null ? null : timestamp(black.lastConnectedAt()),
                black == null ? null : timestamp(black.lastDisconnectedAt()),
                timestamp(aggregate.updatedAt()),
                writeJson(aggregate.toSnapshot()),
                aggregate.id()
        );
    }

    @Override
    public Optional<Contracts.OnlineMatchEvent> findLoggedEvent(UUID actionId) {
        return jdbcTemplate.query("SELECT event_json FROM action_logs WHERE action_id = ?",
                        (rs, rowNum) -> readJson(rs.getString("event_json"), Contracts.OnlineMatchEvent.class),
                        actionId)
                .stream()
                .findFirst();
    }

    @Override
    public void logAction(Contracts.OnlineMatchAction action, Contracts.OnlineMatchEvent event, boolean accepted, Instant createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO action_logs(action_id, match_id, actor_id, base_revision, action_type, accepted, event_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                action.id(),
                action.matchID(),
                action.actorID(),
                action.baseRevision(),
                action.type().wireValue(),
                accepted,
                writeJson(event),
                timestamp(createdAt)
        );
    }

    private RowMapper<MatchAggregate> matchRowMapper() {
        return (rs, rowNum) -> MatchAggregate.fromSnapshot(
                readJson(rs.getString("snapshot_json"), Contracts.OnlineMatchSnapshot.class),
                instant(rs, "white_last_connected_at"),
                instant(rs, "white_last_disconnected_at"),
                instant(rs, "black_last_connected_at"),
                instant(rs, "black_last_disconnected_at")
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(ResultSet rs, String columnName) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize persistent JSON.", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize persistent JSON.", exception);
        }
    }
}
