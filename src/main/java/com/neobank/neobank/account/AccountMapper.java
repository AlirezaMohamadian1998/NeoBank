package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AccountMapper {

    public static Account toAccountEntity(
            CreateAccountRequest request,
            String accountNumber,
            Customer customer,
            LedgerAccount ledgerAccount
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        return Account.createNew(
                accountNumber,
                request.name(),
                request.accountType(),
                customer,
                ledgerAccount
        );
    }

    public static AccountResponse toResponse(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }

        return new AccountResponse(
                account.getAccountNumber(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }
}
