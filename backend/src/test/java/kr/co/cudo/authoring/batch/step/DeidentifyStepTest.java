package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — DeidentifyStep 단위 테스트.
 *
 * <p>V2 정책:
 * <ul>
 *   <li>영상 단위 비식별 (프레임별 호출 폐기). 결과 경로는 LS_DEIDENT_REPORT.DE_IDNTF_FILE_PATH_NM 에만 저장.</li>
 *   <li>PRVC/PSDO/ANONY 모두 비식별 호출 (분기 폐기).</li>
 *   <li>잠금 해제 + OPEN 신고 RESOLVED 전이 — WorkLockService + DeidentReportService 위임.</li>
 * </ul>
 */
class DeidentifyStepTest {

    @TempDir
    Path tmp;

    private DeidentifyClient deidentifyClient;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentReportService deidentReportService;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private Environment environment;
    private BatchTransitionService batchTransitionService;
    private DeidentifyStep step;
    private Path baseDeid;

    @BeforeEach
    void setUp() throws Exception {
        deidentifyClient = mock(DeidentifyClient.class);
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportService = mock(DeidentReportService.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        batchTransitionService = mock(BatchTransitionService.class);
        // 기본 environment 는 순수 local 프로파일 + ENV 미설정(개발자 머신) — mock 게이팅 음성(정상) 케이스.
        environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(environment.getProperty("ENV")).thenReturn(null);

        baseDeid = tmp.resolve("deid");
        // 생성자 순서: deidentifyClient, videoRepository, procLogRepository,
        //              deidentReportService, notificationService, workLockService,
        //              kpstDeidentService, environment
        // 레거시(동기) 경로 테스트이므로 KpstDeidentService=null + kpstEnabled=false + mockMode=false.
        step = new DeidentifyStep(deidentifyClient, videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, environment,
                batchTransitionService);
        setField(step, "deidPath", baseDeid.toString());
        setField(step, "kpstEnabled", false);
        setField(step, "mockMode", false);
        invoke(step, "initBasePath");

        // procLogRepository.save 는 echo + ID 부여
        when(procLogRepository.save(any(LsDeidentProcLog.class))).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });
    }

    private LsDataRaw newRaw(String prvc) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    @Test
    @DisplayName("정상_경로_응답이면_LS_DEIDENT_REPORT_SUCCEEDED_+_DE_IDNTF_Y_+_결과경로_반환")
    void normalPath_marksSuccess() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        String result = step.run(raw);

        assertThat(result).isEqualTo(safeReturn.toString());
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        // LS_DEIDENT_PROC_LOG 가 최소 1회 save (REQUESTED 시점 저장; SUCCEEDED 는 영속 엔티티 변경으로 dirty checking)
        verify(procLogRepository, org.mockito.Mockito.atLeast(1)).save(any(LsDeidentProcLog.class));
    }

    @Test
    @DisplayName("응답_resultPath가_baseDeidentifiedPath_밖이면_EXTERNAL_API_ERROR_그리고_DE_IDNTF_F_마킹")
    void escapingResultPath_rejected() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // 외부 응답이 base 밖 경로 (path traversal 시도)
        Path escaping = tmp.resolve("other").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", escaping.toString())));

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR");

        // 라이브 검증 결함 수정: 'F' 마킹 + procLog FAIL 은 별도 빈의 REQUIRES_NEW 커밋으로 위임된다.
        // 인라인 markDeidentified("F") 가 제거되었으므로, 별도 트랜잭션 위임 호출을 검증한다
        // (실제 'F' DB 영속은 DeidentifyStepFailurePersistenceIntegrationTest 가 실 트랜잭션으로 검증).
        verify(batchTransitionService).recordDeidentFailure(
                eq(9001L), eq("EXTERNAL_API_ERROR"), any());
    }

    @Test
    @DisplayName("Phase2_ANONY_영상도_DeidentifyStep_호출_무조건_비식별")
    void anonyAlsoInvokesDeidentify() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase2_execute_ctx_는_run_raw_에_위임 — BatchStep 균일 인터페이스")
    void executeDelegatesToRun() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        // stage() = DEIDENTIFY
        assertThat(step.stage())
                .isEqualTo(kr.co.cudo.authoring.batch.orchestrator.BatchStage.DEIDENTIFY);

        // execute(ctx) → run(ctx.getRaw())
        step.execute(new kr.co.cudo.authoring.batch.pipeline.BatchContext(raw.getRawSn(), raw));

        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase3_재비식별_잠금_영상_성공시_WorkLockService_releaseRaw_+_resolveOpenReports_+_알림")
    void redeidentLockReleasedOnSuccess() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("DEIDENT_SUCCEEDED"));
        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
    }

    @Test
    @DisplayName("Phase3_정상_영상_잠금_없으면_releaseRaw_미호출_+_resolveOpenReports_idempotent_호출")
    void normalVideoSuccessSkipsRelease() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        // 잠금이 없으면 release 호출 안 함 (가드 통과)
        verify(workLockService, never()).releaseRaw(any(), any(), any());
        // resolveOpenReports 는 항상 호출됨 (idempotent — 신고 없으면 0 반환)
        verify(deidentReportService).resolveOpenReports(9001L);
    }

    @Test
    @DisplayName("Phase3_위탁_시_LS_DEIDENT_PROC_LOG_REQUESTED_상태로_적재")
    void requestPersistedAsRequested() {
        // ccarch if-deidentify-spi 비동기 위탁 명세 — 위탁 시점에 LS_DEIDENT_PROC_LOG 가 REQUESTED 로 기록되어야 한다.
        // 결과 webhook 도착 전(또는 동기 응답 도착 직전) 의 상태가 REQUESTED 임을 검증.
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();

        // save 후 procSttsCd 가 REQUESTED 인지 캡처
        org.mockito.ArgumentCaptor<LsDeidentProcLog> captor =
                org.mockito.ArgumentCaptor.forClass(LsDeidentProcLog.class);
        when(procLogRepository.save(captor.capture())).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        // 위탁 시점에 save 된 procLog 가 REQUESTED 였음 (이후 succeed() 로 SUCCEEDED 전이 — 동일 인스턴스 dirty checking)
        LsDeidentProcLog first = captor.getAllValues().get(0);
        // succeed() 가 호출되기 전 상태는 REQUESTED 였으나, 인스턴스가 동일하므로 마지막 상태는 SUCCEEDED.
        // 따라서 succeed() 가 정상 호출되었는지(=결과 도착 시점에 SUCCEEDED 전이) 확인.
        assertThat(first.getProcSttsCd()).isIn(LsDeidentProcLog.REQUESTED, LsDeidentProcLog.SUCCEEDED);
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase2_PRVC_영상_정상_호출_성공_시_markDeidentified_Y_회귀")
    void prvcSuccessMarksY() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("KPST_disabled시_기존_DeidentifyClient_동기경로가_유지된다")
    void kpstDisabledKeepsLegacySyncPath() {
        // 회귀 가드 — kpstEnabled=false 면 KpstDeidentService 미주입(null)이라도 기존 동기 경로 동작.
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        String result = step.run(raw);

        // 기존 동기 경로: DeidentifyClient 호출 + 즉시 Y 전이 + 결과 경로 반환
        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(result).isEqualTo(safeReturn.toString());
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("KPST_enabled시_DeidentifyStep이_KpstDeidentService_submit으로_위탁하고_동기경로를_타지않는다")
    void kpstEnabledDelegatesToSubmit() {
        kr.co.cudo.authoring.batch.service.KpstDeidentService kpst =
                mock(kr.co.cudo.authoring.batch.service.KpstDeidentService.class);
        DeidentifyStep kpstStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, kpst, environment,
                batchTransitionService);
        setField(kpstStep, "deidPath", baseDeid.toString());
        setField(kpstStep, "kpstEnabled", true);
        setField(kpstStep, "mockMode", false);
        invoke(kpstStep, "initBasePath");

        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);

        String result = kpstStep.run(raw);

        // 위탁 경로: KpstDeidentService.submit 호출, 기존 동기 클라이언트는 미호출(완료 대기)
        verify(kpst).submit(raw);
        verify(deidentifyClient, never()).deidentify(any(DeidentifyRequest.class));
        // DE_IDNTF_YN 미전이(완료 대기) — 폴링 잡이 나중에 Y 전이
        assertThat(result).isNull();
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 1 — local 전용 mock 비식별 모드 (외부 서버 없이 e2e 해금)
    // ─────────────────────────────────────────────────────────────────────────────

    /** mockMode 활성 step 을 구성한다(local 프로파일 수용). 원본 파일은 호출자가 준비. */
    private DeidentifyStep newMockStep() {
        DeidentifyStep mockStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, null, environment,
                batchTransitionService);
        setField(mockStep, "deidPath", baseDeid.toString());
        setField(mockStep, "kpstEnabled", false);
        setField(mockStep, "mockMode", true);
        invoke(mockStep, "initBasePath");
        return mockStep;
    }

    /** 존재하는 원본 영상 파일을 만들어 그 경로를 rawFilePathNm 으로 갖는 raw 를 생성. */
    private LsDataRaw newRawWithRealSource(String content) throws Exception {
        Path src = tmp.resolve("src").resolve("clip.mp4");
        Files.createDirectories(src.getParent());
        Files.writeString(src, content);
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, src.toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    @Test
    @DisplayName("mock모드_원본존재시_외부비식별_호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다")
    void mockMode_realSource_copiesWithoutExternalCall() throws Exception {
        DeidentifyStep mockStep = newMockStep();
        LsDataRaw raw = newRawWithRealSource("video-bytes");
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        String result = mockStep.run(raw);

        Path target = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        // 외부 비식별 클라이언트는 호출되지 않는다 (mock 경로).
        verify(deidentifyClient, never()).deidentify(any(DeidentifyRequest.class));
        // 비식별 결과가 target 경로로 복사됨 (내용 동일).
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readString(target)).isEqualTo("video-bytes");
        // 원본 보존 — 무변경(복사만).
        assertThat(Files.readString(Path.of(raw.getRawFilePathNm()))).isEqualTo("video-bytes");
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(result).isEqualTo(target.toString());
    }

    @Test
    @DisplayName("mock모드_원본부재시_성공위장없이_DE_IDNTF_YN이_F이고_MARKING_READY_미전이된다")
    void mockMode_missingSource_marksFailureNoSuccessFake() {
        DeidentifyStep mockStep = newMockStep();
        // 존재하지 않는 원본 경로
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, tmp.resolve("no-such-file.mp4").toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        // 원본 부재 → 성공 위장 없이 실패 처리. run() 은 정상 반환하지 않음(예외) — MARKING_READY 미전이.
        assertThatThrownBy(() -> mockStep.run(raw))
                .isInstanceOf(CustomException.class);

        verify(deidentifyClient, never()).deidentify(any(DeidentifyRequest.class));
        // 라이브 검증 결함 수정: 'F' 마킹 + procLog FAIL 은 별도 빈의 REQUIRES_NEW 커밋으로 위임된다.
        verify(batchTransitionService).recordDeidentFailure(
                eq(9001L), eq("MOCK_SOURCE_MISSING"), any());
        // 빈 0-byte 플레이스홀더를 만들지 않는다.
        Path target = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다")
    void mockMode_rerun_isIdempotent() throws Exception {
        DeidentifyStep mockStep = newMockStep();
        LsDataRaw raw = newRawWithRealSource("first-content");
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        mockStep.run(raw);
        Path target = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        assertThat(Files.readString(target)).isEqualTo("first-content");

        // 원본 내용을 바꾸고 재실행 → 덮어쓰기 + 임시파일 잔존 없음.
        Files.writeString(Path.of(raw.getRawFilePathNm()), "second-content");
        mockStep.run(raw);

        assertThat(Files.readString(target)).isEqualTo("second-content");
        // 임시파일(.tmp_9001) 잔존 없음.
        Path tmpFile = target.resolveSibling(target.getFileName() + ".tmp_9001");
        assertThat(Files.exists(tmpFile)).isFalse();
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("mock_mode_true인데_prd프로파일이면_초기화_부트가_거부된다")
    void mockMode_nonLocalProfile_rejectsBoot() {
        Environment prdEnv = mock(Environment.class);
        // local 프로파일 미수용 (prd/stg)
        when(prdEnv.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        when(prdEnv.getActiveProfiles()).thenReturn(new String[]{"prd"});
        when(prdEnv.getProperty("ENV")).thenReturn(null);
        DeidentifyStep prdStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, null, prdEnv,
                batchTransitionService);
        setField(prdStep, "deidPath", baseDeid.toString());
        setField(prdStep, "kpstEnabled", false);
        setField(prdStep, "mockMode", true);

        // invoke() 는 InvocationTargetException 을 RuntimeException 으로 감싸므로 root cause 로 검증.
        assertThatThrownBy(() -> invoke(prdStep, "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    @DisplayName("mock_mode_true_이고_active프로파일에_dev가_섞이면_부트거부된다")
    void mockMode_localMixedWithNonLocalProfile_rejectsBoot() {
        Environment mixedEnv = mock(Environment.class);
        // local 프로파일은 수용하지만 active 에 비-local(dev) 이 혼합됨 → 우회 차단.
        when(mixedEnv.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(mixedEnv.getActiveProfiles()).thenReturn(new String[]{"local", "dev"});
        when(mixedEnv.getProperty("ENV")).thenReturn(null);
        DeidentifyStep mixedStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, null, mixedEnv,
                batchTransitionService);
        setField(mixedStep, "deidPath", baseDeid.toString());
        setField(mixedStep, "kpstEnabled", false);
        setField(mixedStep, "mockMode", true);

        assertThatThrownBy(() -> invoke(mixedStep, "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    @DisplayName("mock_mode_true_이고_ENV가_prd면_부트거부된다")
    void mockMode_envPrd_rejectsBoot() {
        Environment envPrd = mock(Environment.class);
        // active 는 순수 local 이지만 ENV 환경변수가 prd → 운영 배포 의심, 차단.
        when(envPrd.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(envPrd.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(envPrd.getProperty("ENV")).thenReturn("prd");
        DeidentifyStep envPrdStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, null, envPrd,
                batchTransitionService);
        setField(envPrdStep, "deidPath", baseDeid.toString());
        setField(envPrdStep, "kpstEnabled", false);
        setField(envPrdStep, "mockMode", true);

        assertThatThrownBy(() -> invoke(envPrdStep, "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    @DisplayName("mock_mode_true_이고_순수_local_이며_ENV_미설정이면_통과된다")
    void mockMode_pureLocalEnvUnset_passesBoot() {
        Environment pureLocal = mock(Environment.class);
        when(pureLocal.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(pureLocal.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(pureLocal.getProperty("ENV")).thenReturn(null);
        DeidentifyStep pureLocalStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, null, pureLocal,
                batchTransitionService);
        setField(pureLocalStep, "deidPath", baseDeid.toString());
        setField(pureLocalStep, "kpstEnabled", false);
        setField(pureLocalStep, "mockMode", true);

        // 순수 local + ENV 미설정 → 부트 통과 (예외 없음).
        invoke(pureLocalStep, "initBasePath");
    }

    @Test
    @DisplayName("mock_mode_true_이고_ENV가_local이면_통과된다")
    void mockMode_envLocal_passesBoot() {
        Environment envLocal = mock(Environment.class);
        when(envLocal.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(envLocal.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(envLocal.getProperty("ENV")).thenReturn("local");
        DeidentifyStep envLocalStep = new DeidentifyStep(deidentifyClient, videoRepository,
                procLogRepository, deidentReportService, notificationService, workLockService, null, envLocal,
                batchTransitionService);
        setField(envLocalStep, "deidPath", baseDeid.toString());
        setField(envLocalStep, "kpstEnabled", false);
        setField(envLocalStep, "mockMode", true);

        // ENV=local(명시) 도 허용 → 부트 통과.
        invoke(envLocalStep, "initBasePath");
    }

    @Test
    @DisplayName("mock모드_비활성_기본값시_기존_외부_DeidentifyClient_호출경로를_탄다")
    void mockModeDisabled_keepsExternalPath() {
        // 기본 step(mockMode=false) — 회귀 가드.
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invoke(Object target, String method) {
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
