package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.AutoLabelInfoProjection;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private VideoQueryService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        MngResourceCctvRepository cctvRepository = mock(MngResourceCctvRepository.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        LsRawDataStatusRepository rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        LsTaskAssignmentRepository taskAssignmentRepository = mock(LsTaskAssignmentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        LsDeidentProcLogRepository deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        service = new VideoQueryService(videoRepository, cctvRepository, srcRepository, lblRepository,
                rawDataStatusRepository, taskAssignmentRepository, userRepository, deidentProcLogRepository);
    }

    private LsDataRaw raw(Long rawSn) {
        return LsDataRaw.createFromIngest("clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
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
}
