package com.bilolbek.collectorChess.realtime;

import com.bilolbek.collectorChess.domain.model.Contracts;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MatchLivePublisher {

    private final ObjectMapper objectMapper;
    private final Map<UUID, Map<UUID, Set<WebSocketSession>>> sessionsByMatch = new ConcurrentHashMap<>();
    private final Map<String, SessionRef> sessionsById = new ConcurrentHashMap<>();

    public MatchLivePublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Registration register(UUID matchId, UUID guestId, WebSocketSession rawSession) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, 5_000, 64 * 1024);
        Map<UUID, Set<WebSocketSession>> guestSessions = sessionsByMatch.computeIfAbsent(matchId, ignored -> new ConcurrentHashMap<>());
        Set<WebSocketSession> sessions = guestSessions.computeIfAbsent(guestId, ignored -> ConcurrentHashMap.newKeySet());
        boolean firstSessionForGuest = sessions.isEmpty();
        sessions.add(session);
        sessionsById.put(session.getId(), new SessionRef(matchId, guestId, session));
        return new Registration(session, firstSessionForGuest);
    }

    public DisconnectRegistration unregister(String sessionId) {
        SessionRef sessionRef = sessionsById.remove(sessionId);
        if (sessionRef == null) {
            return null;
        }
        Map<UUID, Set<WebSocketSession>> guestSessions = sessionsByMatch.get(sessionRef.matchId());
        if (guestSessions == null) {
            return null;
        }
        Set<WebSocketSession> sessions = guestSessions.get(sessionRef.guestId());
        if (sessions == null) {
            return null;
        }
        sessions.remove(sessionRef.session());
        boolean lastSessionForGuest = sessions.isEmpty();
        if (lastSessionForGuest) {
            guestSessions.remove(sessionRef.guestId());
        }
        if (guestSessions.isEmpty()) {
            sessionsByMatch.remove(sessionRef.matchId());
        }
        return new DisconnectRegistration(sessionRef.matchId(), sessionRef.guestId(), lastSessionForGuest);
    }

    public void broadcast(UUID matchId, Contracts.OnlineMatchEvent event) {
        Map<UUID, Set<WebSocketSession>> guestSessions = sessionsByMatch.get(matchId);
        if (guestSessions == null) {
            return;
        }
        TextMessage message = toMessage(event);
        guestSessions.values().stream()
                .flatMap(Set::stream)
                .forEach(session -> send(session, message));
    }

    public void sendToGuest(UUID matchId, UUID guestId, Contracts.OnlineMatchEvent event) {
        Map<UUID, Set<WebSocketSession>> guestSessions = sessionsByMatch.get(matchId);
        if (guestSessions == null) {
            return;
        }
        Set<WebSocketSession> sessions = guestSessions.get(guestId);
        if (sessions == null) {
            return;
        }
        TextMessage message = toMessage(event);
        sessions.forEach(session -> send(session, message));
    }

    public void send(WebSocketSession session, Contracts.OnlineMatchEvent event) {
        send(session, toMessage(event));
    }

    private TextMessage toMessage(Contracts.OnlineMatchEvent event) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(event));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize websocket event.", exception);
        }
    }

    private void send(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException exception) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // Best effort cleanup.
            }
        }
    }

    public record Registration(
            WebSocketSession session,
            boolean firstSessionForGuest
    ) {
    }

    public record DisconnectRegistration(
            UUID matchId,
            UUID guestId,
            boolean lastSessionForGuest
    ) {
    }

    private record SessionRef(
            UUID matchId,
            UUID guestId,
            WebSocketSession session
    ) {
    }
}
