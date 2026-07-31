package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.support.TestAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DepositWithdrawApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;

    private String token;
    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        token = TestAuth.registerAndLogin(mockMvc, "alice@example.com", "s3cretpassword");
        String body = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"GBP"}"""))
                .andReturn().getResponse().getContentAsString();
        accountId = objectMapper.readTree(body).get("id").asText();
    }

    private void deposit(String amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void depositIncreasesBalance() throws Exception {
        deposit("100.00");

        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .andExpect(jsonPath("$.balance").value(100.0000));
    }

    @Test
    void withdrawBeyondBalanceReturns422NotServerError() throws Exception {
        deposit("100.00");

        mockMvc.perform(post("/api/accounts/" + accountId + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.01}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Insufficient funds"));

        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .andExpect(jsonPath("$.balance").value(100.0000));
    }

    @Test
    void withdrawExactBalanceSucceedsAndLeavesZero() throws Exception {
        deposit("100.00");

        mockMvc.perform(post("/api/accounts/" + accountId + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.00}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(0));
    }

    @Test
    void rejectsNegativeAmountWith400() throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-5.00}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
    }

    @Test
    void rejectsZeroAmountWith400() throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":0}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMoreThanTwoDecimalPlacesWith400() throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10.001}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("amount must have at most 2 decimal places"));
    }

    @Test
    void depositToAnotherUsersAccountReturns403() throws Exception {
        String bobToken = TestAuth.registerAndLogin(mockMvc, "bob@example.com", "s3cretpassword");

        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10.00}"""))
                .andExpect(status().isForbidden());
    }
}
