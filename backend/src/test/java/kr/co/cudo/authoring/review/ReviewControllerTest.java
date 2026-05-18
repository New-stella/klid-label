package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;     // user 100
    private String workerNotAssignedToken;  // user 101

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
        authrtRepository.save(LsTaskAssignment.createLabeler(videoId, 100L, 1L));
    }

    private void seedDataStts(String status) {
        LsRawDataStatus stts = LsRawDataStatus.initial(videoId);
        stts.transitionTo(status);
        dataSttsRepository.save(stts);
    }

    // ---------- 권한 / RBAC ----------

    @Test
    @DisplayName("ReviewController_WORKER가_approve_호출시_403")
    void workerCannotApprove() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_IN_REVIEW);
        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ReviewController_REVIEWER는_본인_검수자_미배정_영상도_검수_가능_현재정책")
    void reviewerCanAccessAnyVideoCurrentPolicy() throws Exception {
        // 현재 정책: REVIEWER 는 모든 영상 검수 가능 (본인이 LS_TASK_ASSIGNMENT.REVIEWER 배정 여부 무관).
        seedDataStts(LsRawDataStatus.STTS_PENDING);
        mockMvc.perform(post("/v1/reviews/" + videoId + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("IN_REVIEW"));
    }

    // ---------- 핵심 워크플로우 ----------

    @Test
    @DisplayName("ReviewController_승인시_LS_RAW_DATA_STATUS_APPROVED")
    void approveTransitionsToApproved() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_IN_REVIEW);

        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("APPROVED"));

        LsRawDataStatus after = dataSttsRepository.findById(videoId).orElseThrow();
        assertThat(after.getDataSttsCd()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("ReviewController_반려시_LS_DATA_ISSUE_생성_+_상태_REJECTED_+_DATA_STTS_CD_업데이트")
    void rejectCreatesIssueAndTransitions() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_IN_REVIEW);

        RejectRequest req = new RejectRequest("바운딩박스 좌표가 부정확합니다.");
        mockMvc.perform(post("/v1/reviews/" + videoId + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("REJECTED"));

        LsRawDataStatus after = dataSttsRepository.findById(videoId).orElseThrow();
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
        seedDataStts(LsRawDataStatus.STTS_IN_REVIEW);

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
        seedDataStts(LsRawDataStatus.STTS_IN_REVIEW);

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
        seedDataStts(LsRawDataStatus.STTS_PENDING);

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
        seedDataStts(LsRawDataStatus.STTS_ASSIGNED);

        mockMvc.perform(post("/v1/reviews/" + videoId + "/submit")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("ReviewController_배정된_WORKER의_submit는_PENDING으로_전이")
    void assignedWorkerSubmitTransitionsToPending() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_ASSIGNED);

        mockMvc.perform(post("/v1/reviews/" + videoId + "/submit")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("PENDING"));

        LsRawDataStatus after = dataSttsRepository.findById(videoId).orElseThrow();
        assertThat(after.getDataSttsCd()).isEqualTo("PENDING");
    }

    // ---------- 목록 조회 (REVIEWER) ----------

    @Test
    @DisplayName("ReviewController_REVIEWER_GET_reviews_status_PENDING_필터_페이징_응답")
    void reviewerListsPendingReviews() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_PENDING);

        mockMvc.perform(get("/v1/reviews")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].dataSttsCd").value("PENDING"));
    }

    @Test
    @DisplayName("ReviewController_WORKER가_GET_reviews_호출시_403")
    void workerForbiddenOnReviewList() throws Exception {
        mockMvc.perform(get("/v1/reviews")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ReviewController_목록_응답에_cctvName_workerName_labelCount_가_채워진다")
    void listEnrichesCctvWorkerLabelCount() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_PENDING);

        // 라벨 시드: 영상에 프레임 1개 + 라벨 3개 → labelCount=3
        LsDataSrc src = LsDataSrc.create(videoId, 0,
                "test-rev-list/" + videoId + "/f_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        for (int i = 0; i < 3; i++) {
            labelRepository.save(LsDataLbl.createAutoBbox(
                    src.getSrcSn(), "person", "[[10,20],[30,40]]", BigDecimal.valueOf(0.9)));
        }

        mockMvc.perform(get("/v1/reviews")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                // workerId/workerName: setup() 에서 user 100 을 LABELER 로 배정 → '작업자100'
                .andExpect(jsonPath("$.data.content[0].workerId").value(100))
                .andExpect(jsonPath("$.data.content[0].workerName").value("작업자100"))
                // labelCount: 라벨 3건
                .andExpect(jsonPath("$.data.content[0].labelCount").value(3))
                // cctvName: test-data-video.sql 의 CCTV-001 매핑 '동대문구 회기로 CCTV'
                .andExpect(jsonPath("$.data.content[0].cctvName").value("동대문구 회기로 CCTV"));
    }

    @Test
    @DisplayName("ReviewController_목록_응답에_eventName과_eventTypeCd가_채워진다")
    void listEnrichesEventName() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_PENDING);

        mockMvc.perform(get("/v1/reviews")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                // setup() 의 LsDataRaw.createFromIngest 가 evntTypeCd="EVT-A" 로 시드
                .andExpect(jsonPath("$.data.content[0].eventName").value("EVT-A"))
                .andExpect(jsonPath("$.data.content[0].eventTypeCd").value("EVT-A"));
    }

    @Test
    @DisplayName("ReviewController_미배정_라벨없는_영상은_workerName_빈값_labelCount_0_폴백")
    void listFallsBackWhenNoAssignmentAndNoLabels() throws Exception {
        // 다른 영상 생성 — 배정/라벨 없음
        LsDataRaw raw2 = LsDataRaw.createFromIngest(
                "CLIP-REV-002", "CCTV-002", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip2.mp4",
                LocalDateTime.now(), 30);
        raw2 = rawRepository.save(raw2);
        Long videoId2 = raw2.getRawSn();

        // 두 영상 모두 PENDING
        seedDataStts(LsRawDataStatus.STTS_PENDING);
        LsRawDataStatus stts2 = LsRawDataStatus.initial(videoId2);
        stts2.transitionTo(LsRawDataStatus.STTS_PENDING);
        dataSttsRepository.save(stts2);

        mockMvc.perform(get("/v1/reviews")
                        .param("status", "PENDING")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                // 미배정 영상은 workerName="" (DTO 폴백) 이며 labelCount=0 이다.
                // content 순서는 보장되지 않으므로 둘 중 어느 인덱스에도 0 이 존재해야 한다.
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId2 + ")].workerName")
                        .value(org.hamcrest.Matchers.contains("")))
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId2 + ")].labelCount")
                        .value(org.hamcrest.Matchers.contains(0)))
                // cctvName: CCTV-002 매핑 '강남구 테헤란로 CCTV'
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId2 + ")].cctvName")
                        .value(org.hamcrest.Matchers.contains("강남구 테헤란로 CCTV")));
    }

    // ---------- 단건 응답 enrich (cctvName/workerName/labelCount) ----------

    @Test
    @DisplayName("ReviewController_단건_상세_응답에_cctvName_workerName_labelCount_가_채워진다")
    void getDetailEnrichesCctvWorkerLabelCount() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_PENDING);

        // 라벨 시드 — labelCount=2 검증용
        LsDataSrc src = LsDataSrc.create(videoId, 0,
                "test-rev-detail/" + videoId + "/f_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        for (int i = 0; i < 2; i++) {
            labelRepository.save(LsDataLbl.createAutoBbox(
                    src.getSrcSn(), "person", "[[10,20],[30,40]]", BigDecimal.valueOf(0.9)));
        }

        mockMvc.perform(get("/v1/reviews/" + videoId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workerId").value(100))
                .andExpect(jsonPath("$.data.workerName").value("작업자100"))
                .andExpect(jsonPath("$.data.labelCount").value(2))
                .andExpect(jsonPath("$.data.cctvName").value("동대문구 회기로 CCTV"));
    }

    @Test
    @DisplayName("ReviewController_approve_응답에도_enrich_필드가_채워진다")
    void approveResponseAlsoEnriches() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_IN_REVIEW);

        // 라벨 시드 — labelCount=1
        LsDataSrc src = LsDataSrc.create(videoId, 0,
                "test-rev-approve/" + videoId + "/f_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        labelRepository.save(LsDataLbl.createAutoBbox(
                src.getSrcSn(), "person", "[[10,20],[30,40]]", BigDecimal.valueOf(0.9)));

        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value("APPROVED"))
                // approve 는 flush 이후 enrichOne — cctvName/workerName/labelCount 채워짐
                .andExpect(jsonPath("$.data.workerId").value(100))
                .andExpect(jsonPath("$.data.workerName").value("작업자100"))
                .andExpect(jsonPath("$.data.labelCount").value(1))
                .andExpect(jsonPath("$.data.cctvName").value("동대문구 회기로 CCTV"));
    }

    @Test
    @DisplayName("ReviewController_단건_상세_응답에_eventName이_채워진다")
    void getDetailEnrichesEventName() throws Exception {
        seedDataStts(LsRawDataStatus.STTS_PENDING);

        mockMvc.perform(get("/v1/reviews/" + videoId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // setup() 의 LsDataRaw.createFromIngest 가 evntTypeCd="EVT-A" 로 시드
                .andExpect(jsonPath("$.data.eventName").value("EVT-A"))
                .andExpect(jsonPath("$.data.eventTypeCd").value("EVT-A"));
    }

    @Test
    @DisplayName("ReviewController_단건_미배정_라벨없는_영상은_workerName_빈값_labelCount_0_폴백")
    void getDetailFallsBackWhenNoAssignmentAndNoLabels() throws Exception {
        // 배정/라벨 없는 별도 영상
        LsDataRaw raw3 = LsDataRaw.createFromIngest(
                "CLIP-REV-003", "CCTV-002", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip3.mp4",
                LocalDateTime.now(), 30);
        raw3 = rawRepository.save(raw3);
        Long videoId3 = raw3.getRawSn();

        LsRawDataStatus stts3 = LsRawDataStatus.initial(videoId3);
        stts3.transitionTo(LsRawDataStatus.STTS_PENDING);
        dataSttsRepository.save(stts3);

        mockMvc.perform(get("/v1/reviews/" + videoId3)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 미배정 → workerId=null (JSON null), workerName="" (DTO 폴백)
                .andExpect(jsonPath("$.data.workerId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.workerName").value(""))
                .andExpect(jsonPath("$.data.labelCount").value(0))
                .andExpect(jsonPath("$.data.cctvName").value("강남구 테헤란로 CCTV"));
    }
}
