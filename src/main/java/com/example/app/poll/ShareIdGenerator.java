package com.example.app.poll;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Random 10-char base62 ids for share links (~59 bits, so they cannot be guessed or enumerated). */
@Component
public class ShareIdGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
