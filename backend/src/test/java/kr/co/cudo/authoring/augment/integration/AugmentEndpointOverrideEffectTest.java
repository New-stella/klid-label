package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.config.AugmentUrlPolicy;
import kr.co.cudo.authoring.common.config.GenAiIntegrationWiringGuard;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ★ <b>화면에서 저장한 증강 벤더 주소가 실제로 쓰이는가</b> — 실동작 회귀 가드 (2026-09-08).
 *
 * <h3>왜 이 시험이 따로 필요한가</h3>
 * <p>증강이 {@code IntegrationEndpoint.AUGMENT} 로 등록되어 화면에 <b>외부 증강 벤더 칸</b>이 생겼는데,
 * ①위탁 클라이언트에 주소 재작성 필터가 없고 ②연동 여부 판정이 기동 시점 값만 읽어서
 * <b>저장은 되는데 실동작이 0건</b>이었다. 오류도 경고도 나지 않는 <b>조용한 실패</b>라 기존 시험
 * 전건이 초록인 채로 통과했다 — 바로 그 이유로 같은 라운드가 다른 칸 둘을 화면에서 걷어냈다.
 *
 * <p>그래서 여기서는 <b>선언이 아니라 동작</b>을 본다: 실소켓이 요청을 받았는가(①), 그리고 저장이
 * 접수 관문을 실제로 여는가(②).
 *
 * <p><b>mutation 확인</b>
 * <ul>
 *   <li>{@code AugmentApiWebClientConfig} 에서 {@code IntegrationEndpointExchangeFilter.of(...)}
 *       배선을 지우면 ①·②의 소켓 단언이 실패한다(요청이 배포 기본값으로 나가거나 가드에 막힌다).</li>
 *   <li>{@code AugmentExternalLinkPolicy#effectiveBaseUrl} 을 배포 기본값만 읽도록 되돌리면
 *       ③이 실패한다(저장했는데 계속 「미연동」이라 요청 접수가 503 으로 막힌다).</li>
 * </ul>
 */
class AugmentEndpointOverrideEffectTest {

    private final AugmentApiWebClientConfig cfg = new AugmentApiWebClientConfig();

    /** 아무도 응답하지 않는 배포 기본값(TEST-NET-3) — 여기로 나가면 안 된다는 뜻이다. */
    private static final String UNREACHABLE_BOOT_DEFAULT = "http://203.0.113.10:9400";

    /**
     * 설정 override 를 돌려주는 리졸버 — 실제 조회 경로({@code SystemConfigService} → 리졸버) 그대로다.
     * 판정을 통과시키기 위한 스텁이 아니라 "설정에 값이 저장된 상태" 의 재현이다.
     */
    private static IntegrationEndpointResolver resolverReturning(String overrideUrl) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString())).thenReturn(Optional.ofNullable(overrideUrl));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }

    private WebClient client(String bootDefault, IntegrationEndpointResolver resolver) {
        // 짝 맞춤 축은 여기서 보지 않는다 — 비워 두면 위탁이 그 사유로 먼저 막혀 주소 축 단언이
        //   통째로 무의미해진다. 짝 축은 아래 전용 시험이 <비운 값>으로 따로 고정한다.
        return client(bootDefault, resolver, "0.0.0.0/0");
    }

    private WebClient client(String bootDefault, IntegrationEndpointResolver resolver,
                             String allowlist) {
        return cfg.augmentApiWebClient(bootDefault, new AugmentUrlPolicy(),
                new GenAiIntegrationWiringGuard(bootDefault, allowlist, new AugmentUrlPolicy()),
                resolver);
    }

    /** 위탁(job 생성) 1회. 전송 여부는 <b>소켓 관측으로만</b> 판정한다. */
    private static void commission(WebClient client) {
        try {
            client.post().uri(HttpExternalAugmentClient.JOBS_PATH).bodyValue("{}")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // 응답 처리 실패로 판정이 흐려지지 않게 한다.
        }
    }

    @Test
    @DisplayName("★저장된_주소로_위탁이_나간다 — 배포_기본값이_아니라")
    void savedAddressReceivesTheCommission() throws Exception {
        try (MockWebServer vendor = new MockWebServer()) {
            vendor.start();
            vendor.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

            // given — 배포 기본값은 응답하지 않는 주소, 화면에서 저장한 주소는 목 벤더.
            WebClient client = client(UNREACHABLE_BOOT_DEFAULT,
                    resolverReturning(vendor.url("/").toString()));

            // when
            commission(client);

            // then — 저장한 주소가 실제로 요청을 받았다. 재작성 필터가 빠지면 여기서 실패한다.
            RecordedRequest received = vendor.takeRequest(5, TimeUnit.SECONDS);
            assertThat(received)
                    .as("저장한 주소가 요청을 받아야 한다 — 배선이 빠지면 배포 기본값으로 나간다")
                    .isNotNull();
            assertThat(received.getPath()).isEqualTo(HttpExternalAugmentClient.JOBS_PATH);
        }
    }

    @Test
    @DisplayName("★배포_기본값이_비어도_저장된_주소가_있으면_전송_가드가_열린다")
    void savedAddressOpensTheTransportGuard() throws Exception {
        try (MockWebServer vendor = new MockWebServer()) {
            vendor.start();
            vendor.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

            // given — 배포 기본값 미주입(= 종전이라면 「미연동」이라 전송 자체가 막히던 형상).
            WebClient client = client("", resolverReturning(vendor.url("/").toString()));

            // when
            commission(client);

            // then — 가드가 <재작성된 최종 URL> 을 보므로 정상 위탁이 그대로 나간다.
            //   필터를 가드 <뒤>로 옮기면 여기서 실패한다(주소 없음으로 막힌다).
            assertThat(vendor.takeRequest(5, TimeUnit.SECONDS))
                    .as("저장된 주소가 있으면 전송 관문이 열려야 한다")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("★저장하면_연동됨으로_바뀌어_요청_접수_관문이_열린다")
    void savedAddressFlipsLinkJudgement() {
        // given — 배포 기본값은 비어 있고 화면에서 주소를 저장한 상태.
        AugmentExternalLinkPolicy saved =
                new AugmentExternalLinkPolicy("", resolverReturning("http://genai.vendor.io:9400"));

        // then — 「미연동」이 아니다. 되돌리면 요청 접수가 계속 503 이라 저장이 실동작 0건이 된다.
        assertThat(saved.isNotLinked())
                .as("저장된 주소를 읽지 않으면 화면 저장이 접수 관문을 열지 못한다")
                .isFalse();
    }

    @Test
    @DisplayName("저장이_없으면_배포_기본값_그대로다 — override 부재 시 필터는 아무것도 하지 않는다")
    void withoutOverrideNothingChanges() throws Exception {
        try (MockWebServer bootTarget = new MockWebServer()) {
            bootTarget.start();
            bootTarget.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));
            String boot = bootTarget.url("/").toString();

            // 저장 행 없음(정상 상태) — 리졸버가 빈 Optional 을 돌려준다.
            commission(client(boot, resolverReturning(null)));

            assertThat(bootTarget.takeRequest(5, TimeUnit.SECONDS))
                    .as("override 가 없으면 기존 형상 그대로 배포 기본값으로 나간다")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("저장도_배포_기본값도_없으면_여전히_미연동이고_전송도_막힌다 — fail-closed 유지")
    void withoutAnyAddressStillNotLinked() {
        IntegrationEndpointResolver empty = resolverReturning(null);

        assertThat(new AugmentExternalLinkPolicy("", empty).isNotLinked()).isTrue();
        assertThat(new AugmentExternalLinkPolicy("   ", empty).isNotLinked()).isTrue();

        assertThatThrownBy(() -> client("", empty).post().uri(HttpExternalAugmentClient.JOBS_PATH)
                .bodyValue("{}").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .isInstanceOf(NonRetryableExternalException.class)
                .hasMessageContaining("연동 주소가 설정되지 않아");
    }

    // ── 짝 맞춤 축(위탁 ↔ 콜백 수신)도 저장된 주소로 재평가된다 ─────────────────────────────

    /**
     * ★★ <b>이 조합이 이 시험의 존재 이유다.</b> 배포 기본값이 비어 있으면 짝 맞춤 판정이
     * 「미연동 → 요구 없음」으로 계산되는데, 화면으로 주소를 저장하면 <b>위탁은 실제로 나갈 수
     * 있게 된다</b>. 그 사이가 열려 있으면 허용 대역이 비었는데도 위탁이 나가고 결과는 전건 거부되어
     * 그 증강이 영구 고착된다 — <b>가드가 존재하는 이유 자체가 무력화</b>된다.
     *
     * <p>기존 짝 축 시험은 배포 기본값이 <b>이미 채워진</b> 형상만 봤으므로 이 조합을 덮지 못했다.
     *
     * <p><b>mutation 확인</b>: 짝 판정을 기동 시점 값
     * ({@code wiringGuard.commissionRejectionLabel()})으로 되돌리면 이 시험이 실패한다.
     */
    @Test
    @DisplayName("★★배포_기본값이_비어도_저장한_주소로_위탁하려면_콜백_허용대역이_필요하다")
    void savedAddressStillRequiresPairedCallbackIntake() throws Exception {
        try (MockWebServer vendor = new MockWebServer()) {
            vendor.start();

            for (String allowlist : new String[]{"", "none", "NONE"}) {
                // given — 배포 기본값 미주입 + 화면으로 저장한 주소 + 콜백 수신 전면 차단.
                WebClient client = client("", resolverReturning(vendor.url("/").toString()), allowlist);

                // then — 위탁은 나가지 않는다.
                assertThatThrownBy(() -> client.post().uri(HttpExternalAugmentClient.JOBS_PATH)
                        .bodyValue("{}").retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(5)))
                        .as("allowlist=[%s] — 연결이 나가면 되받지 못할 위탁이 실제로 나갔다는 뜻이다",
                                allowlist)
                        .isInstanceOf(NonRetryableExternalException.class)
                        .hasMessageContaining("짝이 맞지 않아")
                        .hasMessageContaining(GenAiIntegrationWiringGuard.REJECTION_LABEL);
            }

            // and — 소켓에 아무것도 도달하지 않았다(거부가 <전송 전>에 일어났다).
            assertThat(vendor.getRequestCount())
                    .as("짝이 어긋난 위탁은 소켓을 열지 않아야 한다")
                    .isZero();
        }
    }

    /**
     * ★ <b>반대 축도 대칭으로 고정한다</b> — 한쪽만 두면 다음 사람이 가드를 조여 정상 위탁을 막는다.
     * 저장한 주소와 허용 대역이 <b>둘 다</b> 채워져 있으면 위탁은 그대로 나가야 한다.
     */
    @Test
    @DisplayName("★둘_다_채워져_있으면_저장한_주소로_위탁이_그대로_나간다")
    void pairedAndSavedLetsCommissionThrough() throws Exception {
        try (MockWebServer vendor = new MockWebServer()) {
            vendor.start();
            vendor.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

            commission(client("", resolverReturning(vendor.url("/").toString()), "203.0.113.0/24"));

            assertThat(vendor.takeRequest(5, TimeUnit.SECONDS))
                    .as("짝이 맞으면 저장한 주소로 위탁이 나가야 한다 — 가드가 정상 연동을 막지 않는다")
                    .isNotNull();
        }
    }

    /**
     * ★ 짝이 어긋나도 <b>조회·취소는 막지 않는다</b> — 이미 걸린 위탁을 회수·정리하는 경로다.
     * 저장한 주소로 재평가하게 바꾼 뒤에도 이 방향이 유지되는지 함께 고정한다.
     */
    @Test
    @DisplayName("★짝이_어긋나도_조회·취소는_저장한_주소로_그대로_나간다")
    void unpairedStillAllowsQueryAndCancelOnSavedAddress() throws Exception {
        try (MockWebServer vendor = new MockWebServer()) {
            vendor.start();
            vendor.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            vendor.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            WebClient client = client("", resolverReturning(vendor.url("/").toString()), "");

            try {
                client.get().uri(HttpExternalAugmentClient.JOBS_PATH + "/j-1")
                        .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
                client.post().uri(HttpExternalAugmentClient.JOBS_PATH + "/j-1/cancel")
                        .bodyValue("{}").retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // 전송 여부는 소켓 관측으로만 판정한다.
            }

            assertThat(vendor.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(vendor.takeRequest(5, TimeUnit.SECONDS))
                    .as("취소까지 도달해야 한다 — 함께 막으면 고착된 위탁을 정리할 수단을 잃는다")
                    .isNotNull();
        }
    }
}
