package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라이브 목 서버 실연동 통합 테스트 — {@code KpstDeidentifyClient}/{@code VlmClient} 가
 * {@code mock-server/}(FastAPI/uvicorn) 에 실제로 붙어 벤더 규격 왕복을 하는지 검증한다.
 *
 * <p><b>목적</b>: MockWebServer(하네스 스텁)가 아니라 실제로 기동한 벤더 목 서버에 우리 BE 클라이언트가
 * 붙어, KPST 진행조회의 {@code GET + JSON 바디} 프레이밍과 VLM describe 의 {@code request_id echo +
 * status="accepted"} 검증이 실서버 왕복에서 성립하는지를 end-to-end 로 확인한다.
 *
 * <h3>격리 / 게이트</h3>
 * <ul>
 *   <li>{@link #mockLiveEnabled()} 로 게이트한다 — {@code mockLiveIT} 스위치(시스템 프로퍼티 또는
 *       동명 환경변수)가 {@code true} 일 때만 활성. 스위치가 없는 일반 {@code ./gradlew test} 에서는
 *       이 클래스 전체가 <b>비활성(스킵)</b> 되어 기존 빌드 영향 0.</li>
 *   <li><b>켜는 방법은 {@code ./gradlew test -PmockLiveIT} 하나다</b>(2026-08-27 신설). build.gradle 이
 *       그 프로젝트 프로퍼티를 받아 {@code systemProperty} 로 워커에 넘긴다.
 *       <br>⚠ <b>셸 환경변수와 CLI {@code -D} 로는 켜지지 않는다</b> — 워커는 이미 뜬 데몬 환경을
 *       물려받으므로 둘 다 워커까지 도달하지 않는다. 구 서술은 환경변수를 "워커에 상속되는 실효
 *       스위치" 라고 적었으나 <b>그 전달 선언이 애초에 없었고</b>, 그래서 목 서버를 띄우고 스위치를
 *       줘도 클래스가 켜진 적이 없었다.</li>
 *   <li><b>스위치를 켜도 환경이 갖춰지지 않으면 여전히 스킵된다</b> — 아래 {@code assumeTrue} 가
 *       {@code ai-server/.venv/bin/python} 을 요구한다. 그 venv 는 버전관리 대상이 아니라
 *       <b>git 워크트리에는 없다</b>(메인 체크아웃에만 있다). 즉 워크트리에서는 스위치만으로 이
 *       시험을 돌릴 수 없다. <b>스킵 수가 같아도 사유가 다르므로</b>(클래스 비활성 ↔ 전제 미충족)
 *       숫자만 보고 판단하지 말 것.</li>
 *   <li>{@code @SpringBootTest} 미사용 — 클라이언트를 직접 {@code new} 로 생성(단위테스트 방식)해 Spring
 *       컨텍스트/DB 로딩을 회피한다(DB 없이 실행 가능).</li>
 *   <li>목 서버 경로/venv 부재 시 {@link Assumptions#assumeTrue} 로 graceful skip(fail 아님).</li>
 * </ul>
 *
 * <h3>실행</h3>
 * <pre>
 * cd backend &amp;&amp; mockLiveIT=true ./gradlew test \
 *   --tests "kr.co.cudo.authoring.common.client.MockServerLiveIntegrationIT"
 * </pre>
 */
@EnabledIf("mockLiveEnabled")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("라이브 목 서버 실연동 IT — KPST/VLM 클라이언트 왕복")
class MockServerLiveIntegrationIT {

    /**
     * 실행 게이트 — {@code mockLiveIT} 스위치가 {@code true} 이면 활성.
     *
     * <p><b>켜는 방법은 {@code ./gradlew test -PmockLiveIT} 하나다.</b> build.gradle 이 그 프로젝트
     * 프로퍼티를 받아 {@code systemProperty 'mockLiveIT'} 로 워커에 넘긴다. 둘 다 없으면 클래스 전체
     * 비활성 — 일반 빌드 영향 0.
     *
     * <p>⚠ <b>환경변수로는 켜지지 않는다</b>(2026-08-27 실측). 테스트 워커는 이미 뜬 데몬 환경을
     * 물려받으므로 셸 환경변수와 {@code -D} 가 워커까지 도달하지 않는다 — 이 저장소가 build.gradle
     * 주석으로 경고해 둔 함정이다. {@code System.getenv} 분기는 그 경로로 주입되는 다른 실행기를
     * 위해 남겨 두지만, 이 저장소의 {@code ./gradlew test} 에서는 프로젝트 프로퍼티만이 실효 경로다.
     *
     * <p>⚠ 구 서술 폐기 — 환경변수를 "이 저장소 build.gradle 에서 워커에 상속되는 실효 스위치" 로
     * 적었으나 그 전달 선언이 <b>없었다</b>. 그래서 목서버를 띄우고 스위치를 줘도 항상 skipped 였다.
     */
    static boolean mockLiveEnabled() {
        return "true".equalsIgnoreCase(System.getProperty("mockLiveIT"))
                || "true".equalsIgnoreCase(System.getenv("mockLiveIT"));
    }

    /** 헬스체크 폴링 상한. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(15);

    private static Process mockProcess;
    private static int port;
    private static String baseUrl;
    /** 목 서버 프로세스 출력(디버깅/스킵 사유 캡처용). */
    private static final ConcurrentLinkedQueue<String> processLog = new ConcurrentLinkedQueue<>();

    /** 여러 케이스가 재사용하는 KPST 프로젝트 ID(케이스 2 에서 발급). */
    private static long createdPrjId;

    // ── 목 서버 기동 (ProcessBuilder + uvicorn) ─────────────────────────────

    @BeforeAll
    static void startMockServer() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "mock-server/app/main.py 경로를 찾지 못함 — 스킵");

        Path python = repoRoot.resolve("ai-server/.venv/bin/python");
        Path mockDir = repoRoot.resolve("mock-server");
        Assumptions.assumeTrue(Files.isExecutable(python),
                "ai-server/.venv/bin/python 부재 — 스킵");

        port = freePort();
        baseUrl = "http://127.0.0.1:" + port;

        ProcessBuilder pb = new ProcessBuilder(
                python.toString(), "-m", "uvicorn", "app.main:app",
                "--host", "127.0.0.1", "--port", Integer.toString(port));
        pb.directory(mockDir.toFile());
        pb.environment().put("MOCK_CALLBACK_DELAY_SECONDS", "1");
        pb.redirectErrorStream(true);

        mockProcess = pb.start();
        startLogPump(mockProcess);

        boolean healthy = waitForHealth();
        if (!healthy) {
            String tail = logTail();
            destroyProcess();
            if (mockProcess != null && !processExitedCleanly()) {
                // 프로세스가 살아있었는데도 헬스 미달 → 환경/기동 문제로 명확히 스킵(로그 첨부).
                Assumptions.assumeTrue(false,
                        "목 서버 헬스체크 실패(기동 로그):\n" + tail);
            }
            Assumptions.assumeTrue(false, "목 서버 기동 실패(프로세스 종료). 로그:\n" + tail);
        }
    }

    @AfterAll
    static void stopMockServer() {
        destroyProcess();
    }

    // ── 케이스 (한글 @DisplayName, @Order 로 순차 — describe.serial 성격) ─────

    @Test
    @Order(1)
    @DisplayName("connect 는 Connect 를 받아 true 를 반환한다")
    void connect_는_Connect_를_받아_true() {
        boolean ok = kpstClient().connect();
        assertThat(ok).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("프로젝트 생성은 prj_id 를 발급한다")
    void 프로젝트_생성은_prj_id_를_발급() {
        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "liveIT-project", "tester",
                baseUrl + "/export/9001/", "/nas-storage/raw/9001/",
                List.of("sample1.mp4", "sample2.mp4"));

        KpstProjectResponse resp = kpstClient().createProject(req).block();

        assertThat(resp.result()).isEqualTo("success");
        assertThat(resp.prjId()).isGreaterThan(0L);
        createdPrjId = resp.prjId();
    }

    @Test
    @Order(3)
    @DisplayName("진행조회 GET+JSON바디 왕복이 성공한다")
    void 진행조회_GET_JSON바디_왕복이_성공() {
        // 선행 케이스(생성)에서 발급된 prjId 로 폴링 — GET+body 프레이밍이 실서버 왕복에서 성립하는지가 관건.
        assertThat(createdPrjId).isGreaterThan(0L);

        KpstProgressResponse resp = kpstClient().retrieveProgress("tester", createdPrjId);

        assertThat(resp.result()).isEqualTo("success");
        assertThat(resp.data()).isNotNull();
        assertThat(resp.data().prjCount()).isGreaterThanOrEqualTo(1);
        KpstProgressResponse.PrjStatus prj = resp.data().prjStatus().get(0);
        assertThat(prj.prjId()).isEqualTo(createdPrjId);
        // progressRate 필드 접근이 가능(역직렬화 성공)해야 한다.
        assertThat(prj.progressRate()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @Order(4)
    @DisplayName("프로젝트 삭제가 성공한다")
    void 프로젝트_삭제가_성공() {
        assertThat(createdPrjId).isGreaterThan(0L);
        // 예외 없이 완료되면 성공(void).
        kpstClient().deleteProject(createdPrjId, "tester");
    }

    @Test
    @Order(5)
    @DisplayName("VLM verify 는 accepted 와 request_id echo 를 반환한다")
    void VLM_verify_는_accepted와_request_id_echo() {
        VlmTimeseriesRequest req = VlmTimeseriesRequest.ofFrameInterval("live-it-req-001", "fall", "/data/videos/deid.mp4", baseUrl + "/unused");

        VlmTimeseriesResponse resp = vlmClient().submitDescribe(req)
                .block(Duration.ofSeconds(10));

        assertThat(resp).isNotNull();
        // validateResponse 통과(예외 없이 반환) == request_id echo 일치 + status accepted.
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_ACCEPTED);
        assertThat(resp.requestId()).isEqualTo("live-it-req-001");
    }

    // ── 클라이언트 구성 (KpstDeidentifyClientTest / VlmClientTest 최소 설정 복제) ─

    private static KpstDeidentifyClient kpstClient() {
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        // 진행조회는 GET+JSON바디(Content-Length 프레이밍) — 저수준 reactor-netty HttpClient 로 동일 목 서버 지정.
        HttpClient progressHttpClient = HttpClient.create().baseUrl(baseUrl)
                .responseTimeout(Duration.ofSeconds(10));
        CircuitBreaker cb = CircuitBreakerRegistry.of(
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(50)
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                .build())
                .circuitBreaker("kpstDeid");
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        return new KpstDeidentifyClient(webClient, progressHttpClient, cb, retryRegistry);
    }

    private static VlmClient vlmClient() {
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .build());
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        return new VlmClient(webClient, cbRegistry, retryRegistry, 10L);
    }

    // ── 인프라 헬퍼 ─────────────────────────────────────────────────────────

    /**
     * repoRoot 를 해석한다 — {@code mock-server/app/main.py} 존재로 검증. 테스트 CWD 는 보통 backend/
     * 이므로 CWD 와 그 상위들을 훑어 mock-server 를 포함하는 루트를 찾는다. 못 찾으면 null.
     */
    private static Path resolveRepoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("mock-server/app/main.py"))) {
                return p;
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** 프로세스 출력을 데몬 스레드로 계속 읽어 processLog 에 누적한다(무한 대기/파이프 블로킹 방지). */
    private static void startLogPump(Process process) {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processLog.add(line);
                    if (processLog.size() > 500) {
                        processLog.poll();
                    }
                }
            } catch (IOException ignored) {
                // 프로세스 종료 시 스트림 닫힘 — 정상.
            }
        }, "mock-server-log-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /** {@code GET /health} 를 최대 {@link #HEALTH_TIMEOUT} 폴링해 200 을 대기한다. */
    private static boolean waitForHealth() {
        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (mockProcess != null && !mockProcess.isAlive()) {
                return false; // 조기 종료 — 더 기다릴 필요 없음.
            }
            if (probeHealth()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return probeHealth();
    }

    private static boolean probeHealth() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(baseUrl + "/health").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean processExitedCleanly() {
        return mockProcess != null && !mockProcess.isAlive();
    }

    private static String logTail() {
        return String.join("\n", processLog);
    }

    private static void destroyProcess() {
        if (mockProcess == null) {
            return;
        }
        mockProcess.destroy();
        try {
            if (!mockProcess.waitFor(5, TimeUnit.SECONDS)) {
                mockProcess.destroyForcibly();
                mockProcess.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            mockProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        mockProcess = null;
    }
}
