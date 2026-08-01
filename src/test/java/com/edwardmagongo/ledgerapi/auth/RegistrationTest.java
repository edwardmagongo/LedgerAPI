package com.edwardmagongo.ledgerapi.auth;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    void rejectsConcurrentDuplicateEmailWith409() throws Exception {
        // Reproduces the real race the existsByEmail pre-check can't fully close: fire two
        // registrations for the same email at the same instant via a start-gate latch (same
        // technique as TransferConcurrencyTest). Both requests' existsByEmail checks can run
        // before either has committed, so both proceed to save(). Exactly one of the two
        // concurrent inserts wins; the other's row is deferred to flush at commit (User has no
        // @Version and an app-assigned @Id, so Spring Data's save() on it routes through
        // em.merge()), where it hits the DB's unique constraint on users.email. Before this fix,
        // that DataIntegrityViolationException escaped past AuthService's method boundary and
        // was reported as 500 by handleUnexpected; now GlobalExceptionHandler#
        // handleDataIntegrityViolation translates it to 409 like every other write conflict.
        String email = "carol@example.com";
        String body = """
                {"email":"%s","password":"s3cretpassword"}""".formatted(email);

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Callable<Integer> attempt = () -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            };

            List<Future<Integer>> futures = List.of(pool.submit(attempt), pool.submit(attempt));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            assertThat(statuses).allSatisfy(status -> assertThat(status).isNotEqualTo(500));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
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
