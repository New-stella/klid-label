package kr.co.cudo.authoring.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 영상 스트리밍 단기 서명 URL 발급/검증기.
 *
 * <p>&lt;video&gt; 네이티브 엘리먼트는 Authorization 헤더를 붙일 수 없어 인증 스트림을 재생하지 못한다.
 * 이를 해결하기 위해 BE 가 짧은 TTL(기본 60초) HMAC 서명 쿼리를 발급하고, &lt;video src&gt; 는 서명 URL 로 재생한다.
 * JWT 본문은 URL 에 노출되지 않는다.
 *
 * <h3>서명 포맷</h3>
 * <pre>
 *   sig = HMAC-SHA256(signSecret, "{rawSn}.{exp}.{userNo}")  (lowercase hex)
 *   url = /api/v1/videos/{rawSn}/stream?exp={exp}&amp;sig={sig}
 * </pre>
 * <ul>
 *   <li>{@code exp}: 만료 epoch-second (서버 발급 시각 + TTL)</li>
 *   <li>{@code userNo}: 토큰 subject — null 이면 빈 문자열. URL 에는 포함하지 않으며 서명 입력으로만 사용해
 *       타 사용자가 URL 을 그대로 재사용해도 검증이 통과되도록 묶는 데 쓰지 않는다(현재는 발급자 식별/감사 목적).</li>
 * </ul>
 *
 * <h3>보안 가드</h3>
 * <ul>
 *   <li><b>키 분리(CWE-321 회피)</b>: JWT_SECRET 재사용 금지. 별도 설정 {@code authoring.stream.sign-secret} 사용.</li>
 *   <li><b>키 강도</b>: 설정 시 256bit(32B) 이상. 부족하면 부팅 차단(fail-closed).</li>
 *   <li><b>미설정 fail-closed</b>: 시크릿 미설정이면 서명 발급/검증 모두 거부.</li>
 *   <li><b>상수시간 비교(CWE-208)</b>: MessageDigest.isEqual.</li>
 *   <li><b>재사용 공격(CWE-294)</b>: 짧은 TTL(exp) 로 윈도우 제한. exp 가 서명 입력에 포함되어 변조 불가.</li>
 * </ul>
 */
@Slf4j
@Component
public class StreamUrlSigner {

    /** 서명 시크릿 최소 길이 — HS256 권장 256bit = 32B. */
    public static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;
    private final boolean configured;
    private final long ttlSeconds;

    public StreamUrlSigner(
            @Value("${authoring.stream.sign-secret:}") String signSecret,
            @Value("${authoring.stream.url-ttl-seconds:60}") long ttlSeconds) {
        if (signSecret == null || signSecret.isBlank()) {
            this.secretBytes = new byte[0];
            this.configured = false;
        } else {
            byte[] bytes = signSecret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new BeanInitializationException(
                        "authoring.stream.sign-secret 길이가 " + bytes.length + "B 입니다. 최소 "
                                + MIN_SECRET_BYTES + "B(256bit) 이상이어야 합니다.");
            }
            this.secretBytes = bytes;
            this.configured = true;
        }
        // TTL 은 5초~600초 사이로 클램프 (단기 서명 원칙).
        this.ttlSeconds = Math.min(600L, Math.max(5L, ttlSeconds));
    }

    /** 시크릿이 설정되어 서명 발급/검증이 가능한지 여부. */
    public boolean isConfigured() {
        return configured;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * 서명 발급 — exp(epoch-second) 계산 후 sig 반환.
     *
     * @param rawSn  영상 PK
     * @param userNo 발급자 subject (null 허용)
     * @return SignedParams(exp, sig). 미설정 시 IllegalStateException.
     */
    public SignedParams sign(long rawSn, String userNo) {
        if (!configured) {
            throw new IllegalStateException("stream sign-secret 미설정 — 서명 URL 발급 불가 (fail-closed)");
        }
        long exp = Instant.now().plus(Duration.ofSeconds(ttlSeconds)).getEpochSecond();
        String sig = computeHex(rawSn, exp, userNo);
        return new SignedParams(exp, sig, ttlSeconds);
    }

    /**
     * 서명 검증 — exp 미만료 + sig 일치.
     *
     * <p>userNo 는 서명 입력에 포함되지 않는 호출(스트림 검증 시점)에서는 null 로 전달한다.
     * 발급 시 userNo 를 묶었다면 동일 userNo 로 검증해야 일치한다.
     *
     * @return 유효하면 true. 미설정/만료/형식오류/불일치는 모두 false (fail-closed).
     */
    public boolean verify(long rawSn, String expRaw, String sigRaw, String userNo) {
        if (!configured) {
            return false;
        }
        if (expRaw == null || expRaw.isBlank() || sigRaw == null || sigRaw.isBlank()) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(expRaw.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (exp < now) {
            // 만료 — 재사용 공격 윈도우 차단 (CWE-294)
            return false;
        }
        String expected = computeHex(rawSn, exp, userNo);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = sigRaw.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        // 상수시간 비교 (CWE-208)
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private String computeHex(long rawSn, long exp, String userNo) {
        String canonical = rawSn + "." + exp + "." + (userNo == null ? "" : userNo);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            // HMAC 계산 실패는 키/알고리즘 문제 — fail-closed 로 검증 불가 처리.
            throw new IllegalStateException("stream 서명 계산 실패", e);
        }
    }

    /** 서명 발급 결과. */
    public record SignedParams(long exp, String sig, long ttlSeconds) {
    }
}
