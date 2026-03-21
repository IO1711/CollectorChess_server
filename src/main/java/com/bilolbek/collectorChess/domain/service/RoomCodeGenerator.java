package com.bilolbek.collectorChess.domain.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class RoomCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index += 1) {
            builder.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
