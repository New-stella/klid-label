package kr.co.cudo.authoring.common.security;

import jakarta.annotation.PostConstruct;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.SafeUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Arrays;
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
 *
 * <h3>★ 판정 시점이 둘이다 — 기동 시 + 저장 시 (R11 이후 필수)</h3>
 * <p>R11 로 비식별 주소가 <b>운영 화면에서 바뀔 수 있게</b> 되면서, 이 가드가 보는 {@code @Value}
 * 배포값과 실제 호출 주소가 갈린다. 기동 시 1회 판정만 남겨두면 <b>prd 에서도 화면에서 목 서버 주소를
 * 저장해 게이트를 통째로 우회</b>할 수 있다(저장 시 재평가도 WARN 도 없었다).
 * <ul>
 *   <li>{@link #verify()} — 기동 시 배포값 판정(기존 동작 유지).</li>
 *   <li>{@link #verifyForSave(String)} — 저장 시 입력값 판정. <b>강도는 기동 시와 같다</b>
 *       (운영이면 거부, 그 외는 WARN).</li>
 * </ul>
 * 두 경로 모두 {@link #untrustedReason(String)} <b>한 함수</b>를 쓴다 — 판정을 복제하면 한쪽만
 * 갱신돼 갈린다.
 *
 * <p>⚠ 이 가드의 축은 <b>알려진 목/시뮬레이터 호스트명</b>이지 IP 대역이 아니다. 대역 차단은
 * 폐지된 정책이며({@code IntegrationEndpointUrlValidator} 참조) 여기서도 되살리지 않는다 —
 * 사설·링크로컬 주소 자체는 통과한다. 루프백만 예외적으로 비신뢰인데, 그 근거는 대역이 아니라
 * "앱과 같은 머신 = 벤더 실서버일 수 없다"는 배포 토폴로지다.
 */
@Slf4j
@Component
public class DeidentifyEndpointTrustGuard {

    /**
     * 알려진 목/시뮬레이터 호스트(소문자). 루프백을 포함하는 이유는, 비식별 위탁 대상이 앱과 같은
     * 머신이면 벤더 실서버와 시뮬레이터를 구분할 수 없기 때문이다(운영 토폴로지상 KPST 는 별도 서버).
     *
     * <p>IPv6 리터럴은 {@code URI#getHost()} 가 대괄호를 포함해 돌려주므로({@code [::1]})
     * {@link SafeUrl#hostOf(String)} 가 정규화(대괄호 제거 + 소문자)해 준다 — 정규화가 없으면
     * {@code ::1} 항목이 사문화된다.
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
     * <b>저장 시점 판정</b> — 운영자가 화면에서 입력한 비식별 주소를 기동 시와 <b>같은 강도</b>로 본다.
     *
     * <p>운영(prd 프로파일 또는 {@code ENV=prd})에서 목/시뮬레이터 주소면 저장을 거부한다(400).
     * 그 외 프로파일은 WARN 만 남기고 저장을 허용한다 — dev/stg 의 목 서버 연동이 정상 경로이기 때문이며,
     * 이는 {@link #verify()} 의 프로파일별 강도와 정확히 같다.
     *
     * <p>거부 메시지에 <b>입력 호스트를 싣지 않는다</b>(CWE-209/117) — 사유는 고정 문구이고, 진단에
     * 필요한 호스트는 서버 로그에만 남긴다.
     *
     * <p>⚠ {@code kpst.deid.enabled} 로 게이팅하지 <b>않는다</b>. 저장된 값은 위탁이 켜지는 순간
     * 그대로 쓰이므로, 꺼져 있다는 이유로 통과시키면 "끄고 저장 → 켜기"로 우회된다.
     *
     * @param url 저장 요청된 주소(형식 검증을 이미 통과한 값)
     * @throws CustomException 운영에서 신뢰할 수 없는 주소인 경우 {@link ErrorCode#INVALID_INPUT}
     */
    public void verifyForSave(String url) {
        String reason = untrustedReason(url);
        if (reason == null) {
            return;
        }
        if (isProduction()) {
            log.warn("[Deid][Trust] 운영에서 신뢰할 수 없는 비식별 주소 저장을 거부했습니다: {}",
                    LogSanitizer.sanitize(reason));
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "운영 환경에서는 목/시뮬레이터 비식별 서버 주소를 저장할 수 없습니다.");
        }
        log.warn("[Deid][Trust] 신뢰할 수 없는 비식별 주소가 저장되었습니다 — "
                        + "원본이 비식별본으로 서빙될 수 있음: {} activeProfiles={}",
                LogSanitizer.sanitize(reason), Arrays.toString(environment.getActiveProfiles()));
    }

    /**
     * 비신뢰 사유를 반환한다(신뢰 가능하면 null). 판정 축은 두 가지다.
     * <ol>
     *   <li>mock-mode — 외부 무접촉 자체 복사.</li>
     *   <li>위탁 대상 호스트가 알려진 목/시뮬레이터/루프백이거나, 호스트를 확인할 수 없음(fail-secure).</li>
     * </ol>
     *
     * <p>이 오버로드는 <b>기동 시</b> 경로다 — 배포값({@code @Value})과 배포 토글(mock-mode ·
     * kpst.enabled)을 함께 본다. 호스트 축 판정 자체는 {@link #untrustedReason(String)} 에 위임한다.
     */
    String untrustedReason() {
        if (mockMode) {
            return "deidentify mock-mode (자체 복사, 외부 무접촉)";
        }
        if (!kpstEnabled) {
            return null; // 위탁 엔드포인트 없음 — 판정 대상 아님
        }
        return untrustedReason(kpstBaseUrl);
    }

    /**
     * <b>호스트 축 순수 판정</b> — 주어진 주소가 목/시뮬레이터/루프백인지, 호스트를 확인할 수 없는지.
     * 기동 시 경로와 저장 시 경로가 <b>같은 이 함수</b>를 쓴다(판정 복제 금지).
     *
     * <p>호스트 추출은 {@link SafeUrl#hostOf(String)} 에 위임한다 — 언더스코어 호스트
     * ({@code http://kpst_deid:9201}, 도커 컴포즈 서비스명에서 흔함)는 {@code URI#getHost()} 가
     * null 을 주는데, 이를 "판정 불가 → 비신뢰"로 떨구면 prd 에서 <b>앱 전체가 부팅 거부</b>된다.
     * 폴백으로 뽑은 호스트도 <b>동일한 deny 판정을 그대로 태운다</b>(차단 강도는 낮아지지 않는다).
     *
     * @param url 판정 대상 주소
     * @return 비신뢰 사유(신뢰 가능하면 {@code null})
     */
    String untrustedReason(String url) {
        String host = SafeUrl.hostOf(url);
        if (host == null) {
            return "비식별 서버 주소 호스트 파싱 불가"
                    + (url == null || url.isBlank() ? "(값 미설정)" : "(scheme://host:port 형식 확인 필요)")
                    + " — 판정 불가 → 비신뢰 처리";
        }
        if (isUntrustedHost(host)) {
            return "비식별 서버 주소가 목/시뮬레이터 호스트를 가리킴(host=" + host + ")";
        }
        return null;
    }

    private boolean isUntrustedHost(String host) {
        return UNTRUSTED_HOSTS.contains(host) || host.contains(MOCK_HOST_TOKEN);
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
