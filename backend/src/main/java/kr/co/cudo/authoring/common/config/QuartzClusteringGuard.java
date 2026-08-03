package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 배포 환경(stg/prd) Quartz <b>클러스터링 강제</b> — B-ISSUE-81 / B-ISSUE-02.
 *
 * <h3>왜 기동 차단인가</h3>
 * <p>배포 토폴로지는 <b>주 서버 2노드 Active-Active</b> 인데 설정은 전 프로파일이
 * {@code isClustered=false} 였다. {@code @DisallowConcurrentExecution} 은 <b>스케줄러 인스턴스
 * 내부에서만</b> 유효하므로 두 노드의 중복 발화를 막지 못한다 — 관제 학습용 스캔(60s)·KPST 폴링(30s)·
 * export sweep(600s) 트리거가 노드마다 각각 발화한다. 기동·헬스체크·로그는 모두 정상으로 보이고
 * 중복 처리·경합만 조용히 늘어나는 실패라(운영 로그의 WARN 은 배포 로그에 묻힌다) 경고로는 막을 수 없다.
 *
 * <p>그래서 {@link ProfileGatedUrlPolicy}/{@link GenAiIntegrationWiringGuard} 와 <b>동일한 강도·구조</b>로
 * 기동 자체를 실패시킨다. 해제 수단은 명시적이다 — 배포 환경이면 클러스터링을 켜고, 단일 노드
 * 개발 환경(local/dev)이면 그대로 둔다.
 *
 * <h3>allowlist 설계 — "설정만으로 배포 환경을 뚫을 수 없다"</h3>
 * <ol>
 *   <li><b>단일 노드 허용 프로파일 allowlist</b>({@link #SINGLE_NODE_PROFILES} = local/dev)에서만
 *       클러스터링 없이 기동한다. denylist("stg/prd 만 검사")가 아니므로 오타(prd1)·대소문자
 *       (LOCAL)·미지정(default)·혼합({@code local,prd})은 <b>자동으로 엄격</b>이다(fail-closed).</li>
 *   <li><b>배포 표식({@code ENV}) 독립 축</b> — {@code SPRING_PROFILES_ACTIVE} 를 dev 로 둔 채 배포
 *       서버에 올리는 실수를 프로파일 축만으로는 잡지 못한다. {@code DevProfileGuard.DEPLOYED_ENVS} 와
 *       동일 기준을 재사용해 <b>배포 쪽이 이긴다</b>(새 환경변수 발명 금지).</li>
 *   <li><b>{@code @PostConstruct} assert</b> — 판정은 순수 함수({@link #verify})로 분리해 컨테이너 없이도
 *       단위 검증하고, 실제 배선은 컨텍스트 refresh 테스트로 고정한다.</li>
 * </ol>
 * 값은 Quartz 실 프로퍼티({@link #KEY_CLUSTERED})에서 직접 읽으므로 환경변수
 * {@code QUARTZ_CLUSTERED=false} 로 되돌리면 그 즉시 기동이 거부된다(설정 우회 불가).
 *
 * <p>⚠ 클러스터링은 <b>트리거 중복 발화</b>만 막는다. 잡 내부에서 여러 노드/스레드가 같은 행을 집는
 * 레이스는 별도로 원자 클레임(조건부 UPDATE)이 막는다 — 어느 한쪽이 다른 쪽을 대체하지 않는다.
 *
 * <p>⚠ 클러스터 모드는 노드 간 <b>시계 동기(NTP/chrony)</b>가 전제다(misfire/중복 발화 방지).
 */
@Slf4j
@Component
public class QuartzClusteringGuard {

    /** Quartz JobStore 클러스터링 스위치(application.yml 의 {@code QUARTZ_CLUSTERED} 로 주입). */
    static final String KEY_CLUSTERED = "spring.quartz.properties.org.quartz.jobStore.isClustered";

    /**
     * 클러스터링 없이 기동해도 되는 <b>단일 노드</b> 프로파일 allowlist. 여기 밖은 무조건 엄격.
     * 판정 규칙 자체는 {@link DeployedEnvironmentDetector} 가 단독 보유한다(복제 금지).
     */
    static final Set<String> SINGLE_NODE_PROFILES = DeployedEnvironmentDetector.NON_DEPLOYED_PROFILES;

    private final Environment environment;

    public QuartzClusteringGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        boolean clustered = environment.getProperty(KEY_CLUSTERED, Boolean.class, Boolean.FALSE);
        verify(List.of(environment.getActiveProfiles()), environment.getProperty("ENV"), clustered);
        if (clustered) {
            log.info("[Quartz] 클러스터링 활성 — 다중 노드에서 트리거가 1회만 발화합니다."
                    + " 노드 간 시계 동기(NTP) 필수.");
        } else {
            log.info("[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일={}",
                    List.of(environment.getActiveProfiles()));
        }
    }

    /**
     * 순수 판정 — 배포 환경인데 클러스터링이 꺼져 있으면 거부한다.
     *
     * @param activeProfiles 활성 스프링 프로파일(비어 있으면 default)
     * @param envName        배포 환경 표식 {@code ENV} 값(없으면 {@code null})
     * @param clustered      {@link #KEY_CLUSTERED} 실효값
     * @throws IllegalStateException 단일 노드 허용 범위 밖에서 클러스터링이 꺼져 있을 때
     */
    static void verify(List<String> activeProfiles, String envName, boolean clustered) {
        if (clustered || singleNodeAllowed(activeProfiles, envName)) {
            return;
        }
        throw new IllegalStateException(
                KEY_CLUSTERED + "=false 는 " + SINGLE_NODE_PROFILES + " 프로파일에서만 허용됩니다"
                        + " (현재 활성 프로파일=" + activeProfiles + ", ENV=" + deployedEnvMarker(envName) + ")."
                        + " 배포 환경은 2노드 Active-Active 이므로 클러스터링이 꺼지면 배치 트리거가 노드마다"
                        + " 중복 발화합니다(@DisallowConcurrentExecution 은 인스턴스 내부에서만 유효)."
                        + " QUARTZ_CLUSTERED=true 로 두 노드 모두 설정하세요(노드 간 시계 동기 NTP 필수).");
    }

    /**
     * 활성 프로파일이 <b>전부</b> allowlist 안이고 배포 표식({@code ENV})이 없을 때만 단일 노드를 인정한다.
     * 판정은 {@link DeployedEnvironmentDetector} 에 위임한다 — 규칙이 두 벌이면 프로파일이 늘어날 때
     * 한쪽만 고쳐지는 드리프트가 난다.
     */
    private static boolean singleNodeAllowed(List<String> activeProfiles, String envName) {
        return !DeployedEnvironmentDetector.isDeployed(activeProfiles, envName);
    }

    /** {@code ENV} 가 배포 표식이면 정규화된 값을, 아니면 {@code null} 을 돌려준다. */
    private static String deployedEnvMarker(String envName) {
        return DeployedEnvironmentDetector.deployedEnvMarker(envName);
    }
}
