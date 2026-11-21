package com.neobank.neobank.account;

import com.neobank.neobank.creditaccount.CreditAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
@RequiredArgsConstructor
public class AccountNumberGenerator {

    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();

    private final AccountRepository accountRepository;

    private final CreditAccountRepository creditAccountRepository;

    public String generateUnique() {
        for(int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String accountNumber = String.format("%014d", random.nextLong(100_000_000_000_000L));

            if(!accountRepository.existsByAccountNumber(accountNumber)
                    && !creditAccountRepository.existsByAccountNumber(accountNumber)) {
                return accountNumber;
            }
        }

        throw new IllegalStateException("Failed to generate unique account number");
    }
}
