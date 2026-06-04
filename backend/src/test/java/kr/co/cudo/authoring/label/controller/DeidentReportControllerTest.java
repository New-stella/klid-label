package kr.co.cudo.authoring.label.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.dto.DeidentReportRequest;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
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

/**
 * Phase 3 — POST /v1/labels/{srcSn}/deident-report E2E (MockMvc 통합).
 *
 * <p>Phase 3 정책:
 * <ul>
 *   <li>신고 row INSERT → LS_DEIDENT_REPORT (REPORT_STTS_CD='OPEN').</li>
 *   <li>잠금 INSERT → LS_AUTH_WORK_LOCK (LOCK_TARGET_CD='RAW', LOCK_STTS_CD='LOCKED').</li>
 *   <li>영상 DE_IDNTF_YN='F' 마킹.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DeidentReportControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsDeidentReportRepository reportRepository;
    @Autowired private LsAuthWorkLockRepository workLockRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;     // userNo 100 — assigned
    private String workerNotAssignedToken;  // userNo 101 — NOT assigned

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        reviewerToken          = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-DR-001", "CCTV-DR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        srcSn = srcRepository.save(src).getSrcSn();

        // 작업자 100 만 LABELER 배정 (101 미배정)
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    @Test
    @DisplayName("WORKER_본인_배정_영상_신고_201_+_REPORT_STTS_OPEN_저장_+_LS_AUTH_WORK_LOCK_LOCKED_+_DE_IDNTF_F")
    void workerAssignedReports201() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNumber());

        // 신고 row 1건 — REPORT_STTS_CD='OPEN'
        List<LsDeidentReport> reports = reportRepository.findAllByDataRawSnAndReportSttsCd(
                rawSn, LsDeidentReport.REPORT_OPEN);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getRsn()).isEqualTo("얼굴 미블러");
        // 영상 DE_IDNTF_YN='F'
        LsDataRaw reloaded = rawRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getDeIdntfYn()).isEqualTo("F");
        // LS_AUTH_WORK_LOCK 에 LOCKED row 존재
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isTrue();
    }

    @Test
    @DisplayName("WORKER_타인_영상_신고시_403_FORBIDDEN")
    void workerNotAssignedForbidden() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        assertThat(reportRepository.findAllByDataRawSnOrderByReportDtDesc(rawSn)).isEmpty();
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isFalse();
    }

    @Test
    @DisplayName("REVIEWER도_신고_가능_201")
    void reviewerCanReport201() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("관리자 직접 신고");

        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("이미_잠금_영상_재신고시_409_CONFLICT")
    void alreadyLockedConflict409() throws Exception {
        // 1차 신고 → 잠김
        DeidentReportRequest req = new DeidentReportRequest("1차 사유");
        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // 2차 신고 → 409
        DeidentReportRequest req2 = new DeidentReportRequest("2차 사유");
        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("사유_누락_400_INVALID_INPUT")
    void missingReasonBadRequest() throws Exception {
        // reason 빈 문자열
        String body = "{\"reason\":\"\"}";
        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("사유_1000자_초과_400")
    void reasonTooLongBadRequest() throws Exception {
        String tooLong = "X".repeat(1001);
        DeidentReportRequest req = new DeidentReportRequest(tooLong);

        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증_없음_401")
    void unauthenticated401() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("사유");

        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // R1 v1.14 — POST /v1/deident-reports/{rprtSn}/resolve
    // ============================================================

    /** 신고 1건 등록 후 RPRT_SN 반환 (resolve 테스트 픽스처). */
    private Long openReport(String token) throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");
        String json = mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("data").asLong();
    }

    @Test
    @DisplayName("수동_비식별화_완료_resolve_200_+_RESOLVED_전이_+_LOCK_해제")
    void resolveManually200() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        LsDeidentReport reloaded = reportRepository.findById(rprtSn).orElseThrow();
        assertThat(reloaded.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(reloaded.getResolvedDt()).isNotNull();
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isFalse();
    }

    @Test
    @DisplayName("OPEN이_아닌_신고_재_resolve_409")
    void resolveNonOpenConflict409() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);
        // 1차 resolve → RESOLVED
        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk());
        // 2차 resolve → 409
        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("타인_배정_영상_신고_WORKER_resolve_403")
    void resolveByNotAssignedWorkerForbidden403() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("REVIEWER_모든_신고_resolve_200")
    void reviewerResolveAny200() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("resolve_인증_없음_401")
    void resolveUnauthenticated401() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve"))
                .andExpect(status().isUnauthorized());
    }
}
