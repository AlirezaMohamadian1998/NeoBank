package com.neobank.neobank.card.debit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.debit.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.issuing.CardIssuer;
import com.neobank.neobank.card.issuing.IssuedCard;
import com.neobank.neobank.card.limit.CardDailySpendService;
import com.neobank.neobank.card.limit.CardLimitProperties;
import com.neobank.neobank.card.limit.CardSpendingLimits;
import com.neobank.neobank.card.limit.TestCardLimitProperties;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

import static com.neobank.neobank.support.TestFixtures.currentAccount;
import static com.neobank.neobank.support.TestFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DebitCardServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DebitCardRepository debitCardRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private RequestHasher requestHasher;

    @Mock
    private CardIssuer cardIssuer;

    @Mock
    private CardDailySpendService cardDailySpendService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), Clock.systemUTC().getZone());

    private final CardLimitProperties cardLimitProperties = TestCardLimitProperties.standard();

    private DebitCardService debitCardService;

    @BeforeEach
    void setUp() {
        debitCardService = new DebitCardService(
                debitCardRepository,
                accountRepository,
                idempotencyService,
                referenceGenerator,
                requestHasher,
                clock,
                cardIssuer,
                cardLimitProperties,
                cardDailySpendService
        );
    }

    @Test
    void issueDebitCardIssuesAndPersistsNewCardWithIdempotencyRecord() {
        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        ArgumentCaptor<DebitCard> debitCardCaptor = ArgumentCaptor.forClass(DebitCard.class);

        YearMonth now = YearMonth.now(clock);

        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        IssuedCard issuedCard = new IssuedCard(
                "1234",
                now.plusYears(5)
        );

        String requestHash = "a".repeat(64);

        given(requestHasher.hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber)))
                .willReturn(requestHash);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(cardIssuer.issue(now))
                .willReturn(issuedCard);

        given(referenceGenerator.generate())
                .willReturn(cardReference);

        DebitCardIssueResponse response = debitCardService.issueDebitCard(email, accountNumber, idempotencyKey);

        verify(debitCardRepository).save(debitCardCaptor.capture());
        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        DebitCard debitCard = debitCardCaptor.getValue();
        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(debitCard.getFundingAccount())
                .isSameAs(account);

        assertThat(debitCard.getCardReference())
                .isEqualTo(cardReference);

        assertThat(debitCard.getLastFourDigits())
                .isEqualTo(issuedCard.lastFourDigits());

        assertThat(debitCard.getExpirationYearMonth())
                .isEqualTo(issuedCard.expirationYearMonth());

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.INACTIVE);

        assertThat(debitCard.getSpendingLimits())
                .isEqualTo(CardSpendingLimits.createNew(new BigDecimal("25000.00"), new BigDecimal("50000.00")));

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(requestHash);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(cardReference);

        verify(debitCardRepository, times(1)).save(any());
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(1)).generate();
        verify(requestHasher).hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber));
        verify(cardIssuer).issue(now);
    }

    @Test
    void issueDebitCardRejectsInvalidIdempotencyKeyBeforeCallingDependencies() {
        String invalidIdempotencyKey = "11111111111111zzz111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        assertThatThrownBy(() -> debitCardService.issueDebitCard(email, accountNumber, invalidIdempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(accountRepository, debitCardRepository, idempotencyService, referenceGenerator, requestHasher, cardIssuer);
    }

    @Test
    void issueDebitCardThrowsIdempotencyResultNotFoundExceptionWhenReplayResultDoesNotExist() {
        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        String requestHash = "a".repeat(64);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                "2".repeat(32)
        );

        given(requestHasher.hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber)))
                .willReturn(requestHash);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(debitCardRepository.findByCardReference(anyString()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> debitCardService.issueDebitCard(email, accountNumber, idempotencyKey))
                .isInstanceOf(IdempotencyResultNotFoundException.class)
                .hasMessage("Couldn't find debit card with the given reference.");

        verifyNoInteractions(cardIssuer, referenceGenerator);

        verify(debitCardRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
    }
}
