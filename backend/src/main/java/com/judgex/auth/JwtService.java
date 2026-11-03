package com.judgex.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judgex.config.JudgexProps;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HS256 JWT implementation.
 * Hand-rolled on purpose: one fewer dependency to explain, and the algorithm is
 * simple enough to defend line by line in an interview.
 */
@Component
public class JwtService {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final ObjectMapper mapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(ObjectMapper mapper, JudgexProps props) {
        this.mapper = mapper;
        this.secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.tokenTtlHours() * 3600L;
    }

    public String issue(UserPrincipal user) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(user.id()));
        claims.put("usr", user.username());
        claims.put("name", user.displayName());
        claims.put("role", user.role());
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        try {
            String head = ENC.encodeToString(mapper.writeValueAsBytes(header));
            String body = ENC.encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = head + "." + body;
            return signingInput + "." + ENC.encodeToString(sign(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("cannot issue token", e);
        }
    }

    /** Returns null for anything that is not a valid, unexpired token we signed. */
    @SuppressWarnings("unchecked")
    public UserPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = sign(signingInput);
            byte[] actual = DEC.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return null;
            }
            Map<String, Object> claims = mapper.readValue(DEC.decode(parts[1]), Map.class);
            Object exp = claims.get("exp");
            if (!(exp instanceof Number n) || n.longValue() < Instant.now().getEpochSecond()) {
                return null;
            }
            return new UserPrincipal(
                    Long.parseLong(String.valueOf(claims.get("sub"))),
                    String.valueOf(claims.get("usr")),
                    String.valueOf(claims.get("name")),
                    String.valueOf(claims.get("role"))
            );
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
