package kr.co.cudo.authoring.common.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * HIGH-3 fix (CWE-359/778): 비식별 엔드포인트 신뢰 판정 — <b>단일 진입점</b>.
 *
 * <p>목 서버(mock-server)는 원본을 그대로 복사해 "비식별본"을 만든다(위조 비식별). 기존에는
 * {@code authoring.integration.deidentify.mock-mode}(자체 복사) 축만 판정해
 * {@code DeidentifyStep} 이 WARN + prd fail-closed 로 막았는데, dev 전환으로
 * {@code DEIDENTIFY_MOCK_MODE=false} + {@code kpst.deid.base-url=http://klid-mock-server:9400}
 * 조합이 표준 경로가 되면서 <b>같은 위험(원본이 비식별본으로 서빙됨)이 게이트 밖으로 빠져나갔다</b>.
 * stg/prd 도 URL 만 목/시뮬레이터로 바꾸면 아무 경고 없이 동일 상태가 된다.
 *
 * <p>따라서 판정 축을 <b>mock-mode 단일 → "신뢰할 수 없는 비식별 엔드포인트"</b> 로 확장하고,
 * 그 판정을 <b>이 클래스 한 곳</b>에서만 수행한다(설정을 읽는 지점 단일화 — 호출처마다 배선하면
 * 반드시 샌다). 차단은 부팅 시점 fail-closed 한 곳에서 이뤄지므로 이후의 모든 산출·전송 경로
 * (비식별 영상 서빙/프레임 추출/export/관제 통지)가 자동으로 함께 막힌다.
 *
 * <h3>판정 정책 — deny-known-mock (allowlist 아님)</h3>
 * <p>"이 URL 만 허용"하는 allowlist 는 채택하지 않는다. 리포지토리는 stg/prd 의 실제 KPST 주소를
 * 알 수 없어 정상 운영 기동을 깨뜨리기 때문이다. 대신 <b>알려진 목/시뮬레이터 호스트와 루프백을
 * 비신뢰로 표시</b>한다 — 미지의 벤더 주소는 통과한다.
 *
 * <h3>프로파일별 동작</h3>
 * <table><caption>비신뢰 판정 시 동작</caption>
 *   <tr><th>프로파일</th><th>동작</th><th>근거</th></tr>
 *   <tr><td>local</td><td>무음</td><td>목 연동이 정상 구성 — 매 기동 경고는 소음</td></tr>
 *   <tr><td>dev</td><td>WARN</td><td>목 서버 실연동이 dev 의 목적 — 차단하면 파이프라인이 죽는다</td></tr>
 *   <tr><td>stg</td><td>WARN</td><td>기존 mock-mode 게이트(local/dev/stg 허용, prd 차단)와 동일 강도</td></tr>
 *   <tr><td>prd</td><td>부팅 거부</td><td>위조 비식별본이 학습데이터·외부 통지로 유출되는 것을 fail-closed 차단</td></tr>
 * </table>
 *
 * <p>운영 판정은 prd 프로파일 또는 {@code ENV=prd} 표식 둘 다를 본다({@code DeidentifyStep} 선례).
 * 로그·예외 메시지에는 호스트/프로파일만 노출한다(PII·원본경로 미노출, CWE-209).
 */
@Slf4j
@Component
public class DeidentifyEndpointTrustGuard {

    /**
     * 알려진 목/시뮬레이터 호스트(소문자). 루프백을 포함하는 이유는, 비식별 위탁 대상이 앱과 같은
     * 머신이면 벤더 실서버와 시뮬레이터를 구분할 수 없기 때문이다(운영 토폴로지상 KPST 는 별도 서버).
     *
     * <p>IPv6 리터럴은 {@code URI#getHost()} 가 대괄호를 포함해 돌려주므로({@code [::1]}) 비교 전에
     * {@link #normalizeHost(String)} 로 대괄호를 제거한다 — 정규화가 없으면 {@code ::1} 항목이 사문화된다.
     */
    private static final Set<String> UNTRUSTED_HOSTS = Set.of(
            "klid-mock-server", "mock-server", "localhost", "127.0.0.1", "::1", "0.0.0.0");

    /** 호스트명에 이 토큰이 포함되면(예: kpst-mock-01) 목으로 간주한다. */
    private static final String MOCK_HOST_TOKEN = "mock";

    private final Environment environment;

    /** UC018 — KPST 위탁 경로 토글. false 면 위탁 엔드포인트 자체가 없다. */
    @Value("${kpst.deid.enabled:true}")
    private boolean kpstEnabled;

    /** 비식별 위탁 대상 주소 — 신뢰 판정의 유일한 입력 지점. */
    @Value("${kpst.deid.base-url:}")
    private String kpstBaseUrl;

    /** 자체 복사(self-fill) 모드 — 외부 무접촉으로 원본을 비식별본으로 둔갑시킨다. */
    @Value("${authoring.integration.deidentify.mock-mode:false}")
    private boolean mockMode;

    public DeidentifyEndpointTrustGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        String reason = untrustedReason();
        if (reason == null) {
            return;
        }
        if (isProduction()) {
            throw new IllegalStateException(
                    "신뢰할 수 없는 비식별 경로로는 운영(prd) 기동을 허용하지 않습니다(fail-closed): " + reason);
        }
        if (isLocal()) {
            return; // 로컬 자족/목 연동은 정상 구성 — 매 기동 경고 소음 방지
        }
        log.warn("[Deid][Trust] untrusted deidentification endpoint — 원본이 비식별본으로 서빙될 수 있음: {} activeProfiles={}",
                reason, Arrays.toString(environment.getActiveProfiles()));
    }

    /**
     * 비신뢰 사유를 반환한다(신뢰 가능하면 null). 판정 축은 두 가지다.
     * <ol>
     *   <li>mock-mode — 외부 무접촉 자체 복사.</li>
     *   <li>위탁 대상 호스트가 알려진 목/시뮬레이터/루프백이거나, 호스트를 확인할 수 없음(fail-secure).</li>
     * </ol>
     */
    String untrustedReason() {
        if (mockMode) {
            return "deidentify mock-mode (자체 복사, 외부 무접촉)";
        }
        if (!kpstEnabled) {
            return null; // 위탁 엔드포인트 없음 — 판정 대상 아님
        }
        String host = hostOf(kpstBaseUrl);
        if (host == null) {
            return "kpst.deid.base-url 호스트 파싱 불가"
                    + (kpstBaseUrl == null || kpstBaseUrl.isBlank() ? "(값 미설정)" : "(scheme://host:port 형식 확인 필요)")
                    + " — 판정 불가 → 비신뢰 처리";
        }
        if (isUntrustedHost(host)) {
            return "kpst.deid.base-url 이 목/시뮬레이터 호스트를 가리킴(host=" + host + ")";
        }
        return null;
    }

    private boolean isUntrustedHost(String host) {
        return UNTRUSTED_HOSTS.contains(host) || host.contains(MOCK_HOST_TOKEN);
    }

    /**
     * URL 에서 호스트만 소문자로 추출한다. 파싱 불가/호스트 부재면 null(→ 비신뢰, fail-secure).
     *
     * <p>{@code URI#getHost()} 는 RFC 2396 문법을 벗어난 호스트 — 특히 <b>언더스코어가 포함된
     * 호스트</b>({@code http://kpst_deid:9201}, 도커 컴포즈 서비스명에서 흔함) — 에 null 을 돌려준다.
     * 이를 그대로 "판정 불가 → 비신뢰"로 떨구면 prd 에서 <b>앱 전체가 부팅 거부</b>되므로,
     * {@code getAuthority()} 로 폴백해 호스트를 뽑아 <b>동일한 deny 판정을 그대로 태운다</b>
     * (판정 자체를 건너뛰지 않으므로 목/루프백은 언더스코어가 있어도 여전히 비신뢰).
     * 폴백으로도 뽑지 못하면 기존대로 null(비신뢰)을 유지한다.
     */
    private String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                host = hostFromAuthority(uri.getAuthority());
            }
            return (host == null || host.isBlank()) ? null : normalizeHost(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code [userinfo@]host[:port]} 형태의 authority 에서 호스트만 뽑는다(폴백 경로).
     * IPv6 리터럴({@code [::1]:9201})은 대괄호 구간을 호스트로 인식한다.
     */
    private String hostFromAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String candidate = authority.trim();
        int userInfoEnd = candidate.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            candidate = candidate.substring(userInfoEnd + 1);
        }
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            return close > 1 ? candidate.substring(1, close) : null;
        }
        int portStart = candidate.indexOf(':');
        if (portStart >= 0) {
            candidate = candidate.substring(0, portStart);
        }
        return candidate.isBlank() ? null : candidate;
    }

    /** 비교용 정규화 — IPv6 대괄호 제거 + 소문자. */
    private String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean isProduction() {
        if (environment.acceptsProfiles(Profiles.of("prd"))) {
            return true;
        }
        String envName = environment.getProperty("ENV");
        return envName != null && "prd".equalsIgnoreCase(envName.trim());
    }

    private boolean isLocal() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("local"::equalsIgnoreCase);
    }
}
