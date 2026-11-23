package com.judgex.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judgex.config.JudgexProps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic tests for the hand-rolled HS256 token: round trip, tampering and expiry. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef";
    private static final UserPrincipal ALICE = new UserPrincipal(7L, "alice", "Alice", "USER");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JwtService service = new JwtService(mapper, props(SECRET, 24));

    private static JudgexProps props(String secret, int ttlHours) {
        return new JudgexProps(secret, ttlHours, null, null, null, null, null);
    }

    private static String[] split(String token) {
        return token.split("\\.");
    }

    @Test
    @DisplayName("签发后校验可以还原全部 claims")
    void issuedTokenVerifiesBackToPrincipal() {
        String token = service.issue(ALICE);

        assertThat(split(token)).hasSize(3);
        assertThat(service.verify(token)).isEqualTo(ALICE);
        assertThat(service.ttlSeconds()).isEqualTo(24 * 3600L);
    }

    @Test
    @DisplayName("改动 payload 后签名不再匹配，校验失败")
    void tamperedPayloadIsRejected() {
        String token = service.issue(ALICE);
        String[] parts = split(token);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"usr\":\"alice\"");
        String forgedPayload = payload.replace("\"usr\":\"alice\"", "\"usr\":\"mallory\"");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(service.verify(forged)).isNull();
    }

    @Test
    @DisplayName("只翻转签名的 1 个 bit 也会被拒绝（MessageDigest.isEqual 比较）")
    void singleBitSignatureFlipIsRejected() {
        String token = service.issue(ALICE);
        String[] parts = split(token);
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[signature.length - 1] ^= 0x01;
        String forged = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThat(service.verify(forged)).isNull();
    }

    @Test
    @DisplayName("过期 token 必须校验失败")
    void expiredTokenIsRejected() {
        // exp 写在签发时；用负 TTL 造一个已经过期的 token，再由正常 TTL 的实例校验。
        String expired = new JwtService(mapper, props(SECRET, -1)).issue(ALICE);
        assertThat(service.verify(expired)).isNull();
    }

    @Test
    @DisplayName("别的密钥签出的 token 不被接受")
    void tokenFromAnotherSecretIsRejected() {
        String foreign = new JwtService(mapper, props("another-secret-altogether", 24)).issue(ALICE);
        assertThat(service.verify(foreign)).isNull();
    }

    @Test
    @DisplayName("null/空串/段数不对/非法 Base64 一律返回 null")
    void malformedTokensAreRejectedWithoutThrowing() {
        assertThat(service.verify(null)).isNull();
        assertThat(service.verify("")).isNull();
        assertThat(service.verify("   ")).isNull();
        assertThat(service.verify("not-a-token")).isNull();
        assertThat(service.verify("a.b")).isNull();
        assertThat(service.verify("a.b.c.d")).isNull();
        assertThat(service.verify("!!.??.###")).isNull();
    }
}
