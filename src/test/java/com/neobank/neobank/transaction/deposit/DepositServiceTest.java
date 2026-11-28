package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyResultNotFoundException;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.neobank.neobank.support.TestFixtures.currentAccount;
import static com.neobank.neobank.support.TestFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private InternalAccountRepository internalAccountRepository;

    @Mock
    private FxRateService fxRateService;

    private final RequestHasher requestHasher = new RequestHasher();

    private DepositService depositService;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        depositService = new DepositService(
                accountRepository,
                bankTransactionRepository,
                referenceGenerator,
                ledgerPostingService,
                requestHasher,
                idempotencyService,
                internalAccountRepository,
                fxRateService
        );
    }

    @Test
    void depositThrowsInvalidIdempotencyKeyExceptionForInvalidIdempotencyKey() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "1111111111111111!111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                null
        );

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(referenceGenerator, bankTransactionRepository, idempotencyService, accountRepository, internalAccountRepository);
    }

    @Test
    void crossCurrencyIdempotentReplayDoesNotReloadRateLock() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String internalEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String customerEntryReference = "8f3c8a21d9e64b5fa2c17e9084bd6a32";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                lockId
        );

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        String requestHash = requestHasher
                .hashRequest(String
                        .join(
                                "|",
                                TransactionType.DEPOSIT.name(),
                                accountNumber,
                                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                request.requestedCurrency().name(),
                                account.getCurrency().name(),
                                lockId,
                                request.note().trim()
                        )
                );

        FxInfo fxInfo = FxInfo.createNew(
                lockId,
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                request.requestedCurrency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                request.requestedCurrency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                account.getCurrency(),
                                new BigDecimal("48.14")
                        )
                )
        );

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                request.requestedCurrency(),
                TransactionType.DEPOSIT,
                transactionReference,
                request.note()
        );

        bankTransaction.addFxInfo(fxInfo);

        BigDecimal balanceAfter = account.getLedgerAccount().credit(request.amount().multiply(fxInfo.getRate(CurrencyContext.DESTINATION)).setScale(2, RoundingMode.HALF_EVEN));
        BigDecimal internalAccountBalanceAfter = internalLedgerAccount.debit(request.amount());

        bankTransaction.addEntry(
                internalEntryReference,
                request.amount(),
                internalAccountBalanceAfter,
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        bankTransaction.addEntry(
                customerEntryReference,
                request.amount().multiply(fxInfo.getRate(CurrencyContext.DESTINATION)).setScale(2, RoundingMode.HALF_EVEN),
                balanceAfter,
                EntryDirection.CREDIT,
                account.getLedgerAccount()
        );

        bankTransaction.complete();

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                bankTransaction.getReference()
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(bankTransactionRepository.findByReference(idempotencyRecord.getResultReference()))
                .willReturn(Optional.of(bankTransaction));

        ReflectionTestUtils.setField(account.getLedgerAccount(), "id", 1L);

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(transactionReference);

        assertThat(response.entryReference())
                .isEqualTo(customerEntryReference);

        assertThat(response.transactionType())
                .isSameAs(TransactionType.DEPOSIT);

        verify(bankTransactionRepository, never()).save(any());

        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(referenceGenerator, internalAccountRepository, fxRateService);
    }

    @Test
    void crossCurrencyDepositWithoutLockIsRejected() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                null
        );

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("Lock ID is required for cross-currency operation.");

        verifyNoInteractions(bankTransactionRepository, internalAccountRepository, referenceGenerator, fxRateService, idempotencyService);
    }

    @Test
    void sameCurrencyDepositWithLockIsRejected() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                lockId
        );

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessage("Lock ID is not required for same currency operation.");

        verifyNoInteractions(bankTransactionRepository, internalAccountRepository, referenceGenerator, fxRateService, idempotencyService);
    }

    @Test
    void crossCurrencyDepositRejectsLockWithDifferentBaseCurrency() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                lockId
        );

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-08-30T12:00:00Z"),
                CurrencyCode.EUR,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.14"),
                        CurrencyCode.GBP, new BigDecimal("0.74"),
                        CurrencyCode.EUR, new BigDecimal("0.86")
                )
        );

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.DEPOSIT.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.requestedCurrency().name(),
                        account.getCurrency().name(),
                        lockId,
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, request.requestedCurrency()))
                .willReturn(Optional.of(internalAccount));

        given(referenceGenerator.generate())
                .willReturn(transactionReference);

        given(fxRateService.getCachedRate(email, lockId))
                .willReturn(fxResponse);

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("The base currency in the cached fx rate must be the same as the requested currency");

        verifyNoInteractions(bankTransactionRepository);
    }

    @Test
    void depositThrowsIdempotencyResultNotFoundExceptionWhenResultDoesNotExist() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                null
        );

        Customer customer = customer(email);

        Account account = currentAccount(accountNumber, "8f3c8a21d9e64b5fa2c17e9084bd6a32", customer, CurrencyCode.TRY);

        String requestHash = requestHasher
                .hashRequest(String
                        .join(
                                "|",
                                TransactionType.DEPOSIT.name(),
                                accountNumber,
                                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                request.requestedCurrency().name(),
                                account.getCurrency().name(),
                                "",
                                request.note().trim()
                        )
                );

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                transactionReference
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(bankTransactionRepository.findByReference(transactionReference))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(IdempotencyResultNotFoundException.class)
                .hasMessage("Idempotency result not found");

        verify(bankTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator, internalAccountRepository, fxRateService);
    }
}
