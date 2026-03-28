package com.bilolbek.collectorChess.domain.model;

import com.bilolbek.collectorChess.domain.model.Contracts.ChessBoardSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.ChessGameStatusSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.ChessMoveRecordSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.ChessPieceSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.ConnectionStatus;
import com.bilolbek.collectorChess.domain.model.Contracts.EquippedSkillSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.GameStatusKind;
import com.bilolbek.collectorChess.domain.model.Contracts.GuestPlayer;
import com.bilolbek.collectorChess.domain.model.Contracts.MatchPhase;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchOutcome;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchSeat;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineSkillDraftSelection;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineSkillDraftState;
import com.bilolbek.collectorChess.domain.model.Contracts.OutcomeKind;
import com.bilolbek.collectorChess.domain.model.Contracts.PieceColor;
import com.bilolbek.collectorChess.domain.model.Contracts.PieceName;
import com.bilolbek.collectorChess.domain.model.Contracts.Position;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class MatchAggregate {

    public static final int MAX_SKILLS_PER_PLAYER = 2;

    private UUID id;
    private String roomCode;
    private int revision;
    private MatchPhase phase;
    private List<SeatState> seats;
    private List<DraftSelectionState> draftSelections;
    private List<PieceState> pieces;
    private List<MoveRecordState> moveHistory;
    private PieceColor currentTurn;
    private ChessGameStatusSnapshot status;
    private OnlineMatchOutcome outcome;
    private Instant createdAt;
    private Instant updatedAt;

    private MatchAggregate() {
    }

    public static MatchAggregate create(UUID hostGuestId, String displayName, String roomCode, Instant now) {
        MatchAggregate aggregate = new MatchAggregate();
        aggregate.id = UUID.randomUUID();
        aggregate.roomCode = roomCode;
        aggregate.revision = 1;
        aggregate.phase = MatchPhase.WAITING_FOR_PLAYERS;
        aggregate.seats = new ArrayList<>();
        aggregate.seats.add(SeatState.host(hostGuestId, displayName, now));
        aggregate.draftSelections = new ArrayList<>();
        aggregate.pieces = initialPieces();
        aggregate.moveHistory = new ArrayList<>();
        aggregate.currentTurn = PieceColor.WHITE;
        aggregate.status = new ChessGameStatusSnapshot(GameStatusKind.ACTIVE, PieceColor.WHITE, Boolean.FALSE, null);
        aggregate.outcome = null;
        aggregate.createdAt = now;
        aggregate.updatedAt = now;
        return aggregate;
    }

    public static MatchAggregate fromSnapshot(
            OnlineMatchSnapshot snapshot,
            Instant whiteLastConnectedAt,
            Instant whiteLastDisconnectedAt,
            Instant blackLastConnectedAt,
            Instant blackLastDisconnectedAt
    ) {
        MatchAggregate aggregate = new MatchAggregate();
        aggregate.id = snapshot.id();
        aggregate.roomCode = snapshot.roomCode();
        aggregate.revision = snapshot.revision();
        aggregate.phase = snapshot.phase();
        aggregate.currentTurn = snapshot.currentTurn();
        aggregate.status = snapshot.status();
        aggregate.outcome = snapshot.outcome();
        aggregate.createdAt = snapshot.createdAt();
        aggregate.updatedAt = snapshot.updatedAt();
        aggregate.seats = snapshot.seats().stream()
                .map(seat -> SeatState.fromSnapshot(
                        seat,
                        seat.color() == PieceColor.WHITE ? whiteLastConnectedAt : blackLastConnectedAt,
                        seat.color() == PieceColor.WHITE ? whiteLastDisconnectedAt : blackLastDisconnectedAt
                ))
                .collect(Collectors.toCollection(ArrayList::new));
        aggregate.draftSelections = snapshot.draft().selections().stream()
                .map(DraftSelectionState::fromSnapshot)
                .collect(Collectors.toCollection(ArrayList::new));
        aggregate.pieces = snapshot.board().pieces().stream()
                .map(PieceState::fromSnapshot)
                .collect(Collectors.toCollection(ArrayList::new));
        aggregate.moveHistory = snapshot.board().moveHistory().stream()
                .map(MoveRecordState::fromSnapshot)
                .collect(Collectors.toCollection(ArrayList::new));
        return aggregate;
    }

    public OnlineMatchSnapshot toSnapshot() {
        return new OnlineMatchSnapshot(
                id,
                roomCode,
                revision,
                phase,
                orderedSeats().stream().map(SeatState::toSnapshot).toList(),
                new OnlineSkillDraftState(
                        MAX_SKILLS_PER_PLAYER,
                        draftSelections.stream()
                                .sorted(Comparator.comparing((DraftSelectionState selection) -> selection.color.wireValue())
                                        .thenComparing(selection -> selection.skillId))
                                .map(DraftSelectionState::toSnapshot)
                                .toList()
                ),
                new ChessBoardSnapshot(
                        pieces.stream()
                                .sorted(Comparator.comparing((PieceState piece) -> piece.color.wireValue())
                                        .thenComparingInt(piece -> piece.position.row())
                                        .thenComparingInt(piece -> piece.position.column()))
                                .map(PieceState::toSnapshot)
                                .toList(),
                        moveHistory.stream().map(MoveRecordState::toSnapshot).toList()
                ),
                currentTurn,
                status,
                outcome,
                createdAt,
                updatedAt
        );
    }

    public UUID id() {
        return id;
    }

    public String roomCode() {
        return roomCode;
    }

    public int revision() {
        return revision;
    }

    public MatchPhase phase() {
        return phase;
    }

    public void phase(MatchPhase phase) {
        this.phase = phase;
    }

    public List<SeatState> seats() {
        return seats;
    }

    public List<DraftSelectionState> draftSelections() {
        return draftSelections;
    }

    public List<PieceState> pieces() {
        return pieces;
    }

    public List<MoveRecordState> moveHistory() {
        return moveHistory;
    }

    public PieceColor currentTurn() {
        return currentTurn;
    }

    public void currentTurn(PieceColor currentTurn) {
        this.currentTurn = currentTurn;
    }

    public ChessGameStatusSnapshot status() {
        return status;
    }

    public void status(ChessGameStatusSnapshot status) {
        this.status = status;
    }

    public OnlineMatchOutcome outcome() {
        return outcome;
    }

    public void outcome(OnlineMatchOutcome outcome) {
        this.outcome = outcome;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void touch(Instant now) {
        updatedAt = now;
    }

    public void incrementRevision() {
        revision += 1;
    }

    public Optional<SeatState> seatForColor(PieceColor color) {
        return seats.stream().filter(seat -> seat.color == color).findFirst();
    }

    public Optional<SeatState> seatForGuest(UUID guestId) {
        return seats.stream()
                .filter(seat -> Objects.equals(seat.guestId, guestId))
                .findFirst();
    }

    public Optional<PieceState> pieceAt(Position position) {
        return pieces.stream()
                .filter(piece -> piece.position.equals(position))
                .findFirst();
    }

    public Optional<PieceState> pieceById(UUID pieceId) {
        return pieces.stream().filter(piece -> piece.id.equals(pieceId)).findFirst();
    }

    public void addJoiner(UUID guestId, String displayName, Instant now) {
        seats.add(SeatState.joiner(guestId, displayName, now));
        phase = MatchPhase.DRAFTING;
        touch(now);
    }

    public List<SeatState> orderedSeats() {
        return seats.stream()
                .sorted(Comparator.comparing((SeatState seat) -> seat.color.wireValue()))
                .toList();
    }

    public void recordMove(MoveRecordState record) {
        moveHistory.add(record);
    }

    public void removePiece(UUID pieceId) {
        pieces.removeIf(piece -> piece.id.equals(pieceId));
    }

    public void setDraftSelection(PieceColor color, String skillId, Position position) {
        if (position == null) {
            draftSelections.removeIf(selection -> selection.color == color && selection.skillId.equals(skillId));
            return;
        }
        DraftSelectionState existing = draftSelections.stream()
                .filter(selection -> selection.color == color && selection.skillId.equals(skillId))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            draftSelections.add(new DraftSelectionState(color, skillId, position, false));
            return;
        }
        existing.position = position;
    }

    public void lockDraft(PieceColor color) {
        seatForColor(color).ifPresent(seat -> seat.ready = true);
        draftSelections.stream()
                .filter(selection -> selection.color == color)
                .forEach(selection -> selection.lockedIn = true);
    }

    public boolean bothSeatsReady() {
        return orderedSeats().size() == 2 && orderedSeats().stream().allMatch(seat -> seat.ready);
    }

    public void applyDraftSelectionsToPieces() {
        for (DraftSelectionState selection : draftSelections) {
            if (selection.position == null) {
                continue;
            }
            pieceAt(selection.position)
                    .filter(piece -> piece.color == selection.color)
                    .ifPresent(piece -> piece.equippedSkills.add(new EquippedSkillState(UUID.randomUUID(), selection.skillId, false)));
        }
    }

    public void clearOutcome() {
        outcome = null;
    }

    private static List<PieceState> initialPieces() {
        List<PieceState> pieces = new ArrayList<>();
        addBackRank(pieces, PieceColor.WHITE, 0);
        addPawns(pieces, PieceColor.WHITE, 1);
        addPawns(pieces, PieceColor.BLACK, 6);
        addBackRank(pieces, PieceColor.BLACK, 7);
        return pieces;
    }

    private static void addBackRank(List<PieceState> pieces, PieceColor color, int row) {
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.ROOK, color, behaviourId(PieceName.ROOK), new Position(row, 0), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.KNIGHT, color, behaviourId(PieceName.KNIGHT), new Position(row, 1), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.BISHOP, color, behaviourId(PieceName.BISHOP), new Position(row, 2), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.QUEEN, color, behaviourId(PieceName.QUEEN), new Position(row, 3), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.KING, color, behaviourId(PieceName.KING), new Position(row, 4), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.BISHOP, color, behaviourId(PieceName.BISHOP), new Position(row, 5), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.KNIGHT, color, behaviourId(PieceName.KNIGHT), new Position(row, 6), 0, new ArrayList<>()));
        pieces.add(new PieceState(UUID.randomUUID(), PieceName.ROOK, color, behaviourId(PieceName.ROOK), new Position(row, 7), 0, new ArrayList<>()));
    }

    private static void addPawns(List<PieceState> pieces, PieceColor color, int row) {
        for (int column = 0; column < 8; column += 1) {
            pieces.add(new PieceState(UUID.randomUUID(), PieceName.PAWN, color, behaviourId(PieceName.PAWN), new Position(row, column), 0, new ArrayList<>()));
        }
    }

    public static String behaviourId(PieceName pieceName) {
        return switch (pieceName) {
            case KING -> "standardKing";
            case QUEEN -> "standardQueen";
            case ROOK -> "standardRook";
            case BISHOP -> "standardBishop";
            case KNIGHT -> "standardKnight";
            case PAWN -> "standardPawn";
        };
    }

    public static final class SeatState {
        private PieceColor color;
        private UUID guestId;
        private String displayName;
        private Instant playerCreatedAt;
        private boolean host;
        private boolean ready;
        private ConnectionStatus connectionStatus;
        private Instant lastConnectedAt;
        private Instant lastDisconnectedAt;

        private SeatState() {
        }

        static SeatState host(UUID guestId, String displayName, Instant now) {
            SeatState state = new SeatState();
            state.color = PieceColor.WHITE;
            state.guestId = guestId;
            state.displayName = displayName;
            state.playerCreatedAt = now;
            state.host = true;
            state.ready = false;
            state.connectionStatus = ConnectionStatus.CONNECTED;
            state.lastConnectedAt = now;
            return state;
        }

        static SeatState joiner(UUID guestId, String displayName, Instant now) {
            SeatState state = new SeatState();
            state.color = PieceColor.BLACK;
            state.guestId = guestId;
            state.displayName = displayName;
            state.playerCreatedAt = now;
            state.host = false;
            state.ready = false;
            state.connectionStatus = ConnectionStatus.CONNECTED;
            state.lastConnectedAt = now;
            return state;
        }

        static SeatState fromSnapshot(OnlineMatchSeat seat, Instant lastConnectedAt, Instant lastDisconnectedAt) {
            SeatState state = new SeatState();
            state.color = seat.color();
            state.guestId = seat.player().id();
            state.displayName = seat.player().displayName();
            state.playerCreatedAt = seat.player().createdAt();
            state.host = seat.isHost();
            state.ready = seat.isReady();
            state.connectionStatus = seat.connectionStatus();
            state.lastConnectedAt = lastConnectedAt;
            state.lastDisconnectedAt = lastDisconnectedAt;
            return state;
        }

        public OnlineMatchSeat toSnapshot() {
            return new OnlineMatchSeat(
                    color,
                    new GuestPlayer(guestId, displayName, playerCreatedAt),
                    host,
                    null,
                    ready,
                    connectionStatus
            );
        }

        public PieceColor color() {
            return color;
        }

        public UUID guestId() {
            return guestId;
        }

        public String displayName() {
            return displayName;
        }

        public Instant playerCreatedAt() {
            return playerCreatedAt;
        }

        public boolean ready() {
            return ready;
        }

        public ConnectionStatus connectionStatus() {
            return connectionStatus;
        }

        public void connectionStatus(ConnectionStatus connectionStatus) {
            this.connectionStatus = connectionStatus;
        }

        public void ready(boolean ready) {
            this.ready = ready;
        }

        public Instant lastConnectedAt() {
            return lastConnectedAt;
        }

        public void lastConnectedAt(Instant lastConnectedAt) {
            this.lastConnectedAt = lastConnectedAt;
        }

        public Instant lastDisconnectedAt() {
            return lastDisconnectedAt;
        }

        public void lastDisconnectedAt(Instant lastDisconnectedAt) {
            this.lastDisconnectedAt = lastDisconnectedAt;
        }
    }

    public static final class DraftSelectionState {
        private PieceColor color;
        private String skillId;
        private Position position;
        private boolean lockedIn;

        DraftSelectionState(PieceColor color, String skillId, Position position, boolean lockedIn) {
            this.color = color;
            this.skillId = skillId;
            this.position = position;
            this.lockedIn = lockedIn;
        }

        static DraftSelectionState fromSnapshot(OnlineSkillDraftSelection selection) {
            return new DraftSelectionState(selection.color(), selection.skillID(), selection.position(), selection.isLockedIn());
        }

        public OnlineSkillDraftSelection toSnapshot() {
            return new OnlineSkillDraftSelection(color, skillId, position, lockedIn);
        }

        public PieceColor color() {
            return color;
        }

        public String skillId() {
            return skillId;
        }

        public Position position() {
            return position;
        }

        public boolean lockedIn() {
            return lockedIn;
        }
    }

    public static final class PieceState {
        private UUID id;
        private PieceName pieceName;
        private PieceColor color;
        private String behaviourId;
        private Position position;
        private int moveCount;
        private List<EquippedSkillState> equippedSkills;

        PieceState(UUID id, PieceName pieceName, PieceColor color, String behaviourId, Position position, int moveCount, List<EquippedSkillState> equippedSkills) {
            this.id = id;
            this.pieceName = pieceName;
            this.color = color;
            this.behaviourId = behaviourId;
            this.position = position;
            this.moveCount = moveCount;
            this.equippedSkills = equippedSkills;
        }

        static PieceState fromSnapshot(ChessPieceSnapshot snapshot) {
            return new PieceState(
                    snapshot.id(),
                    snapshot.pieceName(),
                    snapshot.pieceColor(),
                    snapshot.behaviourID(),
                    snapshot.position(),
                    snapshot.moveCount(),
                    snapshot.equippedSkills().stream()
                            .map(skill -> new EquippedSkillState(skill.id(), skill.skillID(), skill.hasBeenUsed()))
                            .collect(Collectors.toCollection(ArrayList::new))
            );
        }

        public ChessPieceSnapshot toSnapshot() {
            return new ChessPieceSnapshot(
                    id,
                    pieceName,
                    color,
                    behaviourId,
                    position,
                    moveCount,
                    equippedSkills.stream().map(EquippedSkillState::toSnapshot).toList()
            );
        }

        public PieceState copy() {
            return new PieceState(
                    id,
                    pieceName,
                    color,
                    behaviourId,
                    new Position(position.row(), position.column()),
                    moveCount,
                    equippedSkills.stream().map(EquippedSkillState::copy).collect(Collectors.toCollection(ArrayList::new))
            );
        }

        public UUID id() {
            return id;
        }

        public PieceName pieceName() {
            return pieceName;
        }

        public void pieceName(PieceName pieceName) {
            this.pieceName = pieceName;
        }

        public PieceColor color() {
            return color;
        }

        public String behaviourId() {
            return behaviourId;
        }

        public void behaviourId(String behaviourId) {
            this.behaviourId = behaviourId;
        }

        public Position position() {
            return position;
        }

        public void position(Position position) {
            this.position = position;
        }

        public int moveCount() {
            return moveCount;
        }

        public void incrementMoveCount() {
            moveCount += 1;
        }

        public List<EquippedSkillState> equippedSkills() {
            return equippedSkills;
        }
    }

    public static final class EquippedSkillState {
        private UUID id;
        private String skillId;
        private boolean hasBeenUsed;

        EquippedSkillState(UUID id, String skillId, boolean hasBeenUsed) {
            this.id = id;
            this.skillId = skillId;
            this.hasBeenUsed = hasBeenUsed;
        }

        public EquippedSkillSnapshot toSnapshot() {
            return new EquippedSkillSnapshot(id, skillId, hasBeenUsed);
        }

        public EquippedSkillState copy() {
            return new EquippedSkillState(id, skillId, hasBeenUsed);
        }

        public UUID id() {
            return id;
        }

        public String skillId() {
            return skillId;
        }

        public boolean hasBeenUsed() {
            return hasBeenUsed;
        }

        public void hasBeenUsed(boolean hasBeenUsed) {
            this.hasBeenUsed = hasBeenUsed;
        }
    }

    public static final class MoveRecordState {
        private UUID id;
        private UUID pieceId;
        private PieceName pieceName;
        private PieceColor pieceColor;
        private Position from;
        private Position to;
        private UUID capturedPieceId;
        private PieceName capturedPieceName;
        private PieceName promotionPieceName;
        private UUID equippedSkillId;
        private String skillId;
        private Instant createdAt;

        public MoveRecordState(
                UUID id,
                UUID pieceId,
                PieceName pieceName,
                PieceColor pieceColor,
                Position from,
                Position to,
                UUID capturedPieceId,
                PieceName capturedPieceName,
                PieceName promotionPieceName,
                UUID equippedSkillId,
                String skillId,
                Instant createdAt
        ) {
            this.id = id;
            this.pieceId = pieceId;
            this.pieceName = pieceName;
            this.pieceColor = pieceColor;
            this.from = from;
            this.to = to;
            this.capturedPieceId = capturedPieceId;
            this.capturedPieceName = capturedPieceName;
            this.promotionPieceName = promotionPieceName;
            this.equippedSkillId = equippedSkillId;
            this.skillId = skillId;
            this.createdAt = createdAt;
        }

        static MoveRecordState fromSnapshot(ChessMoveRecordSnapshot snapshot) {
            return new MoveRecordState(
                    snapshot.id(),
                    snapshot.pieceID(),
                    snapshot.pieceName(),
                    snapshot.pieceColor(),
                    snapshot.from(),
                    snapshot.to(),
                    snapshot.capturedPieceID(),
                    snapshot.capturedPieceName(),
                    snapshot.promotionPieceName(),
                    snapshot.equippedSkillID(),
                    snapshot.skillID(),
                    snapshot.createdAt()
            );
        }

        public ChessMoveRecordSnapshot toSnapshot() {
            return new ChessMoveRecordSnapshot(
                    id,
                    pieceId,
                    pieceName,
                    pieceColor,
                    from,
                    to,
                    capturedPieceId,
                    capturedPieceName,
                    promotionPieceName,
                    equippedSkillId,
                    skillId,
                    createdAt
            );
        }

        public UUID pieceId() {
            return pieceId;
        }

        public PieceName pieceName() {
            return pieceName;
        }

        public PieceColor pieceColor() {
            return pieceColor;
        }

        public Position from() {
            return from;
        }

        public Position to() {
            return to;
        }
    }
}
