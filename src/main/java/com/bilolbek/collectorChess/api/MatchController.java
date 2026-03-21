package com.bilolbek.collectorChess.api;

import com.bilolbek.collectorChess.api.dto.CreateMatchRequest;
import com.bilolbek.collectorChess.api.dto.JoinMatchRequest;
import com.bilolbek.collectorChess.api.dto.MatchConnectResponse;
import com.bilolbek.collectorChess.domain.model.Contracts;
import com.bilolbek.collectorChess.domain.service.MatchService;
import com.bilolbek.collectorChess.realtime.MatchLivePublisher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchLivePublisher matchLivePublisher;

    public MatchController(MatchService matchService, MatchLivePublisher matchLivePublisher) {
        this.matchService = matchService;
        this.matchLivePublisher = matchLivePublisher;
    }

    @PostMapping
    public ResponseEntity<MatchConnectResponse> createMatch(@RequestBody CreateMatchRequest request, HttpServletRequest httpServletRequest) {
        Contracts.OnlineMatchSnapshot snapshot = matchService.createMatch(request.guestID(), request.displayName());
        return ResponseEntity.ok(new MatchConnectResponse(snapshot, webSocketUrl(httpServletRequest, snapshot.id(), request.guestID())));
    }

    @PostMapping("/join")
    public ResponseEntity<MatchConnectResponse> joinMatch(@RequestBody JoinMatchRequest request, HttpServletRequest httpServletRequest) {
        MatchService.ServiceSnapshot result = matchService.joinMatch(request.roomCode(), request.guestID(), request.displayName());
        if (result.broadcastEvent() != null) {
            matchLivePublisher.broadcast(result.snapshot().id(), result.broadcastEvent());
        }
        return ResponseEntity.ok(new MatchConnectResponse(result.snapshot(), webSocketUrl(httpServletRequest, result.snapshot().id(), request.guestID())));
    }

    @GetMapping("/{matchID}")
    public ResponseEntity<Contracts.OnlineMatchSnapshot> getMatch(
            @PathVariable("matchID") UUID matchId,
            @RequestParam("guestID") UUID guestId
    ) {
        MatchService.ServiceSnapshot result = matchService.getMatch(matchId, guestId);
        if (result.broadcastEvent() != null) {
            matchLivePublisher.broadcast(matchId, result.broadcastEvent());
        }
        return ResponseEntity.ok(result.snapshot());
    }

    @PostMapping("/{matchID}/actions")
    public ResponseEntity<Contracts.OnlineMatchEvent> submitAction(
            @PathVariable("matchID") UUID matchId,
            @RequestBody Contracts.OnlineMatchAction action
    ) {
        Contracts.OnlineMatchEvent event = matchService.submitAction(matchId, action);
        if (event.type() == Contracts.EventType.ACTION_REJECTED) {
            matchLivePublisher.sendToGuest(matchId, action.actorID(), event);
        } else {
            matchLivePublisher.broadcast(matchId, event);
        }
        return ResponseEntity.ok(event);
    }

    private String webSocketUrl(HttpServletRequest request, UUID matchId, UUID guestId) {
        String scheme = "https".equalsIgnoreCase(request.getScheme()) ? "wss" : "ws";
        return scheme + "://" + request.getServerName() + ":" + request.getServerPort()
                + request.getContextPath() + "/v1/matches/" + matchId + "/live?guestID=" + guestId;
    }
}
