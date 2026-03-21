package com.bilolbek.collectorChess.domain.rules;

import com.bilolbek.collectorChess.domain.model.Contracts.ChessGameStatusSnapshot;
import com.bilolbek.collectorChess.domain.model.Contracts.GameStatusKind;
import com.bilolbek.collectorChess.domain.model.Contracts.MatchPhase;
import com.bilolbek.collectorChess.domain.model.Contracts.Move;
import com.bilolbek.collectorChess.domain.model.Contracts.OnlineMatchOutcome;
import com.bilolbek.collectorChess.domain.model.Contracts.OutcomeKind;
import com.bilolbek.collectorChess.domain.model.Contracts.PieceColor;
import com.bilolbek.collectorChess.domain.model.Contracts.PieceName;
import com.bilolbek.collectorChess.domain.model.Contracts.Position;
import com.bilolbek.collectorChess.domain.model.MatchAggregate;
import com.bilolbek.collectorChess.domain.model.MatchAggregate.EquippedSkillState;
import com.bilolbek.collectorChess.domain.model.MatchAggregate.MoveRecordState;
import com.bilolbek.collectorChess.domain.model.MatchAggregate.PieceState;
import com.bilolbek.collectorChess.domain.service.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChessRules {

    public void applyMove(MatchAggregate match, PieceColor actorColor, Move move, Instant now) {
        if (match.phase() != MatchPhase.ACTIVE) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPhase", "Moves are only valid during the active phase.");
        }
        if (match.currentTurn() != actorColor) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "notYourTurn", "It is not the actor's turn.");
        }
        validateBoardPosition(move.from(), "from");
        validateBoardPosition(move.to(), "to");

        BoardState board = BoardState.from(match);
        PieceState movingPiece = board.pieceAt(move.from())
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "pieceNotFound", "No piece exists at the requested origin square."));
        if (movingPiece.color() != actorColor) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "wrongSeat", "The acting player does not own that piece.");
        }

        MovePlan movePlan = move.equippedSkillID() == null
                ? analyzeStandardMove(board, movingPiece, move)
                : analyzeSkillMove(board, movingPiece, move);

        BoardState simulated = board.copy();
        applyPlan(simulated, movePlan, now);
        if (isKingInCheck(simulated, actorColor)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "selfCheck", "The move would leave the acting king in check.");
        }

        applyPlan(match, movePlan, now);
        updateGameStatus(match, actorColor, now);
    }

    public boolean hasAnyLegalMove(MatchAggregate match, PieceColor color) {
        BoardState board = BoardState.from(match);
        for (PieceState piece : board.pieces()) {
            if (piece.color() != color) {
                continue;
            }
            for (int row = 0; row < 8; row += 1) {
                for (int column = 0; column < 8; column += 1) {
                    Position target = new Position(row, column);
                    Move standardMove = new Move(piece.position(), target, promotionChoiceFor(piece, target), null);
                    if (isLegalMove(board, piece, standardMove, color)) {
                        return true;
                    }
                    for (EquippedSkillState skill : piece.equippedSkills()) {
                        if (skill.hasBeenUsed()) {
                            continue;
                        }
                        Move skillMove = new Move(piece.position(), target, null, skill.id());
                        if (isLegalMove(board, piece, skillMove, color)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean isKingInCheck(MatchAggregate match, PieceColor color) {
        return isKingInCheck(BoardState.from(match), color);
    }

    private boolean isLegalMove(BoardState board, PieceState piece, Move move, PieceColor color) {
        try {
            MovePlan plan = move.equippedSkillID() == null
                    ? analyzeStandardMove(board, piece, move)
                    : analyzeSkillMove(board, piece, move);
            BoardState simulated = board.copy();
            applyPlan(simulated, plan, Instant.now());
            return !isKingInCheck(simulated, color);
        } catch (DomainException ignored) {
            return false;
        }
    }

    private MovePlan analyzeStandardMove(BoardState board, PieceState piece, Move move) {
        if (piece.position().equals(move.to())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "A piece must move to a different square.");
        }
        PieceState target = board.pieceAt(move.to()).orElse(null);
        if (target != null && target.color() == piece.color()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "occupiedByAlly", "A piece cannot move onto an allied piece.");
        }

        return switch (piece.pieceName()) {
            case PAWN -> analyzePawnMove(board, piece, move, target);
            case KNIGHT -> analyzeKnightMove(piece, move, target);
            case BISHOP -> analyzeSlidingMove(board, piece, move, target, true, false);
            case ROOK -> analyzeSlidingMove(board, piece, move, target, false, true);
            case QUEEN -> analyzeSlidingMove(board, piece, move, target, true, true);
            case KING -> analyzeKingMove(board, piece, move, target);
        };
    }

    private MovePlan analyzeSkillMove(BoardState board, PieceState piece, Move move) {
        PieceState target = board.pieceAt(move.to())
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "invalidSkillMove", "Skill moves must target an enemy piece."));
        if (target.color() == piece.color()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidSkillMove", "Skill moves must target an enemy piece.");
        }

        EquippedSkillState equippedSkill = piece.equippedSkills().stream()
                .filter(skill -> Objects.equals(skill.id(), move.equippedSkillID()))
                .findFirst()
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "skillNotEquipped", "The piece does not own the selected equipped skill."));
        if (equippedSkill.hasBeenUsed()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "skillAlreadyUsed", "The selected skill has already been consumed.");
        }

        int rowDelta = move.to().row() - piece.position().row();
        int columnDelta = move.to().column() - piece.position().column();
        String skillId = equippedSkill.skillId();
        switch (skillId) {
            case "shadowstep" -> {
                if (Math.max(Math.abs(rowDelta), Math.abs(columnDelta)) != 1) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "invalidSkillMove", "shadowstep only captures adjacent enemy pieces.");
                }
            }
            case "knightfall" -> {
                if (!((Math.abs(rowDelta) == 2 && Math.abs(columnDelta) == 1) || (Math.abs(rowDelta) == 1 && Math.abs(columnDelta) == 2))) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "invalidSkillMove", "knightfall only captures in a knight pattern.");
                }
            }
            case "deadeye" -> {
                if (!(isStraightMove(rowDelta, columnDelta) || isDiagonalMove(rowDelta, columnDelta))) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "invalidSkillMove", "deadeye only captures in straight or diagonal lines.");
                }
                ensurePathClear(board, piece.position(), move.to());
            }
            default -> throw new DomainException(HttpStatus.BAD_REQUEST, "unknownSkill", "The selected skill is not part of the active skill catalog.");
        }

        return new MovePlan(
                piece.id(),
                piece.pieceName(),
                piece.position(),
                move.to(),
                target.id(),
                target.pieceName(),
                move.promotionPieceName(),
                equippedSkill.id(),
                skillId,
                null,
                null,
                move.to()
        );
    }

    private MovePlan analyzePawnMove(BoardState board, PieceState piece, Move move, PieceState target) {
        int direction = piece.color() == PieceColor.WHITE ? 1 : -1;
        int startingRow = piece.color() == PieceColor.WHITE ? 1 : 6;
        int promotionRow = piece.color() == PieceColor.WHITE ? 7 : 0;
        int rowDelta = move.to().row() - piece.position().row();
        int columnDelta = move.to().column() - piece.position().column();
        Position enPassantCaptureSquare = null;
        UUID capturedPieceId = null;
        PieceName capturedPieceName = null;

        if (columnDelta == 0) {
            if (target != null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Pawns cannot move forward into occupied squares.");
            }
            if (rowDelta == direction) {
                // valid single step
            } else if (rowDelta == 2 * direction && piece.position().row() == startingRow) {
                Position intermediate = new Position(piece.position().row() + direction, piece.position().column());
                if (board.pieceAt(intermediate).isPresent()) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Pawns may not jump over occupied squares.");
                }
            } else {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn movement.");
            }
        } else if (Math.abs(columnDelta) == 1 && rowDelta == direction) {
            if (target == null) {
                MoveRecordState lastMove = board.lastMove().orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn capture."));
                if (lastMove.pieceName() != PieceName.PAWN || lastMove.pieceColor() == piece.color()) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn capture.");
                }
                if (Math.abs(lastMove.to().row() - lastMove.from().row()) != 2) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn capture.");
                }
                if (lastMove.to().row() != piece.position().row() || lastMove.to().column() != move.to().column()) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn capture.");
                }
                PieceState captured = board.pieceAt(lastMove.to())
                        .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn capture."));
                enPassantCaptureSquare = captured.position();
                capturedPieceId = captured.id();
                capturedPieceName = captured.pieceName();
            } else {
                capturedPieceId = target.id();
                capturedPieceName = target.pieceName();
            }
        } else {
            throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal pawn movement.");
        }

        PieceName promotion = move.promotionPieceName();
        if (move.to().row() == promotionRow) {
            if (promotion == null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "promotionRequired", "Promotion moves must specify a promotion piece.");
            }
            if (!(promotion == PieceName.QUEEN || promotion == PieceName.ROOK || promotion == PieceName.BISHOP || promotion == PieceName.KNIGHT)) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPromotion", "Promotion choices are limited to queen, rook, bishop, and knight.");
            }
        } else if (promotion != null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPromotion", "Promotion pieces are only valid on a promotion move.");
        }

        Position captureSquare = enPassantCaptureSquare == null ? move.to() : enPassantCaptureSquare;
        return new MovePlan(piece.id(), piece.pieceName(), piece.position(), move.to(), capturedPieceId, capturedPieceName, promotion, null, null, null, null, captureSquare);
    }

    private MovePlan analyzeKnightMove(PieceState piece, Move move, PieceState target) {
        int rowDelta = Math.abs(move.to().row() - piece.position().row());
        int columnDelta = Math.abs(move.to().column() - piece.position().column());
        if (!((rowDelta == 2 && columnDelta == 1) || (rowDelta == 1 && columnDelta == 2))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal knight movement.");
        }
        return standardPlan(piece, move, target);
    }

    private MovePlan analyzeSlidingMove(BoardState board, PieceState piece, Move move, PieceState target, boolean diagonalAllowed, boolean straightAllowed) {
        int rowDelta = move.to().row() - piece.position().row();
        int columnDelta = move.to().column() - piece.position().column();
        boolean diagonal = isDiagonalMove(rowDelta, columnDelta);
        boolean straight = isStraightMove(rowDelta, columnDelta);
        if (!(diagonalAllowed && diagonal) && !(straightAllowed && straight)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal sliding move.");
        }
        ensurePathClear(board, piece.position(), move.to());
        return standardPlan(piece, move, target);
    }

    private MovePlan analyzeKingMove(BoardState board, PieceState piece, Move move, PieceState target) {
        int rowDelta = move.to().row() - piece.position().row();
        int columnDelta = move.to().column() - piece.position().column();
        if (rowDelta == 0 && Math.abs(columnDelta) == 2) {
            if (piece.moveCount() > 0) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Kings that have moved cannot castle.");
            }
            if (target != null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Castling destination must be empty.");
            }
            int rookColumn = columnDelta > 0 ? 7 : 0;
            PieceState rook = board.pieceAt(new Position(piece.position().row(), rookColumn))
                    .filter(candidate -> candidate.pieceName() == PieceName.ROOK && candidate.color() == piece.color())
                    .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Castling rook is unavailable."));
            if (rook.moveCount() > 0) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Rooks that have moved cannot castle.");
            }
            ensurePathClear(board, piece.position(), rook.position());
            if (isKingInCheck(board, piece.color())) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Castling is not allowed while in check.");
            }
            int step = columnDelta > 0 ? 1 : -1;
            Position intermediate = new Position(piece.position().row(), piece.position().column() + step);
            BoardState intermediateBoard = board.copy();
            MovePlan intermediatePlan = new MovePlan(piece.id(), piece.pieceName(), piece.position(), intermediate, null, null, null, null, null, null, null, null);
            applyPlan(intermediateBoard, intermediatePlan, Instant.now());
            if (isKingInCheck(intermediateBoard, piece.color())) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Castling cannot pass through check.");
            }
            Position rookDestination = new Position(piece.position().row(), move.to().column() - step);
            return new MovePlan(piece.id(), piece.pieceName(), piece.position(), move.to(), null, null, null, null, null, rook.id(), rookDestination, null);
        }
        if (Math.max(Math.abs(rowDelta), Math.abs(columnDelta)) != 1) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Illegal king movement.");
        }
        return standardPlan(piece, move, target);
    }

    private MovePlan standardPlan(PieceState piece, Move move, PieceState target) {
        return new MovePlan(
                piece.id(),
                piece.pieceName(),
                piece.position(),
                move.to(),
                target == null ? null : target.id(),
                target == null ? null : target.pieceName(),
                move.promotionPieceName(),
                move.equippedSkillID(),
                null,
                null,
                null,
                move.to()
        );
    }

    private void updateGameStatus(MatchAggregate match, PieceColor actingColor, Instant now) {
        PieceColor opponent = actingColor.opposite();
        boolean opponentInCheck = isKingInCheck(match, opponent);
        boolean opponentHasMove = hasAnyLegalMove(match, opponent);
        match.currentTurn(opponent);
        if (!opponentHasMove && opponentInCheck) {
            match.phase(MatchPhase.FINISHED);
            match.status(new ChessGameStatusSnapshot(GameStatusKind.CHECKMATE, null, Boolean.TRUE, actingColor));
            match.outcome(new OnlineMatchOutcome(OutcomeKind.CHECKMATE, actingColor, now));
            return;
        }
        if (!opponentHasMove) {
            match.phase(MatchPhase.FINISHED);
            match.status(new ChessGameStatusSnapshot(GameStatusKind.STALEMATE, null, Boolean.FALSE, null));
            match.outcome(new OnlineMatchOutcome(OutcomeKind.STALEMATE, null, now));
            return;
        }
        match.status(new ChessGameStatusSnapshot(GameStatusKind.ACTIVE, opponent, opponentInCheck, null));
        match.outcome(null);
    }

    private boolean isKingInCheck(BoardState board, PieceColor color) {
        PieceState king = board.pieces().stream()
                .filter(piece -> piece.pieceName() == PieceName.KING && piece.color() == color)
                .findFirst()
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "missingKing", "The board no longer contains the acting king."));

        for (PieceState piece : board.pieces()) {
            if (piece.color() == color) {
                continue;
            }
            if (attacksSquare(board, piece, king.position())) {
                return true;
            }
        }
        return false;
    }

    private boolean attacksSquare(BoardState board, PieceState piece, Position target) {
        int rowDelta = target.row() - piece.position().row();
        int columnDelta = target.column() - piece.position().column();
        return switch (piece.pieceName()) {
            case PAWN -> piece.color() == PieceColor.WHITE
                    ? rowDelta == 1 && Math.abs(columnDelta) == 1
                    : rowDelta == -1 && Math.abs(columnDelta) == 1;
            case KNIGHT -> (Math.abs(rowDelta) == 2 && Math.abs(columnDelta) == 1) || (Math.abs(rowDelta) == 1 && Math.abs(columnDelta) == 2);
            case BISHOP -> isDiagonalMove(rowDelta, columnDelta) && pathClear(board, piece.position(), target);
            case ROOK -> isStraightMove(rowDelta, columnDelta) && pathClear(board, piece.position(), target);
            case QUEEN -> (isDiagonalMove(rowDelta, columnDelta) || isStraightMove(rowDelta, columnDelta)) && pathClear(board, piece.position(), target);
            case KING -> Math.max(Math.abs(rowDelta), Math.abs(columnDelta)) == 1;
        };
    }

    private void applyPlan(BoardState board, MovePlan movePlan, Instant now) {
        PieceState movingPiece = board.pieceById(movePlan.pieceId())
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "pieceNotFound", "Unable to resolve moving piece."));
        PieceState capturedPiece = movePlan.captureSquare() == null
                ? board.pieceAt(movePlan.to()).orElse(null)
                : board.pieceAt(movePlan.captureSquare()).orElse(null);

        if (capturedPiece != null) {
            if (capturedPiece.pieceName() == PieceName.KING) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Kings may not be captured.");
            }
            board.removePiece(capturedPiece.id());
        }

        movingPiece.position(movePlan.to());
        movingPiece.incrementMoveCount();
        if (movePlan.promotionPieceName() != null) {
            movingPiece.pieceName(movePlan.promotionPieceName());
            movingPiece.behaviourId(MatchAggregate.behaviourId(movePlan.promotionPieceName()));
        }

        if (movePlan.rookId() != null) {
            PieceState rook = board.pieceById(movePlan.rookId())
                    .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Unable to resolve the castling rook."));
            rook.position(movePlan.rookDestination());
            rook.incrementMoveCount();
        }

        if (movePlan.equippedSkillId() != null) {
            movingPiece.equippedSkills().stream()
                    .filter(skill -> Objects.equals(skill.id(), movePlan.equippedSkillId()))
                    .findFirst()
                    .ifPresent(skill -> skill.hasBeenUsed(true));
        }

        board.moveHistory().add(new MoveRecordState(
                UUID.randomUUID(),
                movingPiece.id(),
                movePlan.resultPieceName(),
                movingPiece.color(),
                movePlan.from(),
                movePlan.to(),
                movePlan.capturedPieceId(),
                movePlan.capturedPieceName(),
                movePlan.promotionPieceName(),
                movePlan.equippedSkillId(),
                movePlan.skillId(),
                now
        ));
    }

    private void applyPlan(MatchAggregate match, MovePlan movePlan, Instant now) {
        PieceState movingPiece = match.pieceById(movePlan.pieceId())
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "pieceNotFound", "Unable to resolve moving piece."));
        PieceState capturedPiece = movePlan.captureSquare() == null
                ? match.pieceAt(movePlan.to()).orElse(null)
                : match.pieceAt(movePlan.captureSquare()).orElse(null);

        if (capturedPiece != null) {
            if (capturedPiece.pieceName() == PieceName.KING) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Kings may not be captured.");
            }
            match.removePiece(capturedPiece.id());
        }

        movingPiece.position(movePlan.to());
        movingPiece.incrementMoveCount();
        if (movePlan.promotionPieceName() != null) {
            movingPiece.pieceName(movePlan.promotionPieceName());
            movingPiece.behaviourId(MatchAggregate.behaviourId(movePlan.promotionPieceName()));
        }

        if (movePlan.rookId() != null) {
            PieceState rook = match.pieceById(movePlan.rookId())
                    .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "illegalMove", "Unable to resolve the castling rook."));
            rook.position(movePlan.rookDestination());
            rook.incrementMoveCount();
        }

        if (movePlan.equippedSkillId() != null) {
            movingPiece.equippedSkills().stream()
                    .filter(skill -> Objects.equals(skill.id(), movePlan.equippedSkillId()))
                    .findFirst()
                    .ifPresent(skill -> skill.hasBeenUsed(true));
        }

        match.recordMove(new MoveRecordState(
                UUID.randomUUID(),
                movingPiece.id(),
                movePlan.resultPieceName(),
                movingPiece.color(),
                movePlan.from(),
                movePlan.to(),
                movePlan.capturedPieceId(),
                movePlan.capturedPieceName(),
                movePlan.promotionPieceName(),
                movePlan.equippedSkillId(),
                movePlan.skillId(),
                now
        ));
    }

    private PieceName promotionChoiceFor(PieceState piece, Position target) {
        if (piece.pieceName() != PieceName.PAWN) {
            return null;
        }
        if (piece.color() == PieceColor.WHITE && target.row() == 7) {
            return PieceName.QUEEN;
        }
        if (piece.color() == PieceColor.BLACK && target.row() == 0) {
            return PieceName.QUEEN;
        }
        return null;
    }

    private static boolean isDiagonalMove(int rowDelta, int columnDelta) {
        return Math.abs(rowDelta) == Math.abs(columnDelta) && rowDelta != 0;
    }

    private static boolean isStraightMove(int rowDelta, int columnDelta) {
        return (rowDelta == 0 && columnDelta != 0) || (columnDelta == 0 && rowDelta != 0);
    }

    private void ensurePathClear(BoardState board, Position from, Position to) {
        if (!pathClear(board, from, to)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "blockedPath", "The path to the destination square is blocked.");
        }
    }

    private boolean pathClear(BoardState board, Position from, Position to) {
        int rowStep = Integer.compare(to.row(), from.row());
        int columnStep = Integer.compare(to.column(), from.column());
        int row = from.row() + rowStep;
        int column = from.column() + columnStep;
        while (row != to.row() || column != to.column()) {
            if (board.pieceAt(new Position(row, column)).isPresent()) {
                return false;
            }
            row += rowStep;
            column += columnStep;
        }
        return true;
    }

    private void validateBoardPosition(Position position, String fieldName) {
        if (position == null || position.row() < 0 || position.row() > 7 || position.column() < 0 || position.column() > 7) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "invalidPosition", "The " + fieldName + " square is outside the board.");
        }
    }

    private record MovePlan(
            UUID pieceId,
            PieceName resultPieceName,
            Position from,
            Position to,
            UUID capturedPieceId,
            PieceName capturedPieceName,
            PieceName promotionPieceName,
            UUID equippedSkillId,
            String skillId,
            UUID rookId,
            Position rookDestination,
            Position captureSquare
    ) {
    }

    private static final class BoardState {
        private final List<PieceState> pieces;
        private final List<MoveRecordState> moveHistory;

        private BoardState(List<PieceState> pieces, List<MoveRecordState> moveHistory) {
            this.pieces = pieces;
            this.moveHistory = moveHistory;
        }

        static BoardState from(MatchAggregate match) {
            List<PieceState> copiedPieces = match.pieces().stream().map(PieceState::copy).toList();
            List<MoveRecordState> copiedMoves = new ArrayList<>(match.moveHistory());
            return new BoardState(new ArrayList<>(copiedPieces), copiedMoves);
        }

        BoardState copy() {
            return new BoardState(
                    pieces.stream().map(PieceState::copy).collect(ArrayList::new, ArrayList::add, ArrayList::addAll),
                    new ArrayList<>(moveHistory)
            );
        }

        List<PieceState> pieces() {
            return pieces;
        }

        List<MoveRecordState> moveHistory() {
            return moveHistory;
        }

        Optional<PieceState> pieceAt(Position position) {
            return pieces.stream().filter(piece -> piece.position().equals(position)).findFirst();
        }

        Optional<PieceState> pieceById(UUID pieceId) {
            return pieces.stream().filter(piece -> piece.id().equals(pieceId)).findFirst();
        }

        Optional<MoveRecordState> lastMove() {
            if (moveHistory.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(moveHistory.get(moveHistory.size() - 1));
        }

        void removePiece(UUID pieceId) {
            pieces.removeIf(piece -> piece.id().equals(pieceId));
        }
    }
}
