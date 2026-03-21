package com.bilolbek.collectorChess.api;

import com.bilolbek.collectorChess.api.dto.ApiErrorResponse;
import com.bilolbek.collectorChess.domain.service.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(DomainException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiErrorResponse(exception.code(), exception.getMessage()));
    }
}
