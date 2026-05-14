package kr.co.cudo.authoring.label.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.dto.DeidentReportRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — POST /v1/labels/{srcSn}/deident-report E2E (MockMvc 통합).
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
    @Autowired private LsPjtUserAuthrtRepository authrtRepository;
    @Autowired private LsDeidentReportRepository reportRepository;

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
        authrtRepository.save(LsPjtUserAuthrt.createLabeler(10L, rawSn, 100L, 1L));
    }

    @Test
    @DisplayName("WORKER_본인_배정_영상_신고_201_+_신고_저장_+_LOCK_+_DE_IDNTF_F")
    void workerAssignedReports201() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNumber());

        assertThat(reportRepository.findAllByRawSnOrderByRprtDtDesc(rawSn)).hasSize(1);
        LsDataRaw reloaded = rawRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.isLockedForRedeident()).isTrue();
        assertThat(reloaded.getDeIdntfYn()).isEqualTo("F");
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

        assertThat(reportRepository.findAllByRawSnOrderByRprtDtDesc(rawSn)).isEmpty();
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
}
