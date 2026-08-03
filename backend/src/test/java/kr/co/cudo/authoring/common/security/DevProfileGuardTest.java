package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dev 프로파일 오배포 가드 테스트 (HIGH-2 / CWE-306·1188).
 *
 * <p>배경: base {@code docker-compose.yml} 의 기본 프로파일이 local → dev 로 바뀌면서, 프로파일을
 * 지정하지 않고 아무 서버에 올려도 dev 로 정상 부팅한다. dev 는 {@code /v1/dev/tokens} 가
 * permitAll + {@code DEV_LOGIN_ENABLED:true} 라 <b>인증 없이 REVIEWER JWT 발급</b>이 가능하므로,
 * 배포 환경 표식(ENV)이 stg/prd 인데 dev 프로파일로 뜨면 부팅을 거부해야 한다.
 *
 * <p>판정 신호는 기존 {@link LocalProfileGuard} 와 동일한 {@code ENV} 환경변수를 재사용한다
 * (새 환경변수 발명 금지). 정상 dev 환경(cudo_246: ENV 미설정 또는 ENV=dev)은 반드시 기동해야 한다.
 */
class DevProfileGuardTest {

    @Test
    @DisplayName("dev프로파일이_운영환경에_배포되면_부팅이_거부된다")
    void devProfileOnDeployedEnvironmentIsRejected() {
        for (String deployedEnv : new String[]{"prd", "stg", "PRD", " stg "}) {
            // given: active profile = dev 인데 배포 환경 표식은 운영/스테이징
            DevProfileGuard guard = new DevProfileGuard(env(true, deployedEnv));

            // when / then: 인증 없는 dev 토큰 발급 경로가 열린 채로 뜨면 안 된다
            assertThatThrownBy(guard::verify)
                    .as("ENV=%s 인데 dev 프로파일로 기동하면 부팅을 거부해야 한다", deployedEnv)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev");
        }
    }

    @Test
    @DisplayName("정상_dev환경에서는_가드가_기동을_막지_않는다")
    void normalDevEnvironmentBootsFine() {
        for (String allowedEnv : new String[]{null, "", "  ", "dev", "DEV", "local"}) {
            // given: cudo_246(정상 dev) — ENV 미설정 또는 dev/local
            DevProfileGuard guard = new DevProfileGuard(env(true, allowedEnv));

            // when / then
            assertThatCode(guard::verify)
                    .as("정상 dev 환경(ENV=%s)에서는 기동을 막으면 안 된다", allowedEnv)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("dev프로파일이_아니면_판정하지_않는다")
    void nonDevProfileIsNotJudged() {
        // given: stg/prd 정상 배포(active profile 이 dev 가 아님)
        DevProfileGuard guard = new DevProfileGuard(env(false, "prd"));

        // when / then: 운영 프로파일 정상 기동을 이 가드가 막아서는 안 된다
        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("알수없는_ENV값은_기동을_막지_않는다")
    void unknownEnvValueDoesNotBlockBoot() {
        // given: 프로젝트 표준 4환경(local/dev/stg/prd) 외 임의 라벨
        DevProfileGuard guard = new DevProfileGuard(env(true, "qa"));

        // when / then: LocalProfileGuard 와 동일하게 명시 운영 표식(stg/prd)만 거부한다
        //   (미지 라벨까지 거부하면 사내 임시 환경의 정상 기동을 막는다)
        assertThatCode(guard::verify).doesNotThrowAnyException();
        assertThat(DevProfileGuard.DEPLOYED_ENVS).containsExactlyInAnyOrder("stg", "prd");
    }

    @Test
    @DisplayName("DeployedEnvironmentDetector의_배포표식_판정과_항상_일치한다_드리프트_가드")
    void agreesWithDeployedEnvironmentDetector() {
        // 이 가드는 dev 프로파일 활성일 때만 판정하므로, 배포 여부 = ENV 표식 여부다.
        // 판정 규칙은 DeployedEnvironmentDetector 가 단독 보유하며 여기 사본을 두지 않는다.
        List<String> envCases = new java.util.ArrayList<>(
                List.of("", "  ", "qa", "dev", "local", "stg", "prd", "PRD", " stg "));
        envCases.add(null);

        for (String envName : envCases) {
            boolean deployed = DeployedEnvironmentDetector.isDeployed(List.of("dev"), envName);

            boolean booted = true;
            try {
                new DevProfileGuard(env(true, envName)).verify();
            } catch (IllegalStateException e) {
                booted = false;
            }
            assertThat(deployed)
                    .as("ENV=%s 판정이 DeployedEnvironmentDetector 와 어긋났다", envName)
                    .isEqualTo(!booted);
        }
    }

    /** active profile 에 dev 포함 여부 + ENV 값을 갖는 Environment mock. */
    private Environment env(boolean devActive, String envName) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenAnswer((Answer<Boolean>) i -> devActive);
        when(environment.getProperty("ENV")).thenReturn(envName);
        return environment;
    }
}
