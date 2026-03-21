package com.bilolbek.collectorChess.api.dto;

import com.bilolbek.collectorChess.domain.model.Contracts;

public record MatchConnectResponse(
        Contracts.OnlineMatchSnapshot snapshot,
        String webSocketURL
) {
}
