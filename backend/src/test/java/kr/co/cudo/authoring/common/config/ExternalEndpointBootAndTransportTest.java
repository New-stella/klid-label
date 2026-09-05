package kr.co.cudo.authoring.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.augment.integration.AugmentApiWebClientConfig;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ★★ <b>외부 연동 주소로는 기동이 막히지 않는다 — 그 주소로 나가려 할 때 막힌다</b>
 * (2026-09-03 사용자 확정, 구속).
 *
 * <h3>왜 이 파일이 따로 있나</h3>
 * <p>이 결정은 <b>연동 하나가 아니라 축 전체</b>에 걸린다. 연동별 파일에만 두면 <b>새 연동이
 * 생겼을 때 그 파일에만 없는</b> 형태로 조용히 새고, 실제로 이 저장소는 「증강만 옛 정책에 남아
 * 같은 성질의 위탁인데 한쪽만 거부」하는 비대칭을 이미 한 번 겪었다. 그래서 <b>한 자리에서 전
 * 연동을 같은 표로</b> 고정한다.
 *
 * <h3>축마다 두 벌을 실증한다</h3>
 * <ol>
 *   <li><b>그 상태로 기동한다</b> — 빈이 예외 없이 만들어진다.</li>
 *   <li><b>그 주소로 나가려 하면 실패한다</b> — 소켓을 열지 않고 즉시 거부된다.</li>
 * </ol>
 *
 * <table border="1">
 *   <caption>축 × 연동 — 지금 어디서 걸리는가</caption>
 *   <tr><th>축</th><th>시계열·비식별·증강</th><th>AI 추론·관제 통지</th></tr>
 *   <tr><td>빈값</td><td>전송 차단</td><td>전송 차단</td></tr>
 *   <tr><td>파싱 불가</td><td>전송 차단</td><td>전송 차단</td></tr>
 *   <tr><td>비허용 스킴</td><td>전송 차단</td><td><b>판정 없음</b>(구 동작 그대로)</td></tr>
 *   <tr><td>예시 호스트</td><td>전송 차단</td><td><b>판정 없음</b>(구 동작 그대로)</td></tr>
 *   <tr><td>예약 대역</td><td>전송 차단</td><td><b>판정 없음</b>(구 동작 그대로)</td></tr>
 * </table>
 *
 * <h3>★ 주소가 아닌 축도 같은 자리로 옮겼다 — <b>짝 맞춤</b></h3>
 * <p>증강은 위탁 주소가 <b>정상이어도</b> 결과를 되받을 콜백 IP allowlist 가 비어 있으면 그 증강이
 * 영구 고착된다. 그 판정도 기동 차단에서 <b>위탁 시점 거부</b>로 옮겼다 — 주소 축과 다른 축이지만
 * 「보호가 필요한 순간은 기동이 아니다」라는 근거가 같다. ⚠ <b>기동만 통과시키고 위탁 거부를 넣지
 * 않으면 그 가드는 없어진 것</b>이므로, 아래 시험은 <b>두 벌을 함께</b> 본다.
 *
 * <p>⚠ 오른쪽 열의 <b>「판정 없음」은 이번 변경이 만든 구멍이 아니다</b> — 그 두 축에는 원래
 * 주소 정책이 없었고(그 사실은 {@code AiSrvrSelector} 주석이 이미 못 박고 있다), 이번 변경은
 * <b>무엇을 막는지를 바꾸지 않는다</b>. 여기에 정책을 새로 걸려면 별도 결정이 필요하다.
 */
class ExternalEndpointBootAndTransportTest {

    // ── 다섯 축의 표본 ──────────────────────────────────────────────────────────────
    private static final String BLANK = "";
    /** 공백이 섞여 {@code URI.create} 가 던진다. */
    private static final String MALFORMED = "http://bad host:9400";
    private static final String BAD_SCHEME = "ftp://vendor.internal:9400";
    private static final String PLACEHOLDER = "https://example.com";
    /** 클라우드 메타데이터(IMDS) — 어떤 환경에서도 정상 위탁 대상이 아니다. */
    private static final String RESERVED_RANGE = "http://169.254.169.254";

    private static final String[] ALL_FIVE_AXES =
            {BLANK, MALFORMED, BAD_SCHEME, PLACEHOLDER, RESERVED_RANGE};
    private static final String[] FORMAT_AXES = {BLANK, MALFORMED};

    private final WebClientConfig cfg = new WebClientConfig();
    private final KpstWebClientConfig kpstCfg = new KpstWebClientConfig();
    private final AugmentApiWebClientConfig augmentCfg = new AugmentApiWebClientConfig();

    /** 「기동한다」 + 「아무것도 나가지 않는다」를 한 번에 본다. */
    private void assertBootsButNeverSends(String label, String url, Function<String, WebClient> factory) {
        WebClient client = assertBoots(label, url, factory);
        assertThatThrownBy(() -> client.post().uri("/probe").bodyValue("{}").retrieve()
                .bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .as("%s / %s — 연결 거부(ConnectException)가 나면 이미 전송을 시도했다는 뜻이다",
                        label, url.isEmpty() ? "(빈값)" : url)
                .isInstanceOf(NonRetryableExternalException.class);
    }

    private WebClient assertBoots(String label, String url, Function<String, WebClient> factory) {
        WebClient[] holder = new WebClient[1];
        assertThatCode(() -> holder[0] = factory.apply(url))
                .as("%s / %s — 연동 주소로 기동이 막히면 안 된다", label, url.isEmpty() ? "(빈값)" : url)
                .doesNotThrowAnyException();
        assertThat(holder[0]).isNotNull();
        return holder[0];
    }

    // ── 주소 정책을 가진 세 연동: 다섯 축 전부 ────────────────────────────────────────

    private WebClient vlm(String url) {
        return cfg.vlmWebClient(url, "", new VlmUrlPolicy(), null);
    }

    private WebClient kpst(String url) {
        return kpstCfg.kpstDeidWebClient(kpstCfg.kpstDeidEndpointAddress(url, "", null), "", null);
    }

    /**
     * 주소 축 표본 — 콜백 allowlist 는 <b>명시</b>해 둔다. 비워 두면 짝 맞춤 가드가 먼저 걸려
     * 거부 사유가 「짝 불일치」로 바뀌고 <b>주소 축 단언이 통째로 무의미</b>해진다.
     */
    private WebClient augment(String url) {
        return augmentCfg.augmentApiWebClient(url, new AugmentUrlPolicy(),
                new GenAiIntegrationWiringGuard(url, "0.0.0.0/0", new AugmentUrlPolicy()));
    }

    @Test
    @DisplayName("★시계열_위탁 — 다섯_축_모두_기동은_되고_전송은_막힌다")
    void timeseriesAllFiveAxes() {
        for (String url : ALL_FIVE_AXES) {
            assertBootsButNeverSends("시계열", url, this::vlm);
        }
    }

    @Test
    @DisplayName("★비식별_위탁 — 다섯_축_모두_기동은_되고_전송은_막힌다")
    void deidentifyAllFiveAxes() {
        for (String url : ALL_FIVE_AXES) {
            assertBootsButNeverSends("비식별", url, this::kpst);
        }
    }

    @Test
    @DisplayName("★증강_위탁 — 다섯_축_모두_기동은_되고_전송은_막힌다")
    void augmentAllFiveAxes() {
        for (String url : ALL_FIVE_AXES) {
            assertBootsButNeverSends("증강", url, this::augment);
        }
    }

    // ── 주소 정책이 없는 두 연동: 형식 축만 ──────────────────────────────────────────

    private WebClient aiServer(String url) {
        return cfg.aiServerWebClient(url, null);
    }

    private WebClient controlNotify(String url) {
        return cfg.controlNotifyWebClient(url, "", false, null, null);
    }

    @Test
    @DisplayName("★AI_추론 — 빈값_파싱불가도_기동은_되고_전송은_막힌다")
    void aiServerFormatAxes() {
        for (String url : FORMAT_AXES) {
            assertBootsButNeverSends("AI 추론", url, this::aiServer);
        }
    }

    @Test
    @DisplayName("★관제_통지 — 빈값_파싱불가도_기동은_되고_전송은_막힌다")
    void controlNotifyFormatAxes() {
        for (String url : FORMAT_AXES) {
            assertBootsButNeverSends("관제 통지", url, this::controlNotify);
        }
    }

    @Test
    @DisplayName("★AI_추론_관제통지는_스킴_예시_예약대역을_판정하지_않는다 — 구 동작 그대로(기동만 확인)")
    void policyLessAxesRemainUnjudged() {
        // 이 두 축에는 원래 주소 정책이 없다. 여기서 정책을 새로 걸면 지금까지 통과하던 값을 막게
        //   되어 「무엇을 막는지는 그대로」를 어긴다. 전송을 시도하지 않으므로 네트워크 의존이 없다.
        for (String url : new String[]{BAD_SCHEME, PLACEHOLDER, RESERVED_RANGE}) {
            assertBoots("AI 추론", url, this::aiServer);
            assertBoots("관제 통지", url, this::controlNotify);
        }
    }

    // ── 주소가 아닌 축: 짝 맞춤(위탁 ↔ 콜백 수신) ───────────────────────────────────

    @Test
    @DisplayName("★★증강_짝맞춤 — 주소가_정상이어도_콜백_allowlist_가_비면_기동은_되고_위탁만_거부된다")
    void augmentPairingBlocksCommissionNotBoot() {
        // given — 주소는 정상(루프백, 내부망 정책 통과)인데 콜백 수신이 전면 차단인 조합.
        String usableUrl = "http://127.0.0.1:1";
        for (String allowlist : new String[]{"", "none"}) {
            WebClient client = assertBoots("증강 짝맞춤", usableUrl, url ->
                    augmentCfg.augmentApiWebClient(url, new AugmentUrlPolicy(),
                            new GenAiIntegrationWiringGuard(url, allowlist, new AugmentUrlPolicy())));

            // when / then — 위탁(job 생성)만 소켓을 열지 않고 거부된다.
            assertThatThrownBy(() -> client.post().uri("/api/genai/jobs").bodyValue("{}")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                    .as("allowlist=[%s] — 연결 거부가 나면 되받지 못할 위탁이 실제로 나갔다는 뜻이다",
                            allowlist)
                    .isInstanceOf(NonRetryableExternalException.class);
        }
    }

    @Test
    @DisplayName("★증강_짝맞춤 — 짝이_맞으면_위탁이_그대로_나간다")
    void augmentPairingLetsCommissionThrough() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));
            String base = server.url("/").toString();

            WebClient client = augmentCfg.augmentApiWebClient(base, new AugmentUrlPolicy(),
                    new GenAiIntegrationWiringGuard(base, "0.0.0.0/0", new AugmentUrlPolicy()));
            try {
                client.post().uri("/api/genai/jobs").bodyValue("{}").retrieve()
                        .bodyToMono(String.class).block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // 전송 여부는 소켓 관측으로만 판정한다.
            }

            RecordedRequest received = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(received).as("짝이 맞으면 위탁이 그대로 나가야 한다").isNotNull();
            assertThat(received.getPath()).isEqualTo("/api/genai/jobs");
        }
    }

    // ── 정상 주소에서는 동작이 그대로다 ─────────────────────────────────────────────

    @Test
    @DisplayName("★정상_주소에서는_요청이_그대로_나간다 — 가드가_정상_연동을_막지_않는다")
    void validAddressStillSends() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String base = server.url("/").toString();
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

            WebClient client = vlm(base);
            try {
                client.post().uri("/probe").bodyValue("{}").retrieve()
                        .bodyToMono(String.class).block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // 전송 여부는 아래 소켓 관측으로만 판정한다.
            }

            RecordedRequest received = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(received).as("정상 주소는 그대로 요청을 받아야 한다").isNotNull();
            assertThat(received.getPath()).isEqualTo("/probe");
        }
    }

    @Test
    @DisplayName("★배포_주소가_거부돼도_운영화면_설정이_있으면_그리로_나간다 — 재기동_없이_되돌릴_길")
    void configuredOverrideRescuesRejectedDeploymentAddress() throws Exception {
        try (MockWebServer override = new MockWebServer()) {
            override.start();
            override.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

            // given — 배포 기본값이 예약 대역(정책 거부)인데 운영 화면에는 정상 주소가 저장돼 있다.
            WebClient client = cfg.vlmWebClient(RESERVED_RANGE, "", new VlmUrlPolicy(),
                    resolverReturning(override.url("/").toString()));

            // when
            try {
                client.post().uri("/probe").bodyValue("{}").retrieve()
                        .bodyToMono(String.class).block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // 소켓 관측으로만 판정
            }

            // then — 거부는 <배포 설정값>에만 걸리고, 저장된 정상 주소는 그대로 나간다.
            assertThat(override.takeRequest(5, TimeUnit.SECONDS))
                    .as("거부된 배포 주소를 운영 화면에서 되돌릴 수 있어야 한다").isNotNull();
        }
    }

    // ── 비식별은 저수준 경로도 함께 막아야 한다(부분 반영은 미반영보다 위험) ─────────────

    @Test
    @DisplayName("★★비식별_진행조회_저수준_경로도_같이_막힌다 — 한쪽만_막으면_그_작업이_영원히_안_끝난다")
    void deidentifyLowLevelPathIsBlockedToo() {
        // given — 배포 주소가 거부됐고 운영 화면 설정도 없다.
        ExternalEndpointAddress rejected = kpstCfg.kpstDeidEndpointAddress(RESERVED_RANGE, "", null);
        assertThat(rejected.usable()).isFalse();

        KpstDeidentifyClient client = new KpstDeidentifyClient(
                kpstCfg.kpstDeidWebClient(rejected, "", noOverrideResolver()),
                kpstCfg.kpstDeidProgressHttpClient(rejected, ""),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(100).build()).circuitBreaker("kpstDeidGuardTest"),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                noOverrideResolver(),
                rejected);

        // when / then — 위탁(WebClient)과 진행조회(저수준 HttpClient)가 <같은 판정>으로 막힌다.
        assertThatThrownBy(() -> client.retrieveProgress("tester", 1L))
                .as("저수준 경로가 안 막히면 위탁은 새 서버로 가고 조회만 옛 서버로 나간다")
                .isInstanceOf(NonRetryableExternalException.class);
    }

    private static IntegrationEndpointResolver noOverrideResolver() {
        return resolverReturning(null);
    }

    private static IntegrationEndpointResolver resolverReturning(String overrideUrl) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString())).thenReturn(Optional.ofNullable(overrideUrl));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }
}
