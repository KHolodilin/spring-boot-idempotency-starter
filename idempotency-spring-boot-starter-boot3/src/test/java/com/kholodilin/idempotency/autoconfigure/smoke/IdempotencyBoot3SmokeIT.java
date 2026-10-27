package com.kholodilin.idempotency.autoconfigure.smoke;

import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import com.kholodilin.idempotency.autoconfigure.IdempotencyCaffeineAutoConfiguration;
import com.kholodilin.idempotency.autoconfigure.IdempotencyJacksonAutoConfiguration;
import com.kholodilin.idempotency.autoconfigure.IdempotencyJdbcAutoConfiguration;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Boot 3 servlet starter smoke: autoconfig + Postgres execute/replay
 * (success and reject) through {@link IdempotencyService}.
 */
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyBoot3SmokeIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    IdempotencyJacksonAutoConfiguration.class,
                    IdempotencyJdbcAutoConfiguration.class,
                    IdempotencyCaffeineAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                    "spring.datasource.username=" + POSTGRES.getUsername(),
                    "spring.datasource.password=" + POSTGRES.getPassword(),
                    "idempotency.persistence.schema.mode=create",
                    "idempotency.persistence.table-name=idempotency_records");

    record Command(String orderId, int amount) {}

    record PaymentResult(String paymentId) {}

    record RejectDetails(long amount, long balance) {}

    @Test
    void successIsPersistedAndReplayedThroughAutoconfig() {
        runner.run(context -> {
            IdempotencyService service = context.getBean(IdempotencyService.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            AtomicInteger actionCalls = new AtomicInteger();
            Command command = new Command("o-smoke", 10);

            ExecutionResult<PaymentResult> first = tx.execute(status -> service.operation("CREATE_PAYMENT")
                    .key("smoke-ok-1")
                    .request(command)
                    .execute(PaymentResult.class, () -> {
                        actionCalls.incrementAndGet();
                        return ExecutionResult.success(new PaymentResult("pay-42"));
                    }));

            ExecutionResult<PaymentResult> replayed = tx.execute(status -> service.operation("CREATE_PAYMENT")
                    .key("smoke-ok-1")
                    .request(command)
                    .execute(PaymentResult.class, () -> {
                        throw new AssertionError("action must not run on replay");
                    }));

            assertThat(actionCalls).hasValue(1);
            assertThat(replayed).isEqualTo(first);
            assertThat(((Success<PaymentResult>) replayed).value()).isEqualTo(new PaymentResult("pay-42"));
        });
    }

    @Test
    void rejectionIsPersistedAndReplayedWithSameDetailsJson() {
        runner.run(context -> {
            IdempotencyService service = context.getBean(IdempotencyService.class);
            IdempotencySerializer serializer = context.getBean(IdempotencySerializer.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            Command command = new Command("o-rej", 1500);
            RejectDetails details = new RejectDetails(1500, 200);

            ExecutionResult<PaymentResult> first = tx.execute(status -> service.operation("CREATE_PAYMENT")
                    .key("smoke-rej-1")
                    .request(command)
                    .execute(
                            PaymentResult.class,
                            () -> ExecutionResult.rejected("INSUFFICIENT_FUNDS", details, serializer)));

            ExecutionResult<PaymentResult> replayed = tx.execute(status -> service.operation("CREATE_PAYMENT")
                    .key("smoke-rej-1")
                    .request(command)
                    .execute(PaymentResult.class, () -> {
                        throw new AssertionError("action must not run on replay");
                    }));

            Rejected<PaymentResult> firstRejected = (Rejected<PaymentResult>) first;
            Rejected<PaymentResult> rejected = (Rejected<PaymentResult>) replayed;
            assertThat(rejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(rejected.detailsJson()).isEqualTo(firstRejected.detailsJson());
            assertThat(rejected.detailsAs(RejectDetails.class, serializer)).isEqualTo(details);
        });
    }
}
