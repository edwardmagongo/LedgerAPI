package com.edwardmagongo.ledgerapi.account;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired ObjectMapper objectMapper;

    private String aliceToken;

    @BeforeEach
    void setUp() throws Exception {
        // Delete transactions before accounts before users: a shared static Testcontainers
        // Postgres instance (see AbstractIntegrationTest) persists data across test classes
        // within the same JVM/Surefire fork, and transactions.account_id / accounts.owner_id
        // are foreign keys, so a blanket wipe must respect that dependency order.
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = TestAuth.registerAndLogin(mockMvc, "alice@example.com", "s3cretpassword");
    }

    private String createAccount(String token) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"GBP"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void createReturnsZeroBalanceActiveAccount() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"GBP"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("GBP"));
    }

    @Test
    void createRejectsUnsupportedCurrencyWith400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"XYZ"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsOnlyOwnAccounts() throws Exception {
        createAccount(aliceToken);
        String bobToken = TestAuth.registerAndLogin(mockMvc, "bob@example.com", "s3cretpassword");
        createAccount(bobToken);

        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getAnotherUsersAccountReturns403() throws Exception {
        String bobToken = TestAuth.registerAndLogin(mockMvc, "bob@example.com", "s3cretpassword");
        String bobAccount = createAccount(bobToken);

        mockMvc.perform(get("/api/accounts/" + bobAccount)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void getMissingAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/accounts/" + java.util.UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void closeEmptyAccountReturns204ThenAccountReadsClosed() throws Exception {
        String accountId = createAccount(aliceToken);

        mockMvc.perform(delete("/api/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken)))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void accountsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
    }
}
