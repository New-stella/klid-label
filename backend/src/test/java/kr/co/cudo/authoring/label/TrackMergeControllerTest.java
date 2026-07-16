package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — 트랙 병합 컨트롤러 통합 테스트 (MockMvc).
 *
 * <p>보안 매트릭스(포털 차단 회귀 방지 · IDOR):
 * <ul>
 *   <li>미인증 → 401.</li>
 *   <li>포털 채널 토큰(PORTAL_USER + CHANNEL_PORTAL) → 403 (역할 + 채널 이중 차단, ADR-013).</li>
 *   <li>타인 배정 WORKER → 403 (CWE-639 IDOR).</li>
 *   <li>from==to → 400 (인가 통과 후 입력 검증).</li>
 *   <li>본인 배정 WORKER / REVIEWER → 200.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class TrackMergeControllerTest {

    private static final String T_FROM = "t-from";
    private static final String T_TO = "t-to";

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
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

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-TM-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // 2 프레임 + 겹치지 않는 트랙 라벨(t-from frame0, t-to frame1) → 병합 성공(200) 경로.
        Long srcSn0 = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        Long srcSn1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();
        lblRepository.save(LsDataLbl.createAutoBbox(srcSn0, null, "person", "[[1,1],[2,2]]",
                BigDecimal.ZERO, T_FROM));
        lblRepository.save(LsDataLbl.createAutoBbox(srcSn1, null, "person", "[[3,3],[4,4]]",
                BigDecimal.ZERO, T_TO));

        // 100L WORKER 만 LABELER 배정.
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private String body(String from, String to) {
        return "{\"fromTrackId\":\"" + from + "\",\"toTrackId\":\"" + to + "\"}";
    }

    @Test
    @DisplayName("트랙병합_미인증_401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(T_FROM, T_TO)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("트랙병합_포털_채널_토큰_403")
    void portalChannelForbidden() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/merge")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(T_FROM, T_TO)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("트랙병합_타인배정_WORKER_403")
    void otherAssignmentForbidden() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/merge")
                        .header("Authorization", "Bearer " + workerOtherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(T_FROM, T_TO)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("트랙병합_from_to_동일_400")
    void fromEqualsToBadRequest() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/merge")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(T_FROM, T_FROM)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("트랙병합_REVIEWER_200")
    void reviewerOk() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/merge")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(T_FROM, T_TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reassignedLabelCount").value(1));
    }

    @Test
    @DisplayName("트랙병합_본인배정_WORKER_200")
    void workerAssignedOk() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/tracks/merge")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(T_FROM, T_TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toTrackId").value(T_TO));
    }
}
