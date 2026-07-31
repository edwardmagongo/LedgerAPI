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

class TransactionHistoryApiTest extends AbstractIntegrationTest {

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

        // 5 deposits of 100.00 then 3 withdrawals of 10.00 => 8 transactions
        for (int i = 0; i < 5; i++) {
            money("deposit", "100.00");
        }
        for (int i = 0; i < 3; i++) {
            money("withdraw", "10.00");
        }
    }

    private void money(String operation, String amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/" + operation)
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions history(String query) throws Exception {
        return mockMvc.perform(get("/api/accounts/" + accountId + "/transactions" + query)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)));
    }

    @Test
    void returnsFirstPageWithDefaultsNewestFirst() throws Exception {
        history("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(8))
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL"));
    }

    @Test
    void paginatesWithExplicitPageAndSize() throws Exception {
        history("?page=0&size=3")
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.totalPages").value(3));

        history("?page=2&size=3")
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void filtersByType() throws Exception {
        history("?type=WITHDRAWAL")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL"));

        history("?type=DEPOSIT")
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void filtersByDateRange() throws Exception {
        history("?from=2000-01-01T00:00:00Z")
                .andExpect(jsonPath("$.totalElements").value(8));

        history("?to=2000-01-01T00:00:00Z")
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void clampsPageSizeToOneHundred() throws Exception {
        history("?size=5000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void rejectsUnknownTypeWith400() throws Exception {
        history("?type=NONSENSE").andExpect(status().isBadRequest());
    }

    @Test
    void anotherUsersHistoryReturns403() throws Exception {
        String bobToken = TestAuth.registerAndLogin(mockMvc, "bob@example.com", "s3cretpassword");

        mockMvc.perform(get("/api/accounts/" + accountId + "/transactions")
                        .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(bobToken)))
                .andExpect(status().isForbidden());
    }
}
