package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoResolutionPersister;
import kr.co.cudo.authoring.video.service.VideoResolutionService;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 변경 서비스 단위 테스트 (R1 정책) — ImageResizer 는 stub/mock 주입(바이너리 비의존).
 * 산출물 = 다운스케일 프레임 이미지셋 + LS_RESOLUTION_EXPORT 1행. 라벨/메타/신규 영상은 생성하지 않는다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class VideoResolutionServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock LsRawDataStatusRepository statusRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsResolutionExportRepository exportRepository;
    @Mock ImageResizer imageResizer;
    @Mock VideoResolutionPersister persister;

    VideoResolutionService service;
    Path storageBase;

    @BeforeEach
    void setup() throws Exception {
        // 격리된 임시 storage base — 실제 프레임 파일을 만들어 Files.exists 통과
        storageBase = Files.createTempDirectory("res-test-");
        service = new VideoResolutionService(videoRepository, statusRepository, srcRepository,
                exportRepository, imageResizer, persister);
        ReflectionTestUtils.setField(service, "storageRawPath", storageBase.toString());
        ReflectionTestUtils.setField(service, "resizeMaxConcurrent", 2);
        ReflectionTestUtils.setField(service, "resizeAcquireTimeoutSec", 5L);

        // 기본: 원본 1920x1080, 중복 없음, 프레임 3건
        when(imageResizer.readDimensions(any(Path.class))).thenReturn(new int[]{1920, 1080});
        when(exportRepository.existsByDataRawSnAndTargetResCd(any(), anyString())).thenReturn(false);
        when(persister.persist(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(new ResolutionChangeResponse(777L, 1920, 1080, 1280, 720, 3));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LsDataRaw raw(Long rawSn, Long orgnlRawSn) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, rawSn + ".mp4", null, 60);
        setField(r, "rawSn", rawSn);
        if (orgnlRawSn != null) {
            setField(r, "orgnlRawSn", orgnlRawSn);
        }
        return r;
    }

    /** storage base 하위 실제 프레임 파일 생성 후 LsDataSrc 반환. */
    private LsDataSrc frame(Long rawSn, int frameNo) {
        try {
            Path dir = storageBase.resolve("frames").resolve(String.valueOf(rawSn));
            Files.createDirectories(dir);
            Path file = dir.resolve("f" + frameNo + ".jpg");
            Files.write(file, new byte[]{1, 2, 3});
            return LsDataSrc.create(rawSn, frameNo, file.toString(), null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void framesAvailable(Long rawSn, int count) {
        List<LsDataSrc> frames = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            frames.add(frame(rawSn, i));
        }
        when(srcRepository.countByRawSn(rawSn)).thenReturn((long) count);
        when(srcRepository.findByRawSnAndFrameNo(eq(rawSn), eq(0))).thenReturn(Optional.of(frames.get(0)));
        Page<LsDataSrc> page = new PageImpl<>(frames);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(rawSn), any())).thenReturn(page);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)).thenReturn(frames);
    }

    private void approved(Long rawSn) {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        st.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(statusRepository.findByRawDataIdIn(any())).thenReturn(List.of(st));
    }

    private ResolutionChangeRequest req(ResolutionPreset p) {
        return new ResolutionChangeRequest(p);
    }

    private ResolutionChangeResponse call(Long rawSn, ResolutionPreset p) {
        return service.changeResolution(rawSn, req(p), "reviewer-1");
    }

    @Test
    @DisplayName("업스케일_요청시_400_거부")
    void upscale_400() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 3);
        // 원본 480x270 인데 RES_1080P(1080) 요청 → 업스케일
        when(imageResizer.readDimensions(any(Path.class))).thenReturn(new int[]{480, 270});

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_1080P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(persister, never()).persist(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("원본과_동일_해상도_요청시_400_거부")
    void sameResolution_400() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 3);
        // 원본 1280x720, RES_720P(720) 요청 → targetH==srcH → 거부
        when(imageResizer.readDimensions(any(Path.class))).thenReturn(new int[]{1280, 720});

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("다운스케일_성공시_이미지셋_생성_및_EXPORT_1행_기록")
    void downscale_success() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 3);

        ResolutionChangeResponse res = call(1L, ResolutionPreset.RES_720P);

        assertThat(res.exportSn()).isEqualTo(777L);
        // 프레임 3건 다운스케일
        verify(imageResizer, atLeastOnce()).resize(any(), any(), anyInt(), anyInt());
        // EXPORT 1행 INSERT (frameCount=3)
        ArgumentCaptor<Integer> frameCap = ArgumentCaptor.forClass(Integer.class);
        verify(persister).persist(any(), eq(ResolutionPreset.RES_720P), anyString(),
                eq(1920), eq(1080), anyInt(), eq(720), frameCap.capture(), eq("reviewer-1"));
        assertThat(frameCap.getValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("해상도_변경_결과에_라벨이_복사되지_않음")
    void noLabelCopy() {
        // 라벨/속성/메타 리포지토리는 서비스 의존성에 아예 없음 → 복사 경로 자체가 제거됨을 구조로 보장.
        // 동작 검증: 성공 응답에 라벨 필드가 없고(타입상), EXPORT 1행만 기록.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 2);

        ResolutionChangeResponse res = call(1L, ResolutionPreset.RES_480P);

        assertThat(res.exportSn()).isNotNull();
        verify(persister).persist(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("새_PENDING_영상이_생성되지_않음")
    void noNewRawCreated() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 2);

        call(1L, ResolutionPreset.RES_480P);

        // videoRepository.save 가 절대 호출되지 않아야 함 (신규 LS_DATA_RAW 미생성)
        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("동일_영상_동일_해상도_중복_요청시_409")
    void duplicate_409() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        when(exportRepository.existsByDataRawSnAndTargetResCd(1L, "RES_720P")).thenReturn(true);

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(imageResizer, never()).resize(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("동시_요청_UK경합_DataIntegrityViolation시_409")
    void ukRace_409() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 2);
        when(persister.persist(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
                .thenThrow(new DataIntegrityViolationException("uk violation"));

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("미검수_영상_요청시_409")
    void notApproved_409() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        LsRawDataStatus st = LsRawDataStatus.initial(1L);
        st.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        when(statusRepository.findByRawDataIdIn(any())).thenReturn(List.of(st));

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("영상_미존재_시_404")
    void notFound_404() {
        when(videoRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> call(404L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("증강본_요청시_400")
    void nestedAugment_400() {
        when(videoRepository.findById(2L)).thenReturn(Optional.of(raw(2L, 1L)));

        assertThatThrownBy(() -> call(2L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(imageResizer, never()).resize(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("프레임_0건_영상_요청시_400")
    void zeroFrames_400() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        when(srcRepository.countByRawSn(1L)).thenReturn(0L);

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(persister, never()).persist(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("프레임_중간_다운스케일_실패시_출력_디렉토리_정리되고_EXPORT_행_미생성")
    void midFailure_cleansDir() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 3);

        Path outputDir = storageBase.toAbsolutePath().normalize()
                .resolve("resolution").resolve("1").resolve("RES_720P");

        // 첫 프레임은 성공(파일 생성), 두번째에서 실패
        org.mockito.Mockito.doAnswer(inv -> {
            Path dst = inv.getArgument(1);
            Files.write(dst, new byte[]{9, 9, 9});
            return null;
        }).doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 다운스케일 실패"))
                .when(imageResizer).resize(any(), any(), anyInt(), anyInt());

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class);

        // 출력 디렉토리 전체 정리
        assertThat(Files.exists(outputDir)).isFalse();
        verify(persister, never()).persist(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("손상_이미지_읽기_실패시_추상_메시지_오류")
    void corruptImage_abstractError() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 1);
        // 첫 프레임 실측에서 손상 이미지 → ImageResizer 가 추상 500
        when(imageResizer.readDimensions(any(Path.class)))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "프레임 이미지를 읽을 수 없습니다."));

        assertThatThrownBy(() -> call(1L, ResolutionPreset.RES_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("응답에_내부_파일_경로_미포함")
    void responseNoInternalPath() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        framesAvailable(1L, 2);

        ResolutionChangeResponse res = call(1L, ResolutionPreset.RES_480P);

        // 응답 record 필드: exportSn/srcW/srcH/targetW/targetH/frameCount 만 — 경로 필드 없음 (컴파일 시점 보장)
        assertThat(res.exportSn()).isNotNull();
        assertThat(res.targetH()).isEqualTo(720); // mock 응답값
    }
}
