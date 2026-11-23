package com.judgex.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic tests for the PBKDF2-SHA256 storage format and password check. */
class PasswordHasherTest {

    @Test
    @DisplayName("哈希格式为 pbkdf2$iter$salt$dk，salt 16 字节、dk 32 字节")
    void hashUsesDocumentedFormat() {
        String hash = PasswordHasher.hash("s3cret");
        String[] parts = hash.split("\\$");

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("pbkdf2");
        assertThat(parts[1]).isEqualTo("120000");
        assertThat(Base64.getDecoder().decode(parts[2])).hasSize(16);
        assertThat(Base64.getDecoder().decode(parts[3])).hasSize(32);
    }

    @Test
    @DisplayName("正确密码通过，错误密码失败")
    void matchesOnlyTheCorrectPassword() {
        String hash = PasswordHasher.hash("correct horse battery staple");

        assertThat(PasswordHasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(PasswordHasher.matches("Correct horse battery staple", hash)).isFalse();
        assertThat(PasswordHasher.matches("", hash)).isFalse();
    }

    @Test
    @DisplayName("同一密码两次哈希盐不同，但都能校验通过")
    void samePasswordHashesDifferentlyAndBothVerify() {
        String first = PasswordHasher.hash("same-password");
        String second = PasswordHasher.hash("same-password");

        assertThat(first).isNotEqualTo(second);
        assertThat(first.split("\\$")[2]).isNotEqualTo(second.split("\\$")[2]);
        assertThat(PasswordHasher.matches("same-password", first)).isTrue();
        assertThat(PasswordHasher.matches("same-password", second)).isTrue();
    }

    @Test
    @DisplayName("损坏/非 PBKDF2 的存储值返回 false 而不是抛异常")
    void malformedStoredValuesAreRejected() {
        assertThat(PasswordHasher.matches("x", "plaintext")).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$120000$onlythree")).isFalse();
        assertThat(PasswordHasher.matches("x", "bcrypt$120000$c2FsdA==$ZGs=")).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$notanumber$c2FsdA==$ZGs=")).isFalse();
    }
}
