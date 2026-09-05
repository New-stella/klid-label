package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * <b>운영 엄격 / 개발 완화</b> 외부 base-url 검증의 <b>공용 골격</b>.
 *
 * <h3>왜 골격을 공용화하는가</h3>
 * <p>판정 자체는 이미 {@link ExternalUrlPolicy} 하나로 모았지만, "언제 완화를 인정하는가" 라는
 * <b>격리 규칙</b>(전용 플래그 + 프로파일 allowlist + 기동 assert)은 {@code VlmUrlPolicy} 안에만 있었다.
 * 그 결과 새 외부 연동(증강)이 추가될 때 <b>자체 검증을 다시 짜는</b> 길이 열려 있었고, 실제로 증강
 * 클라이언트는 "스키마만 보는" 약한 검증을 별도로 갖게 되어 <b>같은 설정값에서 VLM 은 막고 증강은 통과</b>
 * 하는 정책 비대칭이 생겼다(DEV_FIX HIGH-1). 이 클래스는 그 비대칭이 다시 생기지 않도록 격리 규칙을
 * 한 곳에 두고 연동별 차이를 <b>생성자 인자 3개</b>(로그 태그 · URL 프로퍼티명 · 완화 플래그명)로만 표현한다.
 *
 * <h3>완화의 격리 설계 — "설정만으로 prd 를 뚫을 수 없다"</h3>
 * <ol>
 *   <li><b>전용 프로퍼티</b>로만 완화가 켜진다 — 프로파일 이름이나 URL 모양으로 <b>암묵</b> 완화되는
 *       경로가 없다.</li>
 *   <li><b>allowlist 프로파일</b>({@link #INSECURE_ALLOWED_PROFILES} = local/dev)에서만 그 플래그를
 *       인정한다. denylist("prd 만 차단")가 아니라 allowlist 이므로 stg·오타·미지정 프로파일은
 *       자동으로 엄격이다(fail-closed).</li>
 *   <li><b>배포 환경 표식({@code ENV}) 독립 축</b> — 프로파일과 별개로 {@code ENV=stg|prd} 가 설정되어
 *       있으면 완화를 인정하지 않는다({@link #DEPLOYED_ENV_MARKERS}). 프로파일 축만 보면
 *       {@code SPRING_PROFILES_ACTIVE} 를 dev 로 두고 배포 서버에 올리는 실수를 잡지 못한다 —
 *       {@code DevProfileGuard.DEPLOYED_ENVS} 와 <b>동일 기준</b>을 재사용해 <b>배포 쪽이 이긴다</b>.</li>
 *   <li><b>기동 assert</b>({@link #verifyRelaxationScope()}) — 위 두 축을 통과하지 못한 상태에서 플래그가
 *       켜져 있으면 <b>기동 자체를 실패</b>시킨다. 운영에 완화 설정이 들어오는 순간 배포가 죽으므로
 *       조용히 새지 않는다.</li>
 * </ol>
 * {@link #check(String)} 는 기동 assert 와 독립적으로 다시 프로파일을 확인해 엄격/완화 정책을 고르므로,
 * assert 를 우회하더라도 검증 자체가 완화되지 않는다(이중 방어).
 *
 * <h3>⚠ 현재 이 골격을 쓰는 연동은 <b>하나도 없다</b> (2026-09-01)</h3>
 * <p>시계열 위탁({@code VlmUrlPolicy})에 이어 증강 위탁({@code AugmentUrlPolicy})도 이 골격에서
 * <b>빠졌다</b> — 「연동 주소는 대역·전송을 강제하지 않고 스킴·형식만 본다」는 확정 정책이 두 연동을
 * 모두 대상으로 명시하기 때문이다. 위 서술의 "연동마다 검증 강도가 갈라지지 않는다" 는 <b>이 골격을
 * 쓰는 연동들 사이</b>에서만 유효하며, 강도가 갈린 것이 곧 결함이라는 뜻이 아니다(무엇이 확정
 * 정책인지가 판정한다).
 *
 * <p>⚠ <b>그럼에도 이 클래스를 지우지 않는다</b> — {@code QuartzClusteringGuard}·{@code DevProfileGuard}·
 * {@link DeployedEnvironmentDetector} 가 이 골격의 <b>ENV 판정 축</b>과 같은 기준을 쓰고,
 * {@code DeployedEnvironmentDetectorTest} 가 그 정합을 이 클래스의 기동 assert 로 <b>실제로 문다</b>.
 * 프로덕션 하위 클래스가 없다는 사실을 근거로 삭제하면 그 대조 가드가 함께 사라진다.
 */
@Slf4j
public abstract class ProfileGatedUrlPolicy {

    /**
     * 완화 플래그를 <b>인정</b>하는 프로파일 allowlist. 여기에 없으면 무조건 엄격 + 플래그 시 기동 실패.
     * 판정 규칙 자체는 {@link DeployedEnvironmentDetector} 가 단독 보유한다(복제 금지).
     */
    static final Set<String> INSECURE_ALLOWED_PROFILES = DeployedEnvironmentDetector.NON_DEPLOYED_PROFILES;

    /**
     * 배포 환경 표식({@code ENV}) — 프로파일과 <b>독립된 두 번째 방어축</b>.
     * {@link DeployedEnvironmentDetector} 와 동일 기준(새 환경변수 발명 금지).
     */
    static final Set<String> DEPLOYED_ENV_MARKERS = DeployedEnvironmentDetector.DEPLOYED_ENV_MARKERS;

    private final String logTag;
    private final String flagProperty;
    /** 운영 표준 — HTTPS 전용 + 사설/내부 대역 차단. */
    private final ExternalUrlPolicy strict;
    /** local/dev 목업 전용 — 평문 http + 사설 IP 허용(placeholder/스키마 차단은 유지). */
    private final ExternalUrlPolicy relaxed;
    private final Environment environment;
    private final boolean allowInsecureUrl;

    protected ProfileGatedUrlPolicy(String logTag, String urlProperty, String flagProperty,
                                    Environment environment, boolean allowInsecureUrl) {
        this.logTag = logTag;
        this.flagProperty = flagProperty;
        this.strict = ExternalUrlPolicy.strict(urlProperty);
        this.relaxed = ExternalUrlPolicy.internalNetwork(urlProperty);
        this.environment = environment;
        this.allowInsecureUrl = allowInsecureUrl;
    }

    /**
     * 완화 플래그가 허용 프로파일 밖에서 켜져 있으면 기동을 중단한다(fail-closed).
     *
     * <p>해당 연동의 활성 토글(enabled/mode)과 <b>무관하게</b> 검사한다 — 운영 설정 파일에 완화 값이
     * 들어와 있다는 사실 자체가 사고 신호이므로, 토글이 꺼져 있어 "지금은 무해" 하더라도 배포를 막는다.
     */
    @PostConstruct
    public void verifyRelaxationScope() {
        if (!allowInsecureUrl || profileAllowsRelaxation()) {
            if (allowInsecureUrl) {
                log.warn("[{}] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용. property={}",
                        logTag, flagProperty);
            }
            return;
        }
        throw new IllegalStateException(
                flagProperty + "=true 는 " + INSECURE_ALLOWED_PROFILES + " 프로파일에서만 허용됩니다"
                        + " (현재 활성 프로파일=" + Arrays.toString(environment.getActiveProfiles())
                        + ", ENV=" + deployedEnvMarker() + "). 운영 환경의 평문/사설 외부 URL 을 원천 차단합니다.");
    }

    /**
     * 평문(http) 엔드포인트에 인증 토큰이 설정된 경우 경고를 남긴다 (CWE-319).
     *
     * <p>판정·문구는 {@link ExternalUrlPolicy#warnIfTokenOnCleartext} 가 단독 소유한다 — 여기서 다시
     * 쓰면 그것이 두 번째 진실원이 되어 한쪽만 고쳐진다. 이 메서드는 로그 태그만 채워 넘긴다.
     */
    public void warnIfTokenOnCleartext(String baseUrl, String token) {
        ExternalUrlPolicy.warnIfTokenOnCleartext(logTag, baseUrl, token);
    }

    /**
     * base-url 을 현재 프로파일에 맞는 정책으로 검증한다.
     *
     * @return https 면 {@code true}, http 면 {@code false} (호출자의 TLS 구성 분기용)
     * @throws IllegalStateException 정책 위반 시 (빈 생성 실패 → 기동 차단)
     */
    public boolean check(String baseUrl) {
        return policy().check(baseUrl);
    }

    /** {@link #check(String)} 의 반환값이 필요 없는 호출부용 별칭. */
    public void validate(String baseUrl) {
        check(baseUrl);
    }

    /**
     * 이미 해석된 주소 집합에 대해 <b>현재 프로파일의 정책</b>으로 대역 판정만 수행한다.
     *
     * <p>{@link #check(String)} 는 DNS 해석 + 판정을 함께 하므로, 다중 A/AAAA 응답(= 하나만 위험 대역인
     * 호스트)을 재현하려면 실 DNS 를 조작해야 한다. 해석과 판정을 분리해 두면 판정 규칙을 프로파일별로
     * 그대로 검증할 수 있다({@link ExternalUrlPolicy#verifyResolvedAddresses}).
     */
    void verifyResolvedAddresses(String host, InetAddress... addresses) {
        policy().verifyResolvedAddresses(host, addresses);
    }

    /** 현재 적용 정책 — 완화 플래그 ON <b>이면서</b> 허용 프로파일일 때만 완화. */
    private ExternalUrlPolicy policy() {
        return (allowInsecureUrl && profileAllowsRelaxation()) ? relaxed : strict;
    }

    /**
     * 활성 프로파일이 <b>전부</b> allowlist 안이고 <b>배포 표식({@code ENV})이 없을 때만</b> 완화를 인정한다.
     *
     * <p>"하나라도 포함(any)" 이면 {@code local,prd} 같은 혼합 지정으로 운영에서 완화가 살아난다.
     * 활성 프로파일이 아예 없는 경우(=default)도 완화하지 않는다.
     *
     * <p>프로파일 축과 별개로 {@code ENV=stg|prd} 표식을 먼저 거부한다 — {@code SPRING_PROFILES_ACTIVE}
     * 를 dev 로 둔 채 배포 서버에 올리는 실수를 프로파일 축만으로는 잡을 수 없기 때문이다
     * (<b>배포 쪽이 이긴다</b>).
     */
    private boolean profileAllowsRelaxation() {
        return !DeployedEnvironmentDetector.isDeployed(
                List.of(environment.getActiveProfiles()), environment.getProperty("ENV"));
    }

    /** {@code ENV} 가 배포 표식이면 정규화된 값을, 아니면 {@code null} 을 돌려준다. */
    private String deployedEnvMarker() {
        return DeployedEnvironmentDetector.deployedEnvMarker(environment.getProperty("ENV"));
    }
}
