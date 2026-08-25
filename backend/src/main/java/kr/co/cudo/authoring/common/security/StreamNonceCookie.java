package kr.co.cudo.authoring.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * {@code Secure} 부여 여부 — <b>배포 설정({@code authoring.stream.cookie-secure})</b> 이 단일 판정 축이며
     * <b>기본값은 OFF</b> 다. {@code @design API-114}
     *
     * <p>MEDIUM-2 의 실질(요청·헤더를 판정 축으로 쓰지 않는다)은 그대로다 — {@code request.isSecure()} 는
     * 요청에서 유래해 공격자 제어 축에 걸쳐 있고, TLS 종단 LB 뒤에서는 항상 false 라 운영 쿠키에
     * {@code Secure} 가 영영 붙지 않는다. 바뀐 것은 "요청이냐 배포 설정이냐" 가 아니라 그 배포 설정을
     * <b>프로파일로 유추하지 않고 명시 키로 받는다</b> 는 점이다.
     */
    private final boolean cookieSecure;

    public StreamNonceCookie(@Value("${authoring.stream.sign-secret:}") String signSecret,
                             @Value("${authoring.stream.cookie-secure:false}") boolean cookieSecure) {
        this.sealKey = deriveSealKey(signSecret);
        this.cookieSecure = cookieSecure;
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
     *   <li>{@code Secure} — <b>설정 {@code authoring.stream.cookie-secure} 가 단일 판정 축이고 기본은 OFF</b>.
     *       HTTPS 로 서비스되는 배포에서 켜려면 그 키를 {@code true}(환경변수 {@code STREAM_COOKIE_SECURE=true})
     *       로 명시한다.
     *       <p>⚠ <b>구 서술 폐기</b>: <i>"local 프로파일이 아니면 무조건 부여"</i> 는 더 이상 동작이 아니다.
     *       그 판정은 <b>"평문 HTTP = local 프로파일"</b> 을 전제했는데 그 전제가 참이 아니다. 프로파일이
     *       {@code local} 이 아니면서 평문 HTTP 로 서비스되는 배포에서는 브라우저가 {@code Secure} 쿠키를
     *       저장하지 않아 nonce 가 동반되지 않고, 서명 검증이 fail-closed 라 <b>스트림 요청이 전건 401</b>
     *       이 되어 영상이 아예 재생되지 않는다.
     *       <p>해당하는 배포가 <b>개발 서버만이 아니다</b> — 온프렘 <b>운영</b> 설치 형상도 프로파일은
     *       {@code prd} 인데({@code deploy/onprem/config/backend/env.template} 의
     *       {@code SPRING_PROFILES_ACTIVE=prd}) 프런트는 평문 HTTP 로 서비스되고
     *       ({@code nginx.conf.template} 의 {@code listen 80}) 폐쇄망이라 TLS 를 걸지 않는 것이 설치
     *       기본이다({@code deploy/onprem/docs/04-configuration.md}). 즉 이 수정은 개발 편의가 아니라
     *       <b>운영에도 잠복해 있던 결함</b>을 걷어낸 것이고, 그 배포 형상에 맞는 값이 기본 OFF 다.
     *       예외의 취지 자체는 원래 "평문 HTTP 에서 쿠키가 버려지지 않게" 였으므로, 그 취지를 프로파일
     *       유추가 아니라 배포 설정 키로 직접 표현한다.
     *       <p>⚠ <b>기본 OFF 의 대가(인지·수용됨)</b>: 프로파일만으로 자동 부여되던 것이 사라져
     *       <b>stg·prd 도 키를 켜지 않으면 {@code Secure} 가 빠진다</b>. 앞단에 TLS 종단을 두어 HTTPS 로
     *       서비스하는 배포라면 그 배포에서 {@code STREAM_COOKIE_SECURE=true} 로 켜야 하며, 켜지 않으면
     *       nonce 쿠키가 평문 http 요청에 실려 나갈 수 있다. 그럼에도 <b>공통 기본</b>을 OFF 로 둔 것은,
     *       켜져 있어서 나는 고장이 <b>기능 전면 불능(재생 0)</b> 인 반면 꺼져 있어서 나는 노출은
     *       HttpOnly·SameSite·Path 한정·1시간 TTL 로 좁혀진 잔여 위험이기 때문이다. 기본값 회귀는
     *       {@code StreamCookieSecureDefaultGuardTest} 가 고정한다.
     *       <p>{@code request.isSecure()} 로 되돌리지 말 것 — TLS 종단 LB 뒤에서 항상 false 라 운영에
     *       {@code Secure} 가 영영 붙지 않는다(DEV_FIX M-2). {@code server.forward-headers-strategy} 로
     *       푸는 것도 2026-07-25 REDESIGN 으로 되돌린 결정이며 기동 가드가 거부한다.</li>
     *   <li>{@code Path} — 스트림 경로로 한정해 다른 API 요청에 불필요하게 실려 나가지 않게 한다.</li>
     * </ul>
     *
     * @design API-114
     * @design API-084
     */
    private String buildCookie(HttpServletRequest request, String nonce, String subject) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return ResponseCookie.from(COOKIE_NAME, seal(nonce, subject))
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(contextPath + "/v1/videos")
                .maxAge(COOKIE_TTL)
                .build()
                .toString();
    }
}
