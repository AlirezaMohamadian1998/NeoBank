package com.neobank.neobank.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TransactionReferenceGenerator {

    private final BankTransactionRepository bankTransactionRepository;

    public String generate() {
        String reference;
        for(int i = 0; i < 10; i++) {
            reference = UUID.randomUUID()
                    .toString()
                    .replace("-", "");

            if(!bankTransactionRepository.existsByReference(reference)){
                return reference;
            }
        }
        throw new IllegalStateException("Failed to generate unique reference");
    }
}
