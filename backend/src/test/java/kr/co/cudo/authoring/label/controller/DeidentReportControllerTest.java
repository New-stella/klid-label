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
import org.junit.jupiter.api.AfterEach;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    /** R3 — 후보 열거 대상 디렉터리({base}/videos/{rawSn}/) 계산용. */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    private String reviewerToken;
    private String workerAssignedToken;     // userNo 100 — assigned
    private String workerNotAssignedToken;  // userNo 101 — NOT assigned

    private Long srcSn;
    private Long rawSn;
    /** setup() 이 만든 비식별 산출물 — {@link #simulateExternalRedeident(Long)} 가 mtime 을 옮긴다. */
    private Path deidArtifact;
    /**
     * R3 후보 열거 대상 디렉터리({@code {deid_base}/videos/{rawSn}/}) — <b>이 클래스가 소유한다</b>.
     *
     * <p>{@link #purgeCandidateDir()} 참조.
     */
    private Path deidVideoDir;

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
        // V171 — 마킹 단계(rawSn) 신고는 배치 단계가 MARKING_READY(선두 비식별 성공 직후 · 마킹 이전)
        //   일 때만 접수된다. 그 외 상태는 412(전용 케이스는 videoReportAfterMarkingStage412).
        raw.markMarkingReady();
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
        deidArtifact = TestVideoFixtures.writeTinyMp4(tempDir.resolve("deid-" + rawSn + ".mp4"));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, "req-" + rawSn, "/var/raw/clip.mp4", "system");
        procLog.succeed(deidArtifact.toString());
        procLogRepository.save(procLog);

        // R3 후보 열거 디렉터리를 이 테스트가 소유한다(아래 purgeCandidateDir 참조).
        deidVideoDir = Path.of(storageDeidPath).toAbsolutePath().normalize()
                .resolve("videos").resolve(String.valueOf(rawSn));
        // ★ 지난 실행의 잔재를 <b>결정적으로 재현</b>한 뒤 비운다 — 이 두 줄이 세트다.
        //   잔재가 실제로 있는 환경에서만 결함이 드러나면 가드가 조용히 가드를 멈춘다(아래 절 참조).
        plantStaleForeignArtifact();
        purgeCandidateDir();
    }

    /**
     * 지난 실행에서 <b>다른 테스트</b>가 같은 {@code rawSn} 디렉터리에 남긴 비식별 영상을 재현한다.
     *
     * <p>왜 만들자마자 지우는가 — {@link #purgeCandidateDir()} 를 제거하면 이 클래스의 후보 건수 단언이
     * <b>실행 환경과 무관하게 즉시</b> 깨지게 만들기 위해서다. 잔재가 실재하는 환경에서만 깨지도록 두면
     * 그 결함은 이번처럼 <b>전체 회귀에서만, 그것도 시퀀스 소비 위치가 맞아떨어질 때만</b> 드러난다
     * (그래서 오래 숨어 있었다). 파일명은 실제 잔재 생산자
     * ({@code VideoStreamAssignmentAuthorizationTest} 의 {@code STREAM-AUTHZ-*.mp4})와 같은 형태로 둔다.
     */
    private void plantStaleForeignArtifact() {
        try {
            Files.createDirectories(deidVideoDir);
            TestVideoFixtures.writeTinyMp4(deidVideoDir.resolve("STREAM-AUTHZ-stale-run.mp4"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("잔재 재현 픽스처를 만들지 못했다", e);
        }
    }

    /**
     * R3 후보 열거 디렉터리({@code {deid_base}/videos/{rawSn}/})를 <b>빈 상태로 만든다</b> —
     * 이 클래스의 후보 <b>건수</b> 단언({@code $.data.length()})이 성립하기 위한 전제다.
     *
     * <h3>왜 필요한가 (실측 사고 — 실행 간 잔재)</h3>
     * <p>{@code authoring.storage.deidentified-path} 기본값은 <b>프로젝트 상대 경로</b>
     * ({@code ./storage/deidentified})라 {@code @TempDir} 과 달리 <b>테스트 실행이 끝나도 남는다</b>.
     * 그런데 이 디렉터리를 가르는 키인 {@code RAW_SN} 은 <b>실행마다 같은 값이 재발급된다</b>:
     * 테스트 PostgreSQL 은 실행마다 새로 뜨고, 픽스처가 명시 {@code RAW_SN} 삽입 후 시퀀스를
     * 고정된 기준값 이후로 전진시키므로({@code RawVideoFixture.advanceIdentityBeyond}) 자동 발급값이
     * 실행마다 같은 구간을 지난다. 따라서 <b>지난 실행에서 다른 테스트</b>가
     * ({@code {deid_base}/videos/{rawSn}/} 에 비식별 영상을 실제로 쓰는 테스트들이 여럿 있다)
     * 남긴 {@code .mp4} 가, 이번 실행에서 <b>같은 번호를 받은 이 테스트</b>의 후보 목록에 섞여 들어온다.
     * <p>그 결과 신규 테스트 클래스 추가처럼 <b>시퀀스 소비 위치만 달라져도</b>
     * {@code listDeidentCandidates200} 이 {@code expected 1 / actual 2} 로 깨졌다(실측). 순서·잔재와
     * 무관하게 같은 결과를 내도록, 갓 발급된 {@code rawSn} 의 디렉터리를 비우고 시작한다.
     *
     * <h3>지우는 범위 — 갓 발급된 rawSn 하나뿐</h3>
     * <p>{@code rawSn} 은 바로 위에서 시퀀스가 <b>새로 발급</b>한 값이므로, 그 디렉터리에 남아 있는
     * 파일은 정의상 이번 실행의 산출물이 아니다(살아 있는 행이 소유할 수 없다). 하위 디렉터리는
     * 재귀 삭제하지 않고 <b>바로 아래 정규 파일</b>만 지운다(심링크 추종 금지 — 열거 규약과 동일).
     */
    private void purgeCandidateDir() {
        if (!Files.isDirectory(deidVideoDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(deidVideoDir)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "R3 후보 열거 디렉터리를 비우지 못했다 — 후보 건수 단언의 전제가 깨진다", e);
        }
        try {
            Files.deleteIfExists(deidVideoDir); // 비었을 때만 성공한다(하위 디렉터리가 남으면 그대로 둔다).
        } catch (java.io.IOException ignored) {
            // 비우지 못했더라도 위에서 정규 파일은 제거됐으므로 후보 열거 결과는 비어 있다.
        }
    }

    /**
     * 이 클래스가 공유 저장소에 남긴 산출물을 되돌린다 — <b>다음 실행의 다른 테스트</b>가 같은
     * {@code rawSn} 을 받아 우리 잔재를 후보로 보게 되는 것을 막는다(위 {@link #purgeCandidateDir()}
     * 가 설명하는 오염의 <b>생산자 측</b> 대칭).
     */
    @AfterEach
    void cleanupSharedStorage() {
        if (deidVideoDir != null) {
            purgeCandidateDir();
        }
    }

    /**
     * 외부 솔루션의 <b>수동 재비식별 완료</b>를 재현한다 — 산출물 mtime 을 신고시각 이후로 옮긴다.
     *
     * <p>B-ISSUE-42 이전에는 setup() 이 만든 산출물(=신고보다 mtime 이 <b>이전</b>)로도 resolve 가
     * 통과했는데, 이는 mtime 비교가 클럭스큐를 <b>감산</b> 방향으로 관용했기 때문이다(신고시각−60초).
     * 그 관용이 제거되면서 이 픽스처는 실제 운영 순서(신고 → 외부 재비식별 → resolve)를 그대로
     * 재현해야 한다 — 신고 이전부터 있던 파일은 <b>신고를 유발한 그 산출물</b>이라 통과하면 안 된다.
     *
     * <p>mtime 은 실제 저장된 신고시각 +1초로 둔다(파일시스템 타임스탬프 절삭에 흔들리지 않도록
     * 벽시계 now 가 아니라 신고시각 기준 오프셋을 쓴다).
     */
    private void simulateExternalRedeident(Long rprtSn) throws Exception {
        LocalDateTime reportTime = reportRepository.findById(rprtSn).orElseThrow().getReportDt();
        Files.setLastModifiedTime(deidArtifact, FileTime.from(
                reportTime.plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant()));
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
                LsDataRaw.createFromAugment(parent, "/var/deid/aug.mp4", "WINTER", System.nanoTime()));
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
    @DisplayName("마킹단계_신고는_MARKING_READY_에서만_접수된다 (V171)")
    void videoReportAfterMarkingStage412() throws Exception {
        // given — 마킹이 끝나 배치가 돈 영상(COMPLETED). 마킹 화면에서 도달할 상태가 아니다.
        LsDataRaw done = LsDataRaw.createFromIngest(
                "CLIP-DR-DONE", "CCTV-DR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/done.mp4", LocalDateTime.now(), 30);
        done.markDeidentified("Y");
        done.markCompleted();
        done = rawRepository.save(done);
        authrtRepository.save(LsTaskAssignment.createLabeler(done.getRawSn(), 100L, 1L));
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");

        mockMvc.perform(post("/v1/videos/" + done.getRawSn() + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("PRECONDITION_FAILED"))
                // 상태 오라클 금지(CWE-209) — 배치 단계 코드 원문이 사용자 문구에 새지 않는다.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("COMPLETED"))));

        // then — 부작용 0. 'Y' 유지(신고 접수와 무관).
        assertThat(reportRepository.findAllByDataRawSnOrderByReportDtDesc(done.getRawSn())).isEmpty();
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", done.getRawSn(), "LOCKED")).isFalse();
        assertThat(rawRepository.findById(done.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("라벨링단계_신고는_배치단계와_무관하게_접수된다 (V171 — 마킹 제한이 라벨링에 새지 않는다)")
    void labelReportUnaffectedByBatchStage() throws Exception {
        // given — 배치가 끝난 영상(배치 단계 COMPLETED)에서의 라벨링 화면 신고는 정상 동선이다.
        //   ★ R2 주의: 여기서 검증하는 축은 <배치 단계>이지 <검수 승인>이 아니다. 이 영상에는
        //   LS_RAW_DATA_STATUS 행이 없어 미승인이므로 R2 게이트(승인 영상 412)에 걸리지 않는다.
        //   두 축을 혼동해 "검수 완료 영상도 201" 로 읽지 말 것 — 승인 영상은 412 다.
        LsDataRaw done = LsDataRaw.createFromIngest(
                "CLIP-DR-DONE-LBL", "CCTV-DR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/done-lbl.mp4", LocalDateTime.now(), 30);
        done.markDeidentified("Y");
        done.markCompleted();
        done = rawRepository.save(done);
        Long doneSrcSn = srcRepository.save(
                LsDataSrc.create(done.getRawSn(), 0, 0L, "/var/raw/f0.jpg", LocalDateTime.now())).getSrcSn();
        authrtRepository.save(LsTaskAssignment.createLabeler(done.getRawSn(), 100L, 1L));
        DeidentReportRequest req = new DeidentReportRequest("라벨링 중 얼굴 미블러");

        mockMvc.perform(post("/v1/labels/" + doneSrcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // then — 접수 + 단계 LABELING 기록
        List<LsDeidentReport> reports = reportRepository.findAllByDataRawSnAndReportSttsCd(
                done.getRawSn(), LsDeidentReport.REPORT_OPEN);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getDclrStpCd()).isEqualTo(LsDeidentReport.STAGE_LABELING);
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
        simulateExternalRedeident(rprtSn);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
                .andExpect(status().isOk());

        assertThat(reportRepository.findById(rprtSn).orElseThrow().getReportSttsCd())
                .isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isFalse();
    }

    // ============================================================
    // R1 v1.14 — POST /v1/deident-reports/{rprtSn}/resolve
    // ============================================================

    /**
     * R3 — 해소 요청 바디. 서버는 기본값을 고르지 않으므로 <b>어느 산출물인지 반드시 지정</b>해야 한다.
     *
     * <p>setup() 이 만든 비식별 산출물(= 현재 원장이 가리키는 파일)의 파일명을 보낸다. 이 파일은
     * {@link #simulateExternalRedeident} 로 mtime 이 신고 이후로 옮겨져 "제자리 교체" 조건을 만족한다.
     */
    private String resolveBody() throws Exception {
        return resolveBody(deidArtifact.getFileName().toString());
    }

    private String resolveBody(String fileName) throws Exception {
        return objectMapper.writeValueAsString(
                new kr.co.cudo.authoring.label.dto.DeidentResolveRequest(fileName));
    }

    /** 신고 1건 등록 후 RPRT_SN 반환 (resolve 테스트 픽스처). */
    private Long openReport(String token) throws Exception {
        DeidentReportRequest req = new DeidentReportRequest("얼굴 미블러");
        String json = mockMvc.perform(post("/v1/labels/" + srcSn + "/deident-report")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long rprtSn = objectMapper.readTree(json).get("data").asLong();
        // 신고 이후 외부 수동 재비식별이 완료된 상태를 재현 — resolve 시간조건(mtime > 신고시각) 전제.
        simulateExternalRedeident(rprtSn);
        return rprtSn;
    }

    @Test
    @DisplayName("수동_비식별화_완료_resolve_200_+_RESOLVED_전이_+_LOCK_해제")
    void resolveManually200() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
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
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
                .andExpect(status().isOk());
        // 2차 resolve → 409
        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("타인_배정_영상_신고_WORKER_resolve_403")
    void resolveByNotAssignedWorkerForbidden403() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("REVIEWER_모든_신고_resolve_200")
    void reviewerResolveAny200() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
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
    // R3 — GET /v1/deident-reports/{rprtSn}/deident-candidates
    //      + POST …/resolve 의 산출물 선택(목록 대조 수락)
    // ============================================================

    private String candidatesUrl(Long rprtSn) {
        return "/v1/deident-reports/" + rprtSn + "/deident-candidates";
    }

    @Test
    @DisplayName("R3_후보_목록_조회_200_+_현재_산출물이_current로_표시된다")
    void listDeidentCandidates200() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(get(candidatesUrl(rprtSn))
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName")
                        .value(deidArtifact.getFileName().toString()))
                .andExpect(jsonPath("$.data[0].current").value(true))
                .andExpect(jsonPath("$.data[0].eligible").value(true))
                .andExpect(jsonPath("$.data[0].sizeBytes").isNumber())
                .andExpect(jsonPath("$.data[0].modifiedAt").exists());
    }

    /**
     * ★ 회귀 가드 — <b>지난 실행의 잔재</b>가 후보 목록에 섞여 건수 단언을 깨뜨리지 않는다.
     *
     * <p>{@link #purgeCandidateDir()} 가 설명하는 실측 사고를 <b>결정적으로</b> 재현한다: 공유 저장소
     * ({@code {deid_base}/videos/{rawSn}/})에 다른 테스트가 남긴 것과 같은 형태의 파일을 심고,
     * 픽스처 소유 규약(디렉터리를 비우고 시작)을 적용한 뒤 후보가 <b>원장 산출물 1건</b>뿐임을 단언한다.
     *
     * <p>{@code setup()} 이 매 테스트마다 잔재를 <b>심고 비우므로</b>({@link #plantStaleForeignArtifact()}
     * + {@link #purgeCandidateDir()}) 이 단언은 {@code purgeCandidateDir()} 호출을 제거하면 <b>실행
     * 순서·환경과 무관하게 항상 실패</b>한다 — 원래 결함이 전체 회귀에서만 드러나 오래 숨어 있던 조건을
     * 여기서 상수로 고정한다.
     */
    @Test
    @DisplayName("R3_지난_실행이_남긴_같은_rawSn_잔재는_후보_목록에_섞이지_않는다")
    void listDeidentCandidatesIgnoresStaleArtifactsFromPreviousRun() throws Exception {
        // given — setup() 이 잔재를 심고 비운 상태. 열거 디렉터리에 정규 파일이 남아 있지 않아야 한다.
        try (java.util.stream.Stream<Path> entries = Files.exists(deidVideoDir)
                ? Files.list(deidVideoDir) : java.util.stream.Stream.<Path>empty()) {
            assertThat(entries.filter(Files::isRegularFile))
                    .as("후보 열거 디렉터리는 갓 발급된 rawSn 것이라 비어 있어야 한다(잔재 소유 규약)")
                    .isEmpty();
        }

        Long rprtSn = openReport(workerAssignedToken);

        // then — 잔재는 사라지고 후보는 원장이 가리키는 산출물 1건뿐이다.
        mockMvc.perform(get(candidatesUrl(rprtSn))
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName")
                        .value(deidArtifact.getFileName().toString()));
    }

    /**
     * ★ 내부 저장 경로는 응답에 절대 담기지 않는다(CWE-209).
     *
     * <p>파일명만으로 후보를 식별하는 것이 이 API 의 계약이다 — 디렉터리·마운트 구조가 새면 WORKER 도
     * 볼 수 있는 목록으로 저장소 배치가 노출된다. 응답 <b>본문 전체</b>에 산출물의 상위 디렉터리
     * 문자열이 등장하지 않음을 단언한다(필드 단위 단언은 필드가 추가되면 새기 때문).
     */
    @Test
    @DisplayName("R3_후보_목록_응답에는_내부_저장_경로가_들어있지_않다")
    void candidatesResponseNeverLeaksInternalPath() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        String body = mockMvc.perform(get(candidatesUrl(rprtSn))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(deidArtifact.getParent().toString());
        assertThat(body).doesNotContain(deidArtifact.toString());
        assertThat(body).contains(deidArtifact.getFileName().toString());
    }

    @Test
    @DisplayName("R3_산출물이_없으면_후보_목록은_빈_배열_200이다_에러가_아니다")
    void listDeidentCandidatesEmpty200() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);
        // 외부 비식별을 아직 하지 않은 상태를 재현 — 산출물 파일을 지운다(원장 행은 남는다).
        Files.deleteIfExists(deidArtifact);

        mockMvc.perform(get(candidatesUrl(rprtSn))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("R3_후보_목록_타인_배정_WORKER_403")
    void listDeidentCandidatesForbidden403() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        mockMvc.perform(get(candidatesUrl(rprtSn))
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("R3_후보_목록_없는_신고_404")
    void listDeidentCandidatesNotFound404() throws Exception {
        mockMvc.perform(get(candidatesUrl(99999999L))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("R3_후보_목록_인증_없음_401")
    void listDeidentCandidatesUnauthenticated401() throws Exception {
        mockMvc.perform(get(candidatesUrl(1L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R3_resolve_fileName_누락시_400_이고_신고는_OPEN_유지된다")
    void resolveWithoutFileName400() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);

        // 바디 자체가 없는 경우
        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isBadRequest());
        // fileName 이 공백인 경우
        mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("   ")))
                .andExpect(status().isBadRequest());

        assertThat(reportRepository.findById(rprtSn).orElseThrow().getReportSttsCd())
                .isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isTrue();
    }

    @Test
    @DisplayName("R3_후보_목록에_없는_이름과_경로_순회_시도는_400이고_fail_closed다")
    void resolveWithUnlistedOrTraversalFileName400() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);
        String real = deidArtifact.getFileName().toString();

        for (String attempt : List.of(
                "not-in-the-list.mp4",
                "../" + real,
                "./" + real,
                deidArtifact.toString(),
                "../../etc/passwd")) {
            mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                            .header("Authorization", "Bearer " + workerAssignedToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody(attempt)))
                    .andExpect(status().isBadRequest());
        }

        // fail-closed — 신고 OPEN·작업락·DE_IDNTF_YN='F' 모두 유지된다.
        assertThat(reportRepository.findById(rprtSn).orElseThrow().getReportSttsCd())
                .isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                "RAW", rawSn, "LOCKED")).isTrue();
        assertThat(rawRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("F");
    }

    /**
     * ★ R3 핵심 회귀 가드 — 외부 솔루션이 <b>다른 이름</b>으로 만든 산출물을 골라 해소하면, 이후
     * 조회되는 <b>최신 성공 경로가 그 파일</b>이 된다.
     *
     * <p>이 단언이 없으면 목록 선택이 반쪽이 된다: 해소 이후의 프레임 재추출·영상 스트리밍은 모두
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 을 읽으므로, 원장을 재지정하지 않으면 하류가
     * <b>옛 파일</b>을 계속 쓴다. 그리고 이 상황(다른 이름 산출)이야말로 구 판정에서 해소가
     * <b>영원히 불가능</b>했던 바로 그 케이스다.
     */
    @Test
    @DisplayName("R3_다른_이름의_산출물을_골라_해소하면_최신_성공_경로가_그_파일로_바뀐다")
    void resolveWithDifferentlyNamedArtifactRepointsLatestSuccessPath() throws Exception {
        Long rprtSn = openReport(workerAssignedToken);
        LocalDateTime reportTime = reportRepository.findById(rprtSn).orElseThrow().getReportDt();

        // given — KPST 계약({원본stem}-mask{ext})처럼 <다른 이름>으로 산출된 파일이
        //   비식별 영상 디렉터리({deid_base}/videos/{rawSn}/)에 놓인다.
        Path deidDir = Files.createDirectories(
                Path.of(storageDeidPath).toAbsolutePath().normalize()
                        .resolve("videos").resolve(String.valueOf(rawSn)));
        Path fresh = TestVideoFixtures.writeTinyMp4(deidDir.resolve("clip-mask.mp4"));
        Files.setLastModifiedTime(fresh, FileTime.from(
                reportTime.plusSeconds(5).atZone(ZoneId.systemDefault()).toInstant()));
        try {
            // 구 판정은 원장 경로 1개만 봤다 — 원장이 가리키는 파일은 신고 이전 그대로 두어,
            // "다른 이름 산출물이 없으면 이 신고는 해소 불가"였던 상황을 재현한다.
            Files.setLastModifiedTime(deidArtifact, FileTime.from(
                    reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));

            // and — 후보 목록에 두 파일이 모두 뜨고, 새 산출물만 자격을 갖췄다.
            mockMvc.perform(get(candidatesUrl(rprtSn))
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[?(@.fileName=='clip-mask.mp4')].eligible")
                            .value(org.hamcrest.Matchers.hasItem(true)))
                    .andExpect(jsonPath("$.data[?(@.fileName=='" + deidArtifact.getFileName()
                            + "')].eligible").value(org.hamcrest.Matchers.hasItem(false)));

            // when — 사람이 새 산출물을 고른다.
            mockMvc.perform(post("/v1/deident-reports/" + rprtSn + "/resolve")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody("clip-mask.mp4")))
                    .andExpect(status().isOk());

            // then — 최신 성공 원장이 <새 파일>을 가리킨다(하류가 옛 파일을 쓰지 않는다).
            String latest = procLogRepository.findLatestSuccessByDataRawSn(rawSn)
                    .orElseThrow().getDeIdntfFilePathNm();
            assertThat(Path.of(latest).getFileName()).hasToString("clip-mask.mp4");
            // and — 기존 계약(RESOLVED 전이 · 작업락 해제 · 'Y' 복원)은 그대로다.
            assertThat(reportRepository.findById(rprtSn).orElseThrow().getReportSttsCd())
                    .isEqualTo(LsDeidentReport.REPORT_RESOLVED);
            assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                    "RAW", rawSn, "LOCKED")).isFalse();
            assertThat(rawRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
        } finally {
            // 저장소 base 는 프로젝트 상대 경로(@TempDir 아님)라 테스트가 만든 파일을 직접 치운다.
            Files.deleteIfExists(fresh);
            Files.deleteIfExists(deidDir);
        }
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
    @DisplayName("신고_목록_신고자_표시명이_사용자마스터_이름으로_채워진다")
    void listReportsReporterName() throws Exception {
        openReport(workerAssignedToken); // 신고자 = userNo 100 (test-data.sql: USER_NM '작업자100')

        mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                // 하위호환 — 원값(USER_NO)은 그대로 유지된다.
                .andExpect(jsonPath("$.data.content[0].reporterNo").value(100))
                // 신규 — 화면 '신고자' 컬럼이 쓰는 표시명(LS_ACNT_USER.USER_NM).
                .andExpect(jsonPath("$.data.content[0].reporterName").value("작업자100"));
    }

    @Test
    @DisplayName("신고_목록_사용자마스터에_없는_신고자는_표시명_null_이고_목록은_정상_조회된다")
    void listReportsUnknownReporterNameNull() throws Exception {
        // given: 사용자 마스터에 없는 번호(999999)로 신고 행을 직접 적재 — 탈퇴/관제 계정 삭제 상황.
        //   이름 조회 실패가 목록 조회 자체를 깨뜨리면 안 된다(fail-soft).
        reportRepository.save(LsDeidentReport.createReport(rawSn, 999999L, "마스터에 없는 신고자"));

        mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reporterNo").value(999999))
                .andExpect(jsonPath("$.data.content[0].reporterName")
                        .value(org.hamcrest.Matchers.nullValue()));
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
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody()))
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

    // ============================================================
    // A-ISSUE-61 — 정렬 allowlist (strict). 미등록 키가 PropertyReferenceException → 500 으로
    //   새어나가던 회귀 가드 (CWE-770 / CWE-209 / CWE-20).
    // ============================================================

    @Test
    @DisplayName("신고_목록_미등록_정렬키는_500이_아니라_400")
    void listReportsUnknownSortKeyReturns400() throws Exception {
        // given: 엔티티에 존재하지 않는 정렬 키 (수정 전에는 PropertyReferenceException → 500)
        // when/then: allowlist strict 판정으로 400 + 표준 errorCode
        mockMvc.perform(get("/v1/deident-reports")
                        .param("sort", "secretField,desc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("신고_목록_미등록_정렬키_응답에_내부필드명_쿼리원문_미노출")
    void listReportsUnknownSortKeyDoesNotLeakInternals() throws Exception {
        // given/when: 미등록 정렬 키로 요청
        String body = mockMvc.perform(get("/v1/deident-reports")
                        .param("sort", "secretField,desc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // then: 입력값 반사·내부 엔티티명·JPQL·예외 클래스명이 응답에 실리지 않는다 (CWE-209)
        assertThat(body)
                .doesNotContain("secretField")
                .doesNotContain("LsDeidentReport")
                .doesNotContain("PropertyReferenceException")
                .doesNotContain("select");
    }

    @Test
    @DisplayName("신고_목록_내부_미노출_컬럼_정렬키_400")
    void listReportsInternalColumnSortKeyReturns400() throws Exception {
        // given: 자유서술 신고사유(rsn)·신고자(reporterNo)는 정렬 축으로 열지 않는다
        //        (값의 순서만으로 본문 접두/신고 주체를 추론하는 경로 차단)
        mockMvc.perform(get("/v1/deident-reports")
                        .param("sort", "rsn,asc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/v1/deident-reports")
                        .param("sort", "reporterNo,desc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고_목록_정렬_인젝션_문자열도_400_이며_500_아님")
    void listReportsSortInjectionReturns400() throws Exception {
        // given: SQL 인젝션 형태 정렬 키 — allowlist 매핑을 못 통과하므로 쿼리에 닿지 않는다 (CWE-89)
        mockMvc.perform(get("/v1/deident-reports")
                        .param("sort", "reportDt; DROP TABLE LS_DEIDENT_REPORT--,desc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());

        assertThat(reportRepository.count()).isNotNegative();
    }

    @Test
    @DisplayName("신고_목록_정렬항목_개수_상한_초과시_400")
    void listReportsTooManySortOrdersReturns400() throws Exception {
        // given: allowlist 고유 엔티티 필드 수(5)를 넘는 정렬 항목 (CWE-770 쿼리 플랜 오염 차단).
        //   상한은 SortAllowlist.maxOrders 가 allowlist 에서 파생하므로 하드코딩 대신 그 값 +1 을 보낸다.
        int over = kr.co.cudo.authoring.common.util.SortAllowlist
                .maxOrders(kr.co.cudo.authoring.common.util.SortAllowlist.DEIDENT_REPORT) + 1;
        var request = get("/v1/deident-reports").header("Authorization", "Bearer " + reviewerToken);
        for (int i = 0; i < over; i++) {
            request = request.param("sort", "reportDt," + (i % 2 == 0 ? "desc" : "asc"));
        }
        mockMvc.perform(request).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고_목록_등록된_정렬키는_정상_200")
    void listReportsAllowedSortKeyReturns200() throws Exception {
        openReport(workerAssignedToken);

        mockMvc.perform(get("/v1/deident-reports")
                        .param("sort", "reportDt,asc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    @DisplayName("신고_목록_정렬_파라미터_없는_기존호출은_그대로_200")
    void listReportsWithoutSortParamStillWorks() throws Exception {
        // 하위호환 — @PageableDefault(sort=reportDt DESC) 가 allowlist 를 통과해야 한다.
        openReport(workerAssignedToken);

        mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    // ============================================================
    // V171 — 신고 목록에 <신고 단계> 노출.
    //   REVIEWER 가 목록에서 "이 신고를 해소하면 무엇이 일어나는지"(마킹부터 다시 / 프레임만 재추출)를
    //   알 수 있어야 한다. 단계는 optional 추가 필드이며 기존 필드는 건드리지 않는다.
    // ============================================================

    @Test
    @DisplayName("신고목록_응답에_단계가_포함된다")
    void listReportsIncludesStage() throws Exception {
        // given — 마킹 화면 진입(rawSn) 신고 1건
        DeidentReportRequest markingReq = new DeidentReportRequest("마킹 중 얼굴 미블러");
        String markingBody = mockMvc.perform(post("/v1/videos/" + rawSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(markingReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long markingRprtSn = objectMapper.readTree(markingBody).get("data").asLong();

        // given — 라벨링 화면 진입(srcSn) 신고 1건 (신고 시 영상이 잠기므로 별도 영상으로 접수)
        LsDataRaw other = LsDataRaw.createFromIngest(
                "CLIP-DR-STAGE", "CCTV-DR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/stage.mp4", LocalDateTime.now(), 30);
        other.markDeidentified("Y");
        other = rawRepository.save(other);
        Long otherSrcSn = srcRepository.save(
                LsDataSrc.create(other.getRawSn(), 0, "/var/raw/stage_0.jpg", LocalDateTime.now())).getSrcSn();
        authrtRepository.save(LsTaskAssignment.createLabeler(other.getRawSn(), 100L, 1L));
        DeidentReportRequest labelingReq = new DeidentReportRequest("라벨링 중 번호판 미블러");
        String labelingBody = mockMvc.perform(post("/v1/labels/" + otherSrcSn + "/deident-report")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long labelingRprtSn = objectMapper.readTree(labelingBody).get("data").asLong();

        // when — REVIEWER 신고 목록 조회
        String listBody = mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        // then — 진입점별 단계가 그대로 내려온다(정렬에 의존하지 않도록 rprtSn 으로 찾는다)
        assertThat(stageOf(listBody, markingRprtSn)).isEqualTo(LsDeidentReport.STAGE_MARKING);
        assertThat(stageOf(listBody, labelingRprtSn)).isEqualTo(LsDeidentReport.STAGE_LABELING);
    }

    @Test
    @DisplayName("레거시_신고는_단계가_null_로_내려간다")
    void listReportsLegacyStageIsNull() throws Exception {
        // given — V171 이전에 접수된 신고(백필하지 않으므로 DCLR_STP_CD 가 영구히 NULL).
        //   "어디서 신고했는지 지어내지 않는다" 는 구속 정책이라 서버가 기본값으로 채우면 안 된다.
        Long legacySn = reportRepository.save(
                LsDeidentReport.createReport(rawSn, 100L, "레거시 신고", null)).getRprtSn();

        // when
        String body = mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        // then — 값은 null 이되 <키는 존재한다>. 키까지 사라지면 화면이 "값이 없다"와
        //   "필드 자체를 모른다"를 구분할 수 없다(FE 는 null 을 '미상' 으로 표시한다).
        com.fasterxml.jackson.databind.JsonNode row = rowOf(body, legacySn);
        assertThat(row.has("stage")).isTrue();
        assertThat(row.get("stage").isNull()).isTrue();
    }

    @Test
    @DisplayName("신고목록_기존_응답필드는_이름·타입·유무가_그대로다_하위호환")
    void listReportsBackwardCompatibleFields() throws Exception {
        // given — 기존 호출자(구 FE·외부 소비자)가 읽던 7필드. 단계는 <추가>만 허용된다.
        openReport(workerAssignedToken);

        // when
        String body = mockMvc.perform(get("/v1/deident-reports")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 이름·유무 고정
                .andExpect(jsonPath("$.data.content[0].rprtSn").exists())
                .andExpect(jsonPath("$.data.content[0].rawSn").exists())
                .andExpect(jsonPath("$.data.content[0].reporterNo").exists())
                .andExpect(jsonPath("$.data.content[0].reason").exists())
                .andExpect(jsonPath("$.data.content[0].status").exists())
                .andExpect(jsonPath("$.data.content[0].reportDt").exists())
                // 타입 고정 (숫자가 문자열로 바뀌는 식의 breaking change 차단)
                .andExpect(jsonPath("$.data.content[0].rprtSn").isNumber())
                .andExpect(jsonPath("$.data.content[0].rawSn").isNumber())
                .andExpect(jsonPath("$.data.content[0].reporterNo").isNumber())
                .andExpect(jsonPath("$.data.content[0].reason").isString())
                .andExpect(jsonPath("$.data.content[0].status").value("OPEN"))
                // OPEN 신고의 해소일시는 종전대로 null (키는 아래 필드집합 단언이 고정한다)
                .andExpect(jsonPath("$.data.content[0].resolvedDt")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        // then — 필드 집합은 <기존 7개 + 추가분 2개(reporterName · stage)> 뿐.
        //   이름 변경·삭제·의외의 필드 유입을 한 번에 잡는다. 추가는 하위호환이므로 기대집합에 넣고,
        //   기존 7개의 이름·타입·유무는 위 단언이 고정한다.
        //   · reporterName — 2026-08-04 신고자 표시명(main PR #79)
        //   · stage        — V171 신고 단계(브랜치)
        com.fasterxml.jackson.databind.JsonNode row = objectMapper.readTree(body)
                .get("data").get("content").get(0);
        List<String> names = new java.util.ArrayList<>();
        row.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder(
                "rprtSn", "rawSn", "reporterNo", "reason", "status", "reportDt", "resolvedDt",
                "reporterName", "stage");
    }

    /** 목록 응답에서 rprtSn 으로 행을 찾는다(정렬 순서에 의존하지 않기 위함). */
    private com.fasterxml.jackson.databind.JsonNode rowOf(String listBody, long rprtSn) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode row
                : objectMapper.readTree(listBody).get("data").get("content")) {
            if (row.get("rprtSn").asLong() == rprtSn) {
                return row;
            }
        }
        throw new AssertionError("신고 목록에 rprtSn=" + rprtSn + " 행이 없습니다.");
    }

    /** 목록 응답의 특정 행 단계 코드(없으면 null). */
    private String stageOf(String listBody, long rprtSn) throws Exception {
        com.fasterxml.jackson.databind.JsonNode stage = rowOf(listBody, rprtSn).get("stage");
        return stage == null || stage.isNull() ? null : stage.asText();
    }
}
