package com.neobank.neobank.transaction.history;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.LedgerEntryRepository;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryFilter;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionHistoryService {

    private final LedgerEntryRepository ledgerEntryRepository;

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getHistoryByAccount(
            TransactionHistoryFilter filter,
            String customerEmail,
            Pageable pageable
    ) {
        Pageable historyPageable = TransactionHistorySortHelper.normalize(pageable);

        List<Account> accounts = new ArrayList<>();

        if(filter.accountNumber() != null) {
            accounts.add(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(filter.accountNumber(), customerEmail)
                    .orElseThrow(AccountNotFoundException::new));
        } else {
            accounts.addAll(accountRepository.findAllByCustomer_Email(customerEmail));

        }

        List<Long> ledgerAccountIds = accounts.stream()
                .map(Account::getLedgerAccount)
                .map(LedgerAccount::getId)
                .toList();

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(ledgerAccountIds)
                        .and(LedgerEntrySpecifications.hasTransactionType(filter.type()))
                        .and(LedgerEntrySpecifications.hasRequestedCurrency(filter.requestedCurrency()))
                        .and(LedgerEntrySpecifications.createdAfterOrEqual(filter.dateFrom()))
                        .and(LedgerEntrySpecifications.createdBefore(filter.dateTo()))
                        .and(LedgerEntrySpecifications.amountIsGreaterThanOrEqual(filter.appliedMin()))
                        .and(LedgerEntrySpecifications.amountIsLessThanOrEqual(filter.appliedMax()));

        return ledgerEntryRepository
                .findAll(spec, historyPageable)
                .map(entry ->
                        TransactionHistoryMapper.toResponse(
                                entry,
                                filter.accountNumber() != null
                                        ? filter.accountNumber()
                                        : accounts.stream()
                                        .filter(acc ->
                                                acc.getLedgerAccount().getId()
                                                        .equals(entry.getLedgerAccount().getId())
                                        )
                                        .map(Account::getAccountNumber)
                                        .findFirst()
                                        .orElseThrow()

                        )
                );
    }
}
