package kr.co.cudo.authoring.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffFile;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import kr.co.cudo.authoring.version.dto.RollbackRequest;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired private LsPjtUserAuthrtRepository authrtRepository;
    @Autowired private LsDataLblHstryRepository historyRepository;

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
        historyRepository.deleteAll();

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
        authrtRepository.save(LsPjtUserAuthrt.createLabeler(10L, rawSn, 100L, 1L));
    }

    @Test
    @DisplayName("GET_versions_정상_조회")
    void getVersionsReturnsList() throws Exception {
        // 사전 시드 — history 2건
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "1", "{\"v\":1}"));
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", "100", "{\"v\":2}"));

        mockMvc.perform(get("/v1/videos/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("GET_versions_미배정_WORKER_403")
    void getVersionsForbiddenForUnassignedWorker() throws Exception {
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "1", "{}"));

        mockMvc.perform(get("/v1/videos/" + srcSn + "/versions")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET_diff_변경된_라벨만_반환")
    void getDiffReturnsChangedFiles() throws Exception {
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "1", "{}"));
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222", "1", "{}"));

        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of(
                        new DiffFile("labels/" + srcSn + ".json", "modified", 5, 2, "@@ ...")))));

        mockMvc.perform(get("/v1/versions/bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222/diff")
                        .param("compareWith", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].change").value("modified"))
                .andExpect(jsonPath("$.data.files[0].additions").value(5));
    }

    @Test
    @DisplayName("POST_rollback_REVIEWER_정상_동작")
    void postRollbackReviewerOk() throws Exception {
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        historyRepository.save(LsDataLblHstry.create(srcSn, pastSha, "1", "{\"items\":[]}"));

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
    }

    @Test
    @DisplayName("POST_rollback_WORKER_403")
    void postRollbackWorkerForbidden() throws Exception {
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        historyRepository.save(LsDataLblHstry.create(srcSn, pastSha, "1", "{}"));

        RollbackRequest req = new RollbackRequest(srcSn);
        mockMvc.perform(post("/v1/versions/" + pastSha + "/rollback")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
