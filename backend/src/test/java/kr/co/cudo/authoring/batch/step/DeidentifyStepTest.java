package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;

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

    /** 운영과 동일한 캐시 스펙(CacheConfig)으로 실제 Caffeine 캐시매니저를 만든다(초기화 포함). */
    private static SimpleCacheManager newRealCacheManager() {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        return manager;
    }

    /** 주어진 토글로 step 을 구성한다. kpstService=null 이면 미주입. */
    private DeidentifyStep newStep(boolean kpstEnabled, KpstDeidentService kpstService) {
        // selfProvider=null — 단위 테스트는 프록시 없이 execute()→this.run() 직접 호출.
        DeidentifyStep s = new DeidentifyStep(kpstService, null);
        setField(s, "kpstEnabled", kpstEnabled);
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
        DeidentifyStep kpstStep = newStep(true, kpst);

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
        DeidentifyStep kpstStep = newStep(true, kpst);
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
        DeidentifyStep kpstStep = newStep(true, kpst);
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
        DeidentifyStep step = newStep(false, null);
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외 — 임의동작_금지")
    void kpstEnabledButServiceMissing_throwsInternalError() {
        DeidentifyStep step = newStep(true, null);
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("raw_null이면_INVALID_INPUT")
    void nullRaw_throwsInvalidInput() {
        DeidentifyStep step = newStep(true, mock(KpstDeidentService.class));
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
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
