package kr.co.cudo.authoring.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebClientConfig 의 VLM URL 검증(SSRF / HTTPS / placeholder fail-closed) 단위 테스트.
 *
 * <p>대상: {@link WebClientConfig#vlmWebClient(String, String, boolean, Environment)}
 *  - enabled=true + 운영/미지정 프로파일 → 기존 강제(HTTPS + 공인 호스트 + placeholder 차단)
 *  - enabled=true + local/dev 프로파일 → 목 서버(mock-server) 평문 http/내부 호스트 허용
 *  - enabled=false 일 때 검증 생략 (개발 환경 영향 0)
 */
class WebClientConfigTest {

    private final WebClientConfig cfg = new WebClientConfig();

    /** 프로파일 미지정 — 운영으로 간주하는 기본(fail-closed) 환경. */
    private static Environment strictEnv() {
        return new MockEnvironment();
    }

    private static Environment profileEnv(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("WebClientConfig_vlm_enabled_false_시_검증_생략_localhost_허용")
    void enabledFalseSkipsValidation() {
        // given / when / then — enabled=false 면 어떤 URL 이든 빈 생성 성공
        assertThat(cfg.vlmWebClient("http://localhost:9400", "", false, strictEnv())).isNotNull();
        assertThat(cfg.vlmWebClient("", "", false, strictEnv())).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_http_시_빈_생성_실패_HTTPS_강제")
    void httpSchemaRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("http://vlm.example.com", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_localhost_시_enabled_true_빈_생성_실패_SSRF_차단")
    void localhostRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://localhost:9400", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_private_IP_시_빈_생성_실패_SSRF_차단")
    void privateIpRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://10.0.0.5", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        assertThatThrownBy(() -> cfg.vlmWebClient("https://192.168.1.10", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://172.16.0.1", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://169.254.169.254", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://127.0.0.1", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_placeholder_시_빈_생성_실패_fail_closed")
    void placeholderRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://example.com", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://your-vlm-service", "", true, strictEnv()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_정상_HTTPS_공인_IP_시_빈_생성_성공")
    void validHttpsPublicIpAccepted() {
        // 공인 IP(8.8.8.8 — Google DNS) 직접 사용해 DNS 의존성 없이 검증 성공 케이스만 확인.
        assertThat(cfg.vlmWebClient("https://8.8.8.8/", "", true, strictEnv())).isNotNull();
    }

    @Test
    @DisplayName("VLM_local_dev_프로파일은_목서버_평문http_컨테이너주소를_허용한다")
    void mockServerUrlAcceptedOnLocalAndDevProfiles() {
        // given: local/dev 의 VLM 위탁 대상은 목 서버(http, TLS 미지원, 컨테이너 내부 네트워크)다.
        //   여기서 거부하면 vlmWebClient 빈 생성 실패 → 애플리케이션이 아예 기동하지 못한다.
        String mockServerUrl = "http://klid-mock-server:9400";

        // when / then: 호스트 해석 여부와 무관하게(도커 밖에서는 해석 불가) 빈이 생성돼야 한다
        assertThat(cfg.vlmWebClient(mockServerUrl, "", true, profileEnv("local"))).isNotNull();
        assertThat(cfg.vlmWebClient(mockServerUrl, "", true, profileEnv("dev"))).isNotNull();
        // 네이티브 bootRun 경로(호스트 루프백)도 동일하게 허용된다
        assertThat(cfg.vlmWebClient("http://localhost:9400", "", true, profileEnv("local"))).isNotNull();
    }

    @Test
    @DisplayName("VLM_local_프로파일이어도_placeholder_호스트와_빈값은_거부한다")
    void placeholderStillRejectedOnLocalProfile() {
        // 완화 범위는 "평문 http + 내부 호스트"까지다 — 설정 누락(빈 값)·예제 호스트는 여전히 fail-closed
        assertThatThrownBy(() -> cfg.vlmWebClient("", "", true, profileEnv("local")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("http://your-vlm-service:9400", "", true, profileEnv("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> cfg.vlmWebClient("ftp://klid-mock-server:9400", "", true, profileEnv("local")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("VLM_운영표식이_있으면_local_프로파일이_섞여도_평문http를_거부한다")
    void productionMarkerOverridesLocalRelaxation() {
        // given: prd 프로파일 또는 ENV=prd 표식(DeidentifyEndpointTrustGuard 선례)이 있으면 운영이다
        MockEnvironment envMarker = new MockEnvironment();
        envMarker.setActiveProfiles("local");
        envMarker.setProperty("ENV", "prd");

        // when / then: local 프로파일이 섞여 들어와도 완화되지 않는다(운영 오배포 방어)
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, envMarker))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, profileEnv("prd", "local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        // stg 는 완화 대상이 아니다 — 운영급 강제를 유지한다
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, profileEnv("stg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("stg가_함께_활성이면_dev가_섞여도_평문http를_거부한다")
    void stagingProfileOverridesDevRelaxation() {
        // given: acceptsProfiles 는 OR 판정이라 dev,stg 동시 활성이면 완화가 따라붙을 수 있다(MED-1)
        // when / then: 배포 프로파일(stg)이 하나라도 있으면 완화 미적용 — 순서와 무관
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, profileEnv("dev", "stg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, profileEnv("stg", "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, profileEnv("local", "stg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");

        // ENV=stg 표식만 있어도 동일하게 거부한다 (DevProfileGuard.DEPLOYED_ENVS 선례)
        MockEnvironment envMarker = new MockEnvironment();
        envMarker.setActiveProfiles("dev");
        envMarker.setProperty("ENV", "STG");
        assertThatThrownBy(() -> cfg.vlmWebClient("http://klid-mock-server:9400", "", true, envMarker))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("메타데이터_대역_호스트는_개발프로파일에서도_거부된다")
    void metadataRangeRejectedEvenOnDevProfile() {
        // given: dev 에 잘못된 VLM_SERVICE_URL 이 주입돼 클라우드 메타데이터 대역을 향하는 상황(MED-2)
        // when / then: 해석에 성공한 링크로컬/메타데이터 대역은 완화 경로에서도 차단
        assertThatThrownBy(() -> cfg.vlmWebClient("http://169.254.169.254", "", true, profileEnv("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> cfg.vlmWebClient("http://169.254.1.1:9400", "", true, profileEnv("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        // IPv6 링크로컬(fe80::/10)도 동일하게 차단
        assertThatThrownBy(() -> cfg.vlmWebClient("http://[fe80::1]:9400", "", true, profileEnv("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("링크로컬");
    }

    @Test
    @DisplayName("해석되지_않는_컨테이너명은_개발프로파일에서_계속_허용된다")
    void unresolvableContainerNameStillAllowedOnDevProfile() {
        // given: 도커 밖(네이티브 bootRun)에서는 컨테이너명이 해석되지 않는다 — 이때 기동이 막히면 회귀다
        String unresolvable = "http://klid-mock-server-does-not-resolve-" + System.nanoTime() + ":9400";

        // when / then: 해석 실패는 통과(완화 유지). 도커 경로/네이티브 루프백 경로도 함께 회귀 방지
        assertThat(cfg.vlmWebClient(unresolvable, "", true, profileEnv("local"))).isNotNull();
        assertThat(cfg.vlmWebClient(unresolvable, "", true, profileEnv("dev"))).isNotNull();
        assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "", true, profileEnv("local"))).isNotNull();
        assertThat(cfg.vlmWebClient("http://localhost:9400", "", true, profileEnv("local"))).isNotNull();
        assertThat(cfg.vlmWebClient("http://127.0.0.1:9400", "", true, profileEnv("dev"))).isNotNull();
    }

    @Test
    @DisplayName("평문구간에_토큰이_설정되면_경고한다")
    void cleartextTokenLogsWarning() {
        // given: dev 에서 VLM_SERVICE_TOKEN 을 넣으면 Bearer 토큰이 평문 http 로 흐른다(MED-3)
        Logger logger = (Logger) LoggerFactory.getLogger(WebClientConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when
            assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "secret-token-value", true, profileEnv("local")))
                    .isNotNull();

            // then: 경고가 남되 토큰 값은 절대 출력되지 않는다(길이만)
            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warns).anyMatch(m -> m.contains("평문") && m.contains("토큰"));
            assertThat(warns).anyMatch(m -> m.contains("tokenLength=18"));
            assertThat(warns).noneMatch(m -> m.contains("secret-token-value"));

            // and: HTTPS 구간에서는 경고하지 않는다
            appender.list.clear();
            assertThat(cfg.vlmWebClient("https://8.8.8.8/", "secret-token-value", true, strictEnv())).isNotNull();
            assertThat(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("tokenLength")))
                    .isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
