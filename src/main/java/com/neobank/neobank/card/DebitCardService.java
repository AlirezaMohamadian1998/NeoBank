package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.dto.CardLimitsUpdateRequest;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.exception.DebitCardNotFoundException;
import com.neobank.neobank.card.issuing.CardIssuer;
import com.neobank.neobank.card.issuing.IssuedCard;
import com.neobank.neobank.card.limit.CardLimitProperties;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
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

    private final CardIssuer cardIssuer;

    private final CardLimitProperties cardLimitProperties;

    private final CardDailySpendService cardDailySpendService;

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
        IssuedCard issuedCard = cardIssuer.issue(currentYearMonth);

        DebitCard debitCard = DebitCard.createNew(
                referenceGenerator.generate(),
                issuedCard.lastFourDigits(),
                issuedCard.expirationYearMonth(),
                currentYearMonth,
                fundingAccount,
                cardLimitProperties.getDefaultLimits(fundingAccount.getCurrency())
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

        BigDecimal spentToday = cardDailySpendService.getAmountSpent(debitCard, LocalDate.now(clock));

        return DebitCardMapper.toRetrieveDebitCardResponse(debitCard, spentToday);
    }

    @Transactional(readOnly = true)
    public Page<DebitCardRetrieveResponse> getDebitCards(String email, Pageable pageable) {

        Page<DebitCard> debitCards =
                debitCardRepository.findAllByFundingAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(email, pageable);

        Map<Long, BigDecimal> spentTodayByCardId = cardDailySpendService.getAmountSpentPerCard(debitCards.getContent(), LocalDate.now(clock));

        return debitCards.map(debitCard ->
            DebitCardMapper.toRetrieveDebitCardResponse(
                    debitCard,
                    spentTodayByCardId.get(debitCard.getId())
            )
        );
    }

    @Transactional
    public DebitCardRetrieveResponse updateSpendingLimits(String cardReference, String email, CardLimitsUpdateRequest request) {
        DebitCard debitCard =
                debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, email)
                        .orElseThrow(() -> new DebitCardNotFoundException("Debit card not found"));

        CardSpendingLimits newLimits = CardSpendingLimits.createNew(request.perTransactionLimit(), request.dailyLimit());
        CardSpendingLimits maximum = cardLimitProperties.getMaximumLimits(debitCard.getFundingAccount().getCurrency());

        debitCard.changeSpendingLimits(newLimits, maximum);

        DebitCard savedDebitCard = debitCardRepository.save(debitCard);
        BigDecimal spentToday = cardDailySpendService.getAmountSpent(savedDebitCard, LocalDate.now(clock));

        return DebitCardMapper.toRetrieveDebitCardResponse(savedDebitCard, spentToday);
    }

    @Transactional
    public DebitCardRetrieveResponse changeCardStatus(String cardReference, String email, CardStatus targetStatus) {
        DebitCard debitCard =
                debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, email)
                        .orElseThrow(() -> new DebitCardNotFoundException("Debit card not found"));

        debitCard.changeStatus(targetStatus, YearMonth.now(clock));

        DebitCard savedDebitCard = debitCardRepository.save(debitCard);
        BigDecimal spentToday = cardDailySpendService.getAmountSpent(savedDebitCard, LocalDate.now(clock));

        return DebitCardMapper.toRetrieveDebitCardResponse(savedDebitCard, spentToday);
    }
}
