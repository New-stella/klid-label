package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.dto.TaskLabelsResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskMetaResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskSummaryResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TaskQueryService 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class TaskQueryServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsDataMetaRepository metaRepository;

    @InjectMocks private TaskQueryService taskQueryService;

    private LsDataRaw sampleRaw;
    private LsDataSrc frame0;
    private LsDataSrc frame1;

    @BeforeEach
    void setUp() {
        sampleRaw = LsDataRaw.createFromIngest(
                "CLIP-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        // rawSn is auto-generated, so we use reflection to set it for unit test
        setField(sampleRaw, "rawSn", 100L);

        frame0 = LsDataSrc.create(100L, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        setField(frame0, "srcSn", 10L);
        frame1 = LsDataSrc.create(100L, 1, "/var/raw/frame_1.jpg", LocalDateTime.now());
        setField(frame1, "srcSn", 11L);
    }

    // ==================== getSummary ====================

    @Test
    @DisplayName("getSummary_정상_라벨_메타_카운트_정확")
    void getSummary_normal_countsCorrect() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.countByRawSn(100L)).thenReturn(2L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(frame0, frame1));

        LsDataLbl lbl1 = createLabel(10L, "BBOX", "person", "[10,10,50,50]");
        setField(lbl1, "lblSn", 1L);
        LsDataLbl lbl2 = createLabel(10L, "BBOX", "car", "[20,20,60,60]");
        setField(lbl2, "lblSn", 2L);
        LsDataLbl lbl3 = createLabel(11L, "POLYGON", "tree", "[{\"x\":1,\"y\":2}]");
        setField(lbl3, "lblSn", 3L);

        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl1, lbl2, lbl3));

        LsDataMeta meta1 = LsDataMeta.create(100L, "weather", "sunny");
        LsDataMeta meta2 = LsDataMeta.create(100L, "time", "morning");
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of(meta1, meta2));

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.rawSn()).isEqualTo(100L);
        assertThat(response.totalFrames()).isEqualTo(2);
        assertThat(response.labeledFrames()).isEqualTo(2); // both frames have labels
        assertThat(response.totalLabels()).isEqualTo(3);
        assertThat(response.totalMeta()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("PENDING"); // default status from createFromIngest
    }

    @Test
    @DisplayName("getSummary_프레임_없는_영상_0_반환")
    void getSummary_noFrames_returnsZeros() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.countByRawSn(100L)).thenReturn(0L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of());
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.totalFrames()).isZero();
        assertThat(response.labeledFrames()).isZero();
        assertThat(response.totalLabels()).isZero();
        assertThat(response.totalMeta()).isZero();
    }

    @Test
    @DisplayName("getSummary_미존재_rawSn_NOT_FOUND_예외")
    void getSummary_notFound() {
        // given
        when(videoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> taskQueryService.getSummary(999L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== getLabels ====================

    @Test
    @DisplayName("getLabels_전체_프레임_라벨_반환")
    void getLabels_allFrames() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(frame0, frame1));

        LsDataLbl lbl1 = createLabel(10L, "BBOX", "person", "[10,10,50,50]");
        setField(lbl1, "lblSn", 1L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl1));

        // when
        List<TaskLabelsResponse> result = taskQueryService.getLabels(100L, null);

        // then
        assertThat(result).hasSize(2); // 2 frames
        TaskLabelsResponse firstFrame = result.stream()
                .filter(r -> r.srcSn().equals(10L))
                .findFirst().orElseThrow();
        assertThat(firstFrame.labels()).hasSize(1);
        assertThat(firstFrame.labels().get(0).label()).isEqualTo("person");
    }

    @Test
    @DisplayName("getLabels_frameIds_필터_적용")
    void getLabels_filteredByFrameIds() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when -- filter to only frame with srcSn=10
        List<TaskLabelsResponse> result = taskQueryService.getLabels(100L, List.of(10L));

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).srcSn()).isEqualTo(10L);
    }

    @Test
    @DisplayName("getLabels_빈_frameIds_전체_반환")
    void getLabels_emptyFrameIds_returnsAll() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        List<TaskLabelsResponse> result = taskQueryService.getLabels(100L, List.of());

        // then
        assertThat(result).hasSize(2); // empty list = no filter = all frames
    }

    @Test
    @DisplayName("getLabels_미존재_rawSn_NOT_FOUND_예외")
    void getLabels_notFound() {
        // given
        when(videoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> taskQueryService.getLabels(999L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== getMeta ====================

    @Test
    @DisplayName("getMeta_정상_메타_목록_반환")
    void getMeta_normal() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        LsDataMeta meta1 = LsDataMeta.create(100L, "weather", "sunny");
        setField(meta1, "metaSn", 1L);
        LsDataMeta meta2 = LsDataMeta.create(100L, "time", "morning");
        setField(meta2, "metaSn", 2L);
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of(meta1, meta2));

        // when
        TaskMetaResponse response = taskQueryService.getMeta(100L);

        // then
        assertThat(response.rawSn()).isEqualTo(100L);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).metaKey()).isEqualTo("weather");
        assertThat(response.items().get(0).metaVal()).isEqualTo("sunny");
    }

    @Test
    @DisplayName("getMeta_메타_없는_영상_빈_목록")
    void getMeta_noMeta_emptyList() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());

        // when
        TaskMetaResponse response = taskQueryService.getMeta(100L);

        // then
        assertThat(response.rawSn()).isEqualTo(100L);
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("getMeta_미존재_rawSn_NOT_FOUND_예외")
    void getMeta_notFound() {
        // given
        when(videoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> taskQueryService.getMeta(999L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== Helpers ====================

    private LsDataLbl createLabel(Long srcSn, String type, String label, String points) {
        return LsDataLbl.createManual(srcSn, type, null, label, points, 1L);
    }

    /**
     * 테스트용 리플렉션 필드 설정 — @Id @GeneratedValue 필드에 값 주입.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
