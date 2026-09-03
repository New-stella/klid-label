package kr.co.cudo.authoring.observability.health;

import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeidentifyHealthIndicator 단위 테스트 (Phase 12 + 로컬 외부0개 mock 모드).
 * <p>
 * 프로파일/YAML 의존 없이 인디케이터 로직만 검증한다 — mock-mode / kpst-enabled 플래그는
 * {@link ReflectionTestUtils} 로 직접 주입한다(@Value 는 수동 생성 시 적용되지 않는다).
 * <p>
 * 배선(어느 base-url 을 핑하는가)은 단위 생성으로는 고정되지 않으므로 하단
 * {@link #health_pings_kpst_base_url_not_legacy_deidentify_base_url()} /
 * {@link #context_starts_without_kpst_web_client_when_kpst_disabled()} 에서
 * {@link ApplicationContextRunner} 로 실제 주입 축을 고정한다(2026-07-28 오탐 회귀 방지).
 */
class DeidentifyHealthIndicatorTest {

    private MockWebServer mockServer;
    private WebClient webClient;
    private DeidentifyHealthIndicator indicator;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        webClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build();
        // trustGuard=null → 종전 동작(운영 여부를 묻지 않음). 운영 형상은 전용 테스트에서 주입한다.
        indicator = new DeidentifyHealthIndicator(webClient, null);
        // 실행 경로 기본값과 동일: kpst.deid.enabled 기본 true (KPST 위탁이 비식별 단일 경로).
        ReflectionTestUtils.setField(indicator, "kpstEnabled", true);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("mock모드_활성시_외부핑없이_UP_mock")
    void health_up_mock_when_mock_mode_enabled() {
        // given — local 외부0개: 실 비식별 서버 부재. 핑하지 않아야 한다.
        ReflectionTestUtils.setField(indicator, "mockMode", true);

        // when
        Health health = indicator.health();

        // then — 서버에 요청이 전혀 가지 않고 UP(mock).
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
        assertThat(health.getDetails()).containsEntry("mode", "mock");
        assertThat(mockServer.getRequestCount()).isZero();
    }

    /**
     * ★ ADR-062 회귀 고정 — <b>조용한 실패 금지</b>, 단 <b>거짓 「비정상」도 금지</b>.
     *
     * <p>자체 복사 축의 기동 차단이 산출 시점 거부로 옮겨가면서 "앱은 떴는데 비식별만 실패" 라는
     * 상태가 새로 생겼다. 그 상태에서 헬스가 {@code UP(mock)} 을 돌려주면 운영자는 배포 로그를 다시
     * 뒤지기 전까지 정상으로 착각한다 — 이 반전이 인지·수용한 주된 위험이 정확히 그것이다.
     *
     * <p>그렇다고 {@code DOWN} 도 아니다 — ①운영 화면에 정상 주소가 저장돼 있으면 비식별이 실제로
     * 동작할 수 있어 거짓일 수 있고 ②{@code DOWN} 은 집계 503 으로 멀쩡한 노드를 부하분산에서 빼는데
     * 이 문제는 회전으로 풀리지 않으며 ③형제 {@code AiServerHealthIndicator} 가 같은 성질의 형상에
     * 이미 {@code UNKNOWN} 을 쓴다. <b>이 시험은 두 방향의 거짓을 동시에 잡는다.</b>
     */
    @Test
    @DisplayName("★운영에서_mock모드는_UP도_DOWN도_아닌_UNKNOWN이다 — 사유로_알린다")
    void health_unknown_when_self_copy_blocked_in_production() {
        // given — mock-mode=true 인데 운영: 비식별 산출·위탁이 거부되는 형상.
        DeidentifyHealthIndicator withGuard = mockModeIndicator(true);

        // when
        Health health = withGuard.health();

        // then — 외부 핑은 여전히 하지 않지만(대상 없음) 상태는 정직해야 한다.
        assertThat(health.getStatus())
                .as("UP 이면 조용한 실패 / DOWN 이면 멀쩡한 노드를 내린다 — 둘 다 아니다")
                .isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus())
                .as("★거짓 비정상 회귀 가드 — DOWN 으로 되돌리지 말 것(형제 인디케이터와 대칭)")
                .isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("mode", "mock");
        assertThat(health.getDetails())
                .as("사유 문자열은 그대로 유지한다 — 상태를 낮춰도 알림 목적은 유지돼야 한다")
                .containsEntry("error", "SelfCopyBlockedInProduction");
        assertThat(mockServer.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("비운영에서_mock모드는_종전대로_UP이다 — local_dev_stg_동작_불변")
    void health_up_mock_when_not_production() {
        // given — 같은 mock-mode 인데 운영이 아니다(가드가 막지 않는다).
        DeidentifyHealthIndicator withGuard = mockModeIndicator(false);

        // when / then
        Health health = withGuard.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("mode", "mock");
    }

    /**
     * {@code mock-mode=true} + 신뢰 가드 주입 형상. 가드는 <b>생성자로만</b> 넣는다(final 필드) —
     * 운영 여부 판정은 가드가 단독 소유하므로 여기서는 그 결과만 흉내 낸다.
     */
    private DeidentifyHealthIndicator mockModeIndicator(boolean selfCopyBlocked) {
        DeidentifyEndpointTrustGuard guard = mock(DeidentifyEndpointTrustGuard.class);
        when(guard.selfCopyBlocked()).thenReturn(selfCopyBlocked);
        DeidentifyHealthIndicator withGuard = new DeidentifyHealthIndicator(webClient, guard);
        ReflectionTestUtils.setField(withGuard, "kpstEnabled", true);
        ReflectionTestUtils.setField(withGuard, "mockMode", true);
        return withGuard;
    }

    @Test
    @DisplayName("실모드_서버정상응답시_UP")
    void health_up_when_server_responds_ok() {
        // given — 운영(mock-mode=false 기본): 실 서버 핑.
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
    }

    @Test
    @DisplayName("실모드_핑은_벤더가_제공하는_루트경로로_요청한다")
    void health_ping_targets_root_path() throws InterruptedException {
        // given — 실 벤더(KPST)는 /health 를 제공하지 않는다. 루트(/)로 핑해야 한다.
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        // when
        Health health = indicator.health();

        // then — 요청 경로가 루트여야 하며(/health 등 하위 경로 금지) 판정은 UP.
        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/");
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("실모드_서버타임아웃시_DOWN")
    void health_down_when_server_times_out() {
        // given — PING_TIMEOUT(2초) 초과 유도
        mockServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
        assertThat(health.getDetails()).containsKey("error");
        // CWE-209: 에러 상세에 스택트레이스/내부 정보 미포함
        assertThat(health.getDetails().get("error").toString()).doesNotContain("\n");
    }

    @Test
    @DisplayName("mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류")
    void health_down_when_no_deidentify_path_configured() {
        // given — DeidentifyStep.run() 이 "비식별 경로가 구성되지 않았습니다"로 거부하는 형상과 동일.
        ReflectionTestUtils.setField(indicator, "mockMode", false);
        ReflectionTestUtils.setField(indicator, "kpstEnabled", false);

        // when
        Health health = indicator.health();

        // then — 핑할 대상 자체가 없으므로 외부 요청 0건 + DOWN(fail-closed).
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
        assertThat(health.getDetails()).containsEntry("mode", "unconfigured");
        assertThat(mockServer.getRequestCount()).isZero();
    }

    /**
     * 회귀 고정(2026-07-28): 헬스가 <b>실제 위탁 대상</b>({@code kpst.deid.base-url})을 핑해야 한다.
     * 과거에는 {@code deidentifyWebClient}({@code authoring.integration.deidentify.base-url}) 를 핑해
     * 컨테이너 배포에서 {@code localhost:9200} 을 치고 DOWN 오탐이 났다.
     */
    @Test
    @DisplayName("배선_핑대상은_kpst_base_url_이며_구_deidentify_base_url은_건드리지_않는다")
    void health_pings_kpst_base_url_not_legacy_deidentify_base_url() throws IOException {
        MockWebServer kpstServer = new MockWebServer();
        MockWebServer legacyServer = new MockWebServer();
        kpstServer.start();
        legacyServer.start();
        try {
            // given — 두 WebClient 빈이 모두 존재하는 실제 컨텍스트 형상.
            kpstServer.enqueue(new MockResponse().setResponseCode(200));
            legacyServer.enqueue(new MockResponse().setResponseCode(200));

            contextRunner()
                    .withBean("kpstDeidWebClient", WebClient.class,
                            () -> WebClient.builder().baseUrl(kpstServer.url("/").toString()).build())
                    .withBean("deidentifyWebClient", WebClient.class,
                            () -> WebClient.builder().baseUrl(legacyServer.url("/").toString()).build())
                    .withPropertyValues(
                            "authoring.integration.deidentify.mock-mode=false",
                            "kpst.deid.enabled=true")
                    .run(ctx -> {
                        // when
                        Health health = ctx.getBean(DeidentifyHealthIndicator.class).health();

                        // then — KPST 대상만 루트로 1회 핑, 구 비식별 base-url 은 무접촉.
                        assertThat(health.getStatus()).isEqualTo(Status.UP);
                        assertThat(kpstServer.getRequestCount()).isEqualTo(1);
                        assertThat(kpstServer.takeRequest().getPath()).isEqualTo("/");
                        assertThat(legacyServer.getRequestCount()).isZero();
                    });
        } finally {
            kpstServer.shutdown();
            legacyServer.shutdown();
        }
    }

    /**
     * {@code kpst.deid.enabled=false} 면 {@code kpstDeidWebClient} 빈 자체가 없다
     * ({@code KpstWebClientConfig} 의 @ConditionalOnProperty). 그 형상에서도 컨텍스트가 기동해야 하고
     * (optional 주입), mock self-fill 형상이면 외부 핑 없이 UP(mock) 이어야 한다.
     */
    @Test
    @DisplayName("배선_kpst비활성이면_WebClient빈_없이도_기동하고_mock형상은_UP")
    void context_starts_without_kpst_web_client_when_kpst_disabled() {
        contextRunner()
                .withPropertyValues(
                        "kpst.deid.enabled=false",
                        "authoring.integration.deidentify.mock-mode=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DeidentifyHealthIndicator.class);
                    Health health = ctx.getBean(DeidentifyHealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("mode", "mock");
                });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(DeidentifyHealthIndicator.class);
    }
}
