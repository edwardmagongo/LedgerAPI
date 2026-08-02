package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotencyKeyRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.support.TestAuth;
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

class DepositWithdrawIdempotencyApiTest extends AbstractIntegrationTest {

    private static final String KEY_HEADER = "Idempotency-Key";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;

    private String token;
    private String account;

    @BeforeEach
    void setUp() throws Exception {
        idempotencyKeyRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        token = TestAuth.registerAndLogin(mockMvc, "alice@example.com", "s3cretpassword");

        String body = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"GBP\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        account = objectMapper.readTree(body).get("id").asText();
    }

    // Numeric comparison, not a string one: round-tripping a BigDecimal through JSON and back via
    // readTree().asText() does not reliably preserve its original scale (Jackson's tree model may
    // parse the literal through double precision), so "400.0000" can come back as "400.0" despite
    // being the same value. jsonPath(...).value(double) compares numerically and sidesteps that.
    private void assertBalance(double expected) throws Exception {
        mockMvc.perform(get("/api/accounts/" + account)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(expected));
    }

    @Test
    void repeatingADepositWithTheSameKeyCreditsOnceAndReplaysTheBody() throws Exception {
        String first = mockMvc.perform(post("/api/accounts/" + account + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "dep-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/accounts/" + account + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "dep-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertBalance(100.0000);
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void repeatingAWithdrawalWithTheSameKeyDebitsOnce() throws Exception {
        mockMvc.perform(post("/api/accounts/" + account + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500.00}"))
                .andExpect(status().isCreated());

        String first = mockMvc.perform(post("/api/accounts/" + account + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "wd-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/accounts/" + account + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "wd-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertBalance(450.0000);
    }

    @Test
    void reusingOneKeyAcrossDifferentOperationsIsRejected() throws Exception {
        // The operation is part of the fingerprint, so the same key used for a different
        // operation is a reuse, not a replay.
        mockMvc.perform(post("/api/accounts/" + account + "/deposit")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/" + account + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isConflict());

        assertBalance(100.0000);
    }

    @Test
    void aFailedWithdrawalReleasesTheKey() throws Exception {
        mockMvc.perform(post("/api/accounts/" + account + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .header(KEY_HEADER, "wd-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(idempotencyKeyRepository.count()).isZero();
    }

    @Test
    void withNoKeyHeaderDepositsStillApplyTwice() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/accounts/" + account + "/deposit")
                            .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":25.00}"))
                    .andExpect(status().isCreated());
        }

        assertBalance(50.0000);
        assertThat(idempotencyKeyRepository.count()).isZero();
    }
}
