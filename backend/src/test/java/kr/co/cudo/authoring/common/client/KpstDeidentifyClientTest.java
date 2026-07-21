package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 — KPST 비식별 폴링 클라이언트 검증 (MockWebServer).
 *
 * <p>규격 정본: docs/v2-wiki/22-deid-solution-api.md
 */
class KpstDeidentifyClientTest {

    private MockWebServer server;
    private CircuitBreaker circuitBreaker;

    /**
     * 진행조회(GET+JSON 바디) 전용 모킹 서버 — JDK 내장 {@link com.sun.net.httpserver.HttpServer}.
     * MockWebServer 4.12 는 {@code GET + body} 를 서버 측에서 거부하므로 진행조회만 이 서버로 모킹한다.
     */
    private com.sun.net.httpserver.HttpServer progressServer;
    /** 진행조회 서버가 마지막으로 수신한 요청(메서드/경로/바디) — 단언용. */
    private volatile String progressMethod;
    private volatile String progressPath;
    private volatile String progressBody;
    /** 진행조회 서버가 수신한 요청 총 횟수 — 재시도 발생 여부 단언용(요청 카운트 방식). */
    private final java.util.concurrent.atomic.AtomicInteger progressRequestCount =
            new java.util.concurrent.atomic.AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        circuitBreaker = CircuitBreakerRegistry.of(
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(50)
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                // K3: 4xx 비재시도 예외는 서킷 failure 로 집계하지 않는다(프로덕션 YAML 정합).
                                .ignoreExceptions(NonRetryableExternalException.class)
                                .build())
                .circuitBreaker("kpstDeid");
        progressServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        progressServer.start();
        progressRequestCount.set(0);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        if (progressServer != null) {
            progressServer.stop(0);
        }
    }

    private WebClient webClient() {
        return WebClient.builder().baseUrl(server.url("/").toString()).build();
    }

    /**
     * 진행조회 전용 — GET 바디 전송이 가능한 저수준 reactor-netty HttpClient.
     *
     * <p>base-url 은 {@link #progressServer}(JDK 내장 HttpServer)를 가리킨다. MockWebServer 4.12 는
     * {@code GET + body} 요청을 서버 측에서 {@code IllegalArgumentException("Request must not have a
     * body")} 으로 거부해(요청 자체를 읽지 못함) 진행조회 시나리오를 모킹할 수 없다 — 프로덕션 결함이
     * 아니라 MockWebServer 의 하네스 한계다. 따라서 진행조회만 GET 바디를 정상 수신하는 JDK 내장
     * HttpServer 로 모킹한다. read-timeout 을 짧게 둬 어떤 경우에도 무한 대기하지 않는다.
     */
    private HttpClient progressHttpClient() {
        String base = "http://" + progressServer.getAddress().getHostString()
                + ":" + progressServer.getAddress().getPort();
        return HttpClient.create().baseUrl(base)
                .responseTimeout(Duration.ofSeconds(5));
    }

    private RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    /** 프로덕션 정합 재시도 레지스트리 — 3회 재시도하되 4xx 비재시도 예외는 무시(K3). */
    private RetryRegistry tripleAttemptIgnoringNonRetryable() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private KpstDeidentifyClient clientWith(RetryRegistry registry) {
        return new KpstDeidentifyClient(
                webClient(), progressHttpClient(), circuitBreaker, registry);
    }

    /**
     * 진행조회 서버에 {@code /retrieve_progress} 핸들러를 설치한다.
     *
     * <p>핸들러는 메서드/바디와 무관하게 요청 바디 전문을 읽어 기록({@link #progressMethod}/
     * {@link #progressPath}/{@link #progressBody})한 뒤 즉시 200 + 주어진 JSON 을 응답한다. 바디를
     * 끝까지 소비하므로 GET+body 요청도 정상 수신되며(클라이언트 read-timeout 없음), 기록된 바디로
     * "reqUserId/prjId 가 JSON 바디로 전송됨" 을 단언할 수 있다.
     */
    private void installProgressDispatcher(String jsonBody) {
        byte[] respBytes = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        progressServer.createContext("/retrieve_progress", exchange -> {
            try (exchange) {
                progressRequestCount.incrementAndGet();
                progressMethod = exchange.getRequestMethod();
                progressPath = exchange.getRequestURI().getPath();
                progressBody = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                exchange.getResponseBody().write(respBytes);
            }
        });
    }

    /**
     * 진행조회 서버가 항상 주어진 오류 상태코드({@code status})로 응답하도록 설치한다(빈 바디).
     *
     * <p>매 요청마다 {@link #progressRequestCount} 를 증가시키므로, 재시도가 발생하면 카운트가
     * 그만큼 늘어난다 — "4xx 는 1회만(비재시도)" / "5xx 는 3회(재시도)" 를 요청 카운트로 단언한다.
     */
    private void installProgressStatusDispatcher(int status) {
        progressServer.createContext("/retrieve_progress", exchange -> {
            try (exchange) {
                progressRequestCount.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, -1);
            }
        });
    }

    private KpstDeidentifyClient client() {
        return new KpstDeidentifyClient(
                webClient(), progressHttpClient(), circuitBreaker, singleAttempt());
    }

    @Test
    @DisplayName("연결확인이_Connect를_반환한다")
    void connect() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("Connect"));

        boolean ok = client().connect();

        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("프로젝트_생성_응답에서_prjId를_획득한다")
    void createProject() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"prj_id\":279}"));

        // shared-mount 모델: input_path = 원본 부모 디렉터리, export_path = 우리 비식별 base/videos/{rawSn}/.
        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "projectA", "user01",
                "/nas-storage/videos/9001/",
                "/nas-storage/raw/9001/",
                List.of("sample1.mp4", "sample2.mp4"));
        KpstProjectResponse resp = client().createProject(req);

        assertThat(resp.result()).isEqualTo("success");
        assertThat(resp.prjId()).isEqualTo(279L);
        RecordedRequest rec = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rec).isNotNull();
        assertThat(rec.getPath()).isEqualTo("/project");
        // 스네이크케이스 직렬화 검증
        String body = rec.getBody().readUtf8();
        assertThat(body).contains("\"project_name\":\"projectA\"");
        assertThat(body).contains("\"input_path\":\"/nas-storage/raw/9001/\"");
        assertThat(body).contains("\"exp_format\":1");
    }

    @Test
    @DisplayName("프로젝트_동일이름_409를_적절히_처리한다")
    void createProjectConflict() {
        server.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"fail\",\"message\":\"Project already exists\"}"));

        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "projectA", "user01", "/nas-storage/videos/9002/", "/nas-storage/raw/9002/", List.of("a.mp4"));

        assertThatThrownBy(() -> client().createProject(req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("진행률_조회에서_dataset_procState를_파싱한다")
    void retrieveProgress() {
        installProgressDispatcher("{\"result\":\"success\",\"data\":{\"prjCount\":1,\"prjStatus\":[{"
                + "\"prjId\":279,\"prjName\":\"projectA\",\"progressRate\":42.5,\"dsCount\":2,"
                + "\"dsStatus\":[{\"dsId\":1270,\"fileName\":\"sample1.mp4\",\"procState\":2,"
                + "\"progressRate\":100.0,\"totalFrame\":5400}]}]}}");

        KpstProgressResponse resp = client().retrieveProgress("user01", 279L);

        assertThat(resp.data().prjCount()).isEqualTo(1);
        KpstProgressResponse.PrjStatus prj = resp.data().prjStatus().get(0);
        assertThat(prj.prjId()).isEqualTo(279L);
        KpstProgressResponse.DsStatus ds = prj.dsStatus().get(0);
        assertThat(ds.dsId()).isEqualTo(1270L);
        assertThat(ds.procState()).isEqualTo(2);
        assertThat(ds.totalFrame()).isEqualTo(5400);
    }

    @Test
    @DisplayName("진행조회는_쿼리가_아니라_JSON바디로_reqUserId와_prjId를_전송한다")
    void retrieveProgressSendsJsonBody() {
        // given: 실서버는 GET 이라도 JSON 바디 필터를 강제(쿼리 전용은 400 Invalid JSON body).
        installProgressDispatcher("{\"result\":\"success\",\"data\":{\"prjCount\":1,\"prjStatus\":[{"
                + "\"prjId\":279,\"prjName\":\"projectA\",\"progressRate\":100.0,\"dsCount\":1,"
                + "\"dsStatus\":[{\"dsId\":1270,\"fileName\":\"s.mp4\",\"procState\":2,"
                + "\"progressRate\":100.0,\"totalFrame\":5400}]}]}}");

        // when
        client().retrieveProgress("user01", 279L);

        // then: 메서드는 GET, 경로는 쿼리스트링 없이 /retrieve_progress, 서버가 수신한 바디에 필터가 실린다.
        assertThat(progressMethod).isEqualTo("GET");
        assertThat(progressPath).isEqualTo("/retrieve_progress");
        assertThat(progressBody).contains("\"reqUserId\":\"user01\"");
        assertThat(progressBody).contains("\"prjId\":279");
    }

    @Test
    @DisplayName("진행조회_미시작_데이터셋의_null_procState와_None시각을_안전하게_역직렬화한다")
    void retrieveProgressDeserializesNullFields() {
        // given: 실서버는 처리 미시작 시 procState:null, totalFrame:null, startTime:"None" 을 반환.
        //  primitive 였다면 FAIL_ON_NULL_FOR_PRIMITIVES 로 역직렬화가 깨짐 — boxed 로 안전 처리.
        installProgressDispatcher("{\"result\":\"success\",\"data\":{\"prjCount\":1,\"prjStatus\":[{"
                + "\"prjId\":279,\"prjName\":\"projectA\",\"progressRate\":0.0,\"dsCount\":1,"
                + "\"dsStatus\":[{\"dsId\":1270,\"fileName\":\"s.mp4\",\"procState\":null,"
                + "\"progressRate\":0.0,\"totalFrame\":null,\"startTime\":\"None\",\"endTime\":\"None\"}]}]}}");

        KpstProgressResponse resp = client().retrieveProgress("user01", 279L);

        KpstProgressResponse.DsStatus ds = resp.data().prjStatus().get(0).dsStatus().get(0);
        assertThat(ds.procState()).isNull();
        assertThat(ds.totalFrame()).isNull();
        assertThat(ds.startTime()).isEqualTo("None");
    }

    @Test
    @DisplayName("프로젝트_삭제가_id와_userId를_전송한다")
    void deleteProject() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"message\":\"Project '279' deleted successfully\"}"));

        client().deleteProject(279L, "user01");

        RecordedRequest rec = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rec).isNotNull();
        assertThat(rec.getPath()).isEqualTo("/delete_project_id");
        String body = rec.getBody().readUtf8();
        assertThat(body).contains("\"project_id\":279");
        assertThat(body).contains("\"user_id\":\"user01\"");
    }

    // ===== K3: 4xx 비재시도 분류 — 결정적 실패는 재시도·서킷집계 제외 =====

    @Test
    @DisplayName("K3_createProject_409는_재시도없이_1회요청_CONFLICT매핑_보존")
    void createProject409NotRetriedConflictPreserved() {
        // given — 결정적 4xx(409 중복) 응답 1건만 enqueue.
        server.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"fail\",\"message\":\"Project already exists\"}"));
        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "projectA", "user01", "/nas-storage/videos/9002/", "/nas-storage/raw/9002/", List.of("a.mp4"));

        // when / then — 3회 재시도 설정이라도 4xx 는 재시도되지 않고, 사용자-대면 CONFLICT 매핑이 보존된다.
        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable()).createProject(req))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("K3_createProject_400은_재시도없이_1회요청_INVALID_INPUT매핑_보존")
    void createProject400NotRetriedInvalidInputPreserved() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"fail\"}"));
        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "projectA", "user01", "/nas-storage/videos/9002/", "/nas-storage/raw/9002/", List.of("a.mp4"));

        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable()).createProject(req))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("K3_createProject_500은_설정된_횟수만큼_재시도한다")
    void createProject500Retried() {
        // given — 일시적 5xx 는 3회 재시도 대상.
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "projectA", "user01", "/nas-storage/videos/9002/", "/nas-storage/raw/9002/", List.of("a.mp4"));

        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable()).createProject(req))
                .isInstanceOf(CustomException.class);
        // 5xx 는 max-attempts(3) 만큼 재시도 → 3회 요청.
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    // ===== K3 보강 — retrieveProgress(K1 폴링 의존 경로) + deleteProject 4xx/5xx 분류 =====
    // retrieveProgress 는 raw reactor-netty HttpClient(kpstDeidProgressHttpClient) 경로다.
    // 재시도 발생 여부는 진행조회 서버(JDK HttpServer)가 수신한 요청 카운트(progressRequestCount)로 단언한다.

    @Test
    @DisplayName("K3_retrieveProgress_400은_재시도없이_1회요청_INVALID_INPUT매핑_보존")
    void retrieveProgress400NotRetriedInvalidInputPreserved() {
        // given — 진행조회가 결정적 4xx(400)로 응답.
        installProgressStatusDispatcher(400);

        // when / then — 3회 재시도 설정이라도 4xx 는 재시도되지 않고, INVALID_INPUT 매핑이 보존된다.
        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable())
                .retrieveProgress("user01", 279L))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
        // 요청 카운트 1 = 재시도 미발생.
        assertThat(progressRequestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("K3_retrieveProgress_404는_재시도없이_1회요청_NOT_FOUND매핑_보존")
    void retrieveProgress404NotRetriedNotFoundPreserved() {
        // given — 진행조회가 결정적 4xx(404)로 응답.
        installProgressStatusDispatcher(404);

        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable())
                .retrieveProgress("user01", 279L))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(progressRequestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("K3_retrieveProgress_500은_설정된_횟수만큼_재시도한다")
    void retrieveProgress500Retried() {
        // given — 일시적 5xx 는 재시도 대상(대조군). 진행조회가 항상 500 응답.
        installProgressStatusDispatcher(500);

        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable())
                .retrieveProgress("user01", 279L))
                .isInstanceOf(CustomException.class);
        // 5xx 는 max-attempts(3) 만큼 재시도 → 진행조회 서버가 3회 수신.
        assertThat(progressRequestCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("K3_deleteProject_404는_재시도없이_1회요청_NOT_FOUND매핑_보존")
    void deleteProject404NotRetriedNotFoundPreserved() {
        // given — 삭제가 결정적 4xx(404)로 응답.
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"fail\",\"message\":\"Project not found\"}"));

        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable())
                .deleteProject(279L, "user01"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        // 4xx 는 재시도되지 않으므로 요청은 1회뿐.
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("K3_deleteProject_400은_재시도없이_1회요청_INVALID_INPUT매핑_보존")
    void deleteProject400NotRetriedInvalidInputPreserved() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"fail\"}"));

        assertThatThrownBy(() -> clientWith(tripleAttemptIgnoringNonRetryable())
                .deleteProject(279L, "user01"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ===== DEV_FIX 보강 — 삭제 경로 검증 =====

    @Test
    @DisplayName("프로젝트_삭제_result_fail이면_예외")
    void deleteProjectFailRejected() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"fail\",\"message\":\"Project not found\"}"));

        assertThatThrownBy(() -> client().deleteProject(279L, "user01"))
                .isInstanceOf(CustomException.class);
    }
}
