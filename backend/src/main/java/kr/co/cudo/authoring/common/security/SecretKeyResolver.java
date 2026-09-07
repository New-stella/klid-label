package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class SecretKeyResolver implements JwtKeyResolver {

    private static final int MIN_SECRET_BYTES = 32;

    /**
     * HS512 서명 토큰을 검증하려면 키가 이만큼은 되어야 한다.
     *
     * <p>★ 하한(32)을 올리지 않는 이유 — 이미 32~48바이트로 설치된 현장이 있고, 올리면
     * 그 현장이 <기동 자체를 못 한다>. 대신 기동 때 크게 경고한다.
     *
     * <p>★★ 왜 경고가 필요한가 (2026-09-07 현장). 발급 주체가 외부라 서명 알고리즘을
     * 우리가 고르지 못한다. 운영 관제는 <b>HS512</b> 로 서명하는데 이 값이 64 미만이면
     * <b>기동은 성공하고 인계 로그인만 전량 실패</b>한다 — 값이 맞아도 그렇다.
     * 조용한 실패라 설정 파일만 봐서는 원인을 알 수 없다.
     */
    private static final int HS512_KEY_BYTES = 64;

    private final SecretKey key;

    public SecretKeyResolver(@Value("${authoring.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        if (bytes.length < HS512_KEY_BYTES) {
            log.warn("[Auth] JWT_SECRET 이 {}바이트입니다 — HS512 로 서명된 인계 토큰은 검증할 수 없습니다"
                            + " (필요 {}바이트). 값이 맞아도 인계 로그인이 전량 실패합니다."
                            + " 발급 주체의 alg 를 확인하세요.",
                    bytes.length, HS512_KEY_BYTES);
        }
    }

    @Override
    public SecretKey resolve() {
        return key;
    }
}
