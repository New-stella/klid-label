package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.AutoLabelInfoProjection;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.batch.status.BatchStageProgressMapper;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import kr.co.cudo.authoring.video.service.VideoResolutionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug 1 — getAutoLabels 가 LS_DATA_LBL_AI_INFO 의 auto/manual 구분과 신뢰도를 정확히 반환하는지 검증.
 *
 * <p>auto/manual·신뢰도는 LS_DATA_LBL 본체가 아닌 LS_DATA_LBL_AI_INFO 에 있다(transient 함정).
 * 따라서 서비스는 라벨+AI 메타를 함께 투영하는 findAutoLabelInfoByRawSn 를 사용해야 한다.
 *
 * <p><b>표시명·표시색 축</b>([design: API-044]) — 구 동작은 저장된 라벨명을 labelCode·labelName 두
 * 필드에 복사하고 색을 상수(#3B82F6)로 고정했다. AI 가 쓴 라벨명이 COCO 영문 클래스명이라 화면이
 * 영문만 보여주고 막대가 전부 같은 색이었다. 이제 라벨 마스터(LS_LABEL)를 배치 조회해 조달하며,
 * 미연결·비활성은 저장 원문 + 색 없음으로 수렴한다. 마스터 조회는 라벨 종류 수에 비례해 늘지 않는다.
 */
class VideoQueryServiceAutoLabelTest {

    private VideoRepository videoRepository;
    private LsDataLblRepository lblRepository;
    private IngestSourceRepository ingestSourceRepository;
    private LsDataSrcRepository srcRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private BatchStatusService batchStatusService;
    private LsLabelRepository labelMasterRepository;
    private VideoQueryService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        labelMasterRepository = mock(LsLabelRepository.class);
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
                rawDataStatusRepository, taskAssignmentRepository,
                new kr.co.cudo.authoring.user.service.UserNameResolver(userRepository), deidentProcLogRepository,
                batchStatusService, fpsResolver,
                // 해상도 표시값 조달(video.resolution) — 본 테스트는 오토라벨 축만 다뤄 미사용(기본 null).
                mock(VideoResolutionResolver.class), eventTypeService,
                mock(kr.co.cudo.authoring.assignment.service.ReviewApprovalGate.class),
                mock(kr.co.cudo.authoring.batch.status.BatchBundleFailureGate.class),
                // 검증 이벤트 질문 목록 조달(마킹 화면용) — 본 테스트는 유형 미수신 경로만 지나가
                //   질문 조회가 호출되지 않는다(정규화 결과가 null 이면 조회 자체를 하지 않는다).
                mock(kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository.class),
                labelMasterRepository);
    }

    private LsDataRaw raw(Long rawSn) {
        LsDataRaw entity = LsDataRaw.createFromIngest("clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        // createFromIngest 는 PK(rawSn) 를 세팅하지 않으므로(DB 생성 PK) 테스트에서 명시 주입.
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "rawSn", rawSn);
        return entity;
    }

    /** 마스터 미연결(labelId=null) 라벨 투영 — 기존 케이스가 쓰던 모양 그대로. */
    private AutoLabelInfoProjection proj(Long lblSn, String labelNm, String autoLblYn, BigDecimal conf) {
        return proj(lblSn, labelNm, null, autoLblYn, conf);
    }

    /** 마스터 연결 여부까지 지정하는 투영 — labelId 가 null 이면 미연결. */
    private AutoLabelInfoProjection proj(Long lblSn, String labelNm, Long labelId,
                                         String autoLblYn, BigDecimal conf) {
        return new AutoLabelInfoProjection() {
            @Override public Long getLblSn() { return lblSn; }
            @Override public String getLabelNm() { return labelNm; }
            @Override public Long getLabelId() { return labelId; }
            @Override public String getAutoLblYn() { return autoLblYn; }
            @Override public BigDecimal getConfScore() { return conf; }
        };
    }

    /** 활성 라벨 마스터 행 — 배치 조회 응답 stub 용. */
    private LsLabel master(Long labelId, String name, String color) {
        LsLabel label = LsLabel.create(name, color, "BBOX", 0, null, "tester");
        org.springframework.test.util.ReflectionTestUtils.setField(label, "labelId", labelId);
        return label;
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

    // ─── [design: API-044] 표시명·표시색은 라벨 마스터가 단일 진실원 ───

    @Test
    @DisplayName("마스터에_연결된_라벨은_마스터_등록명과_등록색상을_내려준다")
    void linkedLabel_usesMasterNameAndColor() {
        // given — AI 가 쓴 라벨명은 COCO 영문("person")인데 마스터 등록명은 한글
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(101L, "person", 7L, "Y", new BigDecimal("0.92"))));
        when(labelMasterRepository.findByLabelIdInAndUseYn(anyCollection(), eq("Y")))
                .thenReturn(List.of(master(7L, "사람", "#E11D48")));

        // when
        AutoLabelResultResponse.LabelObjectDto dto = service.getAutoLabels(8L).objects().get(0);

        // then — 표시명·색은 마스터 값. labelCode 는 저장 원문 그대로(그룹핑 키라 불변).
        assertThat(dto.labelName()).isEqualTo("사람");
        assertThat(dto.color()).isEqualTo("#E11D48");
        assertThat(dto.labelCode()).isEqualTo("person");
    }

    @Test
    @DisplayName("라벨마다_마스터_색상이_달라_분포_막대의_색이_갈린다")
    void linkedLabels_haveDistinctColors() {
        // given — 마스터 2종(서로 다른 색)에 연결된 라벨 2건
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(1L, "person", 7L, "Y", new BigDecimal("0.90")),
                proj(2L, "car", 8L, "Y", new BigDecimal("0.81"))));
        when(labelMasterRepository.findByLabelIdInAndUseYn(anyCollection(), eq("Y")))
                .thenReturn(List.of(master(7L, "사람", "#E11D48"), master(8L, "승용차", "#2563EB")));

        // when
        List<AutoLabelResultResponse.LabelObjectDto> objects = service.getAutoLabels(8L).objects();

        // then — 구 동작은 두 라벨이 같은 상수색이었다. 이제 마스터 색으로 갈린다.
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::color)
                .containsExactly("#E11D48", "#2563EB");
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::labelName)
                .containsExactly("사람", "승용차");
    }

    @Test
    @DisplayName("마스터_미연결_라벨은_저장된_원문을_그대로_내려주고_색은_null_이다")
    void unlinkedLabel_keepsRawNameAndNullColor() {
        // given — labelId 가 null 인 라벨(마스터 미연결)
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(301L, "bicycle", null, "Y", new BigDecimal("0.78"))));

        // when
        AutoLabelResultResponse.LabelObjectDto dto = service.getAutoLabels(8L).objects().get(0);

        // then — 이름을 지어내지 않고(원문 유지) 빈칸도 아니다. 색은 서버가 만들지 않는다(화면 폴백).
        assertThat(dto.labelName()).isEqualTo("bicycle");
        assertThat(dto.labelName()).isNotBlank();
        assertThat(dto.color()).isNull();
        // 미연결이면 조회할 id 가 없으므로 마스터 조회 자체를 하지 않는다.
        verify(labelMasterRepository, never()).findByLabelIdInAndUseYn(anyCollection(), anyString());
    }

    @Test
    @DisplayName("비활성_soft_delete_마스터는_미연결과_동일하게_원문과_색상없음으로_내려간다")
    void inactiveMaster_isTreatedAsUnlinked() {
        // given — labelId 는 있으나 그 마스터가 USE_YN='N' 이라 활성 조회 결과가 비어 있다
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(401L, "person", 9L, "Y", new BigDecimal("0.55"))));
        when(labelMasterRepository.findByLabelIdInAndUseYn(anyCollection(), eq("Y")))
                .thenReturn(List.of());

        // when
        AutoLabelResultResponse.LabelObjectDto dto = service.getAutoLabels(8L).objects().get(0);

        // then — 삭제한 라벨의 이름·색이 화면에 되살아나지 않는다
        assertThat(dto.labelName()).isEqualTo("person");
        assertThat(dto.color()).isNull();
        // ⚠ 위 두 단언만으로는 이 케이스가 성립하지 않는다 — 조회가 활성 필터를 빼고(findAllById 등)
        //   비활성 마스터를 집어와도 stub 이 빈 목록이라 똑같이 통과한다. 실제로 활성만 조회하는지를
        //   인자로 직접 고정한다.
        verify(labelMasterRepository).findByLabelIdInAndUseYn(anyCollection(), eq("Y"));
    }

    @Test
    @DisplayName("라벨_종류가_늘어도_마스터_조회_횟수는_1회로_동일하다_N더하기1_없음")
    void masterLookup_doesNotScaleWithLabelCount() {
        // given — 같은 서비스로 라벨 1종 / 6종을 각각 조회해 마스터 조회 호출을 실제로 센다.
        //   (주장이 아니라 관측이다 — Answer 가 호출될 때마다 카운터를 올린다.)
        AtomicInteger masterLookupCalls = new AtomicInteger();
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(labelMasterRepository.findByLabelIdInAndUseYn(anyCollection(), eq("Y")))
                .thenAnswer(inv -> {
                    masterLookupCalls.incrementAndGet();
                    return List.of(master(1L, "사람", "#E11D48"));
                });

        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(10L, "person", 1L, "Y", new BigDecimal("0.90"))));
        service.getAutoLabels(8L);
        int callsForOneKind = masterLookupCalls.getAndSet(0);

        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(20L, "person", 1L, "Y", new BigDecimal("0.90")),
                proj(21L, "car", 2L, "Y", new BigDecimal("0.81")),
                proj(22L, "bus", 3L, "Y", new BigDecimal("0.72")),
                proj(23L, "truck", 4L, "Y", new BigDecimal("0.63")),
                proj(24L, "dog", 5L, "Y", new BigDecimal("0.54")),
                proj(25L, "cat", 6L, "Y", new BigDecimal("0.45"))));
        service.getAutoLabels(8L);
        int callsForSixKinds = masterLookupCalls.get();

        // then — 라벨 종류가 6배가 돼도 조회 수는 그대로 1회다(비례하지 않는다)
        assertThat(callsForOneKind).isEqualTo(1);
        assertThat(callsForSixKinds).isEqualTo(callsForOneKind);
    }

    @Test
    @DisplayName("같은_마스터를_가리키는_라벨이_여러건이면_id_를_중복_제거해_한_번만_조회한다")
    void duplicateLabelIds_areDeduplicated() {
        // given — 같은 labelId(7)를 가리키는 라벨 3건
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(1L, "person", 7L, "Y", new BigDecimal("0.90")),
                proj(2L, "person", 7L, "Y", new BigDecimal("0.81")),
                proj(3L, "person", 7L, null, null)));
        when(labelMasterRepository.findByLabelIdInAndUseYn(anyCollection(), eq("Y")))
                .thenReturn(List.of(master(7L, "사람", "#E11D48")));

        // when
        List<AutoLabelResultResponse.LabelObjectDto> objects = service.getAutoLabels(8L).objects();

        // then — 조회 1회 + 넘긴 id 집합에 중복 없음. 세 라벨 모두 같은 마스터 값을 받는다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(labelMasterRepository, times(1)).findByLabelIdInAndUseYn(captor.capture(), eq("Y"));
        assertThat(captor.getValue()).containsExactly(7L);
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::labelName)
                .containsOnly("사람");
    }

    @Test
    @DisplayName("라벨이_0건이면_빈_objects_이고_마스터를_조회하지_않는다")
    void noLabels_skipsMasterLookup() {
        // given
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of());

        // when
        AutoLabelResultResponse result = service.getAutoLabels(8L);

        // then
        assertThat(result.objects()).isEmpty();
        verify(labelMasterRepository, never()).findByLabelIdInAndUseYn(anyCollection(), anyString());
    }

    @Test
    @DisplayName("연결_미연결이_섞여도_각_라벨이_자기_조달처를_따른다")
    void mixedLinkage_resolvesPerLabel() {
        // given — 연결(7) + 미연결(null) 이 한 영상에 섞여 있다
        when(videoRepository.findById(8L)).thenReturn(Optional.of(raw(8L)));
        when(lblRepository.findAutoLabelInfoByRawSn(8L)).thenReturn(List.of(
                proj(1L, "person", 7L, "Y", new BigDecimal("0.90")),
                proj(2L, "bicycle", null, "Y", new BigDecimal("0.78"))));
        when(labelMasterRepository.findByLabelIdInAndUseYn(anyCollection(), eq("Y")))
                .thenReturn(List.of(master(7L, "사람", "#E11D48")));

        // when
        List<AutoLabelResultResponse.LabelObjectDto> objects = service.getAutoLabels(8L).objects();

        // then
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::labelName)
                .containsExactly("사람", "bicycle");
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::color)
                .containsExactly("#E11D48", null);
        // 정렬(LBL_SN ASC)·createdBy·confidence 는 이번 변경 대상이 아니다 — 그대로인지 함께 고정.
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::id)
                .containsExactly("1", "2");
        assertThat(objects).extracting(AutoLabelResultResponse.LabelObjectDto::createdBy)
                .containsOnly("auto");
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
