package com.eventledger.account.repository;

import com.eventledger.account.model.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
}
