package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.AutoLabelInfoProjection;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.batch.status.BatchStageProgressMapper;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bug 1 — getAutoLabels 가 LS_DATA_LBL_AI_INFO 의 auto/manual 구분과 신뢰도를 정확히 반환하는지 검증.
 *
 * <p>auto/manual·신뢰도는 LS_DATA_LBL 본체가 아닌 LS_DATA_LBL_AI_INFO 에 있다(transient 함정).
 * 따라서 서비스는 라벨+AI 메타를 함께 투영하는 findAutoLabelInfoByRawSn 를 사용해야 한다.
 */
class VideoQueryServiceAutoLabelTest {

    private VideoRepository videoRepository;
    private LsDataLblRepository lblRepository;
    private IngestSourceRepository ingestSourceRepository;
    private LsDataSrcRepository srcRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private BatchStatusService batchStatusService;
    private VideoQueryService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        LsTaskAssignmentRepository taskAssignmentRepository = mock(LsTaskAssignmentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        LsDeidentProcLogRepository deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        // DEV_FIX(H10) — 영상 상세는 마킹 화면 정합용으로 실 fps 를 함께 내린다(FE 가 같은 값으로 frameIndex 산출).
        VideoFpsResolver fpsResolver = mock(VideoFpsResolver.class);
        // 목록 이벤트유형 필터(카테고리 키 → EV-코드 변환) 의존 — 본 테스트는 상세/오토라벨만 다뤄 미사용.
        kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService =
                mock(kr.co.cudo.authoring.eventtype.service.EventTypeService.class);
        service = new VideoQueryService(videoRepository, ingestSourceRepository, srcRepository, lblRepository,
                rawDataStatusRepository, taskAssignmentRepository, userRepository, deidentProcLogRepository,
                batchStatusService, fpsResolver, eventTypeService);
    }

    private LsDataRaw raw(Long rawSn) {
        LsDataRaw entity = LsDataRaw.createFromIngest("clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        // createFromIngest 는 PK(rawSn) 를 세팅하지 않으므로(DB 생성 PK) 테스트에서 명시 주입.
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "rawSn", rawSn);
        return entity;
    }

    private AutoLabelInfoProjection proj(Long lblSn, String labelNm, String autoLblYn, BigDecimal conf) {
        return new AutoLabelInfoProjection() {
            @Override public Long getLblSn() { return lblSn; }
            @Override public String getLabelNm() { return labelNm; }
            @Override public String getAutoLblYn() { return autoLblYn; }
            @Override public BigDecimal getConfScore() { return conf; }
        };
    }

    @Test
    @DisplayName("영상에_YOLO_자동라벨이_있으면_getAutoLabels_가_createdBy_auto_와_confidence_를_반환한다")
    void autoLabel_returnsAutoAndConfidence() {
        // given — AI_INFO(auto_lbl_yn='Y', conf_score) 가 있는 자동 라벨
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(101L, "person", "Y", new BigDecimal("0.92"))));

        // when
        AutoLabelResultResponse result = service.getAutoLabels(8L);

        // then
        assertThat(result.objects()).hasSize(1);
        AutoLabelResultResponse.LabelObjectDto dto = result.objects().get(0);
        assertThat(dto.createdBy()).isEqualTo("auto");
        assertThat(dto.confidence()).isNotNull();
        assertThat(dto.confidence()).isEqualTo(0.92);
    }

    @Test
    @DisplayName("수동라벨은_createdBy_manual_로_반환된다")
    void manualLabel_returnsManual() {
        // given — AI_INFO 없는(autoLblYn=null) 수동 라벨
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(201L, "car", null, null)));

        // when
        AutoLabelResultResponse result = service.getAutoLabels(8L);

        // then
        AutoLabelResultResponse.LabelObjectDto dto = result.objects().get(0);
        assertThat(dto.createdBy()).isEqualTo("manual");
        assertThat(dto.confidence()).isNull();
    }

    @Test
    @DisplayName("auto_라벨_개수가_ai_info_의_실제_auto_개수와_일치한다")
    void autoCount_matchesAiInfoAutoCount() {
        // given — auto 2건 + manual 1건
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(1L, "person", "Y", new BigDecimal("0.90")),
                proj(2L, "person", "Y", new BigDecimal("0.81")),
                proj(3L, "car", null, null)));

        // when
        AutoLabelResultResponse result = service.getAutoLabels(8L);

        // then — createdBy='auto' 개수 = ai_info auto 개수(2)
        long autoCount = result.objects().stream()
                .filter(o -> "auto".equals(o.createdBy()))
                .count();
        assertThat(autoCount).isEqualTo(2);
    }

    @Test
    @DisplayName("getOne_은_BatchStatusService_단계리스트를_stages_로_노출한다")
    void getOne_exposesStages() {
        // given — 프레임추출 진행 중(비식별/마킹/VLM 완료)
        when(videoRepository.findById(9L)).thenReturn(Optional.of(raw(9L)));
        when(srcRepository.countByRawSn(9L)).thenReturn(0L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9L)).thenReturn(List.of());
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(9L))).thenReturn(List.of());
        when(batchStatusService.stagesFor(eq(9L), anyBoolean())).thenReturn(List.of(
                new BatchStageProgressMapper.StageStatus("DEIDENTIFY", "DONE", null),
                new BatchStageProgressMapper.StageStatus("MARKING", "DONE", null),
                new BatchStageProgressMapper.StageStatus("VLM", "DONE", null),
                new BatchStageProgressMapper.StageStatus("FRAME_EXTRACT", "PROGRESS", null),
                new BatchStageProgressMapper.StageStatus("YOLO", "PENDING", null),
                new BatchStageProgressMapper.StageStatus("SAM2", "PENDING", null),
                new BatchStageProgressMapper.StageStatus("INTERPOLATE", "PENDING", null)));

        // when
        VideoDetailResponse res = service.getOne(9L);

        // then — canonical 순서·상태가 DTO 로 전달된다
        assertThat(res.stages()).hasSize(7);
        assertThat(res.stages().get(3).name()).isEqualTo("FRAME_EXTRACT");
        assertThat(res.stages().get(3).status()).isEqualTo("PROGRESS");
        assertThat(res.stages().get(0).status()).isEqualTo("DONE");
        assertThat(res.stages().get(6).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("getOne_은_단계로그가_없으면_빈_stages_를_반환한다_배지폴백")
    void getOne_emptyStagesWhenNoLog() {
        // given — 배치 로그 없음(빈 리스트)
        when(videoRepository.findById(10L)).thenReturn(Optional.of(raw(10L)));
        when(srcRepository.countByRawSn(10L)).thenReturn(0L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(10L)).thenReturn(List.of());
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(10L))).thenReturn(List.of());
        when(batchStatusService.stagesFor(eq(10L), anyBoolean())).thenReturn(List.of());

        // when
        VideoDetailResponse res = service.getOne(10L);

        // then — 예외 없이 빈 배열
        assertThat(res.stages()).isEmpty();
    }
}
