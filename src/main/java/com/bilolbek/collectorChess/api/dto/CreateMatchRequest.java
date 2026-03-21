package com.bilolbek.collectorChess.api.dto;

import java.util.UUID;

public record CreateMatchRequest(
        UUID guestID,
        String displayName
) {
}
