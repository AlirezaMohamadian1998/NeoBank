package com.neobank.neobank.transaction;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransactionReferenceGenerator {
    public String generate() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}
