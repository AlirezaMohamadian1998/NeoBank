package com.neobank.neobank.shared.exception;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.card.CardExpiredException;
import com.neobank.neobank.card.CardNotFoundException;
import com.neobank.neobank.card.InvalidCardStatusTransitionException;
import com.neobank.neobank.card.UnsupportedCardStatusException;
import com.neobank.neobank.card.credit.CreditCardNotFoundException;
import com.neobank.neobank.card.credit.InvalidCreditCardClosingException;
import com.neobank.neobank.card.credit.InvalidCreditCardPayoutException;
import com.neobank.neobank.card.credit.InvalidCreditCardRepaymentException;
import com.neobank.neobank.card.credit.PayoffQuoteExpiredException;
import com.neobank.neobank.card.debit.DebitCardNotFoundException;
import com.neobank.neobank.card.limit.CardDailySpendRaceException;
import com.neobank.neobank.card.limit.CardLimitExceededException;
import com.neobank.neobank.card.limit.CardSpendingLimitsNotChangeableException;
import com.neobank.neobank.card.limit.InvalidCardLimitException;
import com.neobank.neobank.card.payment.CardNotActiveException;
import com.neobank.neobank.card.payment.CardPurchaseNotFoundException;
import com.neobank.neobank.card.payment.CardPurchaseNotRefundableException;
import com.neobank.neobank.card.payment.InvalidCardPaymentException;
import com.neobank.neobank.creditaccount.CreditAccountClosedException;
import com.neobank.neobank.creditaccount.CreditLimitExceededException;
import com.neobank.neobank.creditaccount.CreditStatementNotFoundException;
import com.neobank.neobank.creditaccount.NothingToPayOutException;
import com.neobank.neobank.creditaccount.RepaymentExceedsBalanceException;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.EmailAlreadyRegisteredException;
import com.neobank.neobank.fx.FxProviderUnavailableException;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.idempotency.IdempotencyConflictException;
import com.neobank.neobank.idempotency.IdempotencyKeyRaceException;
import com.neobank.neobank.idempotency.IdempotencyResultNotFoundException;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.transaction.deposit.InvalidDepositException;
import com.neobank.neobank.transaction.history.InvalidHistorySortException;
import com.neobank.neobank.transaction.transfer.InvalidTransferException;
import com.neobank.neobank.transaction.withdrawal.InvalidWithdrawalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private final ThrowingController controller = new ThrowingController();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    static Stream<Arguments> apiExceptions() {
        return Stream.of(
                Arguments.of(new AccountNotFoundException("detail"), HttpStatus.NOT_FOUND, "Account not found"),
                Arguments.of(new CardExpiredException("detail"), HttpStatus.CONFLICT, "Card expired"),
                Arguments.of(new CardNotFoundException("detail"), HttpStatus.NOT_FOUND, "Card not found"),
                Arguments.of(new InvalidCardStatusTransitionException("detail"), HttpStatus.CONFLICT, "Invalid card status transition"),
                Arguments.of(new UnsupportedCardStatusException("detail"), HttpStatus.BAD_REQUEST, "Unsupported card status"),
                Arguments.of(new CreditCardNotFoundException("detail"), HttpStatus.NOT_FOUND, "Credit card not found"),
                Arguments.of(new InvalidCreditCardClosingException("detail"), HttpStatus.BAD_REQUEST, "Invalid credit card closing"),
                Arguments.of(new InvalidCreditCardPayoutException("detail"), HttpStatus.BAD_REQUEST, "Invalid credit card payout"),
                Arguments.of(new InvalidCreditCardRepaymentException("detail"), HttpStatus.BAD_REQUEST, "Invalid credit card repayment"),
                Arguments.of(new PayoffQuoteExpiredException("detail"), HttpStatus.CONFLICT, "Payoff quote expired"),
                Arguments.of(new DebitCardNotFoundException("detail"), HttpStatus.NOT_FOUND, "Debit card not found"),
                Arguments.of(new CardLimitExceededException("detail"), HttpStatus.CONFLICT, "Card limit exceeded"),
                Arguments.of(new CardSpendingLimitsNotChangeableException("detail"), HttpStatus.CONFLICT, "Card limits not changeable"),
                Arguments.of(new InvalidCardLimitException("detail"), HttpStatus.BAD_REQUEST, "Invalid card limit"),
                Arguments.of(new CardNotActiveException("detail"), HttpStatus.CONFLICT, "Card not active"),
                Arguments.of(new CardPurchaseNotFoundException("detail"), HttpStatus.NOT_FOUND, "Card purchase not found"),
                Arguments.of(new CardPurchaseNotRefundableException("detail"), HttpStatus.CONFLICT, "Card purchase not refundable"),
                Arguments.of(new InvalidCardPaymentException("detail"), HttpStatus.BAD_REQUEST, "Invalid card payment"),
                Arguments.of(new CreditAccountClosedException("detail"), HttpStatus.CONFLICT, "Credit account closed"),
                Arguments.of(new CreditLimitExceededException("detail"), HttpStatus.CONFLICT, "Credit limit exceeded"),
                Arguments.of(new CreditStatementNotFoundException("detail"), HttpStatus.NOT_FOUND, "Credit statement not found"),
                Arguments.of(new NothingToPayOutException("detail"), HttpStatus.CONFLICT, "Nothing to pay out"),
                Arguments.of(new RepaymentExceedsBalanceException("detail"), HttpStatus.CONFLICT, "Repayment exceeds balance"),
                Arguments.of(new CustomerNotFoundException("detail"), HttpStatus.NOT_FOUND, "Customer not found"),
                Arguments.of(new EmailAlreadyRegisteredException("detail"), HttpStatus.CONFLICT, "Email already registered"),
                Arguments.of(new FxProviderUnavailableException("detail"), HttpStatus.SERVICE_UNAVAILABLE, "Fx provider unavailable"),
                Arguments.of(new FxRateLockUnavailableException("detail"), HttpStatus.CONFLICT, "Fx rate lock unavailable"),
                Arguments.of(new IdempotencyConflictException("detail"), HttpStatus.CONFLICT, "Idempotency Conflict"),
                Arguments.of(new IdempotencyResultNotFoundException("detail"), HttpStatus.INTERNAL_SERVER_ERROR, "Idempotency result not found"),
                Arguments.of(new InvalidIdempotencyKeyException("detail"), HttpStatus.BAD_REQUEST, "Invalid idempotency key"),
                Arguments.of(new InsufficientFundsException(), HttpStatus.CONFLICT, "Insufficient funds"),
                Arguments.of(new InvalidDepositException("detail"), HttpStatus.BAD_REQUEST, "Invalid deposit"),
                Arguments.of(new InvalidHistorySortException("detail"), HttpStatus.BAD_REQUEST, "Invalid history sort"),
                Arguments.of(new InvalidTransferException("detail"), HttpStatus.BAD_REQUEST, "Invalid transfer"),
                Arguments.of(new InvalidWithdrawalException("detail"), HttpStatus.BAD_REQUEST, "Invalid withdrawal")
        );
    }

    @ParameterizedTest
    @MethodSource("apiExceptions")
    void apiExceptionReturnsItsStatusAndTitle(ApiException exception, HttpStatus status, String title) throws Exception {
        controller.toThrow = exception;

        mockMvc.perform(get("/test/errors"))
                .andExpect(status().is(status.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.detail").value(exception.getMessage()))
                .andExpect(jsonPath("$.instance").value("/test/errors"));
    }

    @Test
    void everyApiExceptionHasAnEntryInTheTable() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ApiException.class));

        Set<String> apiExceptions = scanner.findCandidateComponents("com.neobank.neobank").stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        Set<String> inTable = apiExceptions()
                .map(arguments -> arguments.get()[0].getClass().getName())
                .collect(Collectors.toSet());

        assertThat(inTable).isEqualTo(apiExceptions);
    }

    @Test
    void authenticationExceptionReturnsUnauthorized() throws Exception {
        controller.toThrow = new BadCredentialsException("Bad credentials");

        mockMvc.perform(get("/test/errors"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Authentication failed"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andExpect(jsonPath("$.instance").value("/test/errors"));
    }

    static Stream<Arguments> retryableExceptions() {
        return Stream.of(
                Arguments.of(
                        new IdempotencyKeyRaceException(new RuntimeException()),
                        "Idempotency request in progress",
                        "The idempotency key is currently being processed. Retry the same request using the same key."
                ),
                Arguments.of(
                        new ConcurrencyFailureException("Optimistic lock failed"),
                        "Temporary concurrency failure",
                        "The request could not be completed because of a temporary database concurrency conflict. Please retry."
                ),
                Arguments.of(
                        new CardDailySpendRaceException(new RuntimeException()),
                        "Card purchase in progress",
                        "Another purchase on this card is being processed. Retry the same request using the same key."
                )
        );
    }

    @ParameterizedTest
    @MethodSource("retryableExceptions")
    void retryableExceptionReturnsServiceUnavailableWithRetryAfter(
            RuntimeException exception,
            String title,
            String detail
    ) throws Exception {
        controller.toThrow = exception;

        mockMvc.perform(get("/test/errors"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value("/test/errors"));
    }

    @RestController
    static class ThrowingController {
        private RuntimeException toThrow;

        @GetMapping("/test/errors")
        void fail() {
            throw toThrow;
        }
    }
}
