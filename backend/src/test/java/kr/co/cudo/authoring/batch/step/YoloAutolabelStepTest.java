package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoloAutolabelStepTest {

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private VideoRepository videoRepository;
    private YoloAutolabelStep step;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        videoRepository = mock(VideoRepository.class);

        // Create temp directory and dummy image files
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve("10.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8}); // JPEG header
        Files.write(rawDir.resolve("11.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("20.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("30.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("40.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("50.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});

        // 기본은 fail-safe (필터 미적용) 동작을 위해 빈 Optional
        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());

        step = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository, videoRepository,
                new ObjectMapper(), rawDir.toString());
    }

    private LsDataSrc newSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(1L, srcSn.intValue(), srcSn + ".jpg", null);
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(src, srcSn);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        return src;
    }

    private LsDataRaw rawWithEvent(String evntTypeCd) {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getEvntTypeCd()).thenReturn(evntTypeCd);
        return raw;
    }

    @Test
    @DisplayName("YOLO_검출_결과는_AUTO_LBL_YN_Y_+_BBOX_타입_+_score_0_1_저장")
    void detectionsSavedAsAutoBbox() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81)
                ))));

        int saved = step.run(1L);

        assertThat(saved).isEqualTo(4); // 2 frames × 2 detections

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        captor.getAllValues().forEach(lbl -> {
            assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
            assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
            assertThat(lbl.getConfScore()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
    }

    @Test
    @DisplayName("YOLO_검출_결과_없으면_라벨_미저장")
    void noDetectionsNoSaves() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(2L))
                .thenReturn(List.of(newSrc(20L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of())));

        int saved = step.run(2L);

        assertThat(saved).isZero();
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("ai_server_예외시_EXTERNAL_API_ERROR")
    void externalErrorWrapped() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(3L))
                .thenReturn(List.of(newSrc(30L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("ai-server down")));

        assertThatThrownBy(() -> step.run(3L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR");
    }

    @Test
    @DisplayName("EVT_FALL_영상은_person만_저장_car는_필터링")
    void evtFallFiltersToPersonOnly() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(4L)).thenReturn(Optional.of(rawMock));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(4L))
                .thenReturn(List.of(newSrc(40L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("Person", List.of(9.0, 10.0, 11.0, 12.0), 0.75)
                ))));

        int saved = step.run(4L);

        // person 2건 통과 (대소문자 정규화), car 1건 필터링
        assertThat(saved).isEqualTo(2);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        captor.getAllValues().forEach(lbl ->
                assertThat(lbl.getLabel().toLowerCase()).isEqualTo("person"));
    }

    @Test
    @DisplayName("EVT_ACCIDENT_영상은_차량_사람_오토바이_통과")
    void evtAccidentAllowsVehiclesAndPerson() {
        LsDataRaw rawMock = rawWithEvent("EVT_ACCIDENT");
        when(videoRepository.findById(5L)).thenReturn(Optional.of(rawMock));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(5L))
                .thenReturn(List.of(newSrc(50L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("car", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("motorcycle", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("person", List.of(9.0, 10.0, 11.0, 12.0), 0.75),
                        new YoloResponse.Detection("trash", List.of(13.0, 14.0, 15.0, 16.0), 0.55)
                ))));

        int saved = step.run(5L);

        // car/motorcycle/person 통과, trash 필터링
        assertThat(saved).isEqualTo(3);
    }

    @Test
    @DisplayName("eventTypeCd_null이면_fail_safe로_전체_통과")
    void nullEventTypeFailSafeAllowsAll() {
        // videoRepository.findById -> Optional.empty() (setUp 기본값)
        when(srcRepository.findByRawSnOrderByFrameNoAsc(6L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("trash", List.of(9.0, 10.0, 11.0, 12.0), 0.55)
                ))));

        int saved = step.run(6L);

        // 매핑 없음 → 전체 통과
        assertThat(saved).isEqualTo(3);
    }
}
