package kr.co.cudo.authoring.common.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "지금 배포 환경(stg/prd)에서 돌고 있는가" 판정 — <b>런타임 정책 분기용 단일 원천</b>.
 *
 * <h3>왜 별도 빈인가</h3>
 * <p>이 판정 규칙(프로파일 allowlist + {@code ENV} 독립 축)은 {@link QuartzClusteringGuard} ·
 * {@link ProfileGatedUrlPolicy} · {@code DevProfileGuard} 가 각자 복제해 쓰던 것이다. 그 셋은 모두
 * <b>{@code @PostConstruct} 기동 가드</b>라 판정이 private 으로 갇혀 있어, 기동이 아닌 <b>실행시점</b>에
 * 같은 판정이 필요해지면 네 번째 복제본이 생길 수밖에 없었다. 규칙을 여기 한 곳에 두고
 * <b>세 가드가 모두 이를 재사용</b>하게 해 드리프트를 구조적으로 막는다(복제 금지 — 프로파일이
 * 늘어날 때 한쪽만 고쳐지면 "여기선 배포, 저기선 개발" 이 된다).
 * 일치는 {@code DeployedEnvironmentDetectorTest} 의 매트릭스가 고정한다.
 *
 * <h3>판정 규칙 (fail-closed)</h3>
 * <ol>
 *   <li><b>비배포 프로파일 allowlist</b>({@link #NON_DEPLOYED_PROFILES} = local/dev)에 활성 프로파일이
 *       <b>전부</b> 들어있을 때만 비배포로 인정한다. denylist("stg/prd 만 검사")가 아니므로 오타(prd1)·
 *       대소문자(LOCAL)·미지정(default)·혼합({@code local,prd})은 자동으로 배포 취급이다.</li>
 *   <li><b>배포 표식({@code ENV}) 독립 축</b> — {@code SPRING_PROFILES_ACTIVE} 를 dev 로 둔 채 배포
 *       서버에 올리는 실수를 프로파일 축만으로는 잡지 못한다. {@code DevProfileGuard.DEPLOYED_ENVS} 와
 *       동일 기준을 재사용하며 <b>배포 쪽이 이긴다</b>(새 환경변수 발명 금지).</li>
 * </ol>
 * <p>미지 라벨({@code ENV=qa} 등)은 배포 표식이 아니다 — 사내 임시 환경의 정상 동작을 막지 않는다
 * (기존 세 가드와 동일 강도).
 *
 * <p>정적 설정값만 읽는 <b>순수 판정</b>이며 외부 프로세스를 호출하지 않는다. 판정에 네트워크가
 * 끼어들면 상대의 기동 순서에 따라 결과가 흔들려 "설정으로 재현 가능한 정책"이 아니게 된다.
 */
@Component
public class DeployedEnvironmentDetector {

    /** 배포로 보지 <b>않는</b> 프로파일 allowlist. 여기 밖은 전부 배포 취급(fail-closed). */
    public static final Set<String> NON_DEPLOYED_PROFILES = Set.of("local", "dev");

    /** 배포 환경 표식({@code ENV}) — 이 판정의 단일 원천(구 {@code DevProfileGuard.DEPLOYED_ENVS}). */
    public static final Set<String> DEPLOYED_ENV_MARKERS = Set.of("stg", "prd");

    private final Environment environment;

    public DeployedEnvironmentDetector(Environment environment) {
        this.environment = environment;
    }

    /** 현재 실행 환경이 배포(stg/prd)인가. */
    public boolean isDeployed() {
        return isDeployed(List.of(environment.getActiveProfiles()), environment.getProperty("ENV"));
    }

    /**
     * 순수 판정 — 컨테이너 없이도 검증 가능하도록 입력을 인자로 받는다.
     *
     * @param activeProfiles 활성 스프링 프로파일 (null/빈 값이면 default → 배포 취급)
     * @param envName        배포 환경 표식 {@code ENV} 값 (없으면 {@code null})
     */
    public static boolean isDeployed(List<String> activeProfiles, String envName) {
        if (deployedEnvMarker(envName) != null) {
            return true;
        }
        return activeProfiles == null
                || activeProfiles.isEmpty()
                || !NON_DEPLOYED_PROFILES.containsAll(activeProfiles);
    }

    /** {@code ENV} 가 배포 표식이면 정규화된 값을, 아니면 {@code null} 을 돌려준다. */
    public static String deployedEnvMarker(String envName) {
        if (envName == null || envName.isBlank()) {
            return null;
        }
        String normalized = envName.trim().toLowerCase(Locale.ROOT);
        return DEPLOYED_ENV_MARKERS.contains(normalized) ? normalized : null;
    }
}
