package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.CreditCardPayoutRequest;
import com.neobank.neobank.card.dto.CreditCardPayoutResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCreditCardPayoutException;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditAccountClosedException;
import com.neobank.neobank.creditaccount.NothingToPayOutException;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CreditCardBalanceServicePayoutTest {

    private static final String EMAIL = "customer@example.com";

    private static final String CARD_REFERENCE = "2".repeat(32);

    private static final String DESTINATION_ACCOUNT_NUMBER = "12345678900987";

    private static final String IDEMPOTENCY_KEY = "1".repeat(32);

    private static final String LOCK_ID = "3f2b8c1e-7a4d-4e9b-9c2f-1d5e6a7b8c9d";

    @Mock
    private CreditCardRepository creditCardRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private FxRateService fxRateService;

    private final RequestHasher requestHasher = new RequestHasher();

    private final Customer customer = Customer.createNew(EMAIL, "{bcrypt}encoded-password", "Ada Lovelace");

    private CreditCardBalanceService creditCardBalanceService;

    @BeforeEach
    void setUp() {
        creditCardBalanceService = new CreditCardBalanceService(
                creditCardRepository,
                accountRepository,
                bankTransactionRepository,
                new LedgerPostingService(referenceGenerator),
                idempotencyService,
                requestHasher,
                referenceGenerator,
                fxRateService
        );
    }

    @Test
    void payOutMovesTheWholeCreditBalanceToTheDestinationAccount() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "-150.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");
        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(referenceGenerator.generate()).willReturn("a".repeat(32), "b".repeat(32), "c".repeat(32));
        given(bankTransactionRepository.save(any(BankTransaction.class))).willAnswer(invocation -> invocation.getArgument(0));

        CreditCardPayoutResponse response =
                creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL);

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        assertThat(response.transactionReference()).isEqualTo("a".repeat(32));
        assertThat(response.cardReference()).isEqualTo(CARD_REFERENCE);
        assertThat(response.creditAccountNumber()).isEqualTo("55".repeat(7));
        assertThat(response.destinationAccountNumber()).isEqualTo(DESTINATION_ACCOUNT_NUMBER);
        assertThat(response.debitedAmount()).isEqualByComparingTo("150.00");
        assertThat(response.debitedCurrency()).isEqualTo(CurrencyCode.TRY);
        assertThat(response.creditedAmount()).isEqualByComparingTo("150.00");
        assertThat(response.creditedCurrency()).isEqualTo(CurrencyCode.TRY);
        assertThat(response.destinationAccountBalanceAfter()).isEqualByComparingTo("1150.00");
        assertThat(response.creditCardBalanceAfter()).isEqualByComparingTo("0.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("1150.00");
        assertThat(creditCard.getCreditAccount().getLedgerAccount().getBalance()).isEqualByComparingTo("0.00");
        assertThat(idempotencyRecordCaptor.getValue().getResultReference()).isEqualTo("a".repeat(32));
    }

    @Test
    void payOutReplaysExistingTransactionWithoutPostingAgain() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "-150.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");
        BankTransaction existingTransaction = BankTransaction.createNew(
                new BigDecimal("150.00"),
                CurrencyCode.TRY,
                TransactionType.CREDIT_CARD_PAYOUT,
                "a".repeat(32),
                null
        );

        given(referenceGenerator.generate()).willReturn("b".repeat(32), "c".repeat(32));
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        ledgerPostingService.post(existingTransaction, creditCard.getCreditAccount().getLedgerAccount(), EntryDirection.DEBIT, new BigDecimal("150.00"));
        ledgerPostingService.post(existingTransaction, destinationAccount.getLedgerAccount(), EntryDirection.CREDIT, new BigDecimal("150.00"));
        existingTransaction.complete();

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any()))
                .willReturn(Optional.of(IdempotencyRecord.createNew(IDEMPOTENCY_KEY, "f".repeat(64), customer, "a".repeat(32))));
        given(bankTransactionRepository.findByReference("a".repeat(32))).willReturn(Optional.of(existingTransaction));

        CreditCardPayoutResponse response =
                creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL);

        assertThat(response.transactionReference()).isEqualTo("a".repeat(32));
        assertThat(response.creditCardBalanceAfter()).isEqualByComparingTo("0.00");
        assertThat(response.destinationAccountBalanceAfter()).isEqualByComparingTo("1150.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("1150.00");
        verify(bankTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
    }

    @Test
    void payOutRejectsInvalidIdempotencyKey() {
        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), "not-a-key", EMAIL))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Idempotency key is invalid");

        verifyNoInteractions(creditCardRepository, accountRepository, idempotencyService);
    }

    @Test
    void payOutRejectsCardOfAnotherCustomer() {
        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(CARD_REFERENCE, EMAIL))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(CreditCardNotFoundException.class)
                .hasMessage("Credit card not found");

        verifyNoInteractions(accountRepository, idempotencyService);
    }

    @Test
    void payOutRejectsDestinationAccountOfAnotherCustomer() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "-150.00");

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(CARD_REFERENCE, EMAIL))
                .willReturn(Optional.of(creditCard));
        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(DESTINATION_ACCOUNT_NUMBER, EMAIL))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(idempotencyService);
    }

    @Test
    void payOutRejectsSavingsAccount() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "-150.00");
        Account destinationAccount = createDestinationAccount(AccountType.SAVINGS, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(InvalidCreditCardPayoutException.class)
                .hasMessage("Credit balances can only be paid out to a current account");

        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void payOutRejectsClosedCreditAccount() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "0.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");
        creditCard.getCreditAccount().close(destinationAccount);

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(CreditAccountClosedException.class)
                .hasMessage("Credit account is closed");

        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void payOutRejectsCardWithDebt() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "600.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(NothingToPayOutException.class)
                .hasMessage("There is no credit balance to pay out");

        assertThat(creditCard.getCreditAccount().getLedgerAccount().getBalance()).isEqualByComparingTo("600.00");
        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void payOutRejectsCardAtZeroBalance() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "0.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(NothingToPayOutException.class)
                .hasMessage("There is no credit balance to pay out");

        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void payOutToAnotherCurrencyCreditsConvertedAmountUsingTheLockedRate() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.USD, "-100.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(fxRateService.getCachedRate(EMAIL, LOCK_ID)).willReturn(rateLock(CurrencyCode.USD));
        given(referenceGenerator.generate()).willReturn("a".repeat(32), "b".repeat(32), "c".repeat(32));
        given(bankTransactionRepository.save(any(BankTransaction.class))).willAnswer(invocation -> invocation.getArgument(0));

        CreditCardPayoutResponse response =
                creditCardBalanceService.payOut(CARD_REFERENCE, request(LOCK_ID), IDEMPOTENCY_KEY, EMAIL);

        assertThat(response.debitedAmount()).isEqualByComparingTo("100.00");
        assertThat(response.debitedCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(response.creditedAmount()).isEqualByComparingTo("3418.30");
        assertThat(response.creditedCurrency()).isEqualTo(CurrencyCode.TRY);
        assertThat(response.destinationAccountBalanceAfter()).isEqualByComparingTo("4418.30");
        assertThat(response.creditCardBalanceAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void payOutToAnotherCurrencyRequiresALock() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.USD, "-100.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(null), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("Lock ID is required for cross-currency payout");

        verifyNoInteractions(idempotencyService, fxRateService);
    }

    @Test
    void payOutInSameCurrencyRejectsALock() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.TRY, "-150.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(LOCK_ID), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(InvalidCreditCardPayoutException.class)
                .hasMessage("Lock ID is not required for same currency payout");

        verifyNoInteractions(idempotencyService, fxRateService);
    }

    @Test
    void payOutRejectsLockWithAnotherBaseCurrency() {
        CreditCard creditCard = createCreditCardWithBalance(CurrencyCode.USD, "-100.00");
        Account destinationAccount = createDestinationAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndDestinationAccount(creditCard, destinationAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(fxRateService.getCachedRate(EMAIL, LOCK_ID)).willReturn(rateLock(CurrencyCode.TRY));
        given(referenceGenerator.generate()).willReturn("a".repeat(32));

        assertThatThrownBy(() -> creditCardBalanceService.payOut(CARD_REFERENCE, request(LOCK_ID), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("The base currency in the cached fx rate must be the same as the credit card currency");

        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("1000.00");
    }

    private void givenCardAndDestinationAccount(CreditCard creditCard, Account destinationAccount) {
        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(CARD_REFERENCE, EMAIL))
                .willReturn(Optional.of(creditCard));
        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(DESTINATION_ACCOUNT_NUMBER, EMAIL))
                .willReturn(Optional.of(destinationAccount));
    }

    private CreditCard createCreditCardWithBalance(CurrencyCode currency, String balance) {
        CreditAccount creditAccount = CreditAccount.createNew(
                "55".repeat(7),
                "5".repeat(32),
                customer,
                currency,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        );

        BigDecimal amount = new BigDecimal(balance);

        if(amount.signum() > 0) {
            creditAccount.getLedgerAccount().debit(amount);
        } else if(amount.signum() < 0) {
            creditAccount.getLedgerAccount().credit(amount.negate());
        }

        YearMonth now = YearMonth.of(2026, 9);

        CreditCard creditCard = CreditCard.createNew(
                CARD_REFERENCE,
                "5678",
                now.plusYears(5),
                now,
                creditAccount,
                TestCardLimits.generous()
        );

        creditCard.activateCard(now);

        return creditCard;
    }

    private Account createDestinationAccount(AccountType type, CurrencyCode currency, String balance) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew("7".repeat(32), LedgerAccountType.LIABILITY, currency);
        ledgerAccount.credit(new BigDecimal(balance));

        return Account.createNew(DESTINATION_ACCOUNT_NUMBER, "Main", type, customer, ledgerAccount);
    }

    private static CreditCardPayoutRequest request(String lockId) {
        return new CreditCardPayoutRequest(DESTINATION_ACCOUNT_NUMBER, lockId);
    }

    private static FxRateLockResponse rateLock(CurrencyCode baseCurrency) {
        return new FxRateLockResponse(
                LOCK_ID,
                Instant.parse("2026-09-23T12:05:00Z"),
                baseCurrency,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("34.18300000")
                )
        );
    }
}
