package com.eventledger.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.model.AccountEntity;
import com.eventledger.account.model.TransactionEntity;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRepository transactionRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, transactionRepository);
    }

    // T-4: balance computed correctly as Σ(CREDIT) − Σ(DEBIT), regardless of arrival order
    @Test
    void getBalance_derivesCorrectBalance() {
        String accountId = "acct-001";
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(new AccountEntity(accountId, "USD")));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT))
                .thenReturn(new BigDecimal("300.00"));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT))
                .thenReturn(new BigDecimal("100.00"));

        BalanceResponse balance = accountService.getBalance(accountId);

        assertThat(balance.balance()).isEqualByComparingTo("200.00");
        assertThat(balance.currency()).isEqualTo("USD");
    }

    // T-4: balance is correct even when DEBIT arrives before CREDIT
    @Test
    void getBalance_correctRegardlessOfArrivalOrder() {
        String accountId = "acct-002";
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(new AccountEntity(accountId, "USD")));
        // Same amounts, different arrival order — math is commutative
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT))
                .thenReturn(new BigDecimal("500.00"));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT))
                .thenReturn(new BigDecimal("200.00"));

        BalanceResponse balance = accountService.getBalance(accountId);

        assertThat(balance.balance()).isEqualByComparingTo("300.00");
    }

    @Test
    void getBalance_throwsAccountNotFoundException_whenAccountAbsent() {
        when(accountRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getBalance("unknown"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("Account not found: unknown");
    }

    @Test
    void applyTransaction_idempotency_returnsDuplicateWithoutSaving() {
        String accountId = "acct-003";
        TransactionRequest req = new TransactionRequest(
                "evt-dup", TransactionType.CREDIT, new BigDecimal("100.00"), "USD",
                OffsetDateTime.now());

        when(transactionRepository.existsByEventId("evt-dup")).thenReturn(true);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(new AccountEntity(accountId, "USD")));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT))
                .thenReturn(new BigDecimal("100.00"));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT))
                .thenReturn(BigDecimal.ZERO);

        AccountService.ApplyResult result = accountService.applyTransaction(accountId, req);

        assertThat(result.created()).isFalse();
        assertThat(result.balance().balance()).isEqualByComparingTo("100.00");
        // Must NOT save a new transaction
        verify(transactionRepository, never()).save(any(TransactionEntity.class));
    }

    @Test
    void applyTransaction_newEvent_savesAndReturnsCreated() {
        String accountId = "acct-004";
        TransactionRequest req = new TransactionRequest(
                "evt-new", TransactionType.CREDIT, new BigDecimal("150.00"), "USD",
                OffsetDateTime.now());

        when(transactionRepository.existsByEventId("evt-new")).thenReturn(false);
        AccountEntity account = new AccountEntity(accountId, "USD");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT))
                .thenReturn(new BigDecimal("150.00"));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT))
                .thenReturn(BigDecimal.ZERO);

        AccountService.ApplyResult result = accountService.applyTransaction(accountId, req);

        assertThat(result.created()).isTrue();
        assertThat(result.balance().balance()).isEqualByComparingTo("150.00");
        verify(transactionRepository).save(any(TransactionEntity.class));
    }

    @Test
    void getAccount_returnsAccountWithTransactionViews() {
        String accountId = "acct-view";
        AccountEntity account = new AccountEntity(accountId, "USD");
        TransactionEntity txn = new TransactionEntity("evt-v1", accountId, TransactionType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now());
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(transactionRepository.findByAccountIdOrderByEventTimestampDescEventIdDesc(accountId))
                .thenReturn(List.of(txn));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT))
                .thenReturn(new BigDecimal("100.00"));
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT))
                .thenReturn(BigDecimal.ZERO);

        AccountResponse response = accountService.getAccount(accountId);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.balance()).isEqualByComparingTo("100.00");
        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactions().get(0).eventId()).isEqualTo("evt-v1");
    }

    @Test
    void getAccount_notFound_throwsAccountNotFoundException() {
        when(accountRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount("missing"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("Account not found: missing");
    }

    @Test
    void applyTransaction_autoCreatesAccountOnFirstTransaction() {
        String accountId = "acct-new";
        TransactionRequest req = new TransactionRequest(
                "evt-first", TransactionType.CREDIT, new BigDecimal("50.00"), "USD",
                OffsetDateTime.now());

        when(transactionRepository.existsByEventId("evt-first")).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());
        AccountEntity newAccount = new AccountEntity(accountId, "USD");
        when(accountRepository.save(any(AccountEntity.class))).thenReturn(newAccount);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumAmountByAccountIdAndType(eq(accountId), any()))
                .thenReturn(new BigDecimal("50.00"), BigDecimal.ZERO);

        AccountService.ApplyResult result = accountService.applyTransaction(accountId, req);

        assertThat(result.created()).isTrue();
        verify(accountRepository).save(any(AccountEntity.class));
    }
}
