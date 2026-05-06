package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 — TUS 1.0 업로드 컨트롤러 테스트.
 *
 * 검증 영역:
 *  - HEAD/POST/PATCH/DELETE/OPTIONS 5종 정상 동작
 *  - IDOR 방어 (다른 userId 의 fileId 접근 시 403)
 *  - 확장자/크기 allowlist
 *  - 재개 가능 업로드 (offset 누적)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TusUploadControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStorage(DynamicPropertyRegistry registry) {
        // 디스크 경로를 매 테스트 클래스 인스턴스마다 격리
        registry.add("portal.upload.storage-path", () -> tempDir.toAbsolutePath().toString());
        // 빠른 테스트를 위해 최대 크기를 1MB 로 낮춤 — "최대 크기 초과" 테스트에서 활용
        registry.add("portal.upload.max-file-size-bytes", () -> "1048576");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private kr.co.cudo.authoring.portal.repository.PortalUserVideoRepository portalRepo;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String userAToken;       // sub = "alice"
    private String userBToken;       // sub = "bob"

    @BeforeEach
    void setUp() {
        portalRepo.deleteAll();
        userAToken = JwtTestSupport.token(secret, "alice", "PORTAL_USER", "PORTAL", issuer, 60);
        userBToken = JwtTestSupport.token(secret, "bob",   "PORTAL_USER", "PORTAL", issuer, 60);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes());
    }

    private String uploadMetadata(String filename, String filetype) {
        return "filename " + b64(filename) + ",filetype " + b64(filetype);
    }

    private String createUploadFor(String token, long uploadLength, String filename, String filetype) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + token)
                        .header("Upload-Length", String.valueOf(uploadLength))
                        .header("Upload-Metadata", uploadMetadata(filename, filetype))
                        .header("Tus-Resumable", "1.0.0"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Tus-Resumable", "1.0.0"))
                .andExpect(header().exists("Location"))
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("PORTAL_USER_업로드_TUS_HEAD_offset_조회_정상")
    void tusHeadReturnsOffset() throws Exception {
        String fileId = createUploadFor(userAToken, 100L, "video.mp4", "video/mp4");

        mockMvc.perform(head("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "0"))
                .andExpect(header().string("Upload-Length", "100"))
                .andExpect(header().string("Tus-Resumable", "1.0.0"));
    }

    @Test
    @DisplayName("PORTAL_USER_업로드_TUS_PATCH_청크_저장_+_offset_갱신")
    void tusPatchStoresChunkAndUpdatesOffset() throws Exception {
        String fileId = createUploadFor(userAToken, 10L, "v.mp4", "video/mp4");
        byte[] chunk = new byte[]{1, 2, 3, 4, 5};

        mockMvc.perform(patch("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Offset", "0")
                        .header("Tus-Resumable", "1.0.0")
                        .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                        .content(chunk))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "5"))
                .andExpect(header().string("Tus-Resumable", "1.0.0"));

        mockMvc.perform(head("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "5"));
    }

    @Test
    @DisplayName("다른_사용자의_FileID_접근시_403_FORBIDDEN")
    void idorBlocksOtherUser() throws Exception {
        String aliceFileId = createUploadFor(userAToken, 50L, "v.mp4", "video/mp4");

        // bob 토큰으로 alice 의 fileId 접근 → 403
        mockMvc.perform(head("/v1/portal/uploads/" + aliceFileId)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.nullValue()));

        // PATCH 도 차단
        mockMvc.perform(patch("/v1/portal/uploads/" + aliceFileId)
                        .header("Authorization", "Bearer " + userBToken)
                        .header("Upload-Offset", "0")
                        .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                        .content(new byte[]{1, 2, 3}))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TUS_업로드_중단_후_재개시_offset부터_이어받기")
    void tusResumesFromLastOffset() throws Exception {
        String fileId = createUploadFor(userAToken, 10L, "v.mp4", "video/mp4");

        // 1차 청크 (5 바이트)
        mockMvc.perform(patch("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Offset", "0")
                        .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                        .content(new byte[]{1, 2, 3, 4, 5}))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "5"));

        // (네트워크 단절 시뮬레이션 — 클라이언트가 HEAD 로 offset 재조회 후 이어붙임)
        mockMvc.perform(head("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "5"));

        // 2차 청크 (offset=5 부터 5 바이트 — 완료)
        mockMvc.perform(patch("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Offset", "5")
                        .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                        .content(new byte[]{6, 7, 8, 9, 10}))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "10"));

        // offset 불일치 시 409 (TUS 표준)
        mockMvc.perform(patch("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Offset", "0")  // 잘못된 offset
                        .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                        .content(new byte[]{1}))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("확장자_allowlist_(mp4_mov_avi)_외_파일_400")
    void unsupportedExtensionRejected() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Length", "100")
                        .header("Upload-Metadata", uploadMetadata("malware.exe", "application/x-msdownload")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("최대_파일_크기_초과시_413_PAYLOAD_TOO_LARGE")
    void oversizedRejected() throws Exception {
        // @DynamicPropertySource 로 max-file-size-bytes=1MB 설정 → 2MB 시도 → 413
        mockMvc.perform(post("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Length", String.valueOf(2L * 1024 * 1024))
                        .header("Upload-Metadata", uploadMetadata("big.mp4", "video/mp4")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("OPTIONS_요청_TUS_프로토콜_메타_반환")
    void optionsReturnsTusCapability() throws Exception {
        // OPTIONS 는 TUS 표준상 인증 없이도 가능해야 하지만
        // SecurityConfig 가 /v1/portal/** 를 인증 요구로 잡고 있어 토큰 첨부.
        mockMvc.perform(options("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Tus-Resumable", "1.0.0"))
                .andExpect(header().string("Tus-Version", "1.0.0"))
                .andExpect(header().exists("Tus-Extension"))
                .andExpect(header().exists("Tus-Max-Size"));
    }

    @Test
    @DisplayName("TUS_DELETE_본인_세션_삭제_성공")
    void tusDeleteRemovesSession() throws Exception {
        String fileId = createUploadFor(userAToken, 10L, "v.mp4", "video/mp4");
        mockMvc.perform(delete("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Tus-Resumable", "1.0.0"));

        // 삭제 후 조회 → 404
        mockMvc.perform(head("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("path_traversal_파일ID_접근시_404_또는_403")
    void pathTraversalFileIdRejected() throws Exception {
        // .. 포함 fileId → URL 디코딩 시 차단
        mockMvc.perform(head("/v1/portal/uploads/" + "abc..xyz")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("UploadComplete_시_LS_PORTAL_USER_VIDEO_자동_등록")
    void uploadCompleteRegistersPortalVideo() throws Exception {
        String fileId = createUploadFor(userAToken, 5L, "v.mp4", "video/mp4");

        mockMvc.perform(patch("/v1/portal/uploads/" + fileId)
                        .header("Authorization", "Bearer " + userAToken)
                        .header("Upload-Offset", "0")
                        .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                        .content(new byte[]{1, 2, 3, 4, 5}))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "5"));

        // 본인 업로드 목록에 1건 노출
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].fileName").value("v.mp4"));

        // sanity: tempDir 에 파일 존재
        assertThat(tempDir.resolve("alice").resolve(fileId).toFile()).exists();
    }
}
