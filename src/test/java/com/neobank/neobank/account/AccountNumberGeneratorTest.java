package com.neobank.neobank.account;

import com.neobank.neobank.creditaccount.CreditAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountNumberGeneratorTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @InjectMocks
    private AccountNumberGenerator accountNumberGenerator;

    @Test
    void generateUniqueReturnsFourteenDigitNumberFreeInBothTables() {
        given(accountRepository.existsByAccountNumber(anyString())).willReturn(false);
        given(creditAccountRepository.existsByAccountNumber(anyString())).willReturn(false);

        String accountNumber = accountNumberGenerator.generateUnique();

        assertThat(accountNumber).matches("[0-9]{14}");
        verify(accountRepository).existsByAccountNumber(accountNumber);
        verify(creditAccountRepository).existsByAccountNumber(accountNumber);
    }

    @Test
    void generateUniqueRetriesWhenNumberIsTakenByAnAccount() {
        given(accountRepository.existsByAccountNumber(anyString())).willReturn(true, false);
        given(creditAccountRepository.existsByAccountNumber(anyString())).willReturn(false);

        assertThat(accountNumberGenerator.generateUnique()).matches("[0-9]{14}");

        verify(accountRepository, times(2)).existsByAccountNumber(anyString());
        verify(creditAccountRepository, times(1)).existsByAccountNumber(anyString());
    }

    @Test
    void generateUniqueRetriesWhenNumberIsTakenByACreditAccount() {
        given(accountRepository.existsByAccountNumber(anyString())).willReturn(false);
        given(creditAccountRepository.existsByAccountNumber(anyString())).willReturn(true, false);

        assertThat(accountNumberGenerator.generateUnique()).matches("[0-9]{14}");

        verify(accountRepository, times(2)).existsByAccountNumber(anyString());
        verify(creditAccountRepository, times(2)).existsByAccountNumber(anyString());
    }

    @Test
    void generateUniqueThrowsAfterTenCollisions() {
        given(accountRepository.existsByAccountNumber(anyString())).willReturn(true);

        assertThatThrownBy(() -> accountNumberGenerator.generateUnique())
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to generate unique account number");

        verify(accountRepository, times(10)).existsByAccountNumber(anyString());
    }
}
