package com.bilolbek.collectorChess.domain.service;

import com.bilolbek.collectorChess.domain.model.Contracts;
import com.bilolbek.collectorChess.domain.model.Contracts.ActionType;
import com.bilolbek.collectorChess.domain.model.Contracts.ChessGameStatusSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.ConnectionStatus;
import com.bilolbek.collectorChess.domain.model.Contracts.EventType;
import com.bilolbek.collectorChess.domain.model.Contracts.GameStatusKind;
import com.bilolbek.collectorChess.domain.model.Contracts.GuestPlayer;
import com.bilolbek.collectorChess.domain.model.Contracts.JoinableRoomSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.JoinMatchPayload;
import com.bilolbek.collectorChess.domain.model.Contracts.MakeMovePayload;
import com.bilolbek.collectorChess.domain.model.Contracts.MatchPhase;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchEvent;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchOutcome;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.OutcomeKind;
import com.bilolbek.collectorChess.domain.model.Contracts.PieceColor;
import com.bilolbek.collectorChess.domain.model.Contracts.Position;
import com.bilolbek.collectorChess.domain.model.Contracts.UpdateDraftSelectionPayload;
import com.bilolbek.collectorChess.domain.model.MatchAggregate;
import com.bilolbek.collectorChess.domain.model.MatchAggregate.DraftSelectionState;
import com.bilolbek.collectorChess.domain.model.MatchAggregate.SeatState;
import com.bilolbek.collectorChess.domain.rules.ChessRules;
import com.bilolbek.collectorChess.persistence.MatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MatchService {

    private static final Set<String> ACTIVE_SKILLS = Set.of("shadowstep", "knightfall", "deadeye");

    private final MatchRepository matchRepository;
    private final RoomCodeGenerator roomCodeGenerator;
    private final ChessRules chessRules;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MatchService(
            MatchRepository matchRepository,
            RoomCodeGenerator roomCodeGenerator,
            ChessRules chessRules,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.matchRepository = matchRepository;
        this.roomCodeGenerator = roomCodeGenerator;
        this.chessRules = chessRules;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public OnlineMatchSnapshot createMatch(UUID guestId, String displayName) {
        String sanitizedName = validateDisplayName(displayName);
        UUID nonNullGuestId = requireGuestId(guestId);
        Instant now = Instant.now(clock);
        MatchAggregate aggregate = MatchAggregate.create(nonNullGuestId, sanitizedName, uniqueRoomCode(), now);
        matchRepository.insert(aggregate);
        return aggregate.toSnapshot();
    }

    @Transactional(readOnly = true)
    public List<JoinableRoomSnapshot> listJoinableRooms() {
        return matchRepository.findJoinableMatches().stream()
                .map(this::toJoinableRoomSnapshot)
                .toList();
    }

    @Transactional
    public ServiceSnapshot joinMatch(String roomCode, UUID guestId, String displayName) {
        String normalizedRoomCode = validateRoomCode(roomCode);
        String sanitizedName = validateDisplayName(displayName);
        UUID nonNullGuestId = requireGuestId(guestId);
        Instant now = Instant.now(clock);

        MatchAggregate aggregate = matchRepository.findByRoomCode(normalizedRoomCode, true)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "roomNotFound", "The requested room code does not exist."));

        boolean changed = false;
        SeatState existingSeat = aggregate.seatForGuest(nonNullGuestId).orElse(null);
        if (existingSeat != null) {
            changed = markConnected(existingSeat, now);
        } else if (aggregate.seatForColor(PieceColor.BLACK).isEmpty()) {
            aggregate.addJoiner(nonNullGuestId, sanitizedName, now);
            aggregate.incrementRevision();
            changed = true;
        } else {
            throw new DomainException(HttpStatus.CONFLICT, "roomFull", "The black seat is already occupied.");
        }

        aggregate.touch(now);
        if (changed) {
            matchRepository.update(aggregate);
        }
        OnlineMatchEvent broadcastEvent = changed ? buildEvent(aggregate, EventType.SNAPSHOT, null, null) : null;
        return new ServiceSnapshot(aggregate.toSnapshot(), broadcastEvent);
    }

    @Transactional
    public ServiceSnapshot getMatch(UUID matchId, UUID guestId) {
        UUID nonNullGuestId = requireGuestId(guestId);
        Instant now = Instant.now(clock);
        MatchAggregate aggregate = matchRepository.findById(matchId, true)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "matchNotFound", "The requested match does not exist."));
        SeatState seat = aggregate.seatForGuest(nonNullGuestId)
                .orElseThrow(() -> new DomainException(HttpStatus.FORBIDDEN, "seatOwnership", "The guest does not own a seat in this match."));

        boolean changed = markConnected(seat, now);
        aggregate.touch(now);
        if (changed) {
            matchRepository.update(aggregate);
        }
        OnlineMatchEvent broadcastEvent = changed ? buildEvent(aggregate, EventType.PRESENCE_CHANGED, null, null) : null;
        return new ServiceSnapshot(aggregate.toSnapshot(), broadcastEvent);
    }

    @Transactional
    public OnlineMatchEvent submitAction(UUID pathMatchId, Contracts.OnlineMatchAction action) {
        if (action == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "missingAction", "The request body must include an action envelope.");
        }
        if (!pathMatchId.equals(action.matchID())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "matchMismatch", "The action matchID must match the URL path.");
        }

        OnlineMatchEvent cached = matchRepository.findLoggedEvent(action.id()).orElse(null);
        if (cached != null) {
            return cached;
        }

        Instant now = Instant.now(clock);
        MatchAggregate aggregate = matchRepository.findById(pathMatchId, true)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "matchNotFound", "The requested match does not exist."));

        OnlineMatchEvent event;
        try {
            validateActionEnvelope(action, aggregate);
            applyAction(aggregate, action, now);
            aggregate.touch(now);
            event = buildAcceptedActionEvent(aggregate, action);
            persistAcceptedAction(aggregate, action, event, now);
        } catch (DomainException exception) {
            event = buildEvent(aggregate, EventType.ACTION_REJECTED, action.id(), exception.code());
            matchRepository.logAction(action, event, false, now);
        }
        return event;
    }

    @Transactional
    public ServiceSnapshot markGuestConnected(UUID matchId, UUID guestId) {
        Instant now = Instant.now(clock);
        MatchAggregate aggregate = matchRepository.findById(matchId, true)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "matchNotFound", "The requested match does not exist."));
        SeatState seat = aggregate.seatForGuest(guestId)
                .orElseThrow(() -> new DomainException(HttpStatus.FORBIDDEN, "seatOwnership", "The guest does not own a seat in this match."));
        boolean changed = markConnected(seat, now);
        aggregate.touch(now);
        if (changed) {
            matchRepository.update(aggregate);
        }
        OnlineMatchEvent broadcastEvent = changed ? buildEvent(aggregate, EventType.PRESENCE_CHANGED, null, null) : null;
        return new ServiceSnapshot(aggregate.toSnapshot(), broadcastEvent);
    }

    @Transactional
    public OnlineMatchEvent markGuestDisconnected(UUID matchId, UUID guestId) {
        Instant now = Instant.now(clock);
        MatchAggregate aggregate = matchRepository.findById(matchId, true)
                .orElse(null);
        if (aggregate == null) {
            return null;
        }
        SeatState seat = aggregate.seatForGuest(guestId).orElse(null);
        if (seat == null || seat.connectionStatus() == ConnectionStatus.DISCONNECTED) {
            return null;
        }
        seat.connectionStatus(ConnectionStatus.DISCONNECTED);
        seat.lastDisconnectedAt(now);
        aggregate.touch(now);
        matchRepository.update(aggregate);
        return buildEvent(aggregate, EventType.PRESENCE_CHANGED, null, null);
    }

    private void applyAction(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        if (action.baseRevision() != aggregate.revision()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "staleRevision", "The submitted baseRevision does not match the server revision.");
        }

        switch (action.type()) {
            case JOIN_MATCH -> handleJoinMatchAction(aggregate, action, now);
            case UPDATE_DRAFT_SELECTION -> handleDraftSelectionUpdate(aggregate, action, now);
            case LOCK_DRAFT -> handleLockDraft(aggregate, action, now);
            case MAKE_MOVE -> handleMakeMove(aggregate, action, now);
            case RESIGN -> handleResign(aggregate, action, now);
            case LEAVE_MATCH -> handleLeaveMatch(aggregate, action, now);
        }
    }

    private void handleJoinMatchAction(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        JoinMatchPayload payload = convertPayload(action.payload(), JoinMatchPayload.class);
        SeatState existingSeat = requireSeatOwner(aggregate, action.actorID(), false);
        if (existingSeat != null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "alreadyJoined", "This guest already owns a seat in the match.");
        }
        if (aggregate.seatForColor(PieceColor.BLACK).isPresent()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "roomFull", "The black seat is already occupied.");
        }
        aggregate.addJoiner(action.actorID(), validateDisplayName(payload.displayName()), now);
        aggregate.incrementRevision();
    }

    private void handleDraftSelectionUpdate(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        if (aggregate.phase() != MatchPhase.DRAFTING) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPhase", "Draft selections may only be updated during the drafting phase.");
        }
        SeatState seat = requireSeatOwner(aggregate, action.actorID(), true);
        if (seat.ready()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "draftLocked", "Locked-in draft selections cannot be changed.");
        }

        UpdateDraftSelectionPayload payload = convertPayload(action.payload(), UpdateDraftSelectionPayload.class);
        String skillId = validateSkillId(payload.skillID());
        if (payload.position() != null) {
            validateBoardPosition(payload.position());
            aggregate.pieceAt(payload.position())
                    .filter(piece -> piece.color() == seat.color())
                    .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "invalidDraftPiece", "Draft selections must target a piece owned by the acting color."));
        }

        List<DraftSelectionState> colorSelections = aggregate.draftSelections().stream()
                .filter(selection -> selection.color() == seat.color())
                .toList();
        boolean alreadySelected = colorSelections.stream().anyMatch(selection -> selection.skillId().equals(skillId));
        long activeSelectionCount = colorSelections.stream()
                .filter(selection -> selection.position() != null)
                .count();
        if (payload.position() != null && !alreadySelected && activeSelectionCount >= MatchAggregate.MAX_SKILLS_PER_PLAYER) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "skillLimit", "Each player may select at most two skills.");
        }

        aggregate.setDraftSelection(seat.color(), skillId, payload.position());
        aggregate.incrementRevision();
        aggregate.touch(now);
    }

    private void handleLockDraft(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        if (aggregate.phase() != MatchPhase.DRAFTING) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPhase", "Draft locking is only valid during the drafting phase.");
        }
        SeatState seat = requireSeatOwner(aggregate, action.actorID(), true);
        if (seat.ready()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "draftLocked", "The acting seat is already locked in.");
        }

        aggregate.lockDraft(seat.color());
        if (aggregate.bothSeatsReady()) {
            aggregate.applyDraftSelectionsToPieces();
            aggregate.phase(MatchPhase.ACTIVE);
            aggregate.currentTurn(PieceColor.WHITE);
            boolean whiteInCheck = chessRules.isKingInCheck(aggregate, PieceColor.WHITE);
            aggregate.status(new ChessGameStatusSnapshot(GameStatusKind.ACTIVE, PieceColor.WHITE, whiteInCheck, null));
            aggregate.clearOutcome();
        }
        aggregate.incrementRevision();
        aggregate.touch(now);
    }

    private void handleMakeMove(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        MakeMovePayload payload = convertPayload(action.payload(), MakeMovePayload.class);
        SeatState seat = requireSeatOwner(aggregate, action.actorID(), true);
        if (payload.move() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPayload", "makeMove requires a move payload.");
        }
        chessRules.applyMove(aggregate, seat.color(), payload.move(), now);
        aggregate.incrementRevision();
        aggregate.touch(now);
    }

    private void handleResign(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        if (!(aggregate.phase() == MatchPhase.ACTIVE || aggregate.phase() == MatchPhase.DRAFTING)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPhase", "Resignation is only valid in drafting or active matches.");
        }
        SeatState seat = requireSeatOwner(aggregate, action.actorID(), true);
        PieceColor winner = seat.color().opposite();
        aggregate.phase(MatchPhase.FINISHED);
        aggregate.currentTurn(winner);
        aggregate.status(new ChessGameStatusSnapshot(GameStatusKind.ACTIVE, null, null, winner));
        aggregate.outcome(new OnlineMatchOutcome(OutcomeKind.RESIGNATION, winner, now));
        aggregate.incrementRevision();
        aggregate.touch(now);
    }

    private void handleLeaveMatch(MatchAggregate aggregate, Contracts.OnlineMatchAction action, Instant now) {
        SeatState seat = requireSeatOwner(aggregate, action.actorID(), true);
        seat.connectionStatus(ConnectionStatus.DISCONNECTED);
        seat.lastDisconnectedAt(now);
        aggregate.incrementRevision();
        aggregate.touch(now);
    }

    private OnlineMatchEvent buildAcceptedActionEvent(MatchAggregate aggregate, Contracts.OnlineMatchAction action) {
        EventType eventType = action.type() == ActionType.LEAVE_MATCH
                ? EventType.PRESENCE_CHANGED
                : aggregate.phase() == MatchPhase.FINISHED ? EventType.FINISHED : EventType.ACTION_ACCEPTED;
        return buildEvent(aggregate, eventType, action.id(), null);
    }

    private OnlineMatchEvent buildEvent(MatchAggregate aggregate, EventType eventType, UUID actionId, String rejectionReason) {
        OnlineMatchSnapshot snapshot = aggregate.toSnapshot();
        return new OnlineMatchEvent(
                UUID.randomUUID(),
                snapshot.id(),
                snapshot.revision(),
                eventType,
                actionId,
                rejectionReason,
                snapshot
        );
    }

    private JoinableRoomSnapshot toJoinableRoomSnapshot(MatchAggregate aggregate) {
        SeatState hostSeat = aggregate.seatForColor(PieceColor.WHITE)
                .orElseThrow(() -> new IllegalStateException("White seat must exist."));
        GuestPlayer host = new GuestPlayer(
                hostSeat.guestId(),
                hostSeat.displayName(),
                hostSeat.playerCreatedAt()
        );
        return new JoinableRoomSnapshot(
                aggregate.id(),
                aggregate.roomCode(),
                host,
                aggregate.createdAt(),
                aggregate.updatedAt()
        );
    }

    private void persistAcceptedAction(
            MatchAggregate aggregate,
            Contracts.OnlineMatchAction action,
            OnlineMatchEvent event,
            Instant now
    ) {
        if (aggregate.phase() == MatchPhase.FINISHED) {
            matchRepository.logAction(action, event, true, now);
            matchRepository.deleteById(aggregate.id());
            return;
        }
        matchRepository.update(aggregate);
        matchRepository.logAction(action, event, true, now);
    }

    private SeatState requireSeatOwner(MatchAggregate aggregate, UUID actorId, boolean mustExist) {
        SeatState seat = aggregate.seatForGuest(actorId).orElse(null);
        if (mustExist && seat == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "seatOwnership", "The acting guest does not own a seat in this match.");
        }
        return seat;
    }

    private void validateActionEnvelope(Contracts.OnlineMatchAction action, MatchAggregate aggregate) {
        requireGuestId(action.actorID());
        if (action.id() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "missingActionId", "Every action must include a stable UUID.");
        }
        if (action.submittedAt() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "missingTimestamp", "Every action must include submittedAt.");
        }
        if (action.type() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "missingActionType", "Every action must include a type.");
        }
        if (aggregate.phase() == MatchPhase.WAITING_FOR_PLAYERS && action.type() != ActionType.JOIN_MATCH) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPhase", "Only joinMatch is valid while waiting for players.");
        }
    }

    private boolean markConnected(SeatState seat, Instant now) {
        boolean changed = seat.connectionStatus() != ConnectionStatus.CONNECTED;
        seat.connectionStatus(ConnectionStatus.CONNECTED);
        seat.lastConnectedAt(now);
        return changed;
    }

    private <T> T convertPayload(JsonNode payload, Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (Exception exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPayload", "The action payload could not be parsed.");
        }
    }

    private String uniqueRoomCode() {
        for (int attempt = 0; attempt < 20; attempt += 1) {
            String roomCode = roomCodeGenerator.generate();
            if (!matchRepository.roomCodeExists(roomCode)) {
                return roomCode;
            }
        }
        throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR, "roomCodeGenerationFailed", "Unable to generate a unique room code.");
    }

    private UUID requireGuestId(UUID guestId) {
        if (guestId == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "missingGuestId", "guestID is required.");
        }
        return guestId;
    }

    private String validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidDisplayName", "displayName is required.");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 24) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidDisplayName", "displayName must be 24 characters or fewer.");
        }
        return trimmed;
    }

    private String validateRoomCode(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidRoomCode", "roomCode is required.");
        }
        return roomCode.trim().toUpperCase();
    }

    private String validateSkillId(String skillId) {
        if (skillId == null || !ACTIVE_SKILLS.contains(skillId)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "unknownSkill", "The selected skill is not part of the active skill library.");
        }
        return skillId;
    }

    private void validateBoardPosition(Position position) {
        if (position.row() < 0 || position.row() > 7 || position.column() < 0 || position.column() > 7) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPosition", "Board positions must remain within the 8x8 board.");
        }
    }

    public record ServiceSnapshot(
            OnlineMatchSnapshot snapshot,
            OnlineMatchEvent broadcastEvent
    ) {
    }
}
