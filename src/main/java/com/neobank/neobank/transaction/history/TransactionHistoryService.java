package com.neobank.neobank.transaction.history;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.CardPurchaseDetails;
import com.neobank.neobank.card.CardPurchaseDetailsRepository;
import com.neobank.neobank.card.CardRefundDetails;
import com.neobank.neobank.card.CardRefundDetailsRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.LedgerEntryRepository;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryFilter;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionHistoryService {

    private final LedgerEntryRepository ledgerEntryRepository;

    private final AccountRepository accountRepository;

    private final CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    private final CardRefundDetailsRepository cardRefundDetailsRepository;

    @Transactional(readOnly = true)
    public Page<TransactionHistoryItem> getHistoryByAccount(
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

        Page<LedgerEntry> entries = ledgerEntryRepository.findAll(spec, historyPageable);

        List<Long> cardPurchaseTransactionIds = entries.stream()
                .filter(TransactionHistoryMapper::isCardPurchasePayerEntry)
                .map(entry -> entry.getBankTransaction().getId())
                .toList();

        Map<Long, CardPurchaseDetails> cardPurchaseDetailsByTransactionId =
                cardPurchaseTransactionIds.isEmpty()
                        ? Map.of()
                        : cardPurchaseDetailsRepository.findAllByBankTransaction_IdIn(cardPurchaseTransactionIds)
                        .stream()
                        .collect(Collectors.toMap(
                                details -> details.getBankTransaction().getId(),
                                details -> details
                        ));

        List<Long> cardRefundTransactionIds = entries.stream()
                .filter(TransactionHistoryMapper::isCardRefundReceiverEntry)
                .map(entry -> entry.getBankTransaction().getId())
                .toList();

        Map<Long, CardRefundDetails> cardRefundDetailsByTransactionId =
                cardRefundTransactionIds.isEmpty()
                        ? Map.of()
                        : cardRefundDetailsRepository.findAllByBankTransaction_IdIn(cardRefundTransactionIds)
                        .stream()
                        .collect(Collectors.toMap(
                                details -> details.getBankTransaction().getId(),
                                details -> details
                        ));

        return entries
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
                                        .orElseThrow(),
                                cardPurchaseDetailsByTransactionId,
                                cardRefundDetailsByTransactionId
                        )
                );
    }
}
