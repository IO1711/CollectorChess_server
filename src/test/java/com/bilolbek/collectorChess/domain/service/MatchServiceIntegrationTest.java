package com.bilolbek.collectorChess.domain.service;

import com.bilolbek.collectorChess.domain.model.Contracts;
import com.bilolbek.collectorChess.persistence.MatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MatchServiceIntegrationTest {

    @Autowired
    private MatchService matchService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MatchRepository matchRepository;

    @Test
    void createJoinDraftAndMoveFlowProducesAuthoritativeSnapshots() {
        UUID whiteGuestId = UUID.randomUUID();
        UUID blackGuestId = UUID.randomUUID();

        Contracts.OnlineMatchSnapshot created = matchService.createMatch(whiteGuestId, "White Guest");
        MatchService.ServiceSnapshot joined = matchService.joinMatch(created.roomCode(), blackGuestId, "Black Guest");

        Contracts.OnlineMatchEvent whiteDraft = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                joined.snapshot().revision(),
                Contracts.ActionType.UPDATE_DRAFT_SELECTION,
                new Contracts.UpdateDraftSelectionPayload("shadowstep", new Contracts.Position(0, 1))
        );
        Contracts.OnlineMatchEvent whiteLock = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                whiteDraft.snapshot().revision(),
                Contracts.ActionType.LOCK_DRAFT,
                objectMapper.nullNode()
        );
        Contracts.OnlineMatchEvent blackLock = submitAction(
                joined.snapshot().id(),
                blackGuestId,
                whiteLock.snapshot().revision(),
                Contracts.ActionType.LOCK_DRAFT,
                objectMapper.nullNode()
        );
        Contracts.OnlineMatchEvent moveAccepted = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                blackLock.snapshot().revision(),
                Contracts.ActionType.MAKE_MOVE,
                new Contracts.MakeMovePayload(
                        new Contracts.Move(
                                new Contracts.Position(1, 4),
                                new Contracts.Position(3, 4),
                                null,
                                null
                        )
                )
        );

        assertThat(moveAccepted.type()).isEqualTo(Contracts.EventType.ACTION_ACCEPTED);
        assertThat(moveAccepted.snapshot().phase()).isEqualTo(Contracts.MatchPhase.ACTIVE);
        assertThat(moveAccepted.snapshot().currentTurn()).isEqualTo(Contracts.PieceColor.BLACK);
        assertThat(moveAccepted.snapshot().board().moveHistory()).hasSize(1);
        assertThat(moveAccepted.snapshot().board().pieces())
                .anySatisfy(piece -> {
                    assertThat(piece.pieceName()).isEqualTo(Contracts.PieceName.PAWN);
                    assertThat(piece.position()).isEqualTo(new Contracts.Position(3, 4));
                });
        assertThat(moveAccepted.snapshot().board().pieces())
                .filteredOn(piece -> piece.position().equals(new Contracts.Position(0, 1)))
                .singleElement()
                .satisfies(piece -> assertThat(piece.equippedSkills()).extracting(Contracts.EquippedSkillSnapshot::skillID).containsExactly("shadowstep"));
    }

    @Test
    void staleRevisionsAndWrongTurnsReturnRejectedEvents() {
        ActiveMatch activeMatch = createActiveMatch();

        Contracts.OnlineMatchEvent staleRevision = submitAction(
                activeMatch.matchId(),
                activeMatch.whiteGuestId(),
                activeMatch.activeRevision() - 1,
                Contracts.ActionType.MAKE_MOVE,
                new Contracts.MakeMovePayload(
                        new Contracts.Move(new Contracts.Position(1, 4), new Contracts.Position(3, 4), null, null)
                )
        );
        Contracts.OnlineMatchEvent wrongTurn = submitAction(
                activeMatch.matchId(),
                activeMatch.blackGuestId(),
                activeMatch.activeRevision(),
                Contracts.ActionType.MAKE_MOVE,
                new Contracts.MakeMovePayload(
                        new Contracts.Move(new Contracts.Position(6, 4), new Contracts.Position(4, 4), null, null)
                )
        );

        assertThat(staleRevision.type()).isEqualTo(Contracts.EventType.ACTION_REJECTED);
        assertThat(staleRevision.rejectionReason()).isEqualTo("staleRevision");
        assertThat(staleRevision.snapshot().revision()).isEqualTo(activeMatch.activeRevision());

        assertThat(wrongTurn.type()).isEqualTo(Contracts.EventType.ACTION_REJECTED);
        assertThat(wrongTurn.rejectionReason()).isEqualTo("notYourTurn");
        assertThat(wrongTurn.snapshot().currentTurn()).isEqualTo(Contracts.PieceColor.WHITE);
    }

    @Test
    void clearingDraftSelectionFreesTheSlotForAnotherSkill() {
        UUID whiteGuestId = UUID.randomUUID();
        UUID blackGuestId = UUID.randomUUID();

        Contracts.OnlineMatchSnapshot created = matchService.createMatch(whiteGuestId, "White Guest");
        MatchService.ServiceSnapshot joined = matchService.joinMatch(created.roomCode(), blackGuestId, "Black Guest");

        Contracts.OnlineMatchEvent firstSelection = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                joined.snapshot().revision(),
                Contracts.ActionType.UPDATE_DRAFT_SELECTION,
                new Contracts.UpdateDraftSelectionPayload("shadowstep", new Contracts.Position(0, 1))
        );
        Contracts.OnlineMatchEvent secondSelection = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                firstSelection.snapshot().revision(),
                Contracts.ActionType.UPDATE_DRAFT_SELECTION,
                new Contracts.UpdateDraftSelectionPayload("knightfall", new Contracts.Position(0, 6))
        );
        Contracts.OnlineMatchEvent clearedSelection = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                secondSelection.snapshot().revision(),
                Contracts.ActionType.UPDATE_DRAFT_SELECTION,
                new Contracts.UpdateDraftSelectionPayload("shadowstep", null)
        );
        Contracts.OnlineMatchEvent replacementSelection = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                clearedSelection.snapshot().revision(),
                Contracts.ActionType.UPDATE_DRAFT_SELECTION,
                new Contracts.UpdateDraftSelectionPayload("deadeye", new Contracts.Position(1, 3))
        );

        assertThat(clearedSelection.type()).isEqualTo(Contracts.EventType.ACTION_ACCEPTED);
        assertThat(clearedSelection.snapshot().draft().selections())
                .filteredOn(selection -> selection.color() == Contracts.PieceColor.WHITE)
                .extracting(Contracts.OnlineSkillDraftSelection::skillID)
                .containsExactly("knightfall");

        assertThat(replacementSelection.type()).isEqualTo(Contracts.EventType.ACTION_ACCEPTED);
        assertThat(replacementSelection.snapshot().draft().selections())
                .filteredOn(selection -> selection.color() == Contracts.PieceColor.WHITE)
                .extracting(Contracts.OnlineSkillDraftSelection::skillID)
                .containsExactly("deadeye", "knightfall");
    }

    @Test
    void listJoinableRoomsReturnsOnlyRoomsWithAnOpenSeat() {
        Contracts.OnlineMatchSnapshot waitingRoom = matchService.createMatch(UUID.randomUUID(), "Waiting Host");
        Contracts.OnlineMatchSnapshot fullRoom = matchService.createMatch(UUID.randomUUID(), "Full Host");

        matchService.joinMatch(fullRoom.roomCode(), UUID.randomUUID(), "Full Joiner");

        assertThat(matchService.listJoinableRooms())
                .extracting(Contracts.JoinableRoomSnapshot::roomCode)
                .contains(waitingRoom.roomCode())
                .doesNotContain(fullRoom.roomCode());
    }

    @Test
    void resignationFinishesTheMatchAndDeletesTheRoom() {
        ActiveMatch activeMatch = createActiveMatch();

        Contracts.OnlineMatchEvent resigned = submitAction(
                activeMatch.matchId(),
                activeMatch.whiteGuestId(),
                activeMatch.activeRevision(),
                Contracts.ActionType.RESIGN,
                objectMapper.nullNode()
        );

        assertThat(resigned.type()).isEqualTo(Contracts.EventType.FINISHED);
        assertThat(resigned.snapshot().phase()).isEqualTo(Contracts.MatchPhase.FINISHED);
        assertThat(resigned.snapshot().outcome()).isNotNull();
        assertThat(resigned.snapshot().outcome().kind()).isEqualTo(Contracts.OutcomeKind.RESIGNATION);
        assertThat(resigned.snapshot().outcome().winner()).isEqualTo(Contracts.PieceColor.BLACK);
        assertThat(resigned.snapshot().status().winner()).isEqualTo(Contracts.PieceColor.BLACK);
        assertThat(matchRepository.findById(activeMatch.matchId(), false)).isEmpty();
    }

    private ActiveMatch createActiveMatch() {
        UUID whiteGuestId = UUID.randomUUID();
        UUID blackGuestId = UUID.randomUUID();
        Contracts.OnlineMatchSnapshot created = matchService.createMatch(whiteGuestId, "White");
        MatchService.ServiceSnapshot joined = matchService.joinMatch(created.roomCode(), blackGuestId, "Black");
        Contracts.OnlineMatchEvent whiteLock = submitAction(
                joined.snapshot().id(),
                whiteGuestId,
                joined.snapshot().revision(),
                Contracts.ActionType.LOCK_DRAFT,
                objectMapper.nullNode()
        );
        Contracts.OnlineMatchEvent blackLock = submitAction(
                joined.snapshot().id(),
                blackGuestId,
                whiteLock.snapshot().revision(),
                Contracts.ActionType.LOCK_DRAFT,
                objectMapper.nullNode()
        );
        return new ActiveMatch(joined.snapshot().id(), whiteGuestId, blackGuestId, blackLock.snapshot().revision());
    }

    private Contracts.OnlineMatchEvent submitAction(
            UUID matchId,
            UUID actorId,
            int baseRevision,
            Contracts.ActionType actionType,
            Object payload
    ) {
        return matchService.submitAction(
                matchId,
                new Contracts.OnlineMatchAction(
                        UUID.randomUUID(),
                        matchId,
                        actorId,
                        baseRevision,
                        actionType,
                        Instant.now(),
                        objectMapper.valueToTree(payload)
                )
        );
    }

    private record ActiveMatch(
            UUID matchId,
            UUID whiteGuestId,
            UUID blackGuestId,
            int activeRevision
    ) {
    }
}
