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
        // 기본 environment 는 순수 local 프로파일 + ENV 미설정(개발자 머신) — mock 게이팅 허용(정상) 케이스.
        //   prd 미해당이므로 acceptsProfiles(prd)=false 로 둔다(production 의 유일한 acceptsProfiles 호출 대상은 prd).
        environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
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
        // selfProvider=null — 단위 테스트는 프록시 없이 execute()→this.run() 직접 호출(리포지토리 mock).
        DeidentifyStep s = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, kpstService, env,
                batchTransitionService, null);
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

        DeidentResult result = kpstStep.run(raw);

        // 위탁 경로: KpstDeidentService.submit 호출. DE_IDNTF_YN 미전이(완료 대기) — 폴링 잡이 나중에 Y 전이.
        // 반환은 deferred(지연) — 호출자가 MARKING_READY 로 조기 전이하지 않도록 completed=false.
        verify(kpst).submit(raw);
        assertThat(result.completed()).isFalse();
        assertThat(result.deidFilePath()).isNull();
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

        DeidentResult result = mockStep.run(raw);

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
        // 동기 완료 — completed=true + 비식별 산출물 경로 보유(호출자가 즉시 MARKING_READY 전이).
        assertThat(result.completed()).isTrue();
        assertThat(result.deidFilePath()).isEqualTo(target.toString());
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
    // mock-mode 프로파일 게이팅 — prd(운영)만 차단, local/dev/stg 허용 (프라이버시 경계 완화)
    //   배경: KPST 미준비로 dev/stg 에서 mock 비식별로 파이프라인을 굴려야 함.
    //   가드: prd 는 fail-closed 로 끝까지 차단(프로파일/ENV/혼합 모두).
    // ─────────────────────────────────────────────────────────────────────────────

    /** 주어진 environment 로 mock-mode 활성 step 을 구성(초기화 전 상태). */
    private DeidentifyStep newMockStepWith(Environment env) {
        DeidentifyStep s = new DeidentifyStep(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, null, env,
                batchTransitionService, null);
        setField(s, "deidPath", baseDeid.toString());
        setField(s, "kpstEnabled", false);
        setField(s, "mockMode", true);
        return s;
    }

    /** acceptsPrd = production 의 유일한 acceptsProfiles 호출 대상(prd) 이 active 인지. */
    private Environment envWith(String[] activeProfiles, String envVar, boolean acceptsPrd) {
        Environment e = mock(Environment.class);
        when(e.acceptsProfiles(any(Profiles.class))).thenReturn(acceptsPrd);
        when(e.getActiveProfiles()).thenReturn(activeProfiles);
        when(e.getProperty("ENV")).thenReturn(envVar);
        return e;
    }

    @Test
    @DisplayName("prd_프로파일에서_mock활성시_부팅거부")
    void prd_프로파일에서_mock활성시_부팅거부() {
        Environment env = envWith(new String[]{"prd"}, null, true);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prd");
    }

    @Test
    @DisplayName("ENV가_prd면_mock활성시_부팅거부")
    void ENV가_prd면_mock활성시_부팅거부() {
        // 프로파일은 비어도 ENV=prd 면 거부 — 우회 방지(fail-closed).
        Environment env = envWith(new String[]{}, "prd", false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prd");
    }

    @Test
    @DisplayName("prd와_dev가_섞인_active프로파일이면_거부")
    void prd와_dev가_섞인_active프로파일이면_거부() {
        // 혼합 프로파일에 prd 가 하나라도 섞이면 거부 — 우회 방지(fail-closed).
        Environment env = envWith(new String[]{"prd", "dev"}, null, false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prd");
    }

    @Test
    @DisplayName("dev_프로파일에서_mock활성시_부팅허용")
    void dev_프로파일에서_mock활성시_부팅허용() {
        Environment env = envWith(new String[]{"dev"}, null, false);

        invoke(newMockStepWith(env), "initBasePath"); // 예외 없음
    }

    @Test
    @DisplayName("stg_프로파일에서_mock활성시_부팅허용")
    void stg_프로파일에서_mock활성시_부팅허용() {
        Environment env = envWith(new String[]{"stg"}, null, false);

        invoke(newMockStepWith(env), "initBasePath"); // 예외 없음
    }

    @Test
    @DisplayName("local_프로파일에서_mock활성시_부팅허용")
    void local_프로파일에서_mock활성시_부팅허용() {
        Environment env = envWith(new String[]{"local"}, null, false);

        invoke(newMockStepWith(env), "initBasePath"); // 예외 없음(기존 동작 회귀 보호)
    }

    // ── fail-closed(allowlist) 회귀 방지: 미식별/비표준/대소문자 우회/공백/혼합은 모두 거부 ──

    @Test
    @DisplayName("ENV가_PRD_대문자여도_거부")
    void ENV가_PRD_대문자여도_거부() {
        // 대소문자 우회 방지 — active 는 허용(local)이라도 ENV=PRD 면 fail-closed 거부.
        Environment env = envWith(new String[]{"local"}, "PRD", false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ENV가_공백포함_prd여도_거부")
    void ENV가_공백포함_prd여도_거부() {
        // trim 검증 — 앞뒤 공백이 섞인 prd 도 정규화 후 거부.
        Environment env = envWith(new String[]{"local"}, "  prd  ", false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("local과_prd가_섞인_active프로파일이면_거부")
    void local과_prd가_섞인_active프로파일이면_거부() {
        // 허용값(local)에 비허용값(prd)이 하나라도 섞이면 거부 — 혼합 우회 방지.
        Environment env = envWith(new String[]{"local", "prd"}, null, false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("비표준_환경라벨(production)이면_거부")
    void 비표준_환경라벨_production이면_거부() {
        // allow-by-default 회귀 방지(fail-closed 핵심) — prd 로 인식 안 되는 비표준 라벨도 거부.
        Environment env = envWith(new String[]{"production"}, null, false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("active프로파일_미설정이면_거부")
    void active프로파일_미설정이면_거부() {
        // 모호(미식별) → fail-closed 거부. 프로파일도 ENV 도 없으면 mock 부팅 불가.
        Environment env = envWith(new String[]{}, null, false);

        assertThatThrownBy(() -> invoke(newMockStepWith(env), "initBasePath"))
                .getRootCause()
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("dev_프로파일에서_mock활성_부팅시_비식별경고_WARN로그를_1줄_남긴다")
    void dev_프로파일_mock활성_부팅시_WARN로그_1줄() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DeidentifyStep.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Environment env = envWith(new String[]{"dev"}, null, false);
            invoke(newMockStepWith(env), "initBasePath");

            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("non-local"))
                    .count();
            assertThat(warnCount).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
        }
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
