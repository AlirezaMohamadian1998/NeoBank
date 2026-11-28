package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountNumberGenerator accountNumberGenerator;

    private final AccountRepository accountRepository;

    private final CustomerRepository customerRepository;

    private final ReferenceGenerator referenceGenerator;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, String customerEmail) {
        Customer customer = customerRepository.findByEmailIgnoreCase(customerEmail)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found"));

        String accountNumber = accountNumberGenerator.generateUnique();

        Account account = AccountMapper.toAccountEntity(
                request,
                accountNumber,
                referenceGenerator.generate(),
                customer
        );

        return AccountMapper.toResponse(accountRepository.save(account));
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
