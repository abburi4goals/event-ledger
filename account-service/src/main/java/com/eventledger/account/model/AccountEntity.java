package com.eventledger.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * An account in the Account Service.
 *
 * <p><strong>Lean by design.</strong> It stores only its identity and currency — there is
 * deliberately <em>no</em> stored {@code balance} column. Balance is derived on read from the
 * immutable {@code transactions} ledger ({@code Σ(CREDIT) − Σ(DEBIT)}), which is the single source
 * of truth and avoids read-modify-write drift. Accounts are auto-created on the first transaction.
 */
@Entity
@Table(name = "accounts")
public class AccountEntity extends AuditableEntity {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    /** ISO 4217 currency code, taken from the account's first transaction. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Required by JPA. */
    protected AccountEntity() {
    }

    public AccountEntity(String accountId, String currency) {
        this.accountId = accountId;
        this.currency = currency;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountEntity other)) {
            return false;
        }
        return accountId != null && accountId.equals(other.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }
}
