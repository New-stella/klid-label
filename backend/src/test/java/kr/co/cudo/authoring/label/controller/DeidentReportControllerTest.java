package kr.co.cudo.authoring.label.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.label.dto.DeidentReportRequest;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired private LsDeidentProcLogRepository procLogRepository;

    @TempDir Path tempDir;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;     // userNo 100 — assigned
    private String workerNotAssignedToken;  // userNo 101 — NOT assigned

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        // LS_DEIDENT_REPORT 는 @Sql 정리 대상이 아니고 LS_DATA_RAW 에 FK 도 없어 테스트 간 누적된다.
        // 신고 목록(GET /v1/deident-reports)은 전역 조회라 누적분이 그대로 보여 <b>메서드 실행 순서에
        // 따라</b> 건수 단언이 깨진다(신규 케이스 추가만으로 기존 테스트가 실패하던 원인). 순서 의존을
        // 없애기 위해 매 테스트 시작 시 비운다.
        reportRepository.deleteAll();

        reviewerToken          = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-DR-001", "CCTV-DR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        // DEV_FIX(L-2) — 비식별 완료('Y') 상태가 신고 가능 상태다. createFromIngest 기본값 'N'(비식별
        //   미수행)은 마킹·라벨링 화면에 노출조차 되지 않아 신고가 발생할 수 없는 비현실적 픽스처였고,
        //   프로덕션은 이제 412 로 거부한다(전용 케이스는 videoReportBeforeDeident412).
        raw.markDeidentified("Y");
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        srcSn = srcRepository.save(src).getSrcSn();

        // 작업자 100 만 LABELER 배정 (101 미배정)
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));

        // resolve 검증 게이트(CWE-359) 통과 픽스처 — 외부 수동 비식별 산출물이 실재하는 상태를 재현한다:
        // 실제 재생 가능한 최소 mp4(1,546B) + SUCCEEDED procLog(DE_IDNTF_FILE_PATH_NM 기록).
        // 판정이 DeidentArtifactIntegrity(정규파일 + 크기 하한 + 컨테이너 시그니처)로 단일화되어
        // 구 더미(3바이트)는 통과하지 않는다.
        Path deidFile = TestVideoFixtures.writeTinyMp4(tempDir.resolve("deid-" + rawSn + ".mp4"));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, "req-" + rawSn, "/var/raw/clip.mp4", "system");
        procLog.succeed(deidFile.toString());
        procLogRepository.save(procLog);
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
    // B-ISSUE-28 — POST /v1/videos/{rawSn}/deident-report (마킹 단계 신고)
    // ============================================================

    @Test
    @DisplayName("마킹단계_rawSn_신고_WORKER_본인배정_201_+_OPEN_저장_+_작업락_+_DE_IDNTF_F")
    void videoReportWorkerAssigned201() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("마킹 중 번호판 노출 발견");

        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNumber());

        List<LsDeidentReport> reports = reportRepository.findAllByDataRawSnAndReportSttsCd(
                rawSn, LsDeidentReport.REPORT_OPEN);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getRsn()).isEqualTo("마킹 중 번호판 노출 발견");
        assertThat(rawRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("F");
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isTrue();
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_REVIEWER도_201")
    void videoReportReviewer201() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("검수자 직접 신고");

        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_WORKER_타인_영상_403")
    void videoReportNotAssignedForbidden403() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        assertThat(reportRepository.findAllByDataRawSnOrderByReportDtDesc(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_파생영상은_412_PRECONDITION_FAILED")
    void videoReportDerivative412() throws Exception {
        // given — 원본에서 파생된 증강 영상(ORGNL_RAW_SN != null). 재비식별 수단이 없어 접수 불가.
        LsDataRaw parent = rawRepository.findById(rawSn).orElseThrow();
        LsDataRaw derivative = rawRepository.save(
                LsDataRaw.createFromAugment(parent, "/var/deid/aug.mp4", "WINTER"));
        authrtRepository.save(LsTaskAssignment.createLabeler(derivative.getRawSn(), 100L, 1L));
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/videos/" + derivative.getRawSn() + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("PRECONDITION_FAILED"));

        // 신고 행·작업락이 생기지 않는다(부모 rawSn 도 안내에 싣지 않는다).
        assertThat(reportRepository.findAllByDataRawSnOrderByReportDtDesc(derivative.getRawSn())).isEmpty();
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", derivative.getRawSn(), "LOCKED")).isFalse();
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_비식별_미수행_영상은_412_PRECONDITION_FAILED")
    void videoReportBeforeDeident412() throws Exception {
        // given — DEV_FIX(L-2). 비식별 미실행(PENDING, DE_IDNTF_YN='N') 영상. rawSn 진입점은 프레임이
        //   없어도 도달하므로 이 상태에 직접 닿는다. 접수하면 'N'→'F' 로 hasDeidentArtifact() 가
        //   거짓으로 true 가 되고, 재비식별한 적 없는 영상에 작업락이 고착된다.
        LsDataRaw pending = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-DR-PENDING", "CCTV-DR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/pending.mp4",
                LocalDateTime.now(), 30));
        authrtRepository.save(LsTaskAssignment.createLabeler(pending.getRawSn(), 100L, 1L));
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/videos/" + pending.getRawSn() + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("PRECONDITION_FAILED"));

        // then — 신고 행·작업락 없음 + 'N' 유지(판정 원천이 거짓이 되지 않는다).
        assertThat(reportRepository.findAllByDataRawSnOrderByReportDtDesc(pending.getRawSn())).isEmpty();
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", pending.getRawSn(), "LOCKED")).isFalse();
        assertThat(rawRepository.findById(pending.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_이미_잠금이면_409")
    void videoReportAlreadyLocked409() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("1차 사유");
        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeidentReportRequest("2차 사유"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_존재하지_않는_영상_404")
    void videoReportUnknownVideo404() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("사유");

        mockMvc.perform(post("/v1/videos/99999999/deident-report")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_사유_누락_400")
    void videoReportMissingReason400() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_인증_없음_401")
    void videoReportUnauthenticated401() throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("사유");

        mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고건도_resolve로_해소된다")
    void videoReportResolvable() throws Exception {
        String json = mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeidentReportRequest("마킹 중 발견"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long rprtSn = objectMapper.readTree(json).get("data").asLong();

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        assertThat(reportRepository.findById(rprtSn).orElseThrow().getReportSttsCd())
                .isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isFalse();
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

    // ============================================================
    // G-1 — GET /v1/deident-reports (REVIEWER 신고 관리 목록)
    // ============================================================

    @Test
    @DisplayName("신고_목록_REVIEWER_조회_성공_기본_OPEN")
    void listReportsReviewerDefaultOpen() throws Exception {
        openReport(workerAssignedToken); // OPEN 신고 1건

        mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.content[0].reason").value("얼굴 미블러"));
    }

    @Test
    @DisplayName("신고_목록_WORKER_403")
    void listReportsWorkerForbidden() throws Exception {
        mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("신고_목록_status_RESOLVED_필터")
    void listReportsResolvedFilter() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);
        // resolve → RESOLVED 전이
        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // OPEN 필터 → 0건
        mockMvc.perform(get("/v1/deident-reports?status=OPEN")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));

        // RESOLVED 필터 → 1건
        mockMvc.perform(get("/v1/deident-reports?status=RESOLVED")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("RESOLVED"));
    }

    @Test
    @DisplayName("신고_목록_status_allowlist_밖_400")
    void listReportsInvalidStatus400() throws Exception {
        mockMvc.perform(get("/v1/deident-reports?status=DROP")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고_목록_status_악성입력_@Pattern_400")
    void listReportsMaliciousStatus400() throws Exception {
        // given: allowlist 밖 + 악성 형태 입력 → 컨트롤러 @Pattern 단에서 400 (서비스 도달 전 차단)
        mockMvc.perform(get("/v1/deident-reports")
                        .param("status", "OPEN'; DROP TABLE LS_DEIDENT_REPORT--")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("신고_목록_인증_없음_401")
    void listReportsUnauthenticated401() throws Exception {
        mockMvc.perform(get("/v1/deident-reports"))
                .andExpect(status().isUnauthorized());
    }
}
