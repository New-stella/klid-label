package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeployedEnvironmentDetector} 순수 판정 + <b>기존 가드와의 규칙 일치</b> 검증.
 *
 * <p>이 판정 규칙은 {@link QuartzClusteringGuard} 가 이미 쓰던 것(프로파일 allowlist + {@code ENV}
 * 독립 축)을 <b>재사용</b>한 것이다. 두 곳이 각자 판정을 갖게 되면 프로파일이 늘어날 때 한쪽만
 * 고쳐지는 드리프트가 생기므로, 아래 마지막 테스트가 "두 판정이 항상 반대"임을 매트릭스로 고정한다.
 */
class DeployedEnvironmentDetectorTest {

    @Test
    @DisplayName("local_dev_프로파일에_배포표식이_없으면_배포환경이_아니다")
    void localAndDevAreNotDeployed() {
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("local"), null)).isFalse();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("dev"), null)).isFalse();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("local", "dev"), "")).isFalse();
        // 미지 라벨(qa 등)은 배포 표식이 아니다 — 사내 임시 환경을 막지 않는다.
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("dev"), "qa")).isFalse();
    }

    @Test
    @DisplayName("stg_prd_프로파일은_배포환경이다")
    void stgAndPrdAreDeployed() {
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("stg"), null)).isTrue();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("prd"), null)).isTrue();
    }

    @Test
    @DisplayName("혼합_오타_미지정_프로파일은_fail_closed로_배포환경으로_본다")
    void unknownProfilesAreFailClosed() {
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("local", "prd"), null)).isTrue();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("prd1"), null)).isTrue();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("LOCAL"), null)).isTrue();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of(), null)).isTrue();
        assertThat(DeployedEnvironmentDetector.isDeployed(null, null)).isTrue();
    }

    @Test
    @DisplayName("배포표식_ENV가_stg_prd면_프로파일이_dev여도_배포환경이다")
    void deployedEnvMarkerWins() {
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("dev"), "prd")).isTrue();
        assertThat(DeployedEnvironmentDetector.isDeployed(List.of("local"), " STG ")).isTrue();
    }

    @Test
    @DisplayName("Environment_빈에서도_동일하게_판정한다")
    void resolvesFromEnvironment() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(new DeployedEnvironmentDetector(dev).isDeployed()).isFalse();

        MockEnvironment prd = new MockEnvironment();
        prd.setActiveProfiles("prd");
        assertThat(new DeployedEnvironmentDetector(prd).isDeployed()).isTrue();

        MockEnvironment devOnDeployedHost = new MockEnvironment();
        devOnDeployedHost.setActiveProfiles("dev");
        devOnDeployedHost.setProperty("ENV", "stg");
        assertThat(new DeployedEnvironmentDetector(devOnDeployedHost).isDeployed()).isTrue();
    }

    /** 판정 축을 공유하는 모든 가드에 동일하게 먹이는 프로파일 조합. */
    private static List<List<String>> profileCases() {
        return List.of(
                List.of(), List.of("local"), List.of("dev"), List.of("local", "dev"),
                List.of("stg"), List.of("prd"), List.of("local", "prd"), List.of("prd1"),
                List.of("LOCAL"), List.of("qa"));
    }

    /** 배포 표식({@code ENV}) 조합 — 미설정(null)·공백·미지 라벨·정규화 대상 포함. */
    private static List<String> envCases() {
        List<String> cases = new java.util.ArrayList<>(List.of("", "qa", "stg", "prd", " STG "));
        cases.add(null);
        return cases;
    }

    @Test
    @DisplayName("QuartzClusteringGuard의_단일노드_허용_판정과_항상_반대다_드리프트_가드")
    void agreesWithQuartzClusteringGuard() {
        List<List<String>> profileCases = profileCases();
        List<String> envCases = envCases();

        for (List<String> profiles : profileCases) {
            for (String envName : envCases) {
                boolean deployed = DeployedEnvironmentDetector.isDeployed(profiles, envName);
                // 클러스터링 off 상태에서 가드가 통과하면 = 단일 노드(비배포)로 인정한 것.
                boolean singleNodeAllowed = true;
                try {
                    QuartzClusteringGuard.verify(profiles, envName, false);
                } catch (IllegalStateException e) {
                    singleNodeAllowed = false;
                }
                assertThat(deployed)
                        .as("profiles=%s ENV=%s 판정이 QuartzClusteringGuard 와 어긋났다", profiles, envName)
                        .isEqualTo(!singleNodeAllowed);
            }
        }
    }

    @Test
    @DisplayName("ProfileGatedUrlPolicy의_완화_인정_판정과_항상_반대다_드리프트_가드")
    void agreesWithProfileGatedUrlPolicy() {
        for (List<String> profiles : profileCases()) {
            for (String envName : envCases()) {
                boolean deployed = DeployedEnvironmentDetector.isDeployed(profiles, envName);

                MockEnvironment environment = new MockEnvironment();
                environment.setActiveProfiles(profiles.toArray(String[]::new));
                if (envName != null) {
                    environment.setProperty("ENV", envName);
                }
                // 완화 플래그가 켜진 상태에서 기동 assert 가 통과하면 = 완화를 인정한 것(비배포).
                boolean relaxationAllowed = true;
                try {
                    new TestUrlPolicy(environment).verifyRelaxationScope();
                } catch (IllegalStateException e) {
                    relaxationAllowed = false;
                }
                assertThat(deployed)
                        .as("profiles=%s ENV=%s 판정이 ProfileGatedUrlPolicy 와 어긋났다", profiles, envName)
                        .isEqualTo(!relaxationAllowed);
            }
        }
    }

    // DevProfileGuard 와의 일치 매트릭스는 그 가드의 verify() 가 package-private 이라
    // 같은 패키지의 DevProfileGuardTest 가 보유한다(agreesWithDeployedEnvironmentDetector).

    /** {@link ProfileGatedUrlPolicy} 완화 플래그 ON 고정 — 판정 축만 검증하기 위한 최소 구현체. */
    private static final class TestUrlPolicy extends ProfileGatedUrlPolicy {
        private TestUrlPolicy(MockEnvironment environment) {
            super("Test", "test.base-url", "test.allow-insecure-url", environment, true);
        }
    }
}
