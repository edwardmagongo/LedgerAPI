package com.edwardmagongo.ledgerapi.auth;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        // Delete transactions before accounts before users: a shared static Testcontainers
        // Postgres instance (see AbstractIntegrationTest) persists data across test classes
        // within the same JVM/Surefire fork, and transactions.account_id / accounts.owner_id
        // are foreign keys, so a blanket wipe must respect that dependency order.
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registersUserAndPersistsBcryptHash() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"s3cretpassword"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User stored = userRepository.findByEmail("alice@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(stored.getPasswordHash()).isNotEqualTo("s3cretpassword");
    }

    @Test
    void rejectsDuplicateEmailWith409() throws Exception {
        String body = """
                {"email":"bob@example.com","password":"s3cretpassword"}""";

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/api/auth/register"));
    }

    @Test
    void rejectsInvalidEmailAndShortPasswordWith400AndFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }
}
