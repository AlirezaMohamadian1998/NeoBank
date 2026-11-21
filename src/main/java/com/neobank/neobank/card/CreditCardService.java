package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNumberGenerator;
import com.neobank.neobank.card.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.card.issuing.CardIssuer;
import com.neobank.neobank.card.issuing.IssuedCard;
import com.neobank.neobank.card.limit.CardLimitProperties;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditAccountProperties;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.CustomerRepository;
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
public class CreditCardService {

    private final CreditCardRepository creditCardRepository;

    private final CreditAccountRepository creditAccountRepository;

    private final CustomerRepository customerRepository;

    private final IdempotencyService idempotencyService;

    private final RequestHasher requestHasher;

    private final ReferenceGenerator referenceGenerator;

    private final AccountNumberGenerator accountNumberGenerator;

    private final CardIssuer cardIssuer;

    private final CardLimitProperties cardLimitProperties;

    private final CreditAccountProperties creditAccountProperties;

    private final CardDailySpendService cardDailySpendService;

    private final Clock clock;

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
    public CreditCardIssueResponse issueCreditCard(String customerEmail, CreditCardIssueRequest request, String idempotencyKey) {
        if(!idempotencyKey.matches("[0-9a-f]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        Customer customer = customerRepository.findByEmailIgnoreCase(customerEmail)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found"));

        String requestHash = requestHasher.hashRequest(String.join(
                "|",
                "ISSUE_CREDIT_CARD",
                customerEmail,
                request.currency().name()
        ));

        Optional<IdempotencyRecord> existingIdempotencyRecord =
                idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if(existingIdempotencyRecord.isPresent()) {
            CreditCard existingCreditCard = creditCardRepository.findByCardReference(existingIdempotencyRecord.get().getResultReference())
                    .orElseThrow(() -> new IdempotencyResultNotFoundException("Couldn't find credit card with the given reference."));

            return CreditCardMapper.toIssueResponse(existingCreditCard);
        }

        CreditAccount creditAccount = creditAccountRepository.save(CreditAccount.createNew(
                accountNumberGenerator.generateUnique(),
                referenceGenerator.generate(),
                customer,
                request.currency(),
                creditAccountProperties.getDefaultCreditLimit(request.currency())
        ));

        YearMonth currentYearMonth = YearMonth.now(clock);
        IssuedCard issuedCard = cardIssuer.issue(currentYearMonth);

        CreditCard creditCard = creditCardRepository.save(CreditCard.createNew(
                referenceGenerator.generate(),
                issuedCard.lastFourDigits(),
                issuedCard.expirationYearMonth(),
                currentYearMonth,
                creditAccount,
                cardLimitProperties.getDefaultLimits(request.currency())
        ));

        idempotencyService.save(IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                creditCard.getCardReference()
        ));

        return CreditCardMapper.toIssueResponse(creditCard);
    }

    @Transactional(readOnly = true)
    public CreditCardRetrieveResponse getCreditCard(String cardReference, String email) {
        CreditCard creditCard = findOwnedCreditCard(cardReference, email);

        BigDecimal spentToday = cardDailySpendService.getAmountSpent(creditCard, LocalDate.now(clock));

        return CreditCardMapper.toRetrieveResponse(creditCard, spentToday);
    }

    @Transactional(readOnly = true)
    public Page<CreditCardRetrieveResponse> getCreditCards(String email, Pageable pageable) {
        Page<CreditCard> creditCards =
                creditCardRepository.findAllByCreditAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(email, pageable);

        Map<Long, BigDecimal> spentTodayByCardId =
                cardDailySpendService.getAmountSpentPerCard(creditCards.getContent(), LocalDate.now(clock));

        return creditCards.map(creditCard ->
                CreditCardMapper.toRetrieveResponse(creditCard, spentTodayByCardId.get(creditCard.getId()))
        );
    }

    @Transactional
    public CreditCardRetrieveResponse updateSpendingLimits(String cardReference, String email, CardSpendingLimitsUpdateRequest request) {
        CreditCard creditCard = findOwnedCreditCard(cardReference, email);

        CardSpendingLimits newLimits = CardSpendingLimits.createNew(request.perTransactionLimit(), request.dailyLimit());
        CardSpendingLimits maximum = cardLimitProperties.getMaximumLimits(creditCard.getCreditAccount().getCurrency());

        creditCard.changeSpendingLimits(newLimits, maximum);

        CreditCard savedCreditCard = creditCardRepository.save(creditCard);
        BigDecimal spentToday = cardDailySpendService.getAmountSpent(savedCreditCard, LocalDate.now(clock));

        return CreditCardMapper.toRetrieveResponse(savedCreditCard, spentToday);
    }

    @Transactional
    public CreditCardRetrieveResponse changeCardStatus(String cardReference, String email, CardStatus targetStatus) {
        CreditCard creditCard = findOwnedCreditCard(cardReference, email);

        creditCard.changeStatus(targetStatus, YearMonth.now(clock));

        CreditCard savedCreditCard = creditCardRepository.save(creditCard);
        BigDecimal spentToday = cardDailySpendService.getAmountSpent(savedCreditCard, LocalDate.now(clock));

        return CreditCardMapper.toRetrieveResponse(savedCreditCard, spentToday);
    }

    private CreditCard findOwnedCreditCard(String cardReference, String email) {
        return creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(cardReference, email)
                .orElseThrow(() -> new CreditCardNotFoundException("Credit card not found"));
    }
}
