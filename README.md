# Collector Chess Backend Requirements

This document defines the backend contract for the current Collector Chess client.

The iOS app is still guest-only. There are no accounts or profiles yet. Each device stores one persistent guest identity and uses that identity to create, join, reconnect to, and continue matches.

## Current Client Scope

- 2-player online matches only.
- 1 guest player per device.
- Host is `white`.
- Joiner is `black`.
- Match phases are `waitingForPlayers`, `drafting`, `active`, and `finished`.
- Skill drafting happens before the board starts.
- Each color may equip at most `2` skills per match.
- Current skill ids are:
  - `shadowstep`
  - `knightfall`
  - `deadeye`
- Skills are offensive, manual, and one-time use.
- Standard chess rules are already implemented on the client:
  - legal move validation
  - check/checkmate/stalemate
  - castling
  - en passant
  - promotion

Reference client models:

- `CollectorChess/CollectorChess/Online/GuestSessionStore.swift`
- `CollectorChess/CollectorChess/Online/OnlineMatchModels.swift`
- `CollectorChess/CollectorChess/Chess/Domain/ChessBoard.swift`
- `CollectorChess/CollectorChess/Chess/UI/SkillDraftView.swift`

## Required Backend Responsibilities

The backend must be authoritative. The client may preview state locally, but the server must validate and finalize every online state change.

Required responsibilities:

- Create room-based matches.
- Let a second guest join by room code.
- Persist guest seat ownership by `guestID`.
- Persist match snapshots and revision numbers.
- Validate draft selections.
- Validate chess moves and skill usage.
- Broadcast the latest full match snapshot after every accepted action.
- Reject stale or illegal actions with a machine-readable reason and the latest snapshot.
- Support reconnect for the same guest on the same device.

## Guest Identity

Guest identity is device-scoped for now.

Required fields:

- `guestID`: UUID generated and persisted by the client on device.
- `displayName`: editable guest name, max 24 characters on the client.

Backend rules:

- Treat `guestID` as the stable identity for v1.
- Do not require login, token exchange, email, or profile creation.
- The same `guestID` must be able to reconnect to its existing seat.
- A different `guestID` must not take over an occupied seat.

## Match Lifecycle

### 1. Create match

- Host sends guest identity.
- Backend creates a match with:
  - a `matchID`
  - a short `roomCode`
  - `white` seat assigned to host
  - phase `waitingForPlayers`
  - revision `1`

### 2. Join match

- Joiner sends `roomCode` and guest identity.
- If the room exists and the black seat is free, backend assigns the joiner to `black`.
- When both seats are occupied, phase becomes `drafting`.

### 3. Draft skills

- Each seat may assign up to 2 skills.
- Each skill assignment targets one piece position for that player's color.
- A player may also leave a skill unassigned.
- Duplicate skill ids for the same color are not allowed.
- Match moves to `active` only when both seats submit `lockDraft`.

### 4. Play match

- Only the guest whose color matches `currentTurn` may submit a move.
- Server validates the move against the authoritative board state.
- If the move uses a skill, the server must verify:
  - the piece owns the `equippedSkillID`
  - the skill has not been used
  - the move is legal under the skill's rules
- On success:
  - apply the move
  - consume the skill if used
  - update status/check/checkmate/stalemate
  - increment revision
  - broadcast the new full snapshot

### 5. Finish

- Match finishes on:
  - checkmate
  - stalemate
  - resignation
  - manual abort by server/admin if needed
- Profiles, rematch flows, and post-game history are not required yet.

## Required API Surface

The exact framework is up to the backend developer, but the client needs this surface.

### HTTP

`POST /v1/matches`

- Creates a match.
- Request body:

```json
{
  "guestID": "UUID",
  "displayName": "Guest 1A2B"
}
```

- Response body:

```json
{
  "snapshot": "OnlineMatchSnapshot",
  "webSocketURL": "wss://..."
}
```

`POST /v1/matches/join`

- Joins by room code.
- Request body:

```json
{
  "roomCode": "ABC123",
  "guestID": "UUID",
  "displayName": "Guest 9F0D"
}
```

- Response body:

```json
{
  "snapshot": "OnlineMatchSnapshot",
  "webSocketURL": "wss://..."
}
```

`GET /v1/matches/{matchID}?guestID={guestID}`

- Fetches the latest snapshot for reconnect or app relaunch.

`POST /v1/matches/{matchID}/actions`

- Accepts one `OnlineMatchAction`.
- Returns either:
  - accepted action info plus latest snapshot
  - rejected action info plus latest snapshot

### Realtime

`WS /v1/matches/{matchID}/live?guestID={guestID}`

Required websocket behavior:

- Push the latest snapshot when a client connects.
- Push a new event after every accepted action.
- Push presence updates when the other guest disconnects or reconnects.
- Keep messages ordered by `revision`.

If websocket is not possible, provide an event stream alternative with the same payloads, but websocket is the preferred implementation.

## Snapshot Contract

The backend response should mirror the client model in `OnlineMatchModels.swift`.

Required top-level `OnlineMatchSnapshot` fields:

- `id`: UUID
- `roomCode`: string
- `revision`: integer, increases by 1 on every accepted action
- `phase`: `waitingForPlayers | drafting | active | finished`
- `seats`: array of exactly 2 `OnlineMatchSeat` values once both players have joined
- `draft`: `OnlineSkillDraftState`
- `board`: `ChessBoardSnapshot`
- `currentTurn`: `white | black`
- `status`: `ChessGameStatusSnapshot`
- `outcome`: nullable `OnlineMatchOutcome`
- `createdAt`: ISO-8601 timestamp
- `updatedAt`: ISO-8601 timestamp

### OnlineMatchSeat

- `color`: `white | black`
- `player.id`: guest UUID
- `player.displayName`: guest display name
- `player.createdAt`: ISO-8601 timestamp
- `isHost`: bool
- `isLocalDevice`: optional for server responses, but harmless if echoed back
- `isReady`: bool
- `connectionStatus`: `invited | connected | disconnected`

### OnlineSkillDraftState

- `maxSkillsPerPlayer`: must be `2` for current release
- `selections`: array of `OnlineSkillDraftSelection`

### OnlineSkillDraftSelection

- `color`: `white | black`
- `skillID`: string
- `position`: nullable board position
- `isLockedIn`: bool

### ChessBoardSnapshot

- `pieces`: array of `ChessPieceSnapshot`
- `moveHistory`: array of `ChessMoveRecordSnapshot`

### ChessPieceSnapshot

- `id`: UUID for the piece instance
- `pieceName`: `king | queen | rook | bishop | knight | pawn`
- `pieceColor`: `white | black`
- `behaviourID`: current standard values are:
  - `standardKing`
  - `standardQueen`
  - `standardRook`
  - `standardBishop`
  - `standardKnight`
  - `standardPawn`
- `position`: `{ "row": Int, "column": Int }`
- `moveCount`: integer
- `equippedSkills`: array of:
  - `id`: UUID of this equipped skill instance
  - `skillID`: string
  - `hasBeenUsed`: bool

### ChessGameStatusSnapshot

- `kind`: `active | checkmate | stalemate`
- `turn`: nullable `PieceColor`
- `inCheck`: nullable bool
- `winner`: nullable `PieceColor`

## Action Contract

All writes should use the same action envelope.

```json
{
  "id": "UUID",
  "matchID": "UUID",
  "actorID": "UUID",
  "baseRevision": 12,
  "type": "makeMove",
  "submittedAt": "2026-03-21T20:00:00Z",
  "payload": {}
}
```

Required action types:

- `joinMatch`
- `updateDraftSelection`
- `lockDraft`
- `makeMove`
- `resign`
- `leaveMatch`

### updateDraftSelection payload

```json
{
  "skillID": "shadowstep",
  "position": {
    "row": 0,
    "column": 1
  }
}
```

Rules:

- The target piece must belong to the acting color.
- The acting color may have at most 2 selected skills.
- The acting color may not select the same `skillID` twice.
- Setting `position` to `null` should unassign that skill.

### makeMove payload

```json
{
  "move": {
    "from": { "row": 1, "column": 4 },
    "to": { "row": 3, "column": 4 },
    "promotionPieceName": null,
    "equippedSkillID": null
  }
}
```

Rules:

- `promotionPieceName` is required when the move is a promotion.
- `equippedSkillID` is only sent when the player intentionally uses a skill for that move.
- The server must never auto-activate skills.

## Event Contract

Realtime updates should use an event envelope like `OnlineMatchEvent`.

Required event fields:

- `id`
- `matchID`
- `revision`
- `type`
- `actionID`
- `rejectionReason`
- `snapshot`

Required event types:

- `snapshot`
- `actionAccepted`
- `actionRejected`
- `presenceChanged`
- `finished`

## Validation Rules The Backend Must Enforce

- Only 2 seats per match.
- Host is white and joiner is black for v1.
- Only the seat owner may act for that color.
- `baseRevision` must match the server's latest revision.
- Draft updates are only valid during `drafting`.
- Moves are only valid during `active`.
- A player may only move on their turn.
- A move may not leave that player's king in check.
- Kings may not be captured.
- Skill ids must exist in the active skill library.
- A skill may be used only once.
- A skill-equipped move must match the exact piece holding that `equippedSkillID`.
- Castling, en passant, and promotion must follow standard chess rules.
- Promotion choices are limited to `queen`, `rook`, `bishop`, `knight`.

## Reconnect and Persistence

Required behavior:

- Keep finished matches for later inspection if easy, but this is optional for v1.
- Keep active or drafting matches for at least 15 minutes after disconnect.
- Allow the same `guestID` to reconnect and resume its seat.
- Emit `presenceChanged` when a guest disconnects or reconnects.
- Do not auto-award a win on disconnect in v1.

## Not In Scope Yet

- Profiles
- authentication
- ranked matchmaking
- friend lists
- chat
- spectators
- tournaments
- inventory syncing beyond the current local skill library

## Recommended Implementation Notes

- Use websocket for match updates.
- Store the full latest snapshot plus an append-only action log.
- Make action ids idempotent so retries do not duplicate moves.
- Use server-side validation only. The client may preview but must not be trusted.
- Keep timestamps in ISO-8601 UTC.

## One Important Client Detail

Pieces still own executable movement behavior in app code, but online payloads do not send closures. They send `behaviourID` instead. The backend should treat `behaviourID` as the transport-safe identifier for how a piece is supposed to move.
