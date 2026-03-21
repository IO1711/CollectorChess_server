package com.bilolbek.collectorChess.realtime;

import com.bilolbek.collectorChess.domain.model.Contracts;
import com.bilolbek.collectorChess.domain.service.MatchService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Component
public class MatchLiveSocketHandler extends TextWebSocketHandler {

    private final MatchService matchService;
    private final MatchLivePublisher matchLivePublisher;

    public MatchLiveSocketHandler(MatchService matchService, MatchLivePublisher matchLivePublisher) {
        this.matchService = matchService;
        this.matchLivePublisher = matchLivePublisher;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID matchId = extractMatchId(session.getUri());
        UUID guestId = extractGuestId(session.getUri());
        MatchLivePublisher.Registration registration = matchLivePublisher.register(matchId, guestId, session);
        MatchService.ServiceSnapshot serviceSnapshot = matchService.markGuestConnected(matchId, guestId);

        Contracts.OnlineMatchEvent snapshotEvent = new Contracts.OnlineMatchEvent(
                UUID.randomUUID(),
                serviceSnapshot.snapshot().id(),
                serviceSnapshot.snapshot().revision(),
                Contracts.EventType.SNAPSHOT,
                null,
                null,
                serviceSnapshot.snapshot()
        );
        matchLivePublisher.send(registration.session(), snapshotEvent);
        if (serviceSnapshot.broadcastEvent() != null && registration.firstSessionForGuest()) {
            matchLivePublisher.broadcast(matchId, serviceSnapshot.broadcastEvent());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        MatchLivePublisher.DisconnectRegistration disconnect = matchLivePublisher.unregister(session.getId());
        if (disconnect == null || !disconnect.lastSessionForGuest()) {
            return;
        }
        Contracts.OnlineMatchEvent event = matchService.markGuestDisconnected(disconnect.matchId(), disconnect.guestId());
        if (event != null) {
            matchLivePublisher.broadcast(disconnect.matchId(), event);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // The live socket is server-push only for v1.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private UUID extractMatchId(URI uri) {
        List<String> segments = UriComponentsBuilder.fromUri(uri).build().getPathSegments();
        if (segments.size() < 4) {
            throw new IllegalArgumentException("Unable to extract match ID from websocket path.");
        }
        return UUID.fromString(segments.get(2));
    }

    private UUID extractGuestId(URI uri) {
        String guestId = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("guestID");
        if (guestId == null) {
            throw new IllegalArgumentException("Missing guestID query parameter.");
        }
        return UUID.fromString(guestId);
    }
}
