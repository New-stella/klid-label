package kr.co.cudo.authoring.notice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.notice.repository.LsNoticeAttachRepository;
import kr.co.cudo.authoring.notice.repository.LsNoticeRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시판(공지) 첨부파일 통합 테스트.
 *
 * <p>업로드 보안(확장자/크기/경로순회/빈파일) · 권한(REVIEWER/WORKER/PORTAL) ·
 * 가시성(DRAFT → WORKER 404) · IDOR(타 공지 첨부) · 물리 파일 정리 검증.
 *
 * <p>저장 경로는 {@link DynamicPropertySource} 로 OS 임시 디렉토리에 오버라이드해
 * 실제 STORAGE_RAW_PATH 를 오염시키지 않는다.
 *
 * <p><b>비-트랜잭션(실제 커밋) 모드</b>: 첨부/공지 삭제 시 물리 파일은
 * {@code afterCommit} 동기화 콜백에서 제거된다. 따라서 테스트를 {@code @Transactional}
 * 롤백 모드로 두면 콜백이 실행되지 않아 파일 정리 검증이 불가능하다.
 * 본 테스트는 클래스 레벨 {@code @Transactional} 을 두지 않아 MockMvc 요청이 실제 커밋되며,
 * 누적된 공지 row 와 임시 디렉토리는 {@link #cleanupRows()}/{@link #cleanupStorage()} 로 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class NoticeAttachControllerTest {

    private static Path tempStorage;

    @DynamicPropertySource
    static void overrideStorage(DynamicPropertyRegistry registry) throws IOException {
        tempStorage = Files.createTempDirectory("notice-attach-test");
        registry.add("authoring.storage.raw-path", () -> tempStorage.toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsNoticeAttachRepository attachRepository;
    @Autowired private LsNoticeRepository noticeRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
    }

    /** 비-트랜잭션 모드라 커밋된 행이 누적되므로 각 테스트 후 첨부/공지 row 를 정리. */
    @AfterEach
    void cleanupRows() {
        attachRepository.deleteAll();
        noticeRepository.deleteAll();
    }

    /** MINOR 6 — 테스트에서 생성한 물리 파일/임시 디렉토리 정리. */
    @AfterAll
    static void cleanupStorage() {
        if (tempStorage != null) {
            FileSystemUtils.deleteRecursively(tempStorage.toFile());
        }
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode noticeBody(String title, String content, boolean pinned) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("content", content);
        body.put("pinned", pinned);
        return body;
    }

    private long createNotice() throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noticeBody("공지", "본문", false))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long createPublishedNotice() throws Exception {
        long id = createNotice();
        mockMvc.perform(post("/v1/notices/" + id + "/publish")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        return id;
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    }

    private long uploadAttach(long noticeId, MockMultipartFile file) throws Exception {
        MvcResult res = mockMvc.perform(multipart("/v1/notices/" + noticeId + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("attachSn").asLong();
    }

    // ----------------------------------------------------------------- tests

    @Test
    @DisplayName("허용확장자_파일_업로드시_저장되고_원본명_보존")
    void uploadAllowedExtensionStoresAndKeepsOriginalName() throws Exception {
        long noticeId = createNotice();

        mockMvc.perform(multipart("/v1/notices/" + noticeId + "/attachments")
                        .file(file("보고서.pdf", "pdf-bytes".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileName").value("보고서.pdf"))
                .andExpect(jsonPath("$.data.fileSize").value("pdf-bytes".length()))
                // 절대경로/저장파일명(UUID) 미노출 (CWE-209)
                .andExpect(jsonPath("$.data.filePath").doesNotExist())
                .andExpect(jsonPath("$.data.storeFileNm").doesNotExist());
    }

    @Test
    @DisplayName("비허용확장자_exe_업로드시_400")
    void uploadDisallowedExtensionRejected() throws Exception {
        long noticeId = createNotice();

        mockMvc.perform(multipart("/v1/notices/" + noticeId + "/attachments")
                        .file(file("malware.exe", "x".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("크기초과_파일_업로드시_413")
    void uploadOversizedRejected() throws Exception {
        long noticeId = createNotice();
        byte[] over20mb = new byte[(20 * 1024 * 1024) + 1];

        mockMvc.perform(multipart("/v1/notices/" + noticeId + "/attachments")
                        .file(file("big.zip", over20mb))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("경로순회_파일명_업로드시_UUID저장으로_안전하고_원본명만_DB보관")
    void uploadPathTraversalFilenameIsSafe() throws Exception {
        long noticeId = createNotice();

        // 원본명에 경로순회 패턴이 있어도 확장자(.pdf)는 허용이므로 통과하되,
        // 저장 파일명은 UUID 라 storage 밖에 쓰이지 않아야 한다.
        long attachSn = uploadAttach(noticeId,
                file("../../etc/passwd.pdf", "data".getBytes(StandardCharsets.UTF_8)));

        var attach = attachRepository.findById(attachSn).orElseThrow();
        // 저장 파일명은 UUID + .pdf (경로 구분자 없음)
        assertThat(attach.getStoreFileNm()).doesNotContain("/").doesNotContain("..");
        assertThat(attach.getStoreFileNm()).endsWith(".pdf");
        // 저장 경로는 temp storage 내부로 격리
        assertThat(Path.of(attach.getFilePath()).normalize())
                .startsWith(tempStorage.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("WORKER_업로드_시도시_403")
    void workerUploadForbidden() throws Exception {
        long noticeId = createNotice();

        mockMvc.perform(multipart("/v1/notices/" + noticeId + "/attachments")
                        .file(file("doc.pdf", "x".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른_공지의_첨부ID로_다운로드시_404")
    void downloadWithMismatchedNoticeReturns404() throws Exception {
        long noticeA = createPublishedNotice();
        long noticeB = createPublishedNotice();
        long attachOnA = uploadAttach(noticeA, file("a.pdf", "a".getBytes(StandardCharsets.UTF_8)));

        // 첨부는 noticeA 소속인데 noticeB 경로로 접근 → IDOR 차단 404
        mockMvc.perform(get("/v1/notices/" + noticeB + "/attachments/" + attachOnA + "/download")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("WORKER가_발행글_첨부_다운로드_성공")
    void workerDownloadsPublishedAttach() throws Exception {
        long noticeId = createPublishedNotice();
        byte[] content = "hello-pdf".getBytes(StandardCharsets.UTF_8);
        long attachSn = uploadAttach(noticeId, file("안내문.pdf", content));

        MvcResult res = mockMvc.perform(get(
                        "/v1/notices/" + noticeId + "/attachments/" + attachSn + "/download")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                // RFC 5987 — 한글 파일명은 filename* 로 UTF-8 percent-encoding
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn();

        assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    @DisplayName("WORKER가_DRAFT_공지_첨부_다운로드시_404")
    void workerDownloadDraftAttachNotFound() throws Exception {
        long draftNotice = createNotice(); // DRAFT
        long attachSn = uploadAttach(draftNotice, file("초안.pdf", "x".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/v1/notices/" + draftNotice + "/attachments/" + attachSn + "/download")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("첨부_삭제시_DB행과_물리파일_모두_삭제")
    void deleteRemovesRowAndPhysicalFile() throws Exception {
        long noticeId = createNotice();
        long attachSn = uploadAttach(noticeId, file("삭제대상.pdf", "data".getBytes(StandardCharsets.UTF_8)));
        Path stored = Path.of(attachRepository.findById(attachSn).orElseThrow().getFilePath());
        assertThat(Files.exists(stored)).isTrue();

        mockMvc.perform(delete("/v1/notices/" + noticeId + "/attachments/" + attachSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        assertThat(attachRepository.findById(attachSn)).isEmpty();
        assertThat(Files.exists(stored)).isFalse();
    }

    @Test
    @DisplayName("공지_삭제시_첨부_물리파일_정리")
    void deleteNoticeCleansUpAttachFiles() throws Exception {
        long noticeId = createNotice();
        long attachSn = uploadAttach(noticeId, file("부속.pdf", "data".getBytes(StandardCharsets.UTF_8)));
        Path stored = Path.of(attachRepository.findById(attachSn).orElseThrow().getFilePath());
        assertThat(Files.exists(stored)).isTrue();

        mockMvc.perform(delete("/v1/notices/" + noticeId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        assertThat(Files.exists(stored)).isFalse();
    }

    @Test
    @DisplayName("WORKER가_삭제_시도시_403")
    void workerDeleteForbidden() throws Exception {
        long noticeId = createNotice();
        long attachSn = uploadAttach(noticeId, file("doc.pdf", "x".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(delete("/v1/notices/" + noticeId + "/attachments/" + attachSn)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }
}
