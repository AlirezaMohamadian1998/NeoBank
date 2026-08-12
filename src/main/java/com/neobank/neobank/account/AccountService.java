package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {
    private static final int MAX_ACCOUNT_NUMBER_GENERATION_ATTEMPTS = 10;

    private final AccountNumberGenerator accountNumberGenerator;

    private final AccountRepository accountRepository;

    private final CustomerRepository customerRepository;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, String customerEmail) {
        Customer customer = customerRepository.findByEmailIgnoreCase(customerEmail)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found"));

        String accountNumber = generateUniqueAccountNumber();
        Account account = AccountMapper.toAccountEntity(request, accountNumber, customer);

        return AccountMapper.toResponse(accountRepository.save(account));
    }

    private String generateUniqueAccountNumber() {
        String accountNumber;
        for (int i = 0; i < MAX_ACCOUNT_NUMBER_GENERATION_ATTEMPTS; i++) {
            accountNumber = accountNumberGenerator.generate();
            if (!accountRepository.existsByAccountNumber(accountNumber)) {
                return accountNumber;
            }
        }
        throw new IllegalStateException("Failed to generate unique account number");
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getCurrentCustomerAccounts(String email) {
        return accountRepository
                .findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(email)
                .stream()
                .map(AccountMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getCurrentCustomerAccount(String accountNumber, String email) {
        return AccountMapper
                .toResponse(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email)
                        .orElseThrow(() -> new AccountNotFoundException()));
    }
}
