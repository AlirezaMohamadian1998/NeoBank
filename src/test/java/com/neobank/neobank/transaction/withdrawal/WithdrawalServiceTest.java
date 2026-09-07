package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private InternalAccountRepository internalAccountRepository;

    @Mock
    private FxRateService fxRateService;

    private final RequestHasher requestHasher = new RequestHasher();

    private WithdrawalService withdrawalService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        withdrawalService = new WithdrawalService(
                bankTransactionRepository,
                accountRepository,
                referenceGenerator,
                ledgerPostingService,
                idempotencyService,
                requestHasher,
                internalAccountRepository,
                fxRateService
        );
    }

    @Test
    void withdrawDebitsOwnedAccountAndSavesCompletedTransactionWithEntry() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "6f3c8a21d9e64b5fa2c17e9084bd6a30";
        String customerEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String internalEntryReference = "5f3c8a21d9e64b5fa2c17e9084bd6a34";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("1000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        internalLedgerAccount.debit(new BigDecimal("10000.00"));

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        String requestHash = hash(request, account, null);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(referenceGenerator.generate())
                .willReturn(transactionReference, customerEntryReference, internalEntryReference);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, account.getCurrency()))
                .willReturn(Optional.of(internalAccount));

        var response = withdrawalService.withdraw(request, accountNumber, email, idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();

        assertThat(savedTransaction.getEntries())
                .hasSize(2);

        LedgerEntry savedCustomerEntry =
                savedTransaction.getEntries()
                        .stream()
                        .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                        .findFirst()
                        .orElseThrow();

        LedgerEntry savedInternalEntry =
                savedTransaction.getEntries()
                        .stream()
                        .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                        .findFirst()
                        .orElseThrow();

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(transactionReference);

        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.WITHDRAWAL);

        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedCustomerEntry.getReference())
                .isEqualTo(customerEntryReference);

        assertThat(savedCustomerEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(savedCustomerEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedCustomerEntry.getLedgerAccount())
                .isSameAs(ledgerAccount);

        assertThat(savedCustomerEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(savedCustomerEntry.getCurrency())
                .isSameAs(account.getCurrency());

        assertThat(savedCustomerEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(savedInternalEntry.getReference())
                .isEqualTo(internalEntryReference);

        assertThat(savedInternalEntry.getBalanceAfter())
                .isEqualByComparingTo(internalAccount.getBalance());

        assertThat(savedInternalEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedInternalEntry.getLedgerAccount())
                .isSameAs(internalLedgerAccount);

        assertThat(savedInternalEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(savedInternalEntry.getCurrency())
                .isSameAs(account.getCurrency());

        assertThat(savedInternalEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedCustomerEntry.getBalanceAfter());

        assertThat(response.amount())
                .isEqualByComparingTo(savedCustomerEntry.getAmount());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());

        assertThat(response.entryReference())
                .isEqualTo(savedCustomerEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(savedTransaction.getTransactionType());

        assertThat(response.currency())
                .isSameAs(savedCustomerEntry.getCurrency());

        assertThat(response.note())
                .isEqualTo(savedTransaction.getNote());

        assertThat(account.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(hash(request, account, null));

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        verify(referenceGenerator, times(3)).generate();

        verify(accountRepository, never()).save(any(Account.class));

        verify(internalAccountRepository, times(1)).findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY);
    }

    @Test
    void withdrawThrowsInsufficientFundsExceptionWhenBalanceIsInsufficient() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        internalLedgerAccount.debit(new BigDecimal("10000.00"));

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        String requestHash = hash(request, account, null);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(referenceGenerator.generate())
                .willReturn("6f3c8a21d9e64b5fa2c17e9084bd6a30", "9f3c8a21d9e64b5fa2c17e9084bd6a33");

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, account.getCurrency()))
                .willReturn(Optional.of(internalAccount));

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(account.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo("10000.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        verify(referenceGenerator, times(2)).generate();

        verifyNoInteractions(bankTransactionRepository);

        verify(accountRepository, never()).save(any(Account.class));

        verify(idempotencyService, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void withdrawThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService, internalAccountRepository);

        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void withdrawThrowsInvalidIdempotencyKeyExceptionWhenIdempotencyKeyInvalid() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111!11111111111111111";

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService, accountRepository, internalAccountRepository);
    }

    @Test
    void withdrawalReturnsOldResponseForExistingIdempotencyKey() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "6f3c8a21d9e64b5fa2c17e9084bd6a30";
        String customerEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String internalEntryReference = "5f3c8a21d9e64b5fa2c17e9084bd6a34";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("1000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        internalLedgerAccount.debit(new BigDecimal("10000.00"));

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        String requestHash = hash(request, account, null);

        BankTransaction transaction = BankTransaction.createNew(
                request.amount(),
                account.getCurrency(),
                TransactionType.WITHDRAWAL,
                transactionReference,
                request.note()
        );

        BigDecimal customerBalanceAfter = ledgerAccount.debit(request.amount());
        BigDecimal internalBalanceAfter = internalLedgerAccount.credit(request.amount());

        transaction.addEntry(
                internalEntryReference,
                request.amount(),
                internalBalanceAfter,
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        transaction.addEntry(
                customerEntryReference,
                request.amount(),
                customerBalanceAfter,
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.complete();

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                transaction
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        WithdrawalResponse response = withdrawalService.withdraw(request, accountNumber, email, idempotencyKey);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(transactionReference);

        assertThat(response.entryReference())
                .isEqualTo(customerEntryReference);

        assertThat(response.transactionType())
                .isSameAs(TransactionType.WITHDRAWAL);

        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.currency())
                .isSameAs(account.getCurrency());

        assertThat(response.note())
                .isEqualTo(request.note());

        verify(bankTransactionRepository, never()).save(any());

        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(referenceGenerator, internalAccountRepository);
    }


    @Test
    void crossCurrencyWithdrawalUsesRateLockAndPostsConvertedAmounts() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "6f3c8a21d9e64b5fa2c17e9084bd6a30";
        String customerEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String internalEntryReference = "5f3c8a21d9e64b5fa2c17e9084bd6a34";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("49000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        internalLedgerAccount.debit(new BigDecimal("10000.00"));

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.USD, lockId);

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-09-05T12:00:00Z"),
                request.requestedCurrency(),
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.00"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.EUR, new BigDecimal("0.85")
                )
        );

        String requestHash = hash(request, account, lockId);

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD))
                .willReturn(Optional.of(internalAccount));

        given(fxRateService.getCachedRate(email, lockId))
                .willReturn(fxResponse);

        given(referenceGenerator.generate())
                .willReturn(transactionReference, customerEntryReference, internalEntryReference);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        WithdrawalResponse response = withdrawalService.withdraw(request, accountNumber, email, idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction transaction = bankTransactionCaptor.getValue();

        assertThat(transaction.getEntries())
                .hasSize(2);

        LedgerEntry customerEntry = transaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry internalEntry = transaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(response.transactionReference())
                .isEqualTo(transaction.getReference());

        assertThat(response.entryReference())
                .isEqualTo(customerEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(transaction.getTransactionType());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(customerEntry.getAmount());

        assertThat(response.currency())
                .isSameAs(customerEntry.getCurrency());

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(customerEntry.getBalanceAfter());

        assertThat(response.note())
                .isEqualTo(transaction.getNote());

        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(transaction.getRequestedCurrency())
                .isSameAs(request.requestedCurrency());

        assertThat(transaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(transaction.getTransactionType())
                .isSameAs(TransactionType.WITHDRAWAL);

        assertThat(transaction.getReference())
                .isEqualTo(transactionReference);

        assertThat(transaction.getNote())
                .isEqualTo(request.note());

        assertThat(transaction.getFxInfo().getLockId())
                .isEqualTo(lockId);

        assertThat(transaction.getFxInfo().getRates())
                .extracting(FxRate::getCurrencyContext, FxRate::getCurrency, FxRate::getRate)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyContext.REQUEST, request.requestedCurrency(), BigDecimal.ONE),
                        tuple(CurrencyContext.DESTINATION, request.requestedCurrency(), BigDecimal.ONE),
                        tuple(CurrencyContext.SOURCE, account.getCurrency(), new BigDecimal("48.00"))
                );

        assertThat(customerEntry.getReference())
                .isEqualTo(customerEntryReference);

        assertThat(customerEntry.getAmount())
                .isEqualByComparingTo(request.amount()
                        .multiply(fxResponse.rates().get(account.getCurrency()))
                        .setScale(2, RoundingMode.HALF_EVEN)
                );

        assertThat(customerEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(customerEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(customerEntry.getCurrency())
                .isSameAs(account.getCurrency());

        assertThat(customerEntry.getLedgerAccount())
                .isSameAs(ledgerAccount);

        assertThat(customerEntry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(internalEntry.getReference())
                .isEqualTo(internalEntryReference);

        assertThat(internalEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(internalEntry.getBalanceAfter())
                .isEqualByComparingTo(internalAccount.getBalance());

        assertThat(internalEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(internalEntry.getCurrency())
                .isSameAs(request.requestedCurrency());

        assertThat(internalEntry.getLedgerAccount())
                .isSameAs(internalLedgerAccount);

        assertThat(internalEntry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("1000.00"));

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("9000.00"));

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(transaction);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(hash(request, account, lockId));

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        verify(fxRateService).getCachedRate(email, lockId);

        verify(referenceGenerator, times(3)).generate();

        verify(accountRepository, never()).save(any(Account.class));

        verify(internalAccountRepository, times(1)).findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD);
    }

    @Test
    void crossCurrencyIdempotentReplayDoesNotReloadRateLock() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "6f3c8a21d9e64b5fa2c17e9084bd6a30";
        String customerEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String internalEntryReference = "5f3c8a21d9e64b5fa2c17e9084bd6a34";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("49000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        internalLedgerAccount.debit(new BigDecimal("10000.00"));

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.USD, lockId);

        String requestHash = hash(request, account, lockId);

        FxInfo fxInfo = FxInfo.createNew(
                lockId,
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                request.requestedCurrency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                request.requestedCurrency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                account.getCurrency(),
                                new BigDecimal("48.00")
                        )
                )
        );

        BankTransaction transaction = BankTransaction.createNew(
                request.amount(),
                request.requestedCurrency(),
                TransactionType.WITHDRAWAL,
                transactionReference,
                request.note()
        );

        transaction.addFxInfo(fxInfo);

        BigDecimal customerAccountBalanceAfter = ledgerAccount.debit(request.amount().multiply(fxInfo.getRate(CurrencyContext.SOURCE)).setScale(2, RoundingMode.HALF_EVEN));
        BigDecimal internalAccountBalanceAfter = internalLedgerAccount.credit(request.amount());

        transaction.addEntry(
                customerEntryReference,
                request.amount().multiply(fxInfo.getRate(CurrencyContext.SOURCE)).setScale(2, RoundingMode.HALF_EVEN),
                customerAccountBalanceAfter,
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.addEntry(
                internalEntryReference,
                request.amount(),
                internalAccountBalanceAfter,
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        transaction.complete();

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                transaction
        );

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        WithdrawalResponse response = withdrawalService.withdraw(request, accountNumber, email, idempotencyKey);

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("1000.00"));

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("9000.00"));

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(transactionReference);

        assertThat(response.entryReference())
                .isEqualTo(customerEntryReference);

        assertThat(response.transactionType())
                .isSameAs(TransactionType.WITHDRAWAL);

        verify(bankTransactionRepository, never()).save(any());

        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(referenceGenerator, internalAccountRepository, fxRateService);
    }

    @Test
    void crossCurrencyWithdrawalWithoutLockIsRejected() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                null
        );

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("Lock ID is required for cross-currency operation.");

        verifyNoInteractions(bankTransactionRepository, internalAccountRepository, referenceGenerator, fxRateService, idempotencyService);
    }

    @Test
    void sameCurrencyWithdrawalWithLockIsRejected() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                lockId
        );

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Lock ID is not required for same currency operation.");

        verifyNoInteractions(bankTransactionRepository, internalAccountRepository, referenceGenerator, fxRateService, idempotencyService);
    }

    @Test
    void crossCurrencyWithdrawalRejectsLockWithDifferentBaseCurrency() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "6f3c8a21d9e64b5fa2c17e9084bd6a30";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("49000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        internalLedgerAccount.debit(new BigDecimal("10000.00"));

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.USD, lockId);

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-09-05T12:00:00Z"),
                CurrencyCode.EUR,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.00"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.EUR, new BigDecimal("0.85")
                )
        );

        String requestHash = hash(request, account, lockId);

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD))
                .willReturn(Optional.of(internalAccount));

        given(fxRateService.getCachedRate(email, lockId))
                .willReturn(fxResponse);

        given(referenceGenerator.generate())
                .willReturn(transactionReference);

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("The base currency in the cached fx rate must be the same as the requested currency");

        verifyNoInteractions(bankTransactionRepository);
    }


    private String hash(WithdrawalRequest request, Account account, String lockId) {
        return requestHasher.hashRequest(String.join(
                "|",
                TransactionType.WITHDRAWAL.name(),
                account.getAccountNumber(),
                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                request.requestedCurrency().name(),
                account.getCurrency().name(),
                lockId == null ? "" : lockId,
                request.note().trim()
        ));
    }
}
