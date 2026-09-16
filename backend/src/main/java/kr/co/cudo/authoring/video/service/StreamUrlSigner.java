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
 * 영상 스트리밍 주소의 서명 쿼리 발급기.
 *
 * <h3>⚠ 재생 인증 판정에는 더 이상 쓰이지 않는다 (ADR-071)</h3>
 * <p>재생 요청({@code /stream})의 인증은 발급자에게 봉인된 재생 인증 쿠키 하나로 판정한다
 * ({@code StreamSignatureFilter}). 구 동작은 이 서명(기본 60초)과 쿠키(1시간)를 함께 요구했는데, 서명이 먼저
 * 만료돼 재생 도중 부분 요청이 401 로 끊겼다. 이 클래스는 발급 창구({@code /stream-url})가 주소 형태와
 * 응답 계약({@code expiresAt}·{@code ttlSeconds})을 유지하도록, 그리고 되돌리기 여지를 남기도록 존치한다.
 * 따라서 {@code authoring.stream.url-ttl-seconds}(기본값·5~600초 클램프 무변경)는 이제 <b>인증 유효 시간이
 * 아니라 주소에 실리는 만료 표식</b>의 길이다. {@link #verify} 는 재생 경로에서 호출되지 않는다.
 * 아래 서술 중 "검증"·"재사용 차단"은 구 판정 시절의 설계 기록이다.
 *
 * <p>&lt;video&gt; 네이티브 엘리먼트는 Authorization 헤더를 붙일 수 없어 인증 스트림을 재생하지 못한다.
 * JWT 본문은 URL 에 노출되지 않는다.
 *
 * <h3>서명 포맷</h3>
 * <pre>
 *   sig = HMAC-SHA256(signSecret, "{rawSn}.{exp}.{userNo}.{nonce}")  (lowercase hex)
 *   url = /api/v1/videos/{rawSn}/stream?exp={exp}&amp;u={userNo}&amp;sig={sig}
 * </pre>
 * <ul>
 *   <li>{@code exp}: 만료 epoch-second (서버 발급 시각 + TTL)</li>
 *   <li>{@code userNo}: 토큰 subject — <b>URL 쿼리 {@code u} 에도 노출된다</b>. 서명이 이 값을 덮으므로
 *       {@code u} 변조는 거부되지만, URL 전체를 복사한 재사용은 {@code u} 만으로는 막지 못한다
 *       (검증 시점에 "요청자 == u" 를 대조할 인증 주체가 없는 무헤더 경로이기 때문).
 *       실제 재사용 차단은 아래 nonce 가 담당한다.</li>
 *   <li>{@code nonce}: 발급 응답에 함께 내려간 <b>HttpOnly·SameSite 쿠키</b> 값. URL 에는 절대 포함되지
 *       않으며 브라우저만 보관한다. 검증 시 요청 쿠키에서 읽어 서명 입력으로 재구성하므로,
 *       <b>URL 만 유출된 제3자는 쿠키가 없어 서명 검증에 실패</b>한다(CWE-294 실보호).</li>
 * </ul>
 *
 * <h3>보안 가드</h3>
 * <ul>
 *   <li><b>키 분리(CWE-321 회피)</b>: JWT_SECRET 재사용 금지. 별도 설정 {@code authoring.stream.sign-secret} 사용.</li>
 *   <li><b>키 강도</b>: 설정 시 256bit(32B) 이상. 부족하면 부팅 차단(fail-closed).</li>
 *   <li><b>미설정 fail-closed</b>: 시크릿 미설정이면 서명 발급/검증 모두 거부.</li>
 *   <li><b>상수시간 비교(CWE-208)</b>: MessageDigest.isEqual.</li>
 *   <li><b>재사용 공격(CWE-294)</b>: 짧은 TTL(exp) + 클라이언트 바인딩 nonce. nonce 는 TTL 동안 재사용
 *       가능해야 한다 — 브라우저가 같은 URL 로 다수의 Range 요청을 보내므로 1회용 소비는 재생을 깨뜨린다.</li>
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
        // TTL 은 5초~600초 사이로 클램프. 재생 판정에 쓰이지 않으므로(ADR-071) 주소 만료 표식의 길이일 뿐이다.
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
     * @param nonce  발급자 브라우저에 내려갈 HttpOnly 쿠키 값 — 반드시 non-blank (클라이언트 바인딩)
     * @return SignedParams(exp, sig). 미설정/nonce 부재 시 IllegalStateException.
     */
    public SignedParams sign(long rawSn, String userNo, String nonce) {
        if (!configured) {
            throw new IllegalStateException("stream sign-secret 미설정 — 서명 URL 발급 불가 (fail-closed)");
        }
        if (nonce == null || nonce.isBlank()) {
            // 클라이언트 바인딩 없는 서명은 URL 유출 = 재생 가능이라 발급 자체를 거부한다(fail-closed).
            throw new IllegalStateException("stream nonce 미지정 — 서명 URL 발급 불가 (fail-closed)");
        }
        long exp = Instant.now().plus(Duration.ofSeconds(ttlSeconds)).getEpochSecond();
        String sig = computeHex(rawSn, exp, userNo, nonce);
        return new SignedParams(exp, sig, ttlSeconds);
    }

    /**
     * 서명 검증 — exp 미만료 + sig 일치 (+ 클라이언트 바인딩 nonce 일치).
     *
     * <p>{@code nonce} 는 요청 쿠키에서 읽은 값이다. 비어 있으면 <b>즉시 false</b> — 쿠키 없는 요청은
     * "URL 만 확보한 제3자" 이므로 통과시키지 않는다(A-ISSUE-11 fail-closed).
     *
     * @return 유효하면 true. 미설정/만료/형식오류/불일치/nonce 부재는 모두 false (fail-closed).
     */
    public boolean verify(long rawSn, String expRaw, String sigRaw, String userNo, String nonce) {
        if (!configured) {
            return false;
        }
        if (expRaw == null || expRaw.isBlank() || sigRaw == null || sigRaw.isBlank()) {
            return false;
        }
        if (nonce == null || nonce.isBlank()) {
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
        String expected = computeHex(rawSn, exp, userNo, nonce);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = sigRaw.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        // 상수시간 비교 (CWE-208)
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private String computeHex(long rawSn, long exp, String userNo, String nonce) {
        String canonical = rawSn + "." + exp + "." + (userNo == null ? "" : userNo)
                + "." + (nonce == null ? "" : nonce);
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
