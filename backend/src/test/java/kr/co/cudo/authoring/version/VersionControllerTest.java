package kr.co.cudo.authoring.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.version.dto.RollbackRequest;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 — VersionController + LS_LABEL_VERSION 통합 회귀 (DB 스냅샷 기반).
 *
 * <p>외부 버전관리 서버 제거 이후 모든 commit/list/diff/rollback 은 LS_LABEL_VERSION
 * (versionHash + labelPayload) 기준으로 동작한다. 식별자는 라벨 스냅샷의 SHA-256(versionHash).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VersionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsLabelVersionRepository labelVersionRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerNotAssignedToken;

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        labelVersionRepository.deleteAll();

        reviewerToken           = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken     = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken  = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-VER-CTL-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now()))
                .getSrcSn();

        // 작업자 100 만 배정
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    /** DB 스냅샷 버전 1건 시드 — versionHash + labelPayload 보관. */
    private LsLabelVersion seedVersion(String versionHash, String labelPayload,
                                       int versionNo, String reasonCd, String regId, boolean active) {
        LsLabelVersion v = LsLabelVersion.create(rawSn, srcSn, versionHash, labelPayload,
                versionNo, reasonCd, regId);
        if (!active) {
            v.deactivate();
        }
        return labelVersionRepository.save(v);
    }

    // ──────────────────────────────────────────────
    // GET /v1/frames/{srcSn}/versions
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET_frames_versions_정상_조회_DB_스냅샷_목록_반환")
    void getVersionsReturnsList() throws Exception {
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "{\"items\":[]}",
                1, LsLabelVersion.SAVE_REASON_APPROVED, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", "{\"items\":[]}",
                2, LsLabelVersion.SAVE_REASON_APPROVED, "100", true);

        mockMvc.perform(get("/v1/frames/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].commitSha").exists())
                .andExpect(jsonPath("$.data[0].shortHash").exists())
                .andExpect(jsonPath("$.data[0].isCurrent").value(true));
    }

    @Test
    @DisplayName("GET_frames_versions_커밋_없는_새_프레임_빈_배열_200")
    void getVersionsEmptyForFreshSrc() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET_frames_versions_미배정_WORKER_403")
    void getVersionsForbiddenForUnassignedWorker() throws Exception {
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "{\"items\":[]}",
                1, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        mockMvc.perform(get("/v1/frames/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────────────────────────────
    // GET /v1/versions/{versionHash}/diff
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET_diff_두_스냅샷_라벨_단위_ADDED_MODIFIED_REMOVED_분류_반환")
    void getDiffReturnsLabelUnitChanges() throws Exception {
        // id=1: 좌표 변경 → MODIFIED, id=2: from 에만 → REMOVED, id=3: to 에만 → ADDED
        String fromJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[15.0,15.0],[55.0,55.0]]},"
                + "{\"id\":3,\"lblTypeCd\":\"BBOX\",\"label\":\"bike\",\"points\":[[300.0,300.0],[400.0,400.0]]}"
                + "]}";
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", fromJson,
                1, LsLabelVersion.SAVE_REASON_APPROVED, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", toJson,
                2, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        mockMvc.perform(get("/v1/versions/bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222/diff")
                        .param("compareWith", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // FE 정합: data 가 LabelDiff 배열
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.objectId=='1')].type").value("MODIFIED"))
                .andExpect(jsonPath("$.data[?(@.objectId=='2')].type").value("REMOVED"))
                .andExpect(jsonPath("$.data[?(@.objectId=='3')].type").value("ADDED"));
    }

    @Test
    @DisplayName("GET_diff_동일_스냅샷_비교시_빈_배열_회귀가드")
    void getDiffSameSnapshotReturnsEmpty() throws Exception {
        String sameJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", sameJson,
                1, LsLabelVersion.SAVE_REASON_APPROVED, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", sameJson,
                2, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        mockMvc.perform(get("/v1/versions/bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222/diff")
                        .param("compareWith", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET_diff_존재하지_않는_버전_해시_404")
    void getDiffUnknownHashNotFound() throws Exception {
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "{\"items\":[]}",
                1, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        mockMvc.perform(get("/v1/versions/ffffffffffffffffffffffffffffffffffffffff/diff")
                        .param("compareWith", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    // ──────────────────────────────────────────────
    // POST /v1/versions/{versionHash}/rollback
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST_rollback_REVIEWER_정상_대상_버전_행_재활성_적층없음")
    void postRollbackReviewerOk() throws Exception {
        // D-ISSUE-21 — 롤백은 새 행을 적층하지 않고 대상 버전 행을 다시 active 로 전환한다.
        String pastPayload = "{\"items\":[]}";
        String pastHash = sha256Hex(pastPayload);
        seedVersion(pastHash, pastPayload, 1, LsLabelVersion.SAVE_REASON_APPROVED, "1", false);
        String currentPayload = "{\"items\":[{\"id\":1}]}";
        seedVersion(sha256Hex(currentPayload), currentPayload,
                2, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/" + pastHash + "/rollback")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionHash").value(pastHash))
                .andExpect(jsonPath("$.data.srcSn").value(srcSn));

        // 신규 row 없음(2건 유지) + 이전 ACTIVE='Y' row 가 'N' 으로 deactivate
        List<LsLabelVersion> all = labelVersionRepository.findAll();
        assertThat(all).hasSize(2);
        long activeCount = all.stream().filter(v -> "Y".equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
        LsLabelVersion active = all.stream()
                .filter(v -> "Y".equals(v.getActiveYn())).findFirst().orElseThrow();
        assertThat(active.getVersionHash()).isEqualTo(pastHash);
        // 롤백 결과 스냅샷은 대상 버전의 페이로드를 복원한다.
        assertThat(active.getLabelPayload()).isEqualTo(pastPayload);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("POST_rollback_배정된_WORKER_본인_프레임_정상_200")
    void postRollbackAssignedWorkerOk() throws Exception {
        String pastPayload = "{\"items\":[]}";
        String pastHash = sha256Hex(pastPayload);
        seedVersion(pastHash, pastPayload, 1, LsLabelVersion.SAVE_REASON_APPROVED, "1", false);
        String currentPayload = "{\"items\":[{\"id\":9}]}";
        seedVersion(sha256Hex(currentPayload), currentPayload,
                2, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/" + pastHash + "/rollback")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionHash").value(pastHash));

        // 적층 없음 — 기존 2건 유지, active 1건.
        List<LsLabelVersion> all = labelVersionRepository.findAll();
        assertThat(all).hasSize(2);
        long activeCount = all.stream().filter(v -> "Y".equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST_rollback_미배정_WORKER_403")
    void postRollbackUnassignedWorkerForbidden() throws Exception {
        String pastHash = "feedface1234567890abcdef1234567890abcdef";
        seedVersion(pastHash, "{\"items\":[]}", 1, LsLabelVersion.SAVE_REASON_APPROVED, "1", true);

        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/" + pastHash + "/rollback")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST_rollback_잘못된_해시_형식_400")
    void postRollbackInvalidHashRejected() throws Exception {
        // hex 가 아닌 문자(z, -) 포함 → validateHash 에서 INVALID_INPUT(400)
        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/zzzz-not-a-hash-value/rollback")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────
    // 폐기된 수동 커밋 엔드포인트 회귀 가드
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("폐기된_POST_frames_commit_엔드포인트는_더이상_라우팅되지_않음_404")
    void removedCommitEndpointReturns404() throws Exception {
        // 버전 스냅샷은 검수 승인 시점에만 생성 — 수동 커밋(POST /frames/{srcSn}/commit)은 폐기됨.
        mockMvc.perform(post("/v1/frames/" + srcSn + "/commit")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
