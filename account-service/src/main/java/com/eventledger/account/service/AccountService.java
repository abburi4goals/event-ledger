package com.eventledger.account.service;

import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.dto.TransactionView;
import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.model.AccountEntity;
import com.eventledger.account.model.TransactionEntity;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Apply a transaction to an account. Returns the current balance.
     * The boolean flag in the result indicates whether the transaction was new (true) or a
     * duplicate (false), so the controller can return 201 vs 200 accordingly.
     */
    @Transactional
    public ApplyResult applyTransaction(String accountId, TransactionRequest req) {
        log.info("Applying transaction eventId={} accountId={} type={}", req.eventId(), accountId, req.type());

        // Idempotency check first — if this eventId was already processed, return current balance
        if (transactionRepository.existsByEventId(req.eventId())) {
            log.info("Duplicate transaction detected eventId={} — returning current balance", req.eventId());
            BigDecimal balance = deriveBalance(accountId);
            AccountEntity account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            return new ApplyResult(new BalanceResponse(accountId, balance, account.getCurrency()), false);
        }

        // Auto-create account on first transaction
        AccountEntity account = accountRepository.findById(accountId)
                .orElseGet(() -> {
                    log.info("Auto-creating account accountId={}", accountId);
                    return accountRepository.save(new AccountEntity(accountId, req.currency()));
                });

        // Save the transaction
        TransactionEntity txn = new TransactionEntity(
                req.eventId(), accountId, req.type(),
                req.amount(), req.currency(), req.eventTimestamp());
        transactionRepository.save(txn);
        log.info("Transaction saved eventId={} accountId={}", req.eventId(), accountId);

        BigDecimal balance = deriveBalance(accountId);
        return new ApplyResult(new BalanceResponse(accountId, balance, account.getCurrency()), true);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        log.info("Getting balance accountId={}", accountId);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return new BalanceResponse(accountId, deriveBalance(accountId), account.getCurrency());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        log.info("Getting account details accountId={}", accountId);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        List<TransactionView> views = transactionRepository
                .findByAccountIdOrderByEventTimestampDescEventIdDesc(accountId)
                .stream()
                .map(t -> new TransactionView(t.getEventId(), t.getType(), t.getAmount(), t.getEventTimestamp()))
                .toList();
        return new AccountResponse(accountId, deriveBalance(accountId), account.getCurrency(), views);
    }

    private BigDecimal deriveBalance(String accountId) {
        BigDecimal credits = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT);
        BigDecimal debits  = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT);
        return credits.subtract(debits);
    }

    public record ApplyResult(BalanceResponse balance, boolean created) {}
}
