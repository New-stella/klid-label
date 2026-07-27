package kr.co.cudo.authoring.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 서명 스트림 URL 의 <b>클라이언트 바인딩 nonce 쿠키</b> (A-ISSUE-11).
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code /stream} 은 &lt;video&gt; 가 Authorization 헤더를 못 붙여 서명 쿼리로 인증하는 무헤더 경로다.
 * 서명 입력에 {@code userNo} 를 넣어도 그 값이 URL 쿼리({@code u})에 함께 노출되므로, URL 전체를 복사한
 * 제3자는 TTL 동안 그대로 재생할 수 있었다(CWE-294 capture-replay). 즉 {@code u} 바인딩의 방어 기여는 0 이었다.
 *
 * <p>그래서 발급({@code /stream-url}) 시점에 <b>HttpOnly·SameSite 쿠키</b>로만 전달되는 랜덤 nonce 를
 * 서명 입력에 포함시킨다. URL 에는 nonce 가 없으므로 <b>URL 유출만으로는 서명을 재구성할 수 없다</b>.
 * 동일 오리진이라 {@code <video src>} 요청에 쿠키가 자동 부착되어 정상 재생은 영향받지 않는다.
 *
 * <h3>봉인(seal) — 클라이언트가 준 값을 그대로 신뢰하지 않는다 (DEV_FIX M-1)</h3>
 * <p>과거에는 요청 쿠키가 32-hex 형식이기만 하면 <b>서버가 발급한 값인지 확인하지 않고</b> 그대로 서명
 * 비밀로 채택했다(nonce fixation). 공격자가 피해자 브라우저에 임의 nonce 를 심어두면(평문 HTTP 덮어쓰기·
 * 형제 서브도메인·악성 확장) 이후 발급되는 서명이 <b>공격자가 아는 nonce</b> 로 만들어져, URL 이 유출되는
 * 순간 공격자가 그 nonce 를 붙여 재생할 수 있었다 — "클라이언트 바인딩" 이라는 방어 목표 자체가 무너진다.
 *
 * <p>이제 쿠키 값은 다음처럼 <b>서버 비밀 + 발급 대상 subject 로 봉인</b>된다.
 * <pre>
 *   cookie = "{nonce}.{HMAC-SHA256(sealKey, subject + '.' + nonce)}"   (모두 lowercase hex)
 *   sealKey = HMAC-SHA256(authoring.stream.sign-secret, "klid-stream-nonce-seal")   // 도메인 분리 파생키
 * </pre>
 * {@link #read} 는 봉인 검증(상수시간 비교)에 실패하면 값을 폐기하고 {@code null} 을 반환하며, 그 결과
 * {@link #resolveOrIssue} 가 새 nonce 를 발급한다. 따라서 ①서버가 발급하지 않은 값, ②다른 사용자에게
 * 발급된 값은 절대 서명 비밀로 채택되지 않는다. 서명 입력에는 봉인을 뗀 <b>nonce 부분만</b> 들어가므로
 * {@code StreamUrlSigner} 의 canonical 포맷은 그대로다.
 *
 * <h3>1회용 소비를 하지 않는 이유</h3>
 * <p>브라우저는 같은 서명 URL 로 다수의 Range 요청을 보낸다. nonce 를 1회 소비로 폐기하면 두 번째 Range
 * 요청부터 401 이 되어 재생이 깨진다. 따라서 nonce 는 TTL 동안 재사용 가능하다 — 방어 목표는 "재생 횟수
 * 제한" 이 아니라 "URL 만 가진 제3자 차단" 이다.
 *
 * <h3>쿠키 값 재사용</h3>
 * <p>이미 유효(봉인 검증 통과)한 쿠키가 있으면 <b>같은 값을 유지</b>하고 만료만 갱신한다. 매 발급마다 새
 * 값을 심으면 여러 영상을 동시에 열었을 때 앞서 발급한 URL 의 nonce 가 덮여 재생이 끊긴다.
 */
@Component
public class StreamNonceCookie {

    /** 쿠키명 — 값 자체가 비밀이므로 JS 접근 차단(HttpOnly). */
    public static final String COOKIE_NAME = "klid_stream_nonce";

    /** 쿠키 수명 — 서명 URL TTL(≤600s)보다 넉넉하되 유한. 발급 때마다 갱신된다. */
    static final Duration COOKIE_TTL = Duration.ofHours(1);

    /** 128bit 랜덤의 lowercase hex — 형식이 다른 값은 조작으로 보고 무시한다. */
    private static final Pattern VALID_NONCE = Pattern.compile("^[0-9a-f]{32}$");

    /** 봉인 태그 — HMAC-SHA256 hex(64자). */
    private static final Pattern VALID_SEAL = Pattern.compile("^[0-9a-f]{64}$");

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 파생키 도메인 분리 라벨 — 같은 시크릿을 URL 서명과 다른 용도로 쓰기 위한 라벨. */
    private static final String SEAL_KEY_LABEL = "klid-stream-nonce-seal";

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] sealKey;
    private final boolean localProfile;

    public StreamNonceCookie(Environment environment,
                             @Value("${authoring.stream.sign-secret:}") String signSecret) {
        this.sealKey = deriveSealKey(signSecret);
        // MEDIUM-2 — Secure 판정은 프로파일(배포 시 고정된 서버 설정)로만 한다.
        // request.isSecure() 는 요청/헤더에서 유래하는 값이라 공격자 제어 축에 걸쳐 있고, TLS 종단 LB 뒤에서는
        // 항상 false 여서 운영 쿠키에 Secure 가 영영 붙지 않았다.
        this.localProfile = environment.acceptsProfiles(Profiles.of("local"));
    }

    /**
     * 요청에 유효한(봉인 검증을 통과한) nonce 쿠키가 있으면 그대로, 없으면 새로 발급해 응답에 심고 반환한다.
     *
     * @param subject 발급 대상 사용자 식별자(JWT sub). 봉인에 바인딩되어 타 사용자 쿠키 이식을 무력화한다.
     * @return 서명 입력에 넣을 <b>봉인을 뗀</b> nonce
     */
    public String resolveOrIssue(HttpServletRequest request, HttpServletResponse response, String subject) {
        String nonce = read(request, subject);
        if (nonce == null) {
            byte[] raw = new byte[16];
            secureRandom.nextBytes(raw);
            nonce = HexFormat.of().formatHex(raw);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(request, nonce, subject));
        return nonce;
    }

    /**
     * 요청 쿠키에서 nonce 를 읽는다. 없거나 형식이 어긋나거나 <b>봉인이 맞지 않으면</b> {@code null}
     * (fail-closed) — 서버가 발급하지 않았거나 다른 subject 로 발급된 값은 채택하지 않는다.
     *
     * @param subject 봉인 검증에 쓸 사용자 식별자. 서명 경로에서는 서명이 덮는 {@code u} 값이다.
     */
    public String read(HttpServletRequest request, String subject) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return unseal(cookie.getValue(), subject);
            }
        }
        return null;
    }

    /**
     * {@code "{nonce}.{seal}"} 을 검증하고 nonce 부분만 반환. 어긋나면 {@code null}.
     * VisibleForTesting.
     */
    String unseal(String value, String subject) {
        if (value == null) {
            return null;
        }
        int dot = value.indexOf('.');
        if (dot < 0) {
            // 봉인 없는 구형/조작 값 — 채택 금지(nonce fixation 차단).
            return null;
        }
        String nonce = value.substring(0, dot);
        String seal = value.substring(dot + 1);
        if (!VALID_NONCE.matcher(nonce).matches() || !VALID_SEAL.matcher(seal).matches()) {
            return null;
        }
        byte[] expected = sealBytes(nonce, subject);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(seal);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // CWE-208 — 상수시간 비교.
        return MessageDigest.isEqual(expected, actual) ? nonce : null;
    }

    /**
     * 봉인된 쿠키 값 문자열({@code "{nonce}.{seal}"}) — 서버가 Set-Cookie 로 내려보내는 형태.
     *
     * <p>VisibleForTesting: 테스트가 HMAC 계산을 재구현(=드리프트 위험)하지 않고 실제 서버 로직으로
     * 쿠키를 만들 수 있게 공개한다. 서버 내부 계산이라 노출되어도 공격 표면이 되지 않는다.
     */
    public String seal(String nonce, String subject) {
        return nonce + '.' + HexFormat.of().formatHex(sealBytes(nonce, subject));
    }

    private byte[] sealBytes(String nonce, String subject) {
        return hmac(sealKey, normalizeSubject(subject) + '.' + nonce);
    }

    /** subject 미상(무헤더 경로에서 {@code u} 누락 등)도 결정적으로 처리 — null/blank 를 동일 키로 정규화. */
    private static String normalizeSubject(String subject) {
        return (subject == null || subject.isBlank()) ? "-" : subject.trim();
    }

    /**
     * 봉인 전용 파생키. {@code authoring.stream.sign-secret} 을 그대로 쓰지 않고 라벨을 섞어 파생해
     * URL 서명 키와 용도를 분리한다(키 재사용 회피).
     *
     * <p>시크릿 미설정 환경에서는 서명 발급/검증 자체가 비활성(503)이라 nonce 가 쓰이지 않는다. 그 경우
     * JVM 기동 시 랜덤 키를 만들어 <b>봉인 없는 값이 통과하는 일이 절대 없게</b> 한다(fail-closed).
     */
    private static byte[] deriveSealKey(String signSecret) {
        if (signSecret == null || signSecret.isBlank()) {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            return ephemeral;
        }
        return hmac(signSecret.getBytes(StandardCharsets.UTF_8), SEAL_KEY_LABEL);
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("nonce 봉인 계산에 실패했습니다.", e);
        }
    }

    /**
     * 쿠키 헤더 문자열 생성.
     * <ul>
     *   <li>{@code HttpOnly} — XSS 로 nonce 를 탈취해 URL 재사용에 붙이는 경로 차단.</li>
     *   <li>{@code SameSite=Lax} — 크로스사이트 요청에 부착되지 않게 한다(동일 오리진 재생은 정상).</li>
     *   <li>{@code Secure} — <b>local 프로파일이 아니면 무조건 부여</b>. 운영은 TLS 종단 LB 뒤라
     *       {@code request.isSecure()} 가 항상 false 이므로 그 값을 판정 축으로 쓰면 안 된다(DEV_FIX M-2).
     *       local 만 예외를 두는 이유는 평문 HTTP 개발 서버에서 쿠키가 버려지지 않게 하기 위함이며,
     *       프로파일은 배포 설정이라 요청자가 조작할 수 없다.</li>
     *   <li>{@code Path} — 스트림 경로로 한정해 다른 API 요청에 불필요하게 실려 나가지 않게 한다.</li>
     * </ul>
     */
    private String buildCookie(HttpServletRequest request, String nonce, String subject) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return ResponseCookie.from(COOKIE_NAME, seal(nonce, subject))
                .httpOnly(true)
                .secure(!localProfile)
                .sameSite("Lax")
                .path(contextPath + "/v1/videos")
                .maxAge(COOKIE_TTL)
                .build()
                .toString();
    }
}
