package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
import kr.co.cudo.authoring.video.service.ResolutionSnapshotService;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A(검증·스냅샷) 단위 테스트 — 멱등 skip, PII TOCTOU 게이트, 프레임 fail-fast, 스냅샷 값 정합.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolutionSnapshotServiceTest {

    private static final long PARENT = 200L;
    private static final long NEW_RAW = 9100L;
    private static final long DATA_AUG = 42L;

    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock ImageResizer imageResizer;

    ResolutionSnapshotService service;

    @TempDir java.nio.file.Path base;

    @BeforeEach
    void setup() {
        service = new ResolutionSnapshotService(videoRepository, srcRepository,
                deidentProcLogRepository, augRepository, imageResizer);
        ReflectionTestUtils.setField(service, "storageRawPath", base.toString());
    }

    private LsDataRaw newRawMock(String childDeIdntfYn) {
        LsDataRaw newRaw = mock(LsDataRaw.class);
        when(newRaw.getDeIdntfYn()).thenReturn(childDeIdntfYn);
        when(newRaw.getRawFilePathNm())
                .thenReturn(base.resolve("resolution/" + NEW_RAW + "/video/RESL_720P.mp4").toString());
        when(videoRepository.findById(NEW_RAW)).thenReturn(Optional.of(newRaw));
        return newRaw;
    }

    private void seedParentFramesAndVideo() {
        LsDataSrc parentFrame = LsDataSrc.create(PARENT, 0L, 0L,
                base.resolve("frames/f0.jpg").toString(), null);
        parentFrame.attachDeidPath(base.resolve("frames/deid/f0.jpg").toString());
        ReflectionTestUtils.setField(parentFrame, "srcSn", 1000L);

        when(srcRepository.findByRawSnAndFrameNo(eq(PARENT), eq(0))).thenReturn(Optional.of(parentFrame));
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(parentFrame)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(1, 500))))
                .thenReturn(new PageImpl<>(List.of()));

        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm()).thenReturn(base.resolve("videos/deid.mp4").toString());
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(PARENT)).thenReturn(Optional.of(procLog));

        LsDataAug aug = LsDataAug.createResolutionPending(1000L, LsDataAug.AUG_RESL_720P, "rev1");
        when(augRepository.findById(DATA_AUG)).thenReturn(Optional.of(aug));
    }

    @Test
    @DisplayName("정상시_치수_배율_경로_프레임스펙_등록자를_스냅샷한다")
    void snapshotReturnsAllValues() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        Optional<ResolutionSnapshot> opt = service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P);

        assertThat(opt).isPresent();
        ResolutionSnapshot s = opt.get();
        assertThat(s.targetW()).isEqualTo(1280);
        assertThat(s.targetH()).isEqualTo(720);
        assertThat(s.srcW()).isEqualTo(1920);
        assertThat(s.scaleX()).isCloseTo(1280d / 1920d, within(1e-9));
        assertThat(s.regId()).isEqualTo("rev1");
        assertThat(s.deidVideoSrc()).isEqualTo(base.resolve("videos/deid.mp4"));
        assertThat(s.videoDst()).isEqualTo(base.resolve("resolution/" + NEW_RAW + "/video/RESL_720P.mp4"));
        assertThat(s.frames()).hasSize(1);
        // H-1 stale 게이트 기준 시각 — Phase A 가 부모 'Y' 확정 시각을 반드시 담는다.
        assertThat(s.capturedAt()).isNotNull();
        // 파생 리스케일 소스는 반드시 비식별 프레임(PII 안전).
        assertThat(s.frames().get(0).deidSrc()).isEqualTo(base.resolve("frames/deid/f0.jpg"));
        assertThat(s.frames().get(0).parentSrcSn()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다")
    void snapshotIsIdempotentWhenAlreadyDeidentified() {
        newRawMock("Y"); // 이미 확정됨

        Optional<ResolutionSnapshot> opt = service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P);

        assertThat(opt).isEmpty();
        verify(videoRepository, never()).findByRawSnForUpdate(any());
    }

    @Test
    @DisplayName("부모가_비식별미완료(F)면_PII게이트에서_CONFLICT로_거부한다")
    void snapshotAbortsWhenParentNoLongerDeidentified() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("F"); // 창 안에서 PII 노출 확정
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("부모_프레임이_0건이면_fail_fast로_거부한다(#9)")
    void snapshotFailsFastWhenNoFrames() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        // 측정용 첫 프레임도 없음 → 프레임 0건.
        when(srcRepository.findByRawSnAndFrameNo(eq(PARENT), eq(0))).thenReturn(Optional.empty());
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT))).thenReturn(List.of());

        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
