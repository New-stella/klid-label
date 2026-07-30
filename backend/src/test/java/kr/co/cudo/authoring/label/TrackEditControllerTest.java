package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3(트랙 관리 확장) — 트랙 삭제(R4)/split(R5) 컨트롤러 통합 테스트 (MockMvc).
 *
 * <p>보안 매트릭스(포털 차단 회귀 방지 · IDOR · 입력 검증):
 * 미인증 401 / 포털 채널 403 / 타인 배정 403 / 음수 프레임 400 / 본인 배정·REVIEWER 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class TrackEditControllerTest {

    private static final String TRACK = "5";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager controlTxManager;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerOtherToken;
    private String portalToken;

    private Long rawSn;

    @BeforeEach
    void setup() {
        reviewerToken       = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        workerAssignedToken = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        workerOtherToken    = JwtTestSupport.token(secret, "101", "WORKER",      "INTERNAL", issuer, 60);
        portalToken         = JwtTestSupport.token(secret, "200", "PORTAL_USER", "PORTAL",   issuer, 60);

        // ★ 부모 영상만 <b>커밋</b> 시드 — 트랙 삭제/split 이 작업락을 REQUIRES_NEW 로 쓰므로(LS_AUTH_WORK_LOCK)
        //   ambient 미커밋 영상은 그 트랜잭션에서 보이지 않아 V146 FK 검증에 걸린다.
        rawSn = RawVideoFixture.newRawCommitted(controlTxManager, jdbcTemplate);

        // 3 프레임(frameNo 0,1,2) 모두 트랙 "5" 라벨.
        Long s0 = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        Long s1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();
        Long s2 = srcRepository.save(LsDataSrc.create(rawSn, 2, "2.jpg", LocalDateTime.now())).getSrcSn();
        lblRepository.save(LsDataLbl.createAutoBbox(s0, null, "person", "[[1,1],[2,2]]", BigDecimal.ZERO, TRACK));
        lblRepository.save(LsDataLbl.createAutoBbox(s1, null, "person", "[[3,3],[4,4]]", BigDecimal.ZERO, TRACK));
        lblRepository.save(LsDataLbl.createAutoBbox(s2, null, "person", "[[5,5],[6,6]]", BigDecimal.ZERO, TRACK));

        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    @AfterEach
    void tearDown() {
        // ambient(롤백) 트랜잭션을 먼저 닫아야 미커밋 자식의 FK 키공유 잠금이 풀려 부모 삭제가 가능하다.
        RawVideoFixture.endAmbientTransaction();
        RawVideoFixture.deleteRaws(jdbcTemplate, rawSn);
    }

    // ---------- R4 삭제 ----------

    @Test
    @DisplayName("트랙삭제_미인증_401")
    void deleteUnauthenticated() throws Exception {
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/tracks/" + TRACK).param("fromFrameNo", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("트랙삭제_포털_채널_토큰_403")
    void deletePortalForbidden() throws Exception {
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/tracks/" + TRACK).param("fromFrameNo", "1")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("트랙삭제_타인배정_WORKER_403")
    void deleteOtherForbidden() throws Exception {
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/tracks/" + TRACK).param("fromFrameNo", "1")
                        .header("Authorization", "Bearer " + workerOtherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("트랙삭제_음수_fromFrameNo_400")
    void deleteNegativeFrame() throws Exception {
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/tracks/" + TRACK).param("fromFrameNo", "-1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("트랙삭제_REVIEWER_200_지정프레임이후_삭제")
    void deleteReviewerOk() throws Exception {
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/tracks/" + TRACK).param("fromFrameNo", "1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(2))
                .andExpect(jsonPath("$.data.trackId").value(TRACK));
    }

    // ---------- R5 split ----------

    @Test
    @DisplayName("트랙split_미인증_401")
    void splitUnauthenticated() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/" + TRACK + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atFrameNo\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("트랙split_포털_채널_토큰_403")
    void splitPortalForbidden() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/" + TRACK + "/split")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atFrameNo\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("트랙split_음수_atFrameNo_400")
    void splitNegativeFrame() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/" + TRACK + "/split")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atFrameNo\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("트랙split_본인배정_WORKER_200_새trackId_유니크")
    void splitWorkerOk() throws Exception {
        // distinct trackIds = ["5"] → newTrackId = max(5)+1 = "6". atFrameNo=1 → 프레임 1,2 이동(2건).
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/" + TRACK + "/split")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atFrameNo\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newTrackId").value("6"))
                .andExpect(jsonPath("$.data.movedCount").value(2))
                .andExpect(jsonPath("$.data.originalTrackId").value(TRACK));
    }
}
