package com.bilolbek.collectorChess.api.dto;

import java.util.UUID;

public record JoinMatchRequest(
        String roomCode,
        UUID guestID,
        String displayName
) {
}
