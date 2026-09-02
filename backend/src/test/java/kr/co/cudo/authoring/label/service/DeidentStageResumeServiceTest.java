package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V171 — 비식별 누락 신고 해소 후 <b>단계별 재개</b> 단위 테스트.
 *
 * <table border="1">
 *   <caption>재개 지점(사용자 확정, 구속)</caption>
 *   <tr><th>신고 단계</th><th>재개 지점</th></tr>
 *   <tr><td>MARKING</td><td>마킹부터 다시 — MARKING_READY 되감기 + 활성 마킹 종결</td></tr>
 *   <tr><td>LABELING</td><td>프레임 이미지만 재추출 — 마킹 유지 · 라벨 좌표 보존</td></tr>
 * </table>
 */
class DeidentStageResumeServiceTest {

    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private LsDataSrcRepository srcRepository;
    private DeidentFrameAttacher deidentFrameAttacher;
    private DeidentStageResumeService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        deidentFrameAttacher = mock(DeidentFrameAttacher.class);
        service = new DeidentStageResumeService(videoRepository, markingRepository,
                procLogRepository, srcRepository, deidentFrameAttacher);
    }

    private LsDataRaw raw(long rawSn, String stage) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "C-" + rawSn, "CCTV", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(r, "rawSn", rawSn);
        r.markDeidentified("Y");
        setField(r, "dataSttsCd", stage);
        return r;
    }

    private void stubDeidPath(long rawSn, String path) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, "req-" + rawSn, "/orgnl/" + rawSn + ".mp4", "system");
        procLog.succeed(path);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(procLog));
    }

    // ---------- MARKING ----------

    @Test
    @DisplayName("해소시_마킹단계는_MARKING_READY_로_되감긴다")
    void markingResumeRewindsStage() {
        // given — 어떤 경로로든 배치 단계가 넘어가 있으면 재마킹 진입이 막힌다(MarkingGuards).
        LsDataRaw r = raw(9901L, LsDataRaw.DATA_STTS_PROCESSING);
        when(videoRepository.findById(9901L)).thenReturn(Optional.of(r));
        when(markingRepository.findByRawSnAndSttsCdIn(eq(9901L), any())).thenReturn(List.of());

        // when
        boolean changed = service.resumeMarking(9901L);

        // then — 마킹 게이트 두 조건 중 배치 단계 조건이 열린다('Y' 복원은 resolve 가 이미 수행).
        assertThat(changed).isTrue();
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        // 프레임 재추출은 마킹 단계 재개의 일이 아니다 — 마킹 전이라 프레임 자체가 없다.
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("마킹단계_재개는_활성마킹을_종결해_재마킹_409를_푼다")
    void markingResumeTerminatesActiveMarkings() {
        // given — 활성 마킹(PENDING / VLM_REQUESTED)이 남으면 재마킹이 409(V142 부분 유니크)로 막힌다.
        LsDataRaw r = raw(9902L, LsDataRaw.DATA_STTS_MARKING_READY);
        when(videoRepository.findById(9902L)).thenReturn(Optional.of(r));
        LsMarking pending = LsMarking.createManual(9902L, "[]", "1");
        LsMarking requested = LsMarking.createManual(9902L, "[]", "1");
        requested.markVlmRequested();
        when(markingRepository.findByRawSnAndSttsCdIn(eq(9902L), any()))
                .thenReturn(List.of(pending, requested));

        // when
        boolean changed = service.resumeMarking(9902L);

        // then — 둘 다 SKIPPED 로 종결(진행 중 위탁도 종결한다 — 신고된 비식별본 대상 위탁이라 소비 금지).
        assertThat(changed).isTrue();
        assertThat(pending.getSttsCd()).isEqualTo(LsMarking.STATUS_SKIPPED);
        assertThat(requested.getSttsCd()).isEqualTo(LsMarking.STATUS_SKIPPED);
    }

    @Test
    @DisplayName("마킹단계_재개는_이미_MARKING_READY_면_멱등_no_op")
    void markingResumeIsIdempotent() {
        LsDataRaw r = raw(9903L, LsDataRaw.DATA_STTS_MARKING_READY);
        when(videoRepository.findById(9903L)).thenReturn(Optional.of(r));
        when(markingRepository.findByRawSnAndSttsCdIn(eq(9903L), any())).thenReturn(List.of());

        assertThat(service.resumeMarking(9903L)).isFalse();
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("마킹단계_재개는_프레임이_이미_있으면_되감지_않고_실패한다")
    void markingResumeFailsClosedWhenFramesAlreadyExist() {
        // given — 프레임이 이미 추출된 영상. 지금은 마킹 단계 신고가 MARKING_READY 에서만 접수돼
        //   도달하지 않지만, 그 안전성의 근거는 이 서비스 밖(신고 접수 조건·MarkingGuards·
        //   MarkingBatchBridge)에 흩어져 있다. 여기서 직접 재확인하지 않으면 그 전제가 깨졌을 때
        //   경고 없이 되감고 → 재마킹이 LS_DATA_SRC 새 행을 INSERT 해 기존 라벨이 고아가 된다.
        LsDataRaw r = raw(9904L, LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findById(9904L)).thenReturn(Optional.of(r));
        when(srcRepository.countByRawSn(9904L)).thenReturn(3L);

        // when / then — 조용히 성공하지 않는다(fail-closed).
        assertThatThrownBy(() -> service.resumeMarking(9904L))
                .isInstanceOf(CustomException.class);

        // and — ① 배치 단계를 되감지 않았고 ② 활성 마킹 종결도 시도하지 않았다(파괴 0).
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        verify(markingRepository, never()).findByRawSnAndSttsCdIn(anyLong(), any());
    }

    // ---------- LABELING ----------

    @Test
    @DisplayName("해소시_라벨링단계는_프레임만_재추출하고_라벨을_보존한다")
    void labelingResumeReExtractsFramesOnly() {
        // given — 라벨링 중 신고 → 해소. 마킹·라벨은 그대로 두고 프레임 이미지만 새 비식별본으로 교체한다.
        LsDataRaw r = raw(9910L, LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findById(9910L)).thenReturn(Optional.of(r));
        stubDeidPath(9910L, "/nas/videos/9910/001-mask.mp4");
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(7);

        // when
        int attached = service.resumeLabeling(9910L);

        // then — ① 기존 LS_DATA_SRC 행을 갱신하는 DeidentFrameAttacher 로 재추출(SRC_SN 보존 = 라벨 FK 유지).
        //   ⚠ FfmpegFrameExtractor 를 쓰면 새 행이 INSERT 되어 기존 라벨이 고아가 된다.
        assertThat(attached).isEqualTo(7);
        ArgumentCaptor<Path> pathCap = ArgumentCaptor.forClass(Path.class);
        verify(deidentFrameAttacher).attachDeidentFrames(eq(r), pathCap.capture(), eq(true));
        // ② 비식별 영상 경로는 DB 적재값(DE_IDNTF_FILE_PATH_NM) 그대로 — 파일명을 조합·추측하지 않는다.
        assertThat(pathCap.getValue().toString()).isEqualTo("/nas/videos/9910/001-mask.mp4");

        // ③ 마킹은 건드리지 않는다(마킹 유지) — 배치 단계도 되감지 않는다.
        verify(markingRepository, never()).findByRawSnAndSttsCdIn(anyLong(), any());
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
    }

    @Test
    @DisplayName("라벨링단계_재개는_비식별경로가_없으면_추측하지_않고_건너뛴다")
    void labelingResumeSkipsWhenNoRecordedPath() {
        LsDataRaw r = raw(9911L, LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findById(9911L)).thenReturn(Optional.of(r));
        when(procLogRepository.findLatestSuccessByDataRawSn(9911L)).thenReturn(Optional.empty());

        assertThat(service.resumeLabeling(9911L)).isZero();
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
    }

    // ---------- 라우팅 ----------

    @Test
    @DisplayName("단계코드에_따라_재개_지점이_갈린다")
    void resumeRoutesByStage() {
        LsDataRaw marking = raw(9920L, LsDataRaw.DATA_STTS_PROCESSING);
        when(videoRepository.findById(9920L)).thenReturn(Optional.of(marking));
        when(markingRepository.findByRawSnAndSttsCdIn(eq(9920L), any())).thenReturn(List.of());
        LsDataRaw labeling = raw(9921L, LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findById(9921L)).thenReturn(Optional.of(labeling));
        stubDeidPath(9921L, "/nas/videos/9921/deidentified.mp4");

        service.resume(9920L, LsDeidentReport.STAGE_MARKING);
        service.resume(9921L, LsDeidentReport.STAGE_LABELING);

        assertThat(marking.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(deidentFrameAttacher).attachDeidentFrames(eq(labeling), any(), eq(true));
        // 마킹 경로는 프레임을 건드리지 않는다.
        verify(deidentFrameAttacher, never()).attachDeidentFrames(eq(marking), any(), anyBoolean());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
