package kr.co.cudo.authoring.common.client;

import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 관제 outbound 통지({@code ControlNotifyClient} → {@code notify-completed}/{@code notify-updated})의
 * 인증 헤더({@code x-access-token})에 실을 <b>서비스 토큰</b>을 <b>발송 시점에 동적 발급</b>한다.
 *
 * <h3>왜 발송 시점 발급인가 (ADR-063 ⑥)</h3>
 * <p>이 통지는 사용자 요청 스레드가 아니라 백그라운드(디바운서 flush · 폴백 재시도 잡 · export 복구기)에서
 * 나가므로 사용자 인계 토큰을 실을 수 없다(요청 컨텍스트가 없고 인계 토큰은 이미 만료됐을 수 있다). 그래서
 * 우리가 서비스 토큰을 발급한다. 임시로 쓰던 <b>20년 고정 정적 토큰</b>(CWE-798)을 대체하며, 관제 발급
 * 토큰과 형식을 맞춘다.
 *
 * <h3>발급 규칙 — 관제 규칙 정합</h3>
 * <ul>
 *   <li><b>alg=HS256</b> — 반드시 {@code Jwts.SIG.HS256} 로 명시한다. 공유 시크릿({@code JWT_SECRET})은
 *       91바이트라 {@code signWith(key)} 단독 호출 시 jjwt 0.12 가 <b>HS512 를 자동 선택</b>한다. 관제
 *       규칙은 HS256 이므로 키 크기 자동선택에 의존하면 안 된다.</li>
 *   <li><b>iss</b> = {@code authoring.control-notify.token-issuer}(기본 {@code klid-auth}).</li>
 *   <li><b>sub</b> = {@code authoring.control-notify.token-subject}(기본 {@code klid-authoring-notify}) —
 *       사용자 신원이 아니라 서비스 신원이다. 역할을 나르지 않는다({@code ADR-021} 권한 매핑 금지 불변).</li>
 *   <li><b>exp</b> = now + {@code authoring.control-notify.token-ttl-seconds}(기본 300s, 하드코딩 금지) ·
 *       {@code iat} 포함. 발송 즉시 사용이라 짧게 둔다.</li>
 * </ul>
 *
 * <h3>fail-safe · 보안</h3>
 * <p>시크릿(=키)이 없으면 발급 불가로 보고 {@code null} 을 돌려준다({@link #canIssue()} false). 발급 중
 * 예외가 나도 통지를 죽이지 않고 {@code null} 로 흡수한다(폴백 큐가 회수). 시크릿·발급된 토큰 값은
 * 로그·예외 메시지에 <b>절대 출력하지 않는다</b>(CWE-532) — 존재 여부/예외 클래스명 정도만 남긴다.
 *
 * @design ADR-063
 */
@Component
public class ControlNotifyTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ControlNotifyTokenProvider.class);

    /** ttl 오설정(0·음수) fail-safe 기본값 — 발송 즉시 사용이라 짧게 둔다. */
    private static final long DEFAULT_TTL_SECONDS = 300L;
    /** 상한 clamp — 서비스 토큰을 오래 살려 둘 이유가 없다(정적 장수명 회귀 방지). */
    private static final long MAX_TTL_SECONDS = 3600L;

    private final JwtKeyResolver keyResolver;
    private final String issuer;
    private final String subject;
    private final long ttlSeconds;

    // ★ @Autowired 명시 필수 — 생성자가 2개(주입용·테스트용)라 없으면 Spring 이 no-arg 생성자를 찾다
    //   BeanCreationException 으로 컨텍스트가 통째로 실패한다(회귀 가드 ControlNotifyTokenProviderBeanWiringTest).
    @Autowired
    public ControlNotifyTokenProvider(
            ObjectProvider<JwtKeyResolver> keyResolverProvider,
            @Value("${authoring.control-notify.token-issuer:klid-auth}") String issuer,
            @Value("${authoring.control-notify.token-subject:klid-authoring-notify}") String subject,
            @Value("${authoring.control-notify.token-ttl-seconds:300}") long ttlSeconds) {
        // 시크릿 미설정 등으로 키 빈이 없으면 null — 발급 불가 fail-safe (헤더 미부착으로 흡수).
        this.keyResolver = keyResolverProvider.getIfAvailable();
        this.issuer = (issuer == null || issuer.isBlank()) ? "klid-auth" : issuer.trim();
        this.subject = (subject == null || subject.isBlank()) ? "klid-authoring-notify" : subject.trim();
        this.ttlSeconds = clampTtl(ttlSeconds);
    }

    /** 테스트/직접 배선용 생성자 — 키를 직접 주입한다({@code null} 허용 = 발급 불가 fail-safe). */
    public ControlNotifyTokenProvider(JwtKeyResolver keyResolver, String issuer, String subject,
                                      long ttlSeconds) {
        this.keyResolver = keyResolver;
        this.issuer = (issuer == null || issuer.isBlank()) ? "klid-auth" : issuer.trim();
        this.subject = (subject == null || subject.isBlank()) ? "klid-authoring-notify" : subject.trim();
        this.ttlSeconds = clampTtl(ttlSeconds);
    }

    private static long clampTtl(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            log.warn("[ControlNotify] token-ttl-seconds 오설정({}) — 기본값 {}s 로 대체합니다.",
                    ttlSeconds, DEFAULT_TTL_SECONDS);
            return DEFAULT_TTL_SECONDS;
        }
        return Math.min(ttlSeconds, MAX_TTL_SECONDS);
    }

    /** 서명 키가 있어 토큰을 발급할 수 있으면 true. */
    public boolean canIssue() {
        return keyResolver != null;
    }

    long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * HS256 서명 x-access-token 을 발급한다.
     *
     * @return 발급된 JWT compact 문자열, 발급 불가(키 없음)·발급 실패 시 {@code null}(fail-safe).
     */
    public String issue() {
        if (keyResolver == null) {
            return null;
        }
        try {
            Instant now = Instant.now();
            return Jwts.builder()
                    .issuer(issuer)
                    .subject(subject)
                    // jti — 매 발급 고유값. iat/exp 는 초 단위라 같은 초에 두 번 발급하면 토큰이 byte 동일이
                    //   되는데, 이 값이 있어 발급마다 달라진다(freshness/재전송 구분). 관제는 미지의 클레임을 무시.
                    .id(UUID.randomUUID().toString())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                    // ★ HS256 명시 — 키 크기 자동선택(91B → HS512)에 의존 금지 (ADR-063 ⑥).
                    .signWith(keyResolver.resolve(), Jwts.SIG.HS256)
                    .compact();
        } catch (RuntimeException e) {
            // 값은 절대 출력하지 않는다(CWE-532) — 예외 클래스명만. 통지는 헤더 없이 나가고 폴백이 회수한다.
            log.warn("[ControlNotify] x-access-token 발급 실패 — 헤더 없이 전송합니다. cause={}",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
