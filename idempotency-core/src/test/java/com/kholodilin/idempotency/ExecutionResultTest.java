package com.kholodilin.idempotency;

import java.util.Map;

import com.kholodilin.idempotency.exception.IdempotencyRejectedException;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.testsupport.TestIdempotencySerializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionResultTest {

    record Details(long amount, long balance) {}

    private final IdempotencySerializer serializer = new TestIdempotencySerializer();

    @Test
    void successCarriesValue() {
        ExecutionResult<String> result = ExecutionResult.success("hello");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.valueOrThrow()).isEqualTo("hello");
    }

    @Test
    void successAllowsNullValueForVoidLikeOperations() {
        ExecutionResult<Void> result = ExecutionResult.success(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.valueOrThrow()).isNull();
    }

    @Test
    void rejectedFactorySerializesDetailsImmediately() {
        ExecutionResult<String> result =
                ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200), serializer);

        ExecutionResult.Rejected<String> rejected = (ExecutionResult.Rejected<String>) result;
        assertThat(rejected.detailsJson()).contains("\"amount\":1500").contains("\"balance\":200");
        assertThat(rejected.detailsAs(Details.class, serializer)).isEqualTo(new Details(1500, 200));
    }

    @Test
    void rejectedWithRawJsonKeepsStringAsIs() {
        ExecutionResult.Rejected<String> rejected =
                (ExecutionResult.Rejected<String>) ExecutionResult.<String>rejected("X", "{\"a\":1}");

        assertThat(rejected.detailsJson()).isEqualTo("{\"a\":1}");
    }

    @Test
    void rejectedWithNullObjectDetailsYieldsNullJson() {
        ExecutionResult.Rejected<String> rejected =
                (ExecutionResult.Rejected<String>) ExecutionResult.<String>rejected("X", null, serializer);

        assertThat(rejected.detailsJson()).isNull();
        assertThat(rejected.detailsAs(Details.class, serializer)).isNull();
    }

    @Test
    void rejectedWithoutDetailsHasNullJson() {
        ExecutionResult.Rejected<String> rejected =
                (ExecutionResult.Rejected<String>) ExecutionResult.<String>rejected("LIMIT_EXCEEDED");

        assertThat(rejected.detailsJson()).isNull();
    }

    @Test
    void valueOrThrowThrowsForRejected() {
        ExecutionResult<String> result =
                ExecutionResult.rejected("INSUFFICIENT_FUNDS", Map.of("balance", 200), serializer);

        assertThatThrownBy(result::valueOrThrow)
                .isInstanceOf(IdempotencyRejectedException.class)
                .satisfies(e -> {
                    IdempotencyRejectedException rejected = (IdempotencyRejectedException) e;
                    assertThat(rejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(rejected.detailsJson()).contains("\"balance\":200");
                    assertThat(rejected.detailsAs(Map.class, serializer)).containsEntry("balance", 200);
                });
    }

    @Test
    void foldDispatchesSuccess() {
        ExecutionResult<String> result = ExecutionResult.success("value");

        String outcome = result.fold(v -> "ok:" + v, r -> "rejected:" + r.errorCode());

        assertThat(outcome).isEqualTo("ok:value");
    }

    @Test
    void foldDispatchesRejected() {
        ExecutionResult<String> result = ExecutionResult.rejected("LIMIT_EXCEEDED");

        String outcome = result.fold(v -> "ok:" + v, r -> "rejected:" + r.errorCode());

        assertThat(outcome).isEqualTo("rejected:LIMIT_EXCEEDED");
    }

    @Test
    void rejectedRequiresNonNullErrorCode() {
        assertThatThrownBy(() -> new ExecutionResult.Rejected<String>(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
