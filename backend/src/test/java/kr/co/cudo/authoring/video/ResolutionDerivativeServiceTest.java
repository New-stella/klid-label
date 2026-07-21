package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionDerivativeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeService;
import kr.co.cudo.authoring.video.service.ResolutionReservationPersister;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 서비스(오케스트레이션) 단위 테스트 — Phase 2.
 * 파생 체인/검수/해상도 게이트 + 정상 위임을 검증한다(ImageResizer/Persister mock).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ResolutionDerivativeServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock LsRawDataStatusRepository statusRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock ImageResizer imageResizer;
    @Mock ResolutionReservationPersister reservationPersister;

    ResolutionDerivativeService service;

    @BeforeEach
    void setup() {
        service = new ResolutionDerivativeService(
                videoRepository, statusRepository, srcRepository, imageResizer, reservationPersister);
        ReflectionTestUtils.setField(service, "storageRawPath", "/tmp/klid-res-test");
    }

    private LsDataRaw approvedDeidParent(Long rawSn) {
        LsDataRaw parent = LsDataRaw.createFromIngest("CLIP-" + rawSn, "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/tmp/klid-res-test/videos/orig.mp4", LocalDateTime.now(), 30);
        parent.markDeidentified("Y");
        ReflectionTestUtils.setField(parent, "rawSn", rawSn);
        return parent;
    }

    private void seedApprovedFrame(Long rawSn) {
        LsDataSrc frame = LsDataSrc.create(rawSn, 0L, 0L, "frames/f0.jpg", LocalDateTime.now());
        when(srcRepository.findByRawSnAndFrameNo(rawSn, 0)).thenReturn(Optional.of(frame));
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        st.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(statusRepository.findByRawDataIdIn(List.of(rawSn))).thenReturn(List.of(st));
    }

    @Test
    @DisplayName("해상도변경시_새_RAW_SN이_생성되고_ORGNL_RAW_SN이_원본이며_PENDING이다")
    void happyPathDelegatesToPersisterAndReturnsScale() {
        Long parentRawSn = 100L;
        when(videoRepository.findById(parentRawSn)).thenReturn(Optional.of(approvedDeidParent(parentRawSn)));
        seedApprovedFrame(parentRawSn);
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        when(reservationPersister.reserveAndCreate(any(), eq(ResolutionPreset.RES_720P),
                anyInt(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ResolutionReservationPersister.Reservation(500L, 7L));

        ResolutionDerivativeResponse res = service.createDerivative(parentRawSn, ResolutionPreset.RES_720P, "rev1");

        assertThat(res.newRawSn()).isEqualTo(500L);
        assertThat(res.exportSn()).isEqualTo(7L);
        assertThat(res.srcW()).isEqualTo(1920);
        assertThat(res.srcH()).isEqualTo(1080);
        assertThat(res.targetW()).isEqualTo(1280);
        assertThat(res.targetH()).isEqualTo(720);
        // scaleX = 1280/1920, scaleY = 720/1080
        assertThat(res.scaleX()).isEqualTo(1280d / 1920d);
        assertThat(res.scaleY()).isEqualTo(720d / 1080d);
        verify(reservationPersister).reserveAndCreate(any(), eq(ResolutionPreset.RES_720P),
                eq(1920), eq(1080), eq(1280), eq(720), eq("rev1"));
    }

    @Test
    @DisplayName("업스케일_프리셋에서_scaleX_scaleY가_1보다_크게_계산된다")
    void upscaleProducesScaleAboveOne() {
        Long parentRawSn = 101L;
        when(videoRepository.findById(parentRawSn)).thenReturn(Optional.of(approvedDeidParent(parentRawSn)));
        seedApprovedFrame(parentRawSn);
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{640, 360});
        when(reservationPersister.reserveAndCreate(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ResolutionReservationPersister.Reservation(501L, 8L));

        ResolutionDerivativeResponse res = service.createDerivative(parentRawSn, ResolutionPreset.RES_1080P, "rev1");

        assertThat(res.scaleX()).isGreaterThan(1.0);
        assertThat(res.scaleY()).isGreaterThan(1.0);
        assertThat(res.targetW()).isEqualTo(1920);
        assertThat(res.targetH()).isEqualTo(1080);
    }

    @Test
    @DisplayName("srcW_srcH가_0이면_파생생성이_거부된다")
    void zeroDimensionRejected() {
        Long parentRawSn = 102L;
        when(videoRepository.findById(parentRawSn)).thenReturn(Optional.of(approvedDeidParent(parentRawSn)));
        seedApprovedFrame(parentRawSn);
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{0, 0});

        assertThatThrownBy(() -> service.createDerivative(parentRawSn, ResolutionPreset.RES_720P, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(reservationPersister, never()).reserveAndCreate(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("증강본_orgnlRawSn_null아님_원본은_거부된다")
    void derivedParentRejected() {
        Long parentRawSn = 103L;
        LsDataRaw derived = approvedDeidParent(parentRawSn);
        ReflectionTestUtils.setField(derived, "orgnlRawSn", 99L); // 이미 파생/증강본
        when(videoRepository.findById(parentRawSn)).thenReturn(Optional.of(derived));

        assertThatThrownBy(() -> service.createDerivative(parentRawSn, ResolutionPreset.RES_720P, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("검수미완료_영상은_파생생성이_거부된다")
    void notApprovedRejected() {
        Long parentRawSn = 104L;
        when(videoRepository.findById(parentRawSn)).thenReturn(Optional.of(approvedDeidParent(parentRawSn)));
        LsRawDataStatus st = LsRawDataStatus.initial(parentRawSn); // PENDING
        when(statusRepository.findByRawDataIdIn(List.of(parentRawSn))).thenReturn(List.of(st));

        assertThatThrownBy(() -> service.createDerivative(parentRawSn, ResolutionPreset.RES_720P, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }
}
