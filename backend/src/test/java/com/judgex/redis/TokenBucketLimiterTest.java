package com.judgex.redis;

import com.judgex.common.ApiException;
import com.judgex.config.JudgexProps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hermetic tests for the in-process sliding-window fallback.
 * Redis is disabled in the properties, so RedisGate.up() is false without any network call.
 */
class TokenBucketLimiterTest {

    private static JudgexProps props() {
        return new JudgexProps("test-secret", 1, null, null, null,
                new JudgexProps.Redis(false, "test:"), null);
    }

    private static TokenBucketLimiter limiter() {
        JudgexProps props = props();
        return new TokenBucketLimiter(new RedisGate(null, props), props);
    }

    @Test
    @DisplayName("Redis 关闭时仍允许 limit 次，第 limit+1 次抛 RATE_LIMITED")
    void localWindowAllowsExactlyTheLimit() {
        TokenBucketLimiter limiter = limiter();

        assertThatCode(() -> limiter.acquire(1L, 2)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(1L, 2)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(1L, 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.code()).isEqualTo("RATE_LIMITED");
                    assertThat(api.getMessage()).contains("2/min");
                });
    }

    @Test
    @DisplayName("本地窗口按用户隔离，一个用户耗尽不影响另一个")
    void localWindowIsPerUser() {
        TokenBucketLimiter limiter = limiter();

        limiter.acquire(1L, 1);
        assertThatThrownBy(() -> limiter.acquire(1L, 1)).isInstanceOf(ApiException.class);
        assertThatCode(() -> limiter.acquire(2L, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("limit 为 1 时第一次通过、第二次被拒")
    void limitOfOneAllowsSingleCall() {
        TokenBucketLimiter limiter = limiter();

        limiter.acquire(42L, 1);
        assertThatThrownBy(() -> limiter.acquire(42L, 1))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1/min");
    }
}
