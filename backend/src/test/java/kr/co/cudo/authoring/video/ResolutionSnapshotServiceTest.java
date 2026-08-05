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
import kr.co.cudo.authoring.video.service.port.Java2DImageResizer;
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
        when(parent.hasDeidentArtifact()).thenReturn(true);
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
        // E-ISSUE-61 — 목적 파일명은 부모 basename(f0.jpg) 재사용이 아니라 파생 자신의 FRM_NO 기반이다.
        assertThat(s.frames().get(0).dst())
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-0.jpg"));
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
        when(parent.hasDeidentArtifact()).thenReturn(true);
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
        assertThat(s.frames().get(0).dst())
                .isEqualTo(deidBase.resolve("frames/deid/" + NEW_RAW + "/frame-0.jpg"));
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
    @DisplayName("부모_비식별산출물이_없으면(N)_게이트에서_CONFLICT로_거부한다")
    void snapshotAbortsWhenParentHasNoDeidentArtifact() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("N"); // 비식별 미수행 — 복사할 산출물 자체가 없다
        when(parent.hasDeidentArtifact()).thenReturn(false);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("부모가_비식별신고구간(F)이어도_스냅샷이_정상_생성된다")
    void snapshotProceedsWhenParentUnderDeidentReport() {
        // given — 부모가 비식별 누락 신고('F') 구간. 비식별 산출물은 디스크에 존재한다.
        //         해상도 파생은 외부 위탁이 없는 내부 리스케일이라 신고가 생성을 막지 않는다(2026-07-29).
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("F");
        when(parent.hasDeidentArtifact()).thenReturn(true);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        // when
        Optional<ResolutionSnapshot> opt = service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P);

        // then — 스냅샷이 정상 생성되고 소스는 여전히 비식별 프레임이다(PII 폴백 없음)
        assertThat(opt).isPresent();
        assertThat(opt.get().frames()).hasSize(1);
        assertThat(opt.get().frames().get(0).deidSrc())
                .isEqualTo(base.resolve("frames/deid/" + PARENT + "/f0.jpg"));
    }

    @Test
    @DisplayName("확정게이트1_부모의_비식별_비디오_procLog가_없으면_NOT_FOUND로_거부한다(E-29)")
    void snapshotFailsWhenParentDeidentVideoProcLogMissing() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(parent.hasDeidentArtifact()).thenReturn(true);
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
        when(parent.hasDeidentArtifact()).thenReturn(true);
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
        when(parent.hasDeidentArtifact()).thenReturn(true);
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

    // ── E-ISSUE-61 — 목적 프레임 파일명 충돌로 인한 무경고 덮어쓰기(데이터 유실) 회귀 가드 ──────────────

    @Test
    @DisplayName("해상도파생_시_목적_프레임_파일명이_부모_basename이_아니라_프레임번호_기반으로_생성된다")
    void destinationFileNameIsDerivedFromFrameNoNotParentBasename() {
        // given — 부모 프레임의 basename 은 흔한 패턴(frame_001.jpg)이고 FRM_NO 는 7 이다.
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(parent.hasDeidentArtifact()).thenReturn(true);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        LsDataSrc pf = LsDataSrc.create(PARENT, 7L, 7L,
                base.resolve("frames/raw/" + PARENT + "/frame_001.png").toString(), null);
        pf.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/frame_001.png").toString());
        ReflectionTestUtils.setField(pf, "srcSn", 4000L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(pf)));

        // when
        Optional<ResolutionSnapshot> opt = service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P);

        // then — 파생 자신의 FRM_NO 로 이름을 짓고 확장자만 소스에서 승계한다(부모 basename 재사용 금지).
        assertThat(opt).isPresent();
        assertThat(opt.get().frames().get(0).dst())
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-7.png"));
    }

    @Test
    @DisplayName("허용목록에_없는_확장자는_jpg로_강제된다")
    void nonAllowlistedExtensionFallsBackToJpg() {
        // given / when — 소스 basename 의 확장자가 이미지 allowlist(jpg/jpeg/png/bmp) 밖이다.
        // then — 소스 확장자를 그대로 승계하지 않고 jpg 로 확정한다(CWE-22/CWE-20 — 임의 확장자 승계 차단).
        //        ImageResizer 는 목적 확장자로 출력 포맷을 정하므로 이름과 내용도 어긋나지 않는다.
        assertThat(dstOfSingleFrame("frame_001.webp"))
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-3.jpg"));
        assertThat(dstOfSingleFrame("frame_001.gif"))
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-3.jpg"));
    }

    @Test
    @DisplayName("확장자가_없는_소스는_jpg로_강제된다")
    void missingExtensionFallsBackToJpg() {
        // given / when — 확장자가 아예 없거나 점으로 끝나 확장자가 빈 문자열인 소스.
        // then — 미상 확장자도 jpg 로 확정한다(빈 확장자·점 끝 파일명이 목적 파일명에 새지 않는다).
        assertThat(dstOfSingleFrame("frame_001"))
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-3.jpg"));
        assertThat(dstOfSingleFrame("frame_001."))
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-3.jpg"));
    }

    @Test
    @DisplayName("대문자_확장자도_allowlist에_매칭되어_원확장자로_승계된다")
    void uppercaseExtensionIsNormalizedBeforeAllowlistMatch() {
        // given / when — 소스 확장자가 대문자(.PNG/.JPEG). allowlist 는 소문자 집합이므로
        //         정규화가 없으면 매칭에 실패해 대문자라는 이유만으로 jpg 폴백된다.
        // then — 소문자로 정규화 후 매칭되어 원 확장자 계열을 승계하고, 목적 파일명은 소문자로 확정된다.
        assertThat(dstOfSingleFrame("frame_001.PNG"))
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-3.png"));
        assertThat(dstOfSingleFrame("frame_001.JPEG"))
                .isEqualTo(base.resolve("frames/deid/" + NEW_RAW + "/frame-3.jpeg"));
    }

    /**
     * 부모 비식별 프레임 1건(FRM_NO=3)을 주어진 basename 으로 심고 스냅샷한 뒤 목적 경로를 돌려준다.
     * 확장자 승계·폴백 규칙(allowlist) 검증 전용 헬퍼.
     */
    private java.nio.file.Path dstOfSingleFrame(String deidBaseName) {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(parent.hasDeidentArtifact()).thenReturn(true);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        LsDataSrc pf = LsDataSrc.create(PARENT, 3L, 3L,
                base.resolve("frames/raw/" + PARENT + "/" + deidBaseName).toString(), null);
        pf.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/" + deidBaseName).toString());
        ReflectionTestUtils.setField(pf, "srcSn", 7000L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(pf)));

        return service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P)
                .orElseThrow().frames().get(0).dst();
    }

    @Test
    @DisplayName("부모_프레임이_서로_다른_디렉터리에서_동일한_basename을_가져도_해상도파생_목적파일이_충돌하지_않는다")
    void framesWithSameBasenameFromDifferentDirsDoNotOverwriteEachOther() throws Exception {
        // given — 부모 프레임 2건이 서로 다른 소스 디렉터리에 같은 이름(frame_001.jpg)으로 존재한다.
        //         구현이 부모 basename 을 목적 파일명으로 재사용하면 목적 디렉터리가 하나뿐이라
        //         두 번째 프레임이 첫 번째를 무경고로 덮어써 프레임 1장이 유실된다(E-ISSUE-61).
        java.nio.file.Path srcA = base.resolve("frames/deid/" + PARENT + "/frame_001.jpg");
        java.nio.file.Path srcB = base.resolve("frames/deid/" + PARENT + "-b/frame_001.jpg");
        writeImage(srcA, 1920, 1080);
        writeImage(srcB, 1920, 1080);

        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(parent.hasDeidentArtifact()).thenReturn(true);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));

        LsDataSrc f0 = LsDataSrc.create(PARENT, 0L, 0L, srcA.toString(), null);
        f0.attachDeidPath(srcA.toString());
        ReflectionTestUtils.setField(f0, "srcSn", 5000L);
        LsDataSrc f1 = LsDataSrc.create(PARENT, 1L, 1L, srcB.toString(), null);
        f1.attachDeidPath(srcB.toString());
        ReflectionTestUtils.setField(f1, "srcSn", 5001L);

        when(srcRepository.findByRawSnAndFrameNo(eq(PARENT), eq(0))).thenReturn(Optional.of(f0));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(f0, f1)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(1, 500))))
                .thenReturn(new PageImpl<>(List.of()));
        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm())
                .thenReturn(base.resolve("videos/" + PARENT + "/deidentified.mp4").toString());
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(PARENT)).thenReturn(Optional.of(procLog));
        when(augRepository.findById(DATA_AUG))
                .thenReturn(Optional.of(LsDataAug.createResolutionPending(1000L, LsDataAug.AUG_RESL_720P, "rev1")));

        // 실측·리스케일은 실제 구현(Java2DImageResizer)으로 수행해 디스크 산출물을 직접 확인한다.
        ImageResizer realResizer = new Java2DImageResizer();
        ResolutionSnapshotService realService = new ResolutionSnapshotService(videoRepository, srcRepository,
                deidentProcLogRepository, augRepository, realResizer,
                kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport.labelingRoot(base, base));
        ReflectionTestUtils.setField(realService, "storageRawPath", base.toString());
        ReflectionTestUtils.setField(realService, "storageDeidentifiedPath", base.toString());

        // when — Phase A 스냅샷 후 Phase B 와 동일하게 프레임을 리스케일해 실제 파일을 산출한다.
        ResolutionSnapshot s = realService.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P)
                .orElseThrow();
        for (ResolutionSnapshot.FrameSpec f : s.frames()) {
            realResizer.resize(f.deidSrc(), f.dst(), s.targetW(), s.targetH());
        }

        // then — 목적 경로가 서로 다르고, 디스크에도 입력 프레임 수(2)만큼 파일이 남는다.
        assertThat(s.frames()).hasSize(2);
        assertThat(s.frames().get(0).dst()).isNotEqualTo(s.frames().get(1).dst());
        try (var stream = java.nio.file.Files.list(base.resolve("frames/deid/" + NEW_RAW))) {
            assertThat(stream.filter(java.nio.file.Files::isRegularFile).toList()).hasSize(2);
        }
    }

    @Test
    @DisplayName("목적_프레임_경로가_중복되면_예외를_던지고_해상도파생을_실패로_종결한다")
    void snapshotFailsFastOnDuplicateDestinationPath() {
        // given — FRM_NO 가 같고 videoFrameNo 만 다른 부모 프레임 2건. DB 제약
        //         UK_LS_DATA_SRC_RAW_FRAME(RAW_SN, FRM_NO) 이 정상이면 불가능한 조합이지만,
        //         제약 드리프트/데이터 오염 시 두 프레임의 목적 경로가 같아져 무경고 덮어쓰기가 된다.
        //         기존 videoFrameNo 중복 가드는 (10, 20) 이 서로 달라 통과시키므로 목적 경로 가드가 필요하다.
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(parent.hasDeidentArtifact()).thenReturn(true);
        when(videoRepository.findByRawSnForUpdate(PARENT)).thenReturn(Optional.of(parent));
        seedParentFramesAndVideo();

        LsDataSrc dup0 = LsDataSrc.create(PARENT, 0L, 10L,
                base.resolve("frames/raw/" + PARENT + "/a.jpg").toString(), null);
        dup0.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/a.jpg").toString());
        ReflectionTestUtils.setField(dup0, "srcSn", 6000L);
        LsDataSrc dup1 = LsDataSrc.create(PARENT, 0L, 20L,
                base.resolve("frames/raw/" + PARENT + "/b.jpg").toString(), null);
        dup1.attachDeidPath(base.resolve("frames/deid/" + PARENT + "/b.jpg").toString());
        ReflectionTestUtils.setField(dup1, "srcSn", 6001L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(PARENT), eq(PageRequest.of(0, 500))))
                .thenReturn(new PageImpl<>(List.of(dup0, dup1)));

        // when / then — 덮어쓰기 대신 예외로 파생 전체를 실패 종결시킨다(러너가 FAILED 전이).
        assertThatThrownBy(() -> service.snapshot(NEW_RAW, PARENT, DATA_AUG, ResolutionPreset.RESL_720P))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    /** 실제 리스케일 검증용 최소 이미지 파일 생성. */
    private static void writeImage(java.nio.file.Path path, int w, int h) throws Exception {
        java.nio.file.Files.createDirectories(path.getParent());
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.GRAY);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        javax.imageio.ImageIO.write(img, "jpg", path.toFile());
    }

    @Test
    @DisplayName("부모_프레임이_0건이면_fail_fast로_거부한다(#9)")
    void snapshotFailsFastWhenNoFrames() {
        newRawMock("N");
        LsDataRaw parent = mock(LsDataRaw.class);
        when(parent.getDeIdntfYn()).thenReturn("Y");
        when(parent.hasDeidentArtifact()).thenReturn(true);
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
