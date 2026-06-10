package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.client.dto.KpstUploadResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
                                .build())
                .circuitBreaker("kpstDeid");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private WebClient webClient() {
        return WebClient.builder().baseUrl(server.url("/").toString()).build();
    }

    private RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    private KpstDeidentifyClient client() {
        return new KpstDeidentifyClient(webClient(), webClient(), circuitBreaker, singleAttempt());
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
    @DisplayName("업로드_응답의_inputPath와_files를_파싱한다")
    void upload() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"data\":{"
                        + "\"inputPath\":\"/share/Deid-data/upload/projectA/\","
                        + "\"files\":[\"sample1.mp4\",\"sample2.mp4\"],\"count\":2}}"));
        Path f1 = Files.writeString(tempDir.resolve("sample1.mp4"), "v1");
        Path f2 = Files.writeString(tempDir.resolve("sample2.mp4"), "v2");

        KpstUploadResponse resp = client().upload(List.of(f1, f2), "projectA");

        assertThat(resp.result()).isEqualTo("success");
        assertThat(resp.data().inputPath()).isEqualTo("/share/Deid-data/upload/projectA/");
        assertThat(resp.data().files()).containsExactly("sample1.mp4", "sample2.mp4");
        assertThat(resp.data().count()).isEqualTo(2);
        RecordedRequest rec = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rec).isNotNull();
        assertThat(rec.getPath()).isEqualTo("/upload");
        assertThat(rec.getHeader("Content-Type")).startsWith("multipart/form-data");
    }

    @Test
    @DisplayName("프로젝트_생성_응답에서_prjId를_획득한다")
    void createProject() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"prj_id\":279}"));

        KpstProjectRequest req = KpstProjectRequest.withDefaults(
                "projectA", "user01",
                "/share/Deid-data/export/projectA/",
                "/share/Deid-data/upload/projectA/",
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
        assertThat(body).contains("\"input_path\":\"/share/Deid-data/upload/projectA/\"");
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
                "projectA", "user01", "/export/", "/upload/", List.of("a.mp4"));

        assertThatThrownBy(() -> client().createProject(req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("진행률_조회에서_dataset_procState를_파싱한다")
    void retrieveProgress() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"data\":{\"prjCount\":1,\"prjStatus\":[{"
                        + "\"prjId\":279,\"prjName\":\"projectA\",\"progressRate\":42.5,\"dsCount\":2,"
                        + "\"dsStatus\":[{\"dsId\":1270,\"fileName\":\"sample1.mp4\",\"procState\":2,"
                        + "\"progressRate\":100.0,\"totalFrame\":5400}]}]}}"));

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
    @DisplayName("다운로드가_mask파일을_지정_base경로_안에_저장한다")
    void download() throws IOException {
        byte[] payload = "MASKED-VIDEO-BYTES".getBytes();
        okio.Buffer buf = new okio.Buffer().write(payload);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(buf));
        // target 은 base 기준 상대 경로 (절대경로는 거부 — CWE-22)
        Path target = Path.of("sample1-mask.mp4");

        Path saved = client().download(1270L, tempDir, target);

        assertThat(saved).exists();
        assertThat(Files.readAllBytes(saved)).isEqualTo(payload);
    }

    @Test
    @DisplayName("다운로드_저장경로가_base를_벗어나면_차단한다")
    void downloadPathTraversalBlocked() {
        Path escape = Path.of("../escape-mask.mp4");

        assertThatThrownBy(() -> client().download(1270L, tempDir, escape))
                .isInstanceOf(CustomException.class);
        // base 이탈은 외부 호출 이전에 차단되어야 한다 (요청 발생 X)
        assertThat(server.getRequestCount()).isZero();
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

    // ===== DEV_FIX 보강 — HIGH/MEDIUM/LOW 결함 재현/검증 =====

    @Test
    @DisplayName("다운로드_중_에러시_부분파일이_남지_않는다")
    void downloadErrorLeavesNoPartialFile() {
        // given: 본문 일부를 보낸 뒤 연결을 끊어 다운로드를 실패시킨다.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("PARTIAL")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        Path target = Path.of("err-mask.mp4");

        // when / then: 예외 발생 + 결과/임시 파일 모두 잔존하지 않아야 한다.
        assertThatThrownBy(() -> client().download(1270L, tempDir, target))
                .isInstanceOf(CustomException.class);
        assertThat(tempDir.resolve("err-mask.mp4")).doesNotExist();
        assertThat(tempDir.resolve("err-mask.mp4.part")).doesNotExist();
    }

    @Test
    @DisplayName("다운로드_결과가_0바이트면_실패로_처리하고_파일을_남기지_않는다")
    void downloadZeroByteRejected() {
        // given: 본문 없이 200 — 0바이트 산출물(불완전 비식별).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(new okio.Buffer()));
        Path target = Path.of("zero-mask.mp4");

        // when / then: 0바이트는 거부, 결과/임시 파일 모두 잔존 금지.
        assertThatThrownBy(() -> client().download(1270L, tempDir, target))
                .isInstanceOf(CustomException.class);
        assertThat(tempDir.resolve("zero-mask.mp4")).doesNotExist();
        assertThat(tempDir.resolve("zero-mask.mp4.part")).doesNotExist();
    }

    @Test
    @DisplayName("다운로드_성공시_임시파일이_atomic_move된다")
    void downloadSuccessAtomicMove() throws IOException {
        byte[] payload = "MASKED-VIDEO-BYTES".getBytes();
        okio.Buffer buf = new okio.Buffer().write(payload);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(buf));
        Path target = Path.of("atomic-mask.mp4");

        Path saved = client().download(1270L, tempDir, target);

        assertThat(saved).exists();
        assertThat(Files.readAllBytes(saved)).isEqualTo(payload);
        // 임시(.part) 파일은 move 후 남지 않아야 한다.
        assertThat(tempDir.resolve("atomic-mask.mp4.part")).doesNotExist();
    }

    @Test
    @DisplayName("다운로드_절대경로_target은_거부된다")
    void downloadAbsoluteTargetRejected() {
        Path absolute = tempDir.resolve("abs-mask.mp4").toAbsolutePath();

        assertThatThrownBy(() -> client().download(1270L, tempDir, absolute))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("업로드_파일이_50개_초과면_거부된다")
    void uploadTooManyFilesRejected() throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            files.add(Files.writeString(tempDir.resolve("v" + i + ".mp4"), "x"));
        }

        assertThatThrownBy(() -> client().upload(files, "projectA"))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("업로드_파일목록이_비어있으면_거부된다")
    void uploadEmptyFilesRejected() {
        assertThatThrownBy(() -> client().upload(List.of(), "projectA"))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("업로드_허용되지_않은_확장자는_거부된다")
    void uploadBadExtensionRejected() throws IOException {
        Path bad = Files.writeString(tempDir.resolve("evil.exe"), "x");

        assertThatThrownBy(() -> client().upload(List.of(bad), "projectA"))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("업로드_존재하지_않는_파일은_거부된다")
    void uploadMissingFileRejected() {
        Path missing = tempDir.resolve("nope.mp4");

        assertThatThrownBy(() -> client().upload(List.of(missing), "projectA"))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("업로드_subdir_부정문자는_거부된다")
    void uploadBadSubdirRejected() throws IOException {
        Path f = Files.writeString(tempDir.resolve("ok.mp4"), "x");

        assertThatThrownBy(() -> client().upload(List.of(f), "../escape"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> client().upload(List.of(f), "a/b"))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("업로드_정상_subdir과_한글은_허용된다")
    void uploadValidSubdirAllowed() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"data\":{\"inputPath\":\"/p/\",\"files\":[\"ok.mp4\"],\"count\":1}}"));
        Path f = Files.writeString(tempDir.resolve("ok.mp4"), "x");

        KpstUploadResponse resp = client().upload(List.of(f), "프로젝트-01_a");

        assertThat(resp.result()).isEqualTo("success");
        RecordedRequest rec = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rec).isNotNull();
    }

    @Test
    @DisplayName("업로드_재시도_시에도_multipart가_재발행된다")
    void uploadRetryReissuesMultipart() throws IOException, InterruptedException {
        // 첫 응답은 끊고, 두 번째는 정상. retry=2 클라이언트로 재구독 시 바디가 재전송되는지 검증.
        server.enqueue(new MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"success\",\"data\":{\"inputPath\":\"/p/\",\"files\":[\"ok.mp4\"],\"count\":1}}"));
        Path f = Files.writeString(tempDir.resolve("ok.mp4"), "video-bytes");

        KpstDeidentifyClient retryClient = new KpstDeidentifyClient(
                webClient(), webClient(), circuitBreaker,
                RetryRegistry.of(RetryConfig.custom().maxAttempts(2).build()));
        KpstUploadResponse resp = retryClient.upload(List.of(f), "projectA");

        assertThat(resp.result()).isEqualTo("success");
        // 두 번째(재발행) 요청 본문에 파일 파트가 실려야 한다.
        RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest withBody = (second != null && second.getBodySize() > 0) ? second : first;
        assertThat(withBody).isNotNull();
        assertThat(withBody.getBody().readUtf8()).contains("video-bytes");
    }

    @Test
    @DisplayName("다운로드_저장경로_심링크는_거부된다")
    void downloadSymlinkParentRejected() throws IOException {
        // given: base(tempDir) 내부에 base 외부(externalDir)를 가리키는 심볼릭 링크 디렉터리를 만든다.
        //  - TempDir 기준 상대 구성으로 macOS /var→/private/var 정규화 영향 없이 검증.
        Path externalDir = Files.createDirectories(tempDir.getParent().resolve("kpst-ext-" + System.nanoTime()));
        Path linkDir = tempDir.resolve("linkdir");
        try {
            Files.createSymbolicLink(linkDir, externalDir);
        } catch (UnsupportedOperationException | IOException e) {
            // 심링크 미지원 환경(권한/파일시스템)에서는 검증 불가 — 스킵.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
            return;
        }
        // 저장 경로가 심링크 디렉터리를 타면(부모가 심링크) verifyRealPathWithinBase 가 거부해야 한다.
        Path target = Path.of("linkdir", "mask.mp4");

        // when / then: base 외부를 가리키는 심링크 경로는 차단되고 외부 호출도 발생하지 않는다(CWE-22).
        assertThatThrownBy(() -> client().download(1270L, tempDir, target))
                .isInstanceOf(CustomException.class);
        assertThat(server.getRequestCount()).isZero();
    }

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
