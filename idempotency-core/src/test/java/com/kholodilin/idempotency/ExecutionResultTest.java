package com.kholodilin.idempotency;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionResultTest {

    record Details(long amount, long balance) {
    }

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
    void rejectedFactoryConvertsDetailsToJsonTreeImmediately() {
        ExecutionResult<String> result = ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200));

        ExecutionResult.Rejected<String> rejected = (ExecutionResult.Rejected<String>) result;
        JsonNode details = rejected.details();
        assertThat(details.get("amount").asLong()).isEqualTo(1500);
        assertThat(details.get("balance").asLong()).isEqualTo(200);
    }

    @Test
    void detailsAsRestoresTypedDetails() {
        ExecutionResult<String> result = ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200));

        ExecutionResult.Rejected<String> rejected = (ExecutionResult.Rejected<String>) result;
        Details details = rejected.detailsAs(Details.class);
        assertThat(details).isEqualTo(new Details(1500, 200));
    }

    @Test
    void rejectedWithoutDetailsHasNullNode() {
        ExecutionResult.Rejected<String> rejected =
                (ExecutionResult.Rejected<String>) ExecutionResult.<String>rejected("LIMIT_EXCEEDED");

        assertThat(rejected.details().isNull()).isTrue();
    }

    @Test
    void valueOrThrowThrowsForRejected() {
        ExecutionResult<String> result = ExecutionResult.rejected("INSUFFICIENT_FUNDS", Map.of("balance", 200));

        assertThatThrownBy(result::valueOrThrow)
                .isInstanceOf(IdempotencyRejectedException.class)
                .satisfies(e -> {
                    IdempotencyRejectedException rejected = (IdempotencyRejectedException) e;
                    assertThat(rejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(rejected.details().get("balance").asInt()).isEqualTo(200);
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
}
