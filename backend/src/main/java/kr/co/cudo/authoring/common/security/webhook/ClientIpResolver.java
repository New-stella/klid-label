package kr.co.cudo.authoring.common.security.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 웹훅 클라이언트 IP 산출 — 신뢰 프록시 기반 {@code X-Forwarded-For} 파싱 (A-ISSUE-15 / S-24).
 *
 * <h3>왜 remoteAddr 만으로는 안 되는가</h3>
 * <p>LB/Nginx 뒤에 배포되면 모든 요청의 {@code getRemoteAddr()} 이 동일한 프록시 IP 로 수렴한다.
 * 임의의 실패 5회가 누적되면 <b>정상 벤더 콜백까지 60초 동안 429</b> 가 되는 자기유발 DoS 다.
 *
 * <h3>왜 XFF 를 무조건 신뢰하면 안 되는가 (CWE-348)</h3>
 * <p>헤더는 클라이언트가 임의로 채울 수 있다. 무조건 신뢰하면 매 요청 다른 값을 넣어 rate limit 을
 * 무한 우회하고 로그도 위조된다. 그래서 <b>직접 접속 주소(remoteAddr)가 신뢰 프록시 CIDR 안일 때만</b>
 * XFF 를 해석하고, 오른쪽(가장 가까운 홉)부터 신뢰 홉을 벗겨 <b>최초의 비신뢰 값</b>을 클라이언트로 본다.
 * 신뢰 프록시 목록이 비어 있으면(로컬/dev 기본) XFF 는 <b>전면 무시</b>된다.
 *
 * <p>설정: {@code webhook.trusted-proxy-cidrs} (CSV, 예 {@code 10.0.0.0/8,192.168.0.0/16}).
 * 로컬/dev 는 비워 둔다.
 *
 * <h3>{@code server.forward-headers-strategy} 를 켜지 않는다 (REDESIGN 2026-07-25)</h3>
 * <p>이 설정은 {@code ForwardedHeaderFilter} 를 <b>모든 보안 필터보다 앞</b>에 세워
 * {@code getRemoteAddr()} 을 {@code X-Forwarded-For} 의 첫 토큰으로 <b>무검증 치환</b>하고
 * {@code X-Forwarded-*} 를 {@code getHeader()} 에서 숨긴다. 그러면 아래 신뢰 CIDR 대조가
 * <b>공격자가 고른 값</b>을 대조하게 되어 무의미해지고(rate limit 이 헤더 회전으로 완전 우회),
 * 이 클래스의 홉 벗기기·{@code isValidIp} 가드는 도달 불가능한 죽은 코드가 된다.
 * XFF 해석은 <b>이 클래스가 단독으로</b> 수행한다({@code application.yml} 주석 참조).
 * 이 금지는 주석이 아니라 {@code ForwardedHeadersConfigGuard} 의 <b>기동 차단</b>으로 강제된다
 * (yml 공통/프로파일별·환경변수·시스템 프로퍼티 전 축).
 *
 * <h3>운영(prd)·스테이징(stg) 명시 설정 강제 (DEV_FIX M-3 / REDESIGN R-3)</h3>
 * <p>prd·stg 는 LB/Nginx 뒤 배포가 기본인데 이 설정이 <b>빈 기본값</b>이면, 모든 콜백의 클라이언트 IP 가
 * LB IP 하나로 수렴한다. 그 상태에서 아무나 5회 실패시키면 <b>정상 벤더 콜백 전건이 60초 429</b> 가
 * 되고, 공유 카운터를 통해 2노드 전체로 전파된다 — 가용성 사고다. 시크릿은 미설정 시 기동 차단인데
 * 이 값만 조용히 취약/불가용 기본값으로 떨어지는 <b>정책 강도 비대칭</b>을 없애기 위해, 해당
 * 프로파일에서는 값을 <b>명시</b>하도록 강제한다({@link WebhookConfigProfiles}).
 * <ul>
 *   <li>프록시 뒤 → 신뢰 대역 CIDR 을 지정 (예 {@code 10.0.0.0/8})</li>
 *   <li>프록시 없이 직접 노출 → {@code none} 을 명시 (XFF 전면 무시를 의도적으로 선택)</li>
 *   <li>미설정 → <b>기동 차단</b> (조용한 기본값 금지)</li>
 *   <li><b>형식 오류 → 기동 차단</b> (DEV_FIX N-4) — 오타를 {@code log.warn} 후 버리면 matcher 가
 *       비어 XFF 전면 무시로 떨어져 A-ISSUE-15 가 원상복귀한다. {@link WebhookCidrParser} 참조.</li>
 * </ul>
 *
 * <h3>가용성은 설정 강제로 풀고, 런타임 우회로 감추지 않는다 (REDESIGN R-1)</h3>
 * <p>과거에는 "신뢰 프록시 미설정 + XFF 관측" 을 <b>런타임에 감지</b>해 하류 실패 집계를 끄는
 * {@code isClientIpAttributable()} 게이트가 있었다. {@code X-Forwarded-For} 는 <b>공격자가 임의로
 * 붙일 수 있는 헤더</b>이므로, 그것을 조건으로 보안 통제를 끄면 <b>헤더 한 줄로 rate limit 이 무력화</b>
 * 된다(실측: XFF 없이 6회째 429 → XFF 부착 시 15회 전부 401). 게다가 그 가드는 목적이던 가용성도
 * 지키지 못했다 — 필터 단계 실패(411/413)와 서명 불일치는 게이트와 무관하게 수렴 IP 로 집계됐다.
 * 그래서 게이트를 제거하고, "프록시 뒤인데 설정 누락" 문제는 위 <b>기동 차단(fail-fast)</b> 으로만 푼다.
 */
@Slf4j
@Component
public class ClientIpResolver {

    /** 프록시 없음을 <b>명시적으로</b> 선언하는 값 — 빈 값(=미설정)과 구분하기 위한 opt-out 토큰. */
    public static final String NO_PROXY = "none";

    /** XFF 헤더 파싱 상한 — 과대 헤더로 인한 CPU/메모리 소모 차단. */
    static final int MAX_XFF_LENGTH = 1024;

    /** XFF 홉 파싱 상한. */
    static final int MAX_XFF_HOPS = 20;

    static final String HEADER_XFF = "X-Forwarded-For";

    private static final String UNKNOWN = "unknown";

    static final String SETTING_KEY = "webhook.trusted-proxy-cidrs";
    static final String ENV_KEY = "WEBHOOK_TRUSTED_PROXY_CIDRS";

    private final List<IpAddressMatcher> trustedProxies;

    /** 미설정 상태에서 XFF 가 관측됐음을 1회만 경고하기 위한 플래그(로그 폭주 방지). */
    private final AtomicBoolean unconfiguredProxyWarned = new AtomicBoolean(false);

    /** {@code remoteAddr} 이 IP 리터럴이 아닌 이상 신호를 1회만 경고하기 위한 플래그. */
    private final AtomicBoolean nonLiteralRemoteAddrWarned = new AtomicBoolean(false);

    public ClientIpResolver(@Value("${webhook.trusted-proxy-cidrs:}") String trustedProxyCidrs,
                            Environment environment) {
        if (WebhookConfigProfiles.requiresExplicitConfig(environment)
                && (trustedProxyCidrs == null || trustedProxyCidrs.isBlank())) {
            throw new BeanInitializationException(
                    "webhook.trusted-proxy-cidrs (env WEBHOOK_TRUSTED_PROXY_CIDRS) 가 설정되지 않았습니다. "
                            + WebhookConfigProfiles.describeProfiles()
                            + " 프로파일은 LB/Nginx 뒤 배포가 기본이라, 미설정이면 모든 콜백의 클라이언트 IP 가 "
                            + "LB IP 하나로 수렴해 임의의 인증 실패 5회가 정상 벤더 콜백 전건을 60초 차단합니다. "
                            + "프록시 뒤면 신뢰 대역(예: 10.0.0.0/8)을, 프록시 없이 직접 노출이면 '"
                            + NO_PROXY + "' 을 명시하세요.");
        }
        // 형식 오류는 무시하지 않고 기동을 막는다 — 조용히 버리면 matcher 가 비어 XFF 전면 무시로
        // 떨어져 A-ISSUE-15 가 원상복귀한다(DEV_FIX N-4).
        this.trustedProxies = isNoProxy(trustedProxyCidrs)
                ? List.of()
                : WebhookCidrParser.parseStrict(trustedProxyCidrs, SETTING_KEY, ENV_KEY);
        if (!trustedProxies.isEmpty()) {
            log.info("[Webhook] trusted proxy CIDR configured count={}", trustedProxies.size());
        }
    }

    /** 신뢰 프록시가 지정돼 있는가(=XFF 를 해석하는가). */
    public boolean isProxyAware() {
        return !trustedProxies.isEmpty();
    }

    /**
     * rate limit 키 · 로그에 사용할 클라이언트 IP.
     *
     * <p>신뢰 프록시가 미설정이면 {@code none} 과 동일하게 <b>"프록시 없음 = {@code remoteAddr} 이 곧
     * 진짜 클라이언트"</b> 로 해석한다. "귀속 불가" 라는 제3의 상태를 두고 하류 보안 통제를 끄지 않는다
     * (REDESIGN R-1).
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = normalize(request.getRemoteAddr());
        if (!isValidIp(remoteAddr)) {
            // 서블릿 컨테이너의 remoteAddr 은 항상 IP 리터럴이다. 그렇지 않다면 상위 래퍼가 헤더 유래
            // 값으로 덮어썼다는 뜻이므로 신뢰하지 않고 단일 sentinel 로 접는다(fail-closed).
            //   보증 범위: 비-IP 문자열이 공유 카운터 테이블·allowlist 판정에 들어가지 않는다
            //   + IpAddressMatcher → InetAddress.getByName() 의 요청 스레드 DNS 조회 유입을 막는다
            //   + 이상 신호를 1회 경고한다. allowlist 설정 시 sentinel 은 어떤 CIDR 에도 매칭되지 않는다.
            //   ※ XFF 우회 자체를 막는 통제가 아니다 — 공격자가 넣는 값은 대개 유효 IPv4 리터럴이라
            //     이 분기에 걸리지 않는다. 우회 차단은 forward-headers-strategy 미사용
            //     (ForwardedHeadersConfigGuard 기동 차단) + 아래 신뢰 CIDR 대조가 담당한다.
            warnNonLiteralRemoteAddr(remoteAddr);
            return UNKNOWN;
        }
        if (trustedProxies.isEmpty() || !isTrusted(remoteAddr)) {
            // 신뢰 프록시 설정이 없거나 직접 접속이 신뢰 홉이 아니면 XFF 는 폐기한다(위조 방지).
            //   ※ XFF 존재 여부로 rate limit 을 완화·비활성하지 않는다 — 공격자가 헤더 한 줄로 제한을
            //     무력화하기 때문이다(R-1). local/dev 설정 누락 가능성만 프로세스당 1회 경고한다.
            warnIfUnconfiguredProxy(request);
            return remoteAddr;
        }
        String header = request.getHeader(HEADER_XFF);
        if (header == null || header.isBlank() || header.length() > MAX_XFF_LENGTH) {
            return remoteAddr;
        }
        List<String> hops = Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_XFF_HOPS)
                .toList();
        // 오른쪽(가장 가까운 홉)부터 신뢰 프록시를 벗기고, 최초의 비신뢰 값을 클라이언트로 본다.
        for (int i = hops.size() - 1; i >= 0; i--) {
            String candidate = normalize(hops.get(i));
            if (!isValidIp(candidate)) {
                // 위조·비정상 값 — 더 왼쪽은 신뢰할 수 없으므로 직접 접속 주소로 폴백.
                return remoteAddr;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return remoteAddr;
    }

    private boolean isTrusted(String ip) {
        if (ip == null || UNKNOWN.equals(ip)) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // 비정상 IP 리터럴 — 신뢰하지 않음
                return false;
            }
        }
        return false;
    }

    /**
     * IPv4/IPv6 <b>리터럴</b> 인가 — 문자 집합 검사만으로는 부족하다(DEV_FIX L-3).
     *
     * <p>과거 구현은 "16진 문자 · {@code .} · {@code :} 로만 구성" 을 검사해 {@code 00a} 같은 값이
     * 통과했다. 그 값은 {@code IpAddressMatcher} → {@code InetAddress.getByName("00a")} 로 이어져
     * <b>요청 스레드에서 DNS 조회</b>가 발생한다(외부 호출 지연·차단 표면). 구조까지 검증해 차단한다.
     */
    private boolean isValidIp(String candidate) {
        if (UNKNOWN.equals(candidate)) {
            return false;
        }
        return WebhookCidrParser.isIpLiteral(candidate);
    }

    /**
     * 설정 누락 가능성 <b>1회</b> 경고 — 요청마다 남기지 않는다(무제한 WARN 증식 차단).
     *
     * <p>이 경고는 <b>운영 안내</b>일 뿐 동작을 바꾸지 않는다. 과거 구현은 이 상황을 근거로 하류 실패
     * 집계를 껐는데(N-3), 그 조건이 공격자 제어 헤더라 rate limit 이 통째로 무력화됐다(R-1).
     * prd/stg 는 {@link WebhookConfigProfiles} 의 기동 차단으로 이 상태 자체가 배포될 수 없다.
     */
    private void warnIfUnconfiguredProxy(HttpServletRequest request) {
        if (!trustedProxies.isEmpty() || unconfiguredProxyWarned.get()) {
            return;
        }
        String header = request.getHeader(HEADER_XFF);
        if (header != null && !header.isBlank() && unconfiguredProxyWarned.compareAndSet(false, true)) {
            log.warn("[Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다. "
                    + "XFF 는 폐기하고 remoteAddr 로 집계합니다. 프록시 뒤라면 신뢰 대역을 설정하고, "
                    + "직접 노출이면 '{}' 을 명시하세요. (이 경고는 프로세스당 1회만 출력)", NO_PROXY);
        }
    }

    /**
     * {@code remoteAddr} 이 IP 리터럴이 아닌 <b>이상 신호</b>를 프로세스당 1회 경고한다.
     *
     * <p>정상 배포에서는 발생하지 않는다. 발생했다면 상위 서블릿 필터가 요청 헤더 유래 값으로
     * {@code getRemoteAddr()} 을 덮어썼을 가능성이 높으므로(신뢰 축 오염) 설정을 점검해야 한다.
     * 이 경고는 <b>안내</b>일 뿐이며 통제 완화의 근거로 쓰지 않는다 — 값은 이미 폐기됐다.
     *
     * <p>이 경고·sentinel 로 XFF 우회가 막히는 것은 아니다(공격 값은 대개 유효 IP 리터럴이라 여기까지
     * 오지 않는다). 우회 차단은 {@code ForwardedHeadersConfigGuard} 의 기동 차단이 담당한다.
     */
    private void warnNonLiteralRemoteAddr(String remoteAddr) {
        if (nonLiteralRemoteAddrWarned.compareAndSet(false, true)) {
            log.warn("[Webhook] remoteAddr 이 IP 리터럴이 아닙니다 value={} — 신뢰하지 않고 '{}' 로 "
                            + "집계합니다. 상위 필터가 X-Forwarded-* 로 remoteAddr 을 덮어쓰고 있는지 "
                            + "확인하세요(server.forward-headers-strategy 금지). (프로세스당 1회 출력)",
                    WebhookProtectedPaths.sanitize(remoteAddr), UNKNOWN);
        }
    }

    /** {@code none} = 프록시 없음을 명시적으로 선택 (빈 값=미설정 과 구분). */
    private static boolean isNoProxy(String value) {
        return value != null && NO_PROXY.equalsIgnoreCase(value.trim());
    }

    /** 포트·대괄호 제거 후 정규화. */
    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String value = raw.trim();
        if (value.startsWith("[")) { // [::1]:8080 형태
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : UNKNOWN;
        }
        int colon = value.indexOf(':');
        if (colon > -1 && value.indexOf(':', colon + 1) == -1) { // IPv4:port
            return value.substring(0, colon);
        }
        return value;
    }
}
