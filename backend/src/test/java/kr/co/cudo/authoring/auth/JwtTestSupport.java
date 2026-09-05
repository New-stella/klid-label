package kr.co.cudo.authoring.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

public final class JwtTestSupport {

    private JwtTestSupport() {}

    public static SecretKey key(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            return Keys.hmacShaKeyFor(padded);
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    public static String token(String secret, String subject, String role, String channel,
                               String issuer, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .claim("role", role)
                .claim("channel", channel)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key(secret))
                .compact();
    }

    public static String tokenWithName(String secret, String subject, String role, String channel,
                                       String issuer, String name, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .claim("role", role)
                .claim("channel", channel)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key(secret))
                .compact();
    }

    /**
     * 관제 인계 토큰 — 비숫자 {@code sub} + {@code userId}/{@code userNm} 클레임, iss 없이 발급.
     * 실제 관제 토큰이 문자열 로그인 ID 로 식별하고 iss·role 클레임을 우리 규격대로 싣지 않는 모양을
     * 재현한다(@design ADR-063). {@code userNm} 이 null 이면 이름 클레임을 싣지 않는다.
     */
    public static String controlToken(String secret, String subject, String userId, String userNm,
                                       String channel, long ttlSeconds) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .claim("channel", channel)
                .claim("userId", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (userNm != null) {
            builder.claim("userNm", userNm);
        }
        return builder.signWith(key(secret)).compact();
    }

    public static String expiredToken(String secret, String subject, String role, String channel, String issuer) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .claim("role", role)
                .claim("channel", channel)
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key(secret))
                .compact();
    }
}
