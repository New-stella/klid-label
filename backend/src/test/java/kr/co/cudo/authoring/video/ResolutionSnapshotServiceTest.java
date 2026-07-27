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
    @TempDir java.nio.file.Path deidBase;

    @BeforeEach
    void setup() {
        // 기존 케이스는 구 위치(비식별 저장소 서브트리) 기준이라 롤백 전략 리졸버를 주입한다.
        // co-locate 신 위치 수용은 별도 케이스에서 검증한다.
        service = new ResolutionSnapshotService(videoRepository, srcRepository,
                deidentProcLogRepository, augRepository, imageResizer,
                kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport.labelingRoot(base, base));
        ReflectionTestUtils.setField(service, "storageRawPath", base.toString());
        // 기본은 raw==deid(단일 tempdir)로 두어 기존 케이스 유지. deid 분리 케이스는 개별 테스트에서 재설정.
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", base.toString());
    }

    private LsDataRaw newRawMock(String childDeIdntfYn) {
        LsDataRaw newRaw = mock(LsDataRaw.class);
        when(newRaw.getDeIdntfYn()).thenReturn(childDeIdntfYn);
        when(newRaw.getRawFilePathNm())
                .thenReturn(base.resolve("videos/resolution/" + PARENT + "/RESL_720P.mp4").toString());
        when(videoRepository.findById(NEW_RAW)).thenReturn(Optional.of(newRaw));
        return newRaw;
    }

    private void seedParentFramesAndVideo() {
        LsDataSrc parentFrame = LsDataSrc.create(PARENT, 0L, 0L,
                base.resolve("frames/f0.jpg").toString(), null);
        parentFrame.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/f0.jpg").toString());
        ReflectionTestUtils.setField(parentFrame, "srcSn", 1000L);

        when(srcRepository.findByRawSnAndFrameNo(eq(PARENT), eq(0))).thenReturn(Optional.of(parentFrame));
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(parentFrame)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(1, 500))))
                .thenReturn(new PageImpl<>(List.of()));

        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm()).thenReturn(base.resolve("videos/" + PARENT + "/deidentified.mp4").toString());
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
        assertThat(s.deidVideoSrc()).isEqualTo(base.resolve("videos/" + PARENT + "/deidentified.mp4"));
        assertThat(s.videoDst()).isEqualTo(base.resolve("videos/resolution/" + PARENT + "/RESL_720P.mp4"));
        assertThat(s.frames()).hasSize(1);
        // H-1 stale 게이트 기준 시각 — Phase A 가 부모 'Y' 확정 시각을 반드시 담는다.
        assertThat(s.capturedAt()).isNotNull();
        // 파생 리스케일 소스는 반드시 비식별 프레임(PII 안전).
        assertThat(s.frames().get(0).deidSrc()).isEqualTo(base.resolve("frames/deid/" + PARENT + "/f0.jpg"));
        assertThat(s.frames().get(0).parentSrcSn()).isEqualTo(1000L);
        // E-ISSUE-21 — 파생 프레임 산출물은 비식별 저장소의 비식별 전용 서브트리에 놓인다.
        assertThat(s.frames().get(0).dst())
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/f0.jpg"));
    }

    @Test
    @DisplayName("해상도_파생_프레임_이미지가_deid_base_하위에_생성됨(raw_base와_분리된_환경)")
    void snapshotSucceedsWhenDeidPathUnderSeparateDeidBase() {
        // given: 운영처럼 raw base(/storage/raw) 와 deid base(/storage/deidentified) 가 분리된 상황.
        //        구버전은 deid 프레임/비디오를 raw base 로만 검증해 "경로가 허용된 저장 경로를 벗어납니다" 로 실패했다.
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", deidBase.toString());

        LsDataRaw newRaw = mock(LsDataRaw.class);
        when(newRaw.getDeIdntfYn()).thenReturn("N");
        // 출력(파생 비디오) 경로는 비식별 base 하위(E-ISSUE-21).
        when(newRaw.getRawFilePathNm())
                .thenReturn(deidBase.resolve("videos/resolution/" + PARENT + "/RESL_720P.mp4").toString());
        when(videoRepository.findById(NEW_RAW)).thenReturn(Optional.of(newRaw));

        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));

        // 프레임 소스(비식별)와 비식별 비디오는 deid base 하위 절대경로.
        LsDataSrc parentFrame = LsDataSrc.create(PARENT, 0L, 0L,
                base.resolve("frames/f0.jpg").toString(), null);
        parentFrame.attachDeidPath(deidBase.resolve("frames/deid/" + PARENT + "/f0.jpg").toString());
        ReflectionTestUtils.setField(parentFrame, "srcSn", 1000L);
        when(srcRepository.findByRawSnAndFrameNo(eq(PARENT), eq(0))).thenReturn(Optional.of(parentFrame));
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(parentFrame)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(1, 500))))
                .thenReturn(new PageImpl<>(List.of()));

        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm()).thenReturn(deidBase.resolve("videos/" + PARENT + "/deidentified.mp4").toString());
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(PARENT)).thenReturn(Optional.of(procLog));
        LsDataAug aug = LsDataAug.createResolutionPending(1000L, LsDataAug.AUG_RESL_720P, "rev1");
        when(augRepository.findById(DATA_AUG)).thenReturn(Optional.of(aug));

        // when
        Optional<ResolutionSnapshot> opt = service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P);

        // then: deid base 로 통과 → 스냅샷 성공, 입력·출력 모두 deid base 하위
        assertThat(opt).isPresent();
        ResolutionSnapshot s = opt.get();
        assertThat(s.deidVideoSrc()).isEqualTo(deidBase.resolve("videos/" + PARENT + "/deidentified.mp4"));
        assertThat(s.videoDst()).isEqualTo(deidBase.resolve("videos/resolution/" + PARENT + "/RESL_720P.mp4"));
        assertThat(s.frames()).hasSize(1);
        assertThat(s.frames().get(0).deidSrc()).isEqualTo(deidBase.resolve("frames/deid/" + PARENT + "/f0.jpg"));
        assertThat(s.frames().get(0).dst()).isEqualTo(deidBase.resolve("frames/deid/" + NEW_RAW + "/f0.jpg"));
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
    @DisplayName("확정게이트1_부모의_비식별_비디오_procLog가_없으면_NOT_FOUND로_거부한다(E-29)")
    void snapshotFailsWhenParentDeidentVideoProcLogMissing() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();
        // 비식별 비디오 성공 이력 부재 — 복사할 비식별 원본이 없다.
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(PARENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("확정게이트2_부모프레임에_중복_videoFrameNo가_있으면_fail_fast로_거부한다(E-29)")
    void snapshotFailsFastOnDuplicateVideoFrameNo() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        // 같은 videoFrameNo(0) 를 가진 프레임 2건 — 라벨 이중매핑 위험이라 fail-fast 해야 한다.
        LsDataSrc dup0 = LsDataSrc.create(PARENT, 0L, 0L, base.resolve("frames/a.jpg").toString(), null);
        dup0.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/a.jpg").toString());
        ReflectionTestUtils.setField(dup0, "srcSn", 2000L);
        LsDataSrc dup1 = LsDataSrc.create(PARENT, 1L, 0L, base.resolve("frames/b.jpg").toString(), null);
        dup1.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/b.jpg").toString());
        ReflectionTestUtils.setField(dup1, "srcSn", 2001L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(dup0, dup1)));

        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("부모_비식별프레임이_frames_raw_하위면_파생소스로_거부된다(동일_base_격리)")
    void snapshotRejectsParentDeidFrameUnderRawSubtree() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        // 오염된 비식별 경로 — 두 base 가 같으므로 base 검사는 통과하지만 원본 프레임 서브트리다.
        LsDataSrc polluted = LsDataSrc.create(PARENT, 0L, 0L,
                base.resolve("frames/raw/" + PARENT + "/f0.jpg").toString(), null);
        polluted.attachDeidPath(base.resolve("frames/raw/" + PARENT + "/f0.jpg").toString());
        ReflectionTestUtils.setField(polluted, "srcSn", 3000L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(polluted)));

        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
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
