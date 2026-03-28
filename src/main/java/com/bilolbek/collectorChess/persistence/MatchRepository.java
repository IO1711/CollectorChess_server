package com.bilolbek.collectorChess.persistence;

import com.bilolbek.collectorChess.domain.model.Contracts;
import com.bilolbek.collectorChess.domain.model.MatchAggregate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchRepository {

    Optional<MatchAggregate> findById(UUID matchId, boolean forUpdate);

    Optional<MatchAggregate> findByRoomCode(String roomCode, boolean forUpdate);

    List<MatchAggregate> findJoinableMatches();

    boolean roomCodeExists(String roomCode);

    void insert(MatchAggregate aggregate);

    void update(MatchAggregate aggregate);

    void deleteById(UUID matchId);

    Optional<Contracts.OnlineMatchEvent> findLoggedEvent(UUID actionId);

    void logAction(Contracts.OnlineMatchAction action, Contracts.OnlineMatchEvent event, boolean accepted, Instant createdAt);
}
