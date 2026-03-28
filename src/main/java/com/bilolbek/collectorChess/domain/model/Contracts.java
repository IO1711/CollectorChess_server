package com.bilolbek.collectorChess.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Contracts {

    private Contracts() {
    }

    public enum MatchPhase {
        WAITING_FOR_PLAYERS("waitingForPlayers"),
        DRAFTING("drafting"),
        ACTIVE("active"),
        FINISHED("finished");

        private final String wireValue;

        MatchPhase(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static MatchPhase fromWireValue(String value) {
            for (MatchPhase phase : values()) {
                if (phase.wireValue.equals(value)) {
                    return phase;
                }
            }
            throw new IllegalArgumentException("Unknown match phase: " + value);
        }
    }

    public enum PieceColor {
        WHITE("white"),
        BLACK("black");

        private final String wireValue;

        PieceColor(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static PieceColor fromWireValue(String value) {
            for (PieceColor color : values()) {
                if (color.wireValue.equals(value)) {
                    return color;
                }
            }
            throw new IllegalArgumentException("Unknown piece color: " + value);
        }

        public PieceColor opposite() {
            return this == WHITE ? BLACK : WHITE;
        }
    }

    public enum ConnectionStatus {
        INVITED("invited"),
        CONNECTED("connected"),
        DISCONNECTED("disconnected");

        private final String wireValue;

        ConnectionStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static ConnectionStatus fromWireValue(String value) {
            for (ConnectionStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown connection status: " + value);
        }
    }

    public enum GameStatusKind {
        ACTIVE("active"),
        CHECKMATE("checkmate"),
        STALEMATE("stalemate");

        private final String wireValue;

        GameStatusKind(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static GameStatusKind fromWireValue(String value) {
            for (GameStatusKind kind : values()) {
                if (kind.wireValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown game status kind: " + value);
        }
    }

    public enum OutcomeKind {
        CHECKMATE("checkmate"),
        STALEMATE("stalemate"),
        RESIGNATION("resignation"),
        ABORTED("aborted");

        private final String wireValue;

        OutcomeKind(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static OutcomeKind fromWireValue(String value) {
            for (OutcomeKind kind : values()) {
                if (kind.wireValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown outcome kind: " + value);
        }
    }

    public enum PieceName {
        KING("king"),
        QUEEN("queen"),
        ROOK("rook"),
        BISHOP("bishop"),
        KNIGHT("knight"),
        PAWN("pawn");

        private final String wireValue;

        PieceName(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static PieceName fromWireValue(String value) {
            for (PieceName pieceName : values()) {
                if (pieceName.wireValue.equals(value)) {
                    return pieceName;
                }
            }
            throw new IllegalArgumentException("Unknown piece name: " + value);
        }
    }

    public enum ActionType {
        JOIN_MATCH("joinMatch"),
        UPDATE_DRAFT_SELECTION("updateDraftSelection"),
        LOCK_DRAFT("lockDraft"),
        MAKE_MOVE("makeMove"),
        RESIGN("resign"),
        LEAVE_MATCH("leaveMatch");

        private final String wireValue;

        ActionType(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static ActionType fromWireValue(String value) {
            for (ActionType type : values()) {
                if (type.wireValue.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown action type: " + value);
        }
    }

    public enum EventType {
        SNAPSHOT("snapshot"),
        ACTION_ACCEPTED("actionAccepted"),
        ACTION_REJECTED("actionRejected"),
        PRESENCE_CHANGED("presenceChanged"),
        FINISHED("finished");

        private final String wireValue;

        EventType(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static EventType fromWireValue(String value) {
            for (EventType type : values()) {
                if (type.wireValue.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown event type: " + value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(
            int row,
            int column
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GuestPlayer(
            UUID id,
            String displayName,
            Instant createdAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineMatchSeat(
            PieceColor color,
            GuestPlayer player,
            boolean isHost,
            Boolean isLocalDevice,
            boolean isReady,
            ConnectionStatus connectionStatus
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineSkillDraftSelection(
            PieceColor color,
            String skillID,
            Position position,
            boolean isLockedIn
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineSkillDraftState(
            int maxSkillsPerPlayer,
            List<OnlineSkillDraftSelection> selections
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EquippedSkillSnapshot(
            UUID id,
            String skillID,
            boolean hasBeenUsed
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChessPieceSnapshot(
            UUID id,
            PieceName pieceName,
            PieceColor pieceColor,
            String behaviourID,
            Position position,
            int moveCount,
            List<EquippedSkillSnapshot> equippedSkills
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChessMoveRecordSnapshot(
            UUID id,
            UUID pieceID,
            PieceName pieceName,
            PieceColor pieceColor,
            Position from,
            Position to,
            UUID capturedPieceID,
            PieceName capturedPieceName,
            PieceName promotionPieceName,
            UUID equippedSkillID,
            String skillID,
            Instant createdAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChessBoardSnapshot(
            List<ChessPieceSnapshot> pieces,
            List<ChessMoveRecordSnapshot> moveHistory
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChessGameStatusSnapshot(
            GameStatusKind kind,
            PieceColor turn,
            Boolean inCheck,
            PieceColor winner
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineMatchOutcome(
            OutcomeKind kind,
            PieceColor winner,
            Instant finishedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineMatchSnapshot(
            UUID id,
            String roomCode,
            int revision,
            MatchPhase phase,
            List<OnlineMatchSeat> seats,
            OnlineSkillDraftState draft,
            ChessBoardSnapshot board,
            PieceColor currentTurn,
            ChessGameStatusSnapshot status,
            OnlineMatchOutcome outcome,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JoinableRoomSnapshot(
            UUID id,
            String roomCode,
            GuestPlayer host,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineMatchAction(
            UUID id,
            UUID matchID,
            UUID actorID,
            int baseRevision,
            ActionType type,
            Instant submittedAt,
            JsonNode payload
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlineMatchEvent(
            UUID id,
            UUID matchID,
            int revision,
            EventType type,
            UUID actionID,
            String rejectionReason,
            OnlineMatchSnapshot snapshot
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateDraftSelectionPayload(
            String skillID,
            Position position
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Move(
            Position from,
            Position to,
            PieceName promotionPieceName,
            UUID equippedSkillID
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MakeMovePayload(
            Move move
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JoinMatchPayload(
            String displayName
    ) {
    }
}
