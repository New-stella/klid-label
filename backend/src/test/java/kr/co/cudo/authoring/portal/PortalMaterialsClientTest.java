package kr.co.cudo.authoring.portal;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.PortalMaterialsClient;
import kr.co.cudo.authoring.common.client.dto.PortalMaterialsResponse;
import kr.co.cudo.authoring.common.config.WebClientConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포털 소재 조달 클라이언트의 계약면을 고정한다.
 *
 * <p>고정하는 축:
 * <ol>
 *   <li><b>미구성이면 조달이 닫히고 기동은 산다</b> — 빈 생성도 클라이언트 생성도 예외를 던지지
 *       않되, 호출은 <b>바깥으로 나가기 전에</b> 거부된다.</li>
 *   <li><b>4xx 는 재시도하지 않고 5xx 는 재시도한다</b> — 왕복 횟수로 본다.</li>
 *   <li><b>비어 올 수 있는 값이 실패가 아니다</b> — 코드·소재 구분·무결성 값이 없어도 파싱된다.</li>
 *   <li><b>모르는 소재 구분은 무시한다</b> — 거부가 아니라 건너뛴다.</li>
 * </ol>
 *
 * @design INT-014
 */
class PortalMaterialsClientTest {

    private MockWebServer server;
    private CircuitBreakerRegistry cbRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                // 프로덕션 YAML 정합 — 4xx 비재시도 예외는 서킷 failure 로 집계하지 않는다.
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /** 프로덕션 정합 — 3회 시도하되 비재시도 예외는 건너뛴다. */
    private RetryRegistry prodRetry() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private PortalMaterialsClient client(String baseUrl, String apiKey) {
        WebClient webClient = new WebClientConfig().portalMaterialsWebClient(baseUrl, apiKey);
        return new PortalMaterialsClient(webClient, cbRegistry, prodRetry(), baseUrl, apiKey, 5);
    }

    private String baseUrl() {
        return server.url("/").toString();
    }

    @Test
    @DisplayName("★설정이_비어도_기동은_살고_조달만_닫힌다_바깥으로_나가지_않는다")
    void notConfigured_closesFetchButNotBoot() {
        // 빈 생성과 클라이언트 생성이 예외를 던지지 않는다 = 기동을 막지 않는다.
        PortalMaterialsClient blank = client("", "");
        assertThat(blank.available()).isFalse();

        assertThatThrownBy(() -> blank.fetch(4704L))
                .isInstanceOf(PortalMaterialsClient.PortalMaterialsUnavailableException.class);
        // ★ 미구성 실패는 <재시도해도 같다> — 비재시도 계열이라 재시도 백오프를 태우지 않는다.
        assertThat(new PortalMaterialsClient.PortalMaterialsUnavailableException("x"))
                .isInstanceOf(NonRetryableExternalException.class);
        // 그리고 아무 데도 나가지 않았다.
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("★주소만_있고_키가_없으면_조달이_닫힌다_전건_401이_될_호출을_보내지_않는다")
    void missingApiKey_closesFetch() {
        PortalMaterialsClient noKey = client(baseUrl(), "  ");
        assertThat(noKey.available()).isFalse();
        assertThatThrownBy(() -> noKey.fetch(4704L))
                .isInstanceOf(PortalMaterialsClient.PortalMaterialsUnavailableException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("경로와_사전공유키_헤더가_계약대로_나간다")
    void sendsPathAndApiKeyHeader() throws Exception {
        server.enqueue(json(200, """
                {"datasetId":4704,"code":"DS-1","version":"11.0.0","variant":"A",
                 "repoRootDir":"/repo","files":[]}"""));

        PortalMaterialsResponse response = client(baseUrl(), "shared-secret").fetch(4704L);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/internal/v1/datasets/4704/materials");
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getHeader("x-api-key")).isEqualTo("shared-secret");
        assertThat(response.datasetId()).isEqualTo(4704L);
    }

    @Test
    @DisplayName("★4xx는_재시도하지_않는다_한_번만_묻는다")
    void clientError_isNotRetried() {
        server.enqueue(json(404, "{\"error\":\"RESOURCE_NOT_FOUND\",\"path\":\"/api/internal/v1/x\"}"));

        PortalMaterialsClient client = client(baseUrl(), "k");
        assertThatThrownBy(() -> client.fetch(4704L))
                .isInstanceOf(NonRetryableExternalException.class)
                // ⚠ 외부 본문 원문(경로 포함)이 예외 메시지로 새지 않는다.
                .hasMessageNotContaining("/api/internal/v1/x")
                .hasMessageContaining("404");

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★인증실패도_재시도하지_않는다_같은_키로_다시_물어도_같다")
    void unauthorized_isNotRetried() {
        server.enqueue(json(401, "{\"error\":\"UNAUTHORIZED\"}"));

        PortalMaterialsClient client = client(baseUrl(), "wrong");
        assertThatThrownBy(() -> client.fetch(4704L))
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★5xx는_재시도한다_설정한_횟수만큼_묻는다")
    void serverError_isRetried() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(json(500, "{\"error\":\"INTERNAL_ERROR\"}"));
        }

        PortalMaterialsClient client = client(baseUrl(), "k");
        assertThatThrownBy(() -> client.fetch(4704L)).isInstanceOf(Throwable.class);

        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("★코드_소재구분_무결성값이_비어도_정상_처리된다_옛_데이터를_실패로_다루지_않는다")
    void nullableFields_areNotFailures() {
        server.enqueue(json(200, """
                {"datasetId":4704,"code":null,"version":"11.0.0","variant":null,
                 "repoRootDir":"/repo",
                 "files":[{"fileId":1,"fileDv":"DEPLOYMENT_ZIP","fileName":"d.zip",
                           "localPath":"/repo/d.zip","fileSize":10,"checksum":null}]}"""));

        PortalMaterialsResponse response = client(baseUrl(), "k").fetch(4704L);

        assertThat(response.code()).isNull();
        assertThat(response.variant()).isNull();
        assertThat(response.deploymentZip()).isNotNull();
        assertThat(response.deploymentZip().checksum()).isNull();
    }

    @Test
    @DisplayName("★모르는_소재구분은_거부가_아니라_무시다_벤더_값역의_사본을_만들지_않는다")
    void unknownFileDivision_isIgnoredNotRejected() {
        server.enqueue(json(200, """
                {"datasetId":4704,"code":"c","version":"v","variant":null,
                 "repoRootDir":"/repo",
                 "files":[{"fileId":1,"fileDv":"DEPLOYMENT_ZIP","fileName":"d.zip",
                           "localPath":"/repo/d.zip","fileSize":1,"checksum":"a"},
                          {"fileId":2,"fileDv":"THUMBNAIL_WEBP","fileName":"t.webp",
                           "localPath":"/repo/t.webp","fileSize":1,"checksum":"b"},
                          {"fileId":3,"fileDv":"DATASET_VIDEO","fileName":"v.mp4",
                           "localPath":"/repo/v.mp4","fileSize":1,"checksum":"c"}]}"""));

        PortalMaterialsResponse response = client(baseUrl(), "k").fetch(4704L);

        // 파싱 자체는 통째로 성공하고(무시가 곧 거부가 아니다)
        assertThat(response.filesOrEmpty()).hasSize(3);
        // 우리가 아는 구분만 골라 쓴다.
        assertThat(response.deploymentZip().fileName()).isEqualTo("d.zip");
        assertThat(response.datasetVideos()).singleElement()
                .extracting(PortalMaterialsResponse.MaterialFile::fileName).isEqualTo("v.mp4");
    }

    @Test
    @DisplayName("★알지_못하는_응답_필드가_늘어도_깨지지_않는다")
    void unknownResponseFields_areTolerated() {
        server.enqueue(json(200, """
                {"datasetId":4704,"code":"c","version":"v","variant":null,"repoRootDir":"/repo",
                 "newFieldFromPortal":"x",
                 "files":[{"fileId":1,"fileDv":"DEPLOYMENT_ZIP","fileName":"d.zip",
                           "localPath":"/repo/d.zip","fileSize":1,"checksum":"a","extra":1}]}"""));

        assertThat(client(baseUrl(), "k").fetch(4704L).deploymentZip()).isNotNull();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MED-2 (독립 QA 2026-09-15) — 전송계층 겹 가드가 «고정돼 있지 않았다».
    //
    // requireHost 는 「구성 판정이 나중에 느슨해져도 요청이 새지 않게」 둔 둘째 방어선인데,
    // 기존 시험은 전부 그 «앞의» 구성 게이트(configured)에서 끊겨 필터까지 도달이 0 이었다.
    // 즉 그 필터를 통째로 지워도 RED 가 나지 않는다 — 있다고 «주장될» 뿐 고정돼 있지 않다.
    //
    // ★ 이것은 이 라운드에서 두 번째로 만난 같은 구조다. 첫 번째는 해제기의 「선언 크기 사전
    //   검사가 실제 바이트 계수를 가린 것」이었다. 공통 형태 — <앞의 검사가 뒤의 진짜 방어선을
    //   시험에서 가린다>. 그래서 여기서는 구성 게이트를 «우회»해 빈을 직접 태운다.
    //   ⚠ 구성 게이트를 통과시키는 방식으로 짜면 그 게이트가 다시 앞을 가려 무의미해진다.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★주소가_비면_전송계층이_막는다_구성게이트를_우회해도_바깥으로_나가지_않는다")
    void requireHost_blocksTransport_evenWhenConfigGateBypassed() {
        // given: 구성 판정을 거치지 않고 빈 base 로 만든 WebClient 를 «직접» 쓴다
        WebClient bare = new WebClientConfig().portalMaterialsWebClient("", "key");

        // when / then: 호스트가 없으므로 전송 전에 끊긴다
        assertThatThrownBy(() -> bare.get().uri("/api/internal/v1/datasets/1/materials")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    // ⚠ 여기에 「막힌 요청이 loopback 으로 새지 않는다」 시험을 두었다가 «걷어냈다»(2026-09-15).
    //   변이(호스트 판정을 항상 통과로)를 넣어도 그 시험은 green 이었다 — 유출은 localhost:80 으로
    //   가는데 목서버는 임의 포트에 있어 «새어도 볼 수가 없다». 고정하는 것이 없으면서 안심만 주는
    //   시험이라, 남겨 두면 다음 사람이 그 축이 지켜진다고 믿는다. 위 시험 하나가 실제 방어선이다.

}
