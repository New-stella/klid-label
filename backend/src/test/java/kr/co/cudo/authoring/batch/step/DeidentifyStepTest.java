package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
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
 * DeidentifyStep 단위 테스트 — UC018 KPST 단일 경로 + local mock.
 *
 * <p>경로 단일화 후 본 단계가 취할 수 있는 경로는 아래 셋뿐이다(레거시 동기 SPI 제거).
 * <ul>
 *   <li>① mockMode(local 전용): 외부 호출 없이 원본을 비식별 경로로 복사.</li>
 *   <li>② KPST 위탁({@code kpst.deid.enabled=true}): {@link KpstDeidentService#submit} 위탁만.</li>
 *   <li>③ 설정 오류(mock 아님 + KPST 미주입): 레거시 폴백 없이 명확한 예외.</li>
 * </ul>
 */
class DeidentifyStepTest {

    @TempDir
    Path tmp;

    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentReportService deidentReportService;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private Environment environment;
    private BatchTransitionService batchTransitionService;
    private Path baseDeid;

    @BeforeEach
    void setUp() throws Exception {
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

        // procLogRepository.save 는 echo + ID 부여
        when(procLogRepository.save(any(LsDeidentProcLog.class))).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });
    }

    /** 주어진 토글로 step 을 구성한다. kpstService=null 이면 미주입. */
    private DeidentifyStep newStep(boolean kpstEnabled, boolean mockMode, KpstDeidentService kpstService) {
        return newStep(kpstEnabled, mockMode, kpstService, environment);
    }

    private DeidentifyStep newStep(boolean kpstEnabled, boolean mockMode,
                                   KpstDeidentService kpstService, Environment env) {
        DeidentifyStep s = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, kpstService, env,
                batchTransitionService);
        setField(s, "deidPath", baseDeid.toString());
        setField(s, "kpstEnabled", kpstEnabled);
        setField(s, "mockMode", mockMode);
        invoke(s, "initBasePath");
        return s;
    }

    private LsDataRaw newRaw(String prvc) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
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

    // ─────────────────────────────────────────────────────────────────────────────
    // ② KPST 위탁 경로 (기본)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KPST_enabled시_DeidentifyStep이_KpstDeidentService_submit으로_위탁하고_즉시_Y전이하지_않는다")
    void kpstEnabledDelegatesToSubmit() {
        KpstDeidentService kpst = mock(KpstDeidentService.class);
        DeidentifyStep kpstStep = newStep(true, false, kpst);

        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);

        String result = kpstStep.run(raw);

        // 위탁 경로: KpstDeidentService.submit 호출. DE_IDNTF_YN 미전이(완료 대기) — 폴링 잡이 나중에 Y 전이.
        verify(kpst).submit(raw);
        assertThat(result).isNull();
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
    }

    @Test
    @DisplayName("execute_ctx_는_run_raw_에_위임 — BatchStep 균일 인터페이스(KPST 위탁)")
    void executeDelegatesToRun() {
        KpstDeidentService kpst = mock(KpstDeidentService.class);
        DeidentifyStep kpstStep = newStep(true, false, kpst);
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);

        assertThat(kpstStep.stage())
                .isEqualTo(kr.co.cudo.authoring.batch.orchestrator.BatchStage.DEIDENTIFY);

        kpstStep.execute(new kr.co.cudo.authoring.batch.pipeline.BatchContext(raw.getRawSn(), raw));

        verify(kpst).submit(raw);
    }

    @Test
    @DisplayName("ANONY_영상도_KPST_위탁_무조건_비식별(분기_없음)")
    void anonyAlsoSubmitsToKpst() {
        KpstDeidentService kpst = mock(KpstDeidentService.class);
        DeidentifyStep kpstStep = newStep(true, false, kpst);
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);

        kpstStep.run(raw);

        verify(kpst).submit(raw);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ③ 설정 오류 경로 (레거시 폴백 제거 — 명확한 예외)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KPST_disabled_이고_mock도_아니면_레거시폴백_없이_설정오류_예외(INTERNAL_ERROR)")
    void noPathConfigured_throwsInternalError() {
        // mock 아님 + KPST 서비스 미주입(또는 enabled=false) → 레거시 동기 경로가 제거되어 설정 오류.
        DeidentifyStep step = newStep(false, false, null);
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외 — 임의동작_금지")
    void kpstEnabledButServiceMissing_throwsInternalError() {
        DeidentifyStep step = newStep(true, false, null);
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("raw_null이면_INVALID_INPUT")
    void nullRaw_throwsInvalidInput() {
        DeidentifyStep step = newStep(true, false, mock(KpstDeidentService.class));
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ① local 전용 mock 비식별 모드 (외부 서버 없이 e2e 해금)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다")
    void mockMode_realSource_copiesWithoutExternalCall() throws Exception {
        // mock 우선 — KPST 토글이 켜져 있어도 mockMode 가 앞선다(local 자족).
        KpstDeidentService kpst = mock(KpstDeidentService.class);
        DeidentifyStep mockStep = newStep(true, true, kpst);
        LsDataRaw raw = newRawWithRealSource("video-bytes");
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        String result = mockStep.run(raw);

        Path target = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        // KPST 위탁은 호출되지 않는다 (mock 경로가 우선).
        verify(kpst, never()).submit(any());
        // 비식별 결과가 target 경로로 복사됨 (내용 동일).
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readString(target)).isEqualTo("video-bytes");
        // 원본 보존 — 무변경(복사만).
        assertThat(Files.readString(Path.of(raw.getRawFilePathNm()))).isEqualTo("video-bytes");
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(result).isEqualTo(target.toString());
    }

    @Test
    @DisplayName("mock모드_재비식별_잠금영상_성공시_releaseRaw_+_resolveOpenReports_+_알림")
    void mockMode_lockReleasedOnSuccess() throws Exception {
        DeidentifyStep mockStep = newStep(false, true, null);
        LsDataRaw raw = newRawWithRealSource("video-bytes");
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);

        mockStep.run(raw);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("DEIDENT_SUCCEEDED"));
        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
    }

    @Test
    @DisplayName("mock모드_원본부재시_성공위장없이_recordDeidentFailure_+_MARKING_READY_미전이")
    void mockMode_missingSource_marksFailureNoSuccessFake() {
        DeidentifyStep mockStep = newStep(false, true, null);
        // 존재하지 않는 원본 경로
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, tmp.resolve("no-such-file.mp4").toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        // 원본 부재 → 성공 위장 없이 실패 처리. run() 은 정상 반환하지 않음(예외) — MARKING_READY 미전이.
        assertThatThrownBy(() -> mockStep.run(raw))
                .isInstanceOf(CustomException.class);

        // 'F' 마킹 + procLog FAIL 은 별도 빈의 REQUIRES_NEW 커밋으로 위임된다.
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
        DeidentifyStep mockStep = newStep(false, true, null);
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

    // ─────────────────────────────────────────────────────────────────────────────
    // mock-mode 프로파일 게이팅 (HIGH-1) — 운영 노출 차단
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mock_mode_true인데_prd프로파일이면_초기화_부트가_거부된다")
    void mockMode_nonLocalProfile_rejectsBoot() {
        Environment prdEnv = mock(Environment.class);
        when(prdEnv.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        when(prdEnv.getActiveProfiles()).thenReturn(new String[]{"prd"});
        when(prdEnv.getProperty("ENV")).thenReturn(null);
        DeidentifyStep prdStep = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, prdEnv,
                batchTransitionService);
        setField(prdStep, "deidPath", baseDeid.toString());
        setField(prdStep, "kpstEnabled", false);
        setField(prdStep, "mockMode", true);

        assertThatThrownBy(() -> invoke(prdStep, "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    @DisplayName("mock_mode_true_이고_active프로파일에_dev가_섞이면_부트거부된다")
    void mockMode_localMixedWithNonLocalProfile_rejectsBoot() {
        Environment mixedEnv = mock(Environment.class);
        when(mixedEnv.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(mixedEnv.getActiveProfiles()).thenReturn(new String[]{"local", "dev"});
        when(mixedEnv.getProperty("ENV")).thenReturn(null);
        DeidentifyStep mixedStep = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, mixedEnv,
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
        when(envPrd.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(envPrd.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(envPrd.getProperty("ENV")).thenReturn("prd");
        DeidentifyStep envPrdStep = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, envPrd,
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
        DeidentifyStep pureLocalStep = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, pureLocal,
                batchTransitionService);
        setField(pureLocalStep, "deidPath", baseDeid.toString());
        setField(pureLocalStep, "kpstEnabled", false);
        setField(pureLocalStep, "mockMode", true);

        invoke(pureLocalStep, "initBasePath");
    }

    @Test
    @DisplayName("mock_mode_true_이고_ENV가_local이면_통과된다")
    void mockMode_envLocal_passesBoot() {
        Environment envLocal = mock(Environment.class);
        when(envLocal.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(envLocal.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(envLocal.getProperty("ENV")).thenReturn("local");
        DeidentifyStep envLocalStep = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, envLocal,
                batchTransitionService);
        setField(envLocalStep, "deidPath", baseDeid.toString());
        setField(envLocalStep, "kpstEnabled", false);
        setField(envLocalStep, "mockMode", true);

        invoke(envLocalStep, "initBasePath");
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
