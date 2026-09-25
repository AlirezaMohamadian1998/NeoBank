package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.issuing.DebitCardIssuer;
import com.neobank.neobank.card.issuing.IssuedDebitCard;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DebitCardService {

    private final DebitCardRepository debitCardRepository;

    private final AccountRepository accountRepository;

    private final IdempotencyService idempotencyService;

    private final ReferenceGenerator referenceGenerator;

    private final RequestHasher requestHasher;

    private final Clock clock;

    private final DebitCardIssuer debitCardIssuer;

    @Transactional
    @Retryable(
            maxRetries = 4,
            maxDelay = 4000,
            delay = 200,
            jitter = 50,
            multiplier = 2,
            includes = {
                    ConcurrencyFailureException.class,
                    IdempotencyKeyRaceException.class
            }
    )
    public DebitCardIssueResponse issueDebitCard(String customerEmail, String accountNumber, String idempotencyKey) {
        if(!idempotencyKey.matches("[0-9a-f]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        Account fundingAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(AccountNotFoundException::new);

        String canonicalRequest = String.join(
                "|",
                "ISSUE_DEBIT_CARD",
                customerEmail,
                accountNumber
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        Optional<IdempotencyRecord> existingIdempotencyRecord = idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if(existingIdempotencyRecord.isPresent()) {
            DebitCard existingDebitCard = debitCardRepository.findByCardReference(existingIdempotencyRecord.get().getResultReference())
                    .orElseThrow(() -> new IdempotencyResultNotFoundException("Couldn't find debit card with the given reference."));

            return DebitCardMapper.toIssueDebitCardResponse(existingDebitCard, fundingAccount.getAccountNumber());
        }

        YearMonth currentYearMonth = YearMonth.now(clock);
        IssuedDebitCard issuedDebitCard = debitCardIssuer.issue(currentYearMonth);

        DebitCard debitCard = DebitCard.createNew(
                referenceGenerator.generate(),
                issuedDebitCard.lastFourDigits(),
                issuedDebitCard.expirationYearMonth(),
                currentYearMonth,
                fundingAccount
        );

        debitCardRepository.save(debitCard);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                fundingAccount.getCustomer(),
                debitCard.getCardReference()
        );

        idempotencyService.save(idempotencyRecord);

        return DebitCardMapper.toIssueDebitCardResponse(debitCard, fundingAccount.getAccountNumber());
    }

    @Transactional(readOnly = true)
    public DebitCardRetrieveResponse getDebitCard(String cardReference, String email) {

        DebitCard debitCard =
                debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, email)
                        .orElseThrow(() -> new DebitCardNotFoundException("Debit card not found"));

        return DebitCardMapper.toRetrieveDebitCardResponse(debitCard, debitCard.getFundingAccount().getAccountNumber());
    }

    @Transactional(readOnly = true)
    public Page<DebitCardRetrieveResponse> getDebitCards(String email, Pageable pageable) {

        Page<DebitCard> debitCards =
                debitCardRepository.findAllByFundingAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(email, pageable);

        return debitCards.map(debitCard ->
            DebitCardMapper.toRetrieveDebitCardResponse(debitCard, debitCard.getFundingAccount().getAccountNumber())
        );
    }
}
