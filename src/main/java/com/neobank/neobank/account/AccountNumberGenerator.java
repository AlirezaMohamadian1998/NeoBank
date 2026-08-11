package com.neobank.neobank.account;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return String.format("%014d", random.nextLong(100_000_000_000_000L));
    }
}
