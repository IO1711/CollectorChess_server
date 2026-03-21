package com.bilolbek.collectorChess.api.dto;

public record ApiErrorResponse(
        String code,
        String message
) {
}
