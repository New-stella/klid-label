package kr.co.cudo.authoring.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffFile;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 — VersionController + LS_LABEL_VERSION 통합 회귀.
 * 기존 LS_DATA_LBL_HSTRY.GITEA_CMT_HASH 경로 폐기. 모든 commit/list/rollback 은
 * LS_LABEL_VERSION 기준으로 동작.
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

    @MockBean private GiteaClient giteaClient;

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

    private LsLabelVersion seedVersion(String sha, int versionNo, String reasonCd, String regId, boolean active) {
        LsLabelVersion v = LsLabelVersion.create(0L, rawSn, srcSn, sha, versionNo, reasonCd, regId);
        if (!active) {
            v.deactivate();
        }
        return labelVersionRepository.save(v);
    }

    // ──────────────────────────────────────────────
    // GET /v1/frames/{srcSn}/versions (정식)
    // GET /v1/videos/{srcSn}/versions (deprecated alias)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET_frames_versions_정상_조회_LS_LABEL_VERSION_fallback")
    void getVersionsReturnsList() throws Exception {
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", 2, LsLabelVersion.SAVE_REASON_MANUAL, "100", true);

        // Gitea 호출 실패 → DB 단독 fallback 으로 응답
        when(giteaClient.listCommits(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

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
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", true);

        mockMvc.perform(get("/v1/frames/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET_videos_versions_deprecated_alias_정식_경로와_동일_응답")
    void getVersionsLegacyAliasReturnsSamePayload() throws Exception {
        // given: 동일 시드 데이터
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", 2, LsLabelVersion.SAVE_REASON_MANUAL, "100", true);
        when(giteaClient.listCommits(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        // when / then: 기존 /videos/{srcSn}/versions 도 200 + 동일 size
        mockMvc.perform(get("/v1/videos/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ──────────────────────────────────────────────
    // GET /v1/versions/{commit}/diff
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET_diff_라벨_단위_ADDED_MODIFIED_REMOVED_분류_반환")
    void getDiffReturnsLabelUnitChanges() throws Exception {
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", 2, LsLabelVersion.SAVE_REASON_MANUAL, "1", true);

        // Gitea compare 응답: 파일 단위 메타 (FE 미사용, 내부 보존)
        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of(
                        new DiffFile("labels/" + srcSn + ".json", "modified", 5, 2, "@@ ...")))));

        // 라벨 JSON 두 SHA 시점:
        //  - id=1: 좌표 변경 → MODIFIED
        //  - id=2: from 에만 존재 → REMOVED
        //  - id=3: to 에만 존재 → ADDED
        String fromJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[15.0,15.0],[55.0,55.0]]},"
                + "{\"id\":3,\"lblTypeCd\":\"BBOX\",\"label\":\"bike\",\"points\":[[300.0,300.0],[400.0,400.0]]}"
                + "]}";
        when(giteaClient.getContent(anyString(),
                org.mockito.ArgumentMatchers.eq("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"), anyString()))
                .thenReturn(Mono.just(fromJson));
        when(giteaClient.getContent(anyString(),
                org.mockito.ArgumentMatchers.eq("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222"), anyString()))
                .thenReturn(Mono.just(toJson));

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
    @DisplayName("GET_diff_files_파일_단위_내부_API_별도_경로_유지")
    void getDiffFilesEndpointStillReturnsFileMeta() throws Exception {
        seedVersion("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", false);
        seedVersion("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", 2, LsLabelVersion.SAVE_REASON_MANUAL, "1", true);

        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of(
                        new DiffFile("labels/" + srcSn + ".json", "modified", 5, 2, "@@ ...")))));
        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[]}"));

        mockMvc.perform(get("/v1/versions/bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222/diff/files")
                        .param("compareWith", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].change").value("modified"))
                .andExpect(jsonPath("$.data.files[0].additions").value(5));
    }

    // ──────────────────────────────────────────────
    // POST /v1/versions/{commit}/rollback
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST_rollback_REVIEWER_정상_LS_LABEL_VERSION_새_row_INSERT")
    void postRollbackReviewerOk() throws Exception {
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        seedVersion(pastSha, 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", false);
        seedVersion("0000000000000000000000000000000000000000", 2, LsLabelVersion.SAVE_REASON_MANUAL, "1", true);

        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[]}"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "ccccdddd1111222233334444555566667777aaaa", "rollback", "1", Instant.now())));

        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/" + pastSha + "/rollback")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.giteaCmtHash")
                        .value("ccccdddd1111222233334444555566667777aaaa"));

        // ROLLBACK 신규 row + 이전 ACTIVE='Y' row 가 'N' 으로 deactivate
        List<LsLabelVersion> all = labelVersionRepository.findAll();
        assertThat(all).hasSize(3);
        long activeCount = all.stream().filter(v -> "Y".equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
        LsLabelVersion active = all.stream().filter(v -> "Y".equals(v.getActiveYn())).findFirst().orElseThrow();
        assertThat(active.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_ROLLBACK);
        assertThat(active.getGiteaCmtHash()).isEqualTo("ccccdddd1111222233334444555566667777aaaa");
    }

    @Test
    @DisplayName("POST_rollback_배정된_WORKER_본인_프레임_정상_200")
    void postRollbackAssignedWorkerOk() throws Exception {
        // given: WORKER(100) 가 본인에게 배정된 프레임에 대해 롤백 요청
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        seedVersion(pastSha, 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", false);
        seedVersion("0000000000000000000000000000000000000000", 2, LsLabelVersion.SAVE_REASON_MANUAL, "1", true);

        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[]}"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "ddddeeee1111222233334444555566667777aaaa", "rollback", "100", Instant.now())));

        // when
        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/" + pastSha + "/rollback")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.giteaCmtHash")
                        .value("ddddeeee1111222233334444555566667777aaaa"));

        // ROLLBACK 신규 row + 이전 ACTIVE='Y' row 가 'N' 으로 deactivate
        List<LsLabelVersion> all = labelVersionRepository.findAll();
        assertThat(all).hasSize(3);
        long activeCount = all.stream().filter(v -> "Y".equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST_rollback_미배정_WORKER_403")
    void postRollbackUnassignedWorkerForbidden() throws Exception {
        // given: WORKER(101) 는 어떤 프레임에도 배정되지 않음 → accessGuard 차단
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        seedVersion(pastSha, 1, LsLabelVersion.SAVE_REASON_MANUAL, "1", true);

        RollbackRequest req = new RollbackRequest(srcSn);
        // when / then
        mockMvc.perform(post("/v1/versions/" + pastSha + "/rollback")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
