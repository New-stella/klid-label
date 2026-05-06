package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class SecretKeyResolver implements JwtKeyResolver {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    public SecretKeyResolver(@Value("${authoring.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    @Override
    public SecretKey resolve() {
        return key;
    }
}
