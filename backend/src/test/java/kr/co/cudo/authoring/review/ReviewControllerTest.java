package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtDataSttsRepository;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsPjtUserAuthrtRepository authrtRepository;
    @Autowired private LsPjtDataSttsRepository dataSttsRepository;
    @Autowired private IssueRepository issueRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;     // user 100
    private String workerNotAssignedToken;  // user 101

    private static final Long PJT_ID = 10L;
    private Long videoId;

    @BeforeEach
    void setup() {
        reviewerToken           = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken     = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken  = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        // 영상 시드
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-REV-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        videoId = raw.getRawSn();

        // 작업자 100 만 배정
        authrtRepository.save(LsPjtUserAuthrt.createLabeler(PJT_ID, videoId, 100L, 1L));
    }

    private void seedDataStts(String status) {
        LsPjtDataStts stts = LsPjtDataStts.initial(PJT_ID, videoId);
        stts.transitionTo(status);
        dataSttsRepository.save(stts);
    }

    // ---------- 권한 / RBAC ----------

    @Test
    @DisplayName("ReviewController_WORKER가_approve_호출시_403")
    void workerCannotApprove() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_IN_REVIEW);
        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ReviewController_REVIEWER는_본인_검수자_미배정_영상도_검수_가능_현재정책")
    void reviewerCanAccessAnyVideoCurrentPolicy() throws Exception {
        // 현재 정책: REVIEWER 는 모든 영상 검수 가능 (본인이 LS_PJT_USER_AUTHRT.REVIEWER 배정 여부 무관).
        seedDataStts(LsPjtDataStts.STTS_PENDING);
        mockMvc.perform(post("/v1/reviews/" + videoId + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("IN_REVIEW"));
    }

    // ---------- 핵심 워크플로우 ----------

    @Test
    @DisplayName("ReviewController_승인시_LS_PJT_DATA_STTS_APPROVED")
    void approveTransitionsToApproved() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_IN_REVIEW);

        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("APPROVED"));

        LsPjtDataStts after = dataSttsRepository.findById(LsPjtDataStts.Pk.of(PJT_ID, videoId)).orElseThrow();
        assertThat(after.getDataSttsCd()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("ReviewController_반려시_LS_DATA_ISSUE_생성_+_상태_REJECTED_+_DATA_STTS_CD_업데이트")
    void rejectCreatesIssueAndTransitions() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_IN_REVIEW);

        RejectRequest req = new RejectRequest("바운딩박스 좌표가 부정확합니다.");
        mockMvc.perform(post("/v1/reviews/" + videoId + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("REJECTED"));

        LsPjtDataStts after = dataSttsRepository.findById(LsPjtDataStts.Pk.of(PJT_ID, videoId)).orElseThrow();
        assertThat(after.getDataSttsCd()).isEqualTo("REJECTED");

        List<LsDataIssue> issues = issueRepository.findByVideoIdOrderByRegisteredAtDesc(videoId);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getIssueReason()).isEqualTo("바운딩박스 좌표가 부정확합니다.");
        assertThat(issues.get(0).getReportedUserNo()).isEqualTo("1");
        assertThat(issues.get(0).getUpDataIssueSn()).isNull();
    }

    @Test
    @DisplayName("ReviewController_중복_승인_시도시_409_CONFLICT_낙관적_잠금_또는_상태")
    void duplicateApproveReturnsConflict() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_IN_REVIEW);

        // 1차 승인 — 성공 → APPROVED
        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 2차 승인 — APPROVED 는 최종 상태 → CONFLICT
        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("ReviewController_반려_사유_누락시_INVALID_INPUT_400")
    void rejectWithoutReasonReturns400() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_IN_REVIEW);

        // reason = "" (NotBlank 위반)
        RejectRequest req = new RejectRequest("");
        mockMvc.perform(post("/v1/reviews/" + videoId + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("ReviewController_PENDING에서_APPROVED_직접_전이는_불가_INVALID_INPUT")
    void pendingToApprovedDirectlyForbidden() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_PENDING);

        // PENDING → APPROVED 직접 시도 (IN_REVIEW 거쳐야 함) → INVALID_INPUT
        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ---------- submit (WORKER 전용) ----------

    @Test
    @DisplayName("ReviewController_미배정_WORKER가_submit_시도시_403_IDOR")
    void notAssignedWorkerSubmitForbidden() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_ASSIGNED);

        mockMvc.perform(post("/v1/reviews/" + videoId + "/submit")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("ReviewController_배정된_WORKER의_submit는_PENDING으로_전이")
    void assignedWorkerSubmitTransitionsToPending() throws Exception {
        seedDataStts(LsPjtDataStts.STTS_ASSIGNED);

        mockMvc.perform(post("/v1/reviews/" + videoId + "/submit")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("PENDING"));

        LsPjtDataStts after = dataSttsRepository.findById(LsPjtDataStts.Pk.of(PJT_ID, videoId)).orElseThrow();
        assertThat(after.getDataSttsCd()).isEqualTo("PENDING");
    }
}
