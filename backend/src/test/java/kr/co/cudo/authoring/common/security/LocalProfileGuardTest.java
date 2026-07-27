package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LocalProfileGuard 회귀 테스트 (A-ISSUE-21).
 *
 * <p>이 가드는 "운영 환경(ENV=dev/stg/prd)에 local 프로파일이 잘못 배포되면 부팅 거부" 라는 배포 안전의
 * 마지막 방어선인데 전용 테스트가 0건이었다. {@code System.getenv} 모킹 라이브러리를 새로 도입하는 대신
 * 판정 본체를 {@code verify(String envName)} 로 추출해 표준 JUnit 으로 커버한다.
 */
class LocalProfileGuardTest {

    private LocalProfileGuard guardWithProfiles(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new LocalProfileGuard(env);
    }

    @Test
    @DisplayName("ENV_prd_인데_active_profile_local_이면_부팅_거부")
    void prdEnvWithLocalProfileThrows() {
        LocalProfileGuard guard = guardWithProfiles("local");

        assertThatThrownBy(() -> guard.verify("prd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local profile");
    }

    @Test
    @DisplayName("ENV_dev_stg_도_active_profile_local_이면_부팅_거부")
    void devAndStgEnvWithLocalProfileThrow() {
        LocalProfileGuard guard = guardWithProfiles("local");

        assertThatThrownBy(() -> guard.verify("dev")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.verify("stg")).isInstanceOf(IllegalStateException.class);
        // 대소문자 무시 판정 — 회귀 시 우회 경로가 되지 않도록 함께 고정한다.
        assertThatThrownBy(() -> guard.verify("PRD")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ENV_미설정_이면_active_profile_local_이어도_통과_개발자머신")
    void nullEnvWithLocalProfilePasses() {
        LocalProfileGuard guard = guardWithProfiles("local");

        assertThatCode(() -> guard.verify(null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.verify("")).doesNotThrowAnyException();
        assertThatCode(() -> guard.verify("local")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("active_profile_에_local_이_없으면_즉시_통과")
    void nonLocalProfilePassesImmediately() {
        LocalProfileGuard guard = guardWithProfiles("dev");

        assertThatCode(() -> guard.verify("prd")).doesNotThrowAnyException();
    }
}
