package com.eventledger.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionView;
import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.service.AccountService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    // T-5: Missing required field returns 400 with field error
    @Test
    void postTransaction_missingEventId_returns400() throws Exception {
        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("eventId"))
                .andExpect(jsonPath("$.errors[0].message").value("eventId is required"));
    }

    // T-6: amount = 0 → 400
    @Test
    void postTransaction_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "type": "CREDIT",
                                  "amount": 0,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    // T-7: Unknown type → 400 field error on "type"
    @Test
    void postTransaction_unknownType_returns400() throws Exception {
        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "type": "TRANSFER",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value("type must be CREDIT or DEBIT"));
    }

    @Test
    void postTransaction_valid_returns201() throws Exception {
        BalanceResponse balance = new BalanceResponse("acct-001", new BigDecimal("100.00"), "USD");
        when(accountService.applyTransaction(eq("acct-001"), any()))
                .thenReturn(new AccountService.ApplyResult(balance, true));

        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("acct-001"))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void postTransaction_duplicate_returns200() throws Exception {
        BalanceResponse balance = new BalanceResponse("acct-001", new BigDecimal("100.00"), "USD");
        when(accountService.applyTransaction(eq("acct-001"), any()))
                .thenReturn(new AccountService.ApplyResult(balance, false));

        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void getBalance_unknownAccount_returns404() throws Exception {
        when(accountService.getBalance("unknown"))
                .thenThrow(new AccountNotFoundException("unknown"));

        mockMvc.perform(get("/accounts/unknown/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Account not found: unknown"));
    }

    @Test
    void getAccount_returns200WithTransactions() throws Exception {
        TransactionView view = new TransactionView("evt-001", TransactionType.CREDIT,
                new BigDecimal("100.00"), OffsetDateTime.now());
        AccountResponse resp = new AccountResponse("acct-001", new BigDecimal("100.00"), "USD", List.of(view));
        when(accountService.getAccount("acct-001")).thenReturn(resp);

        mockMvc.perform(get("/accounts/acct-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-001"))
                .andExpect(jsonPath("$.balance").value(100.00))
                .andExpect(jsonPath("$.transactions[0].eventId").value("evt-001"));
    }

    // Covers GlobalExceptionHandler OffsetDateTime deserialization branch
    @Test
    void postTransaction_badTimestamp_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "not-a-date"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("eventTimestamp"));
    }

    // Covers GlobalExceptionHandler catch-all (handleUnexpected)
    @Test
    void postTransaction_serviceThrowsUnexpected_returns500() throws Exception {
        when(accountService.applyTransaction(any(), any())).thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }
}
