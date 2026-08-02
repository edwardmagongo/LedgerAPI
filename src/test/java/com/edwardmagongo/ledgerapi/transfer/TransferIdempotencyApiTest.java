package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotencyKeyRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferIdempotencyApiTest extends AbstractIntegrationTest {

    private static final String KEY_HEADER = "Idempotency-Key";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;

    private String aliceToken;
    private String bobToken;
    private String aliceAccount;
    private String bobAccount;

    @BeforeEach
    void setUp() throws Exception {
        idempotencyKeyRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceToken = TestAuth.registerAndLogin(mockMvc, "alice@example.com", "s3cretpassword");
        bobToken = TestAuth.registerAndLogin(mockMvc, "bob@example.com", "s3cretpassword");
        aliceAccount = createAccount(aliceToken);
        bobAccount = createAccount(bobToken);
        deposit(aliceToken, aliceAccount, "500.00");
    }

    private String createAccount(String token) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"GBP\"}"))
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

    private String transferBody(String from, String to, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to
                + "\",\"amount\":" + amount + "}";
    }

    // Numeric comparison, not a string one: round-tripping a BigDecimal through JSON and back via
    // readTree().asText() does not reliably preserve its original scale (Jackson's tree model may
    // parse the literal through double precision), so "400.0000" can come back as "400.0" despite
    // being the same value. jsonPath(...).value(double) compares numerically and sidesteps that.
    private void assertBalance(String token, String accountId, double expected) throws Exception {
        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(expected));
    }

    @Test
    void repeatingATransferWithTheSameKeyMovesTheMoneyOnceAndReplaysTheBody() throws Exception {
        String body = transferBody(aliceAccount, bobAccount, "100.00");

        String first = mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).as("replay must be byte-identical to the original").isEqualTo(first);
        assertBalance(aliceToken, aliceAccount, 400.0000);
        assertBalance(bobToken, bobAccount, 100.0000);
        assertThat(transactionRepository.count())
                .as("one deposit plus exactly one transfer's two legs")
                .isEqualTo(3);
    }

    @Test
    void reusingAKeyWithADifferentAmountIsRejectedAndMovesNothing() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "100.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "250.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This Idempotency-Key was already used with different request parameters"));

        assertBalance(aliceToken, aliceAccount, 400.0000);
    }

    @Test
    void anEquivalentAmountWrittenDifferentlyStillReplays() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "100.00")))
                .andExpect(status().isCreated());

        // 100.0 is the same request as 100.00 and must not be treated as a key reuse.
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "100.0")))
                .andExpect(status().isCreated());

        assertBalance(aliceToken, aliceAccount, 400.0000);
    }

    @Test
    void aFailedTransferReleasesTheKeySoItCanBeRetried() throws Exception {
        String tooMuch = transferBody(aliceAccount, bobAccount, "5000.00");

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-4")
                        .contentType(MediaType.APPLICATION_JSON).content(tooMuch))
                .andExpect(status().isUnprocessableEntity());

        assertThat(idempotencyKeyRepository.count()).as("the claim must be released").isZero();

        // The same key now works for a transfer that fits.
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "transfer-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "100.00")))
                .andExpect(status().isCreated());

        assertBalance(aliceToken, aliceAccount, 400.0000);
    }

    @Test
    void aBlankKeyIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key must not be blank"));

        assertBalance(aliceToken, aliceAccount, 500.0000);
    }

    @Test
    void withNoKeyHeaderTheEndpointBehavesExactlyAsBefore() throws Exception {
        String body = transferBody(aliceAccount, bobAccount, "100.00");

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Without a key, two identical requests move money twice — unchanged behaviour.
        assertBalance(aliceToken, aliceAccount, 300.0000);
        assertThat(idempotencyKeyRepository.count()).isZero();
    }

    @Test
    void twoUsersMayUseTheSameKeyString() throws Exception {
        deposit(bobToken, bobAccount, "500.00");

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(aliceToken))
                        .header(KEY_HEADER, "shared-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount, "100.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(bobToken))
                        .header(KEY_HEADER, "shared-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(bobAccount, aliceAccount, "50.00")))
                .andExpect(status().isCreated());

        assertThat(idempotencyKeyRepository.count()).isEqualTo(2);
    }
}
