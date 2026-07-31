package com.edwardmagongo.ledgerapi.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real Postgres instance.
 *
 * <p>The Postgres container is started exactly once, in a static initializer, and lives for the
 * duration of the JVM/Surefire fork. It is deliberately NOT managed by JUnit 5's
 * {@code @Testcontainers} extension ({@code @Container}/{@code @Testcontainers}): that extension
 * runs its start/stop lifecycle per test class, but the static container field is shared across
 * every subclass (Java statics belong to the declaring class, not the subclass). With
 * {@code @Testcontainers} present, the first IT class to run starts the container and the last of
 * its {@code afterAll} callbacks stops it; the next IT class's {@code beforeAll} then restarts it,
 * and Docker assigns a new ephemeral host port. Spring's test-context cache, however, reuses the
 * {@code ApplicationContext} (and its Hikari pool) it built for the first class, which still
 * points at the now-dead old port — causing intermittent connection failures in whichever IT class
 * ran first once a later IT class's container restart invalidates the cached context. Starting the
 * container once in a static initializer, with no JUnit-managed stop in between, avoids the restart
 * entirely; Testcontainers' Ryuk reaper cleans it up when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
