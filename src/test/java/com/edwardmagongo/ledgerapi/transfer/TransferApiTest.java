package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.support.TestAuth;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;

    private String aliceToken;
    private String bobToken;
    private String aliceAccount;
    private String bobAccount;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceToken = TestAuth.registerAndLogin(mockMvc, "alice@example.com", "s3cretpassword");
        bobToken = TestAuth.registerAndLogin(mockMvc, "bob@example.com", "s3cretpassword");
        aliceAccount = createAccount(aliceToken, "GBP");
        bobAccount = createAccount(bobToken, "GBP");
        deposit(aliceToken, aliceAccount, "500.00");
    }

    private String createAccount(String token, String currency) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void deposit(String token, String accountId, String amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions transfer(String token, String from,
                                                                        String to, String amount) throws Exception {
        return mockMvc.perform(post("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId":"%s","toAccountId":"%s","amount":%s}"""
                        .formatted(from, to, amount)));
    }

    private void assertBalance(String token, String accountId, double expected) throws Exception {
        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .andExpect(jsonPath("$.balance").value(expected));
    }

    @Test
    void transferMovesMoneyBetweenUsersAndWritesBothLegs() throws Exception {
        transfer(aliceToken, aliceAccount, bobAccount, "125.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andExpect(jsonPath("$.fromBalanceAfter").value(374.5000))
                .andExpect(jsonPath("$.toBalanceAfter").value(125.5000));

        assertBalance(aliceToken, aliceAccount, 374.5000);
        assertBalance(bobToken, bobAccount, 125.5000);

        // exactly two ledger rows exist for this transfer, one per account
        UUID aliceId = UUID.fromString(aliceAccount);
        UUID bobId = UUID.fromString(bobAccount);
        org.assertj.core.api.Assertions
                .assertThat(transactionRepository.findByAccountId(aliceId)).hasSize(2); // deposit + transfer out
        org.assertj.core.api.Assertions
                .assertThat(transactionRepository.findByAccountId(bobId)).hasSize(1);   // transfer in
    }

    @Test
    void transferBeyondBalanceReturns422AndChangesNothing() throws Exception {
        transfer(aliceToken, aliceAccount, bobAccount, "500.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Insufficient funds"));

        assertBalance(aliceToken, aliceAccount, 500.0000);
        assertBalance(bobToken, bobAccount, 0);
    }

    @Test
    void transferFromAnAccountYouDoNotOwnReturns403() throws Exception {
        transfer(bobToken, aliceAccount, bobAccount, "10.00")
                .andExpect(status().isForbidden());

        assertBalance(aliceToken, aliceAccount, 500.0000);
    }

    @Test
    void selfTransferReturns400() throws Exception {
        transfer(aliceToken, aliceAccount, aliceAccount, "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Source and destination accounts must be different"));

        assertBalance(aliceToken, aliceAccount, 500.0000);
    }

    @Test
    void currencyMismatchReturns400() throws Exception {
        String aliceUsd = createAccount(aliceToken, "USD");

        transfer(aliceToken, aliceAccount, aliceUsd, "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Source and destination accounts must use the same currency"));
    }

    @Test
    void transferToClosedAccountReturns409() throws Exception {
        mockMvc.perform(delete("/api/accounts/" + bobAccount)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(bobToken)))
                .andExpect(status().isNoContent());

        transfer(aliceToken, aliceAccount, bobAccount, "10.00")
                .andExpect(status().isConflict());

        assertBalance(aliceToken, aliceAccount, 500.0000);
    }

    @Test
    void transferToMissingAccountReturns404() throws Exception {
        transfer(aliceToken, aliceAccount, UUID.randomUUID().toString(), "10.00")
                .andExpect(status().isNotFound());

        assertBalance(aliceToken, aliceAccount, 500.0000);
    }

    @Test
    void transferRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"%s","toAccountId":"%s","amount":10.00}"""
                                .formatted(aliceAccount, bobAccount)))
                .andExpect(status().isUnauthorized());
    }
}
