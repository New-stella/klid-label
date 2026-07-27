package kr.co.cudo.authoring.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dev 로그인 토글 정책 회귀 (A-ISSUE-05).
 *
 * <ul>
 *   <li>stg 는 OFF — 인증 없이 임의 권한 토큰을 발급하는 {@code /v1/dev/tokens} 가 온프렘 개발서버에
 *       노출되던 경로를 닫는다.</li>
 *   <li>local/dev 는 ON 유지 — 관제서버 없이 부트스트랩해야 하는 환경.</li>
 *   <li>prd <b>및 stg</b> 는 설정 자체를 fail-fast — WARN 로그가 아니라 부팅 거부 (DEV_FIX H-3).
 *       yml 리터럴 {@code false} 는 환경변수 {@code AUTHORING_DEV_LOGIN_ENABLED=true} 하나로 덮이므로
 *       파일값만으로는 stg 정책이 강제되지 않았다.</li>
 * </ul>
 */
class DevLoginToggleTest {

    private Object devLoginEnabled(String yaml) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml));
        return sources.stream()
                .map(s -> s.getProperty("authoring.dev.login.enabled"))
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("dev_토큰_발급은_stg_에서_비활성")
    void devLoginDisabledOnStg() throws IOException {
        assertThat(String.valueOf(devLoginEnabled("application-stg.yml"))).isEqualTo("false");
    }

    @Test
    @DisplayName("dev_프로파일은_dev_토큰_발급_유지_회귀방지")
    void devLoginStillEnabledOnDev() throws IOException {
        assertThat(String.valueOf(devLoginEnabled("application-dev.yml"))).isEqualTo("true");
    }

    @Test
    @DisplayName("prd_프로파일에서_dev_로그인_활성화시_부팅_거부")
    void prdWithDevLoginFailsFast() {
        assertThatThrownBy(() -> DevToggleProfileGuard.verify(true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prd");
    }

    @Test
    @DisplayName("prd_라도_dev_로그인_비활성이면_정상_기동")
    void prdWithoutDevLoginBoots() {
        assertThatCode(() -> DevToggleProfileGuard.verify(true, false)).doesNotThrowAnyException();
        assertThatCode(() -> DevToggleProfileGuard.verify(false, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stg_프로파일에서_dev_로그인_환경변수_활성화시_부팅_거부")
    void stgWithDevLoginFailsFast() {
        // given: stg 활성 + 환경변수로 dev 로그인이 덮여 켜진 상황(yml 리터럴 false 를 무력화한 케이스)
        // when/then: 부팅 거부 — 메시지에 stg 가 명시되어 운영자가 원인을 즉시 식별할 수 있어야 한다.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("stg");
        assertThatThrownBy(() -> new DevToggleProfileGuard(env, true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stg");
    }

    @Test
    @DisplayName("stg_프로파일도_dev_로그인_비활성이면_정상_기동")
    void stgWithoutDevLoginBoots() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("stg");
        assertThatCode(() -> new DevToggleProfileGuard(env, false).verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local_dev_프로파일은_dev_로그인_켜져도_기동_유지_회귀방지")
    void localAndDevStillBootWithDevLogin() {
        // 부팅 차단 대상을 운영 계열로 넓히면서 bring-up 환경까지 막아버리지 않았는지 확인한다.
        for (String profile : new String[]{"local", "dev"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThatCode(() -> new DevToggleProfileGuard(env, true).verify()).doesNotThrowAnyException();
        }
    }
}
