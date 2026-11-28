package com.neobank.neobank.support;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TestFixtures {

    public static Customer customer(String email) {
        return Customer.createNew(email, "{bcrypt}encoded-password", "Ada Lovelace");
    }

    public static Account currentAccount(
            String accountNumber,
            String ledgerReference,
            Customer customer,
            CurrencyCode currency
    ) {
        return Account.createNew(
                accountNumber,
                ledgerReference,
                "Private Account",
                AccountType.CURRENT,
                customer,
                currency
        );
    }
}
