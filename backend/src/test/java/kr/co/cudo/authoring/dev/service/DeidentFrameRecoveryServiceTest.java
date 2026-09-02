package kr.co.cudo.authoring.dev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.dev.dto.DeidentFrameRecoveryResponse;
import kr.co.cudo.authoring.dev.service.DeidentFrameNoBackfillTxService.FrameNoFix;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [개발/검수 전용] 레거시 비식별 프레임 복구 단위 테스트.
 *
 * <p>검증 축은 셋이다.
 * <ol>
 *   <li><b>순서</b> — {@code VDO_FRM_NO} 복원이 <b>커밋된 뒤</b> 재추출이 돈다. 뒤집히면 attacher 가
 *       값 없는 프레임을 전부 skip 해 기능이 조용히 아무것도 안 한다.</li>
 *   <li><b>fail-closed</b> — 프레임/마킹 개수 불일치, 비식별 경로 부재·경계 밖일 때 <b>추측하지 않고</b>
 *       건너뛴다(원본 폴백 없음).</li>
 *   <li><b>부작용 없음</b> — dry-run 은 아무것도 쓰지 않고, 실행도 도메인 이벤트를 발행하지 않는다.</li>
 * </ol>
 */
class DeidentFrameRecoveryServiceTest {

    private static final long RAW_SN = 7L;

    private VideoRepository videoRepository;
    private LsDataSrcRepository srcRepository;
    private LsMarkingRepository markingRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentFrameNoBackfillTxService frameNoBackfillTxService;
    private DeidentFrameAttacher deidentFrameAttacher;
    private VideoArtifactRootResolver artifactRootResolver;
    private DeidentReportGate deidentReportGate;
    private DeidentFrameRecoveryService service;

    @TempDir
    Path storage;

    private Path deidVideo;
    /** 판정기가 돌려주는 <b>실경로</b> — macOS 의 {@code /var → /private/var} 처럼 심링크가 풀린다. */
    private Path deidVideoReal;

    @BeforeEach
    void setUp() throws IOException {
        videoRepository = mock(VideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        frameNoBackfillTxService = mock(DeidentFrameNoBackfillTxService.class);
        deidentFrameAttacher = mock(DeidentFrameAttacher.class);
        artifactRootResolver = mock(VideoArtifactRootResolver.class);
        deidentReportGate = mock(DeidentReportGate.class);
        service = new DeidentFrameRecoveryService(videoRepository, srcRepository, markingRepository,
                procLogRepository, frameNoBackfillTxService, deidentFrameAttacher, artifactRootResolver,
                deidentReportGate, new ObjectMapper());

        deidVideo = storage.resolve("videos").resolve(String.valueOf(RAW_SN)).resolve("clip-mask.mp4");
        // 무결성 판정(정규파일 + 크기 하한 + 컨테이너 시그니처)을 통과하는 최소 실 mp4.
        TestVideoFixtures.writeTinyMp4(deidVideo);
        deidVideoReal = deidVideo.toRealPath();
        when(artifactRootResolver.readableDeidVideoBases(anyLong(), any())).thenReturn(List.of(storage));
        when(srcRepository.countRawSnsMissingDeidFramePath()).thenReturn(1L);
    }

    // ────────────────────────── VDO_FRM_NO 복원 ──────────────────────────

    @Test
    @DisplayName("VDO_FRM_NO가_NULL이면_마킹에서_복원해_채운다")
    void restoresVideoFrameNoFromMarking() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(3));
        givenDeidVideoPath();
        givenMarks(0, 30, 60);
        when(frameNoBackfillTxService.restore(eq(RAW_SN), any())).thenReturn(3);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(3);

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FrameNoFix>> captor = ArgumentCaptor.forClass(List.class);
        verify(frameNoBackfillTxService).restore(eq(RAW_SN), captor.capture());
        // 마킹 배열 순서 = FRM_NO 오름차순 프레임 순서 (초기 추출이 그렇게 적재한다). 정렬하지 않는다.
        assertThat(captor.getValue())
                .extracting(FrameNoFix::srcSn, FrameNoFix::videoFrameNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 0L),
                        org.assertj.core.groups.Tuple.tuple(102L, 30L),
                        org.assertj.core.groups.Tuple.tuple(103L, 60L));
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result,
                        DeidentFrameRecoveryResponse.Item::videoFrameNoRestored,
                        DeidentFrameRecoveryResponse.Item::deidFrameAttached)
                .containsExactly("RECOVERED", 3, 3);
    }

    @Test
    @DisplayName("프레임수와_마킹수가_다르면_백필하지_않고_사유를_보고한다")
    void skipsWhenFrameAndMarkCountDiffer() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(3));
        givenDeidVideoPath();
        givenMarks(0, 30, 60, 90, 120);   // 프레임 3 vs 마킹 5 — 1:1 대응 특정 불가

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        // 추측 매핑 금지 — 위치가 어긋나면 기존 라벨 좌표가 엉뚱한 장면에 얹힌다.
        verifyNoInteractions(frameNoBackfillTxService);
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result,
                        DeidentFrameRecoveryResponse.Item::reason,
                        DeidentFrameRecoveryResponse.Item::frameCount,
                        DeidentFrameRecoveryResponse.Item::markCount)
                .containsExactly("SKIPPED", "MARK_COUNT_MISMATCH", 3, 5);
    }

    @Test
    @DisplayName("VDO_FRM_NO_백필_후에_비식별_프레임_재추출이_수행된다")
    void restoresFrameNoBeforeReExtraction() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(frameNoBackfillTxService.restore(eq(RAW_SN), any())).thenReturn(2);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(2);

        service.recover(RAW_SN);

        // 순서가 뒤집히면 attacher 의 REQUIRES_NEW 트랜잭션이 아직 NULL 인 VDO_FRM_NO 를 읽어
        // 전 프레임을 skip 한다 — "성공했는데 아무것도 안 바뀜" 이 되므로 순서 자체를 고정한다.
        InOrder order = inOrder(frameNoBackfillTxService, deidentFrameAttacher);
        order.verify(frameNoBackfillTxService).restore(eq(RAW_SN), any());
        // 넘기는 경로는 판정기가 돌려준 <b>실경로</b> 그대로다 — lexical 경로로 판정하고 lexical 경로로
        // 열면 판정~open 사이 심링크 치환으로 마스킹 전 원본이 "비식별본"이 된다(CWE-59/367).
        order.verify(deidentFrameAttacher).attachDeidentFrames(any(), eq(deidVideoReal), eq(false));
    }

    @Test
    @DisplayName("이미_VDO_FRM_NO가_있으면_마킹을_읽지_않고_재추출만_한다")
    void skipsMarkingLookupWhenVideoFrameNoPresent() {
        givenVideo();
        givenFrames(framesWithVideoFrameNo(0L, 30L));
        givenDeidVideoPath();
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(2);

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        verifyNoInteractions(markingRepository);
        // 복원할 것이 없으면 별도 트랜잭션을 열지 않는다(불필요한 커밋 경계 제거).
        verifyNoInteractions(frameNoBackfillTxService);
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result)
                .isEqualTo("RECOVERED");
    }

    @Test
    @DisplayName("기존_VDO_FRM_NO가_마킹매핑과_충돌하면_건너뛴다")
    void skipsWhenExistingVideoFrameNoConflictsWithMapping() {
        givenVideo();
        List<LsDataSrc> frames = framesWithoutVideoFrameNo(2);
        setField(frames.get(1), "videoFrameNo", 999L);   // 매핑 결과(30)와 불일치
        givenFrames(frames);
        givenDeidVideoPath();
        givenMarks(0, 30);

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        verifyNoInteractions(frameNoBackfillTxService);
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("VDO_FRM_NO_CONFLICT");
    }

    @Test
    @DisplayName("마킹이_없으면_위치를_지어내지_않고_건너뛴다")
    void skipsWhenNoMarkingExists() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        verifyNoInteractions(frameNoBackfillTxService);
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("NO_MARKING");
    }

    // ────────────────────────── 비식별 영상 fail-closed ──────────────────────────

    @Test
    @DisplayName("비식별_영상_경로가_없으면_원본으로_폴백하지_않고_건너뛴다")
    void skipsWhenDeidentifiedVideoPathMissing() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        when(procLogRepository.findLatestSuccessByDataRawSn(RAW_SN)).thenReturn(Optional.empty());

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        // 원본(마스킹 전) 영상에서 뽑아 "비식별본"으로 적재하면 PII 가 그대로 새어 나간다(CWE-359).
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        verifyNoInteractions(frameNoBackfillTxService);
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("NO_DEID_VIDEO_PATH");
    }

    @Test
    @DisplayName("비식별_영상_경로가_허용_저장소_밖이면_건너뛴다")
    void skipsWhenDeidentifiedVideoPathEscapesStorage() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        // 허용 base 를 다른 서브트리로 좁히면 적재값이 경계 밖이 된다(경로 순회·심링크 치환 방어).
        when(artifactRootResolver.readableDeidVideoBases(anyLong(), any()))
                .thenReturn(List.of(storage.resolve("other-subtree")));

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("DEID_VIDEO_PATH_REJECTED");
    }

    @Test
    @DisplayName("비식별_영상_파일이_없으면_건너뛴다")
    void skipsWhenDeidentifiedVideoFileMissing() throws IOException {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        Files.delete(deidVideo);
        givenDeidVideoPath();

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("DEID_VIDEO_MISSING");
    }

    @Test
    @DisplayName("재추출이_실패해도_다른_영상_처리를_막지_않고_사유를_보고한다")
    void isolatesAttachFailure() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(frameNoBackfillTxService.restore(eq(RAW_SN), any())).thenReturn(2);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean()))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT,
                        "비식별 출력 해상도가 원본과 다릅니다 rawSn=" + RAW_SN));

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        DeidentFrameRecoveryResponse.Item item = response.items().get(0);
        assertThat(item.result()).isEqualTo("FAILED");
        assertThat(item.reason()).isEqualTo("ATTACH_FAILED");
        // 예외 메시지(경로가 실릴 수 있다)는 응답으로 반사되지 않는다 — 사유는 서버가 고른 열거값뿐.
        assertThat(item.reason()).doesNotContain("/");
    }

    // ────────────────────────── dry-run · 멱등 ──────────────────────────

    @Test
    @DisplayName("dryRun이면_아무것도_변경하지_않고_대상만_보고한다")
    void previewChangesNothing() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(3));
        givenDeidVideoPath();
        givenMarks(0, 30, 60);

        DeidentFrameRecoveryResponse response = service.preview(RAW_SN);

        verifyNoInteractions(frameNoBackfillTxService);
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        assertThat(response.dryRun()).isTrue();
        assertThat(response.restoredVideoFrameNoCount()).isZero();
        assertThat(response.attachedDeidFrameCount()).isZero();
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result,
                        DeidentFrameRecoveryResponse.Item::videoFrameNoRestored,
                        DeidentFrameRecoveryResponse.Item::deidFrameAttached)
                .containsExactly("PLANNED", 3, 3);
    }

    @Test
    @DisplayName("두_번_실행해도_결과가_같다")
    void isIdempotent() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(frameNoBackfillTxService.restore(eq(RAW_SN), any())).thenReturn(2);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(2);

        DeidentFrameRecoveryResponse first = service.recover(RAW_SN);

        // 1회차 이후 상태: VDO_FRM_NO 도 비식별 경로도 채워졌다.
        givenFrames(framesRecovered(0L, 30L));
        when(srcRepository.countRawSnsMissingDeidFramePath()).thenReturn(0L);
        DeidentFrameRecoveryResponse second = service.recover(RAW_SN);

        assertThat(first.items().get(0).result()).isEqualTo("RECOVERED");
        // 두 번째는 대상 자체가 사라져 no-op 이며 추가 쓰기가 없다.
        assertThat(second.items().get(0).result()).isEqualTo("SKIPPED");
        assertThat(second.items().get(0).reason()).isEqualTo("ALREADY_RECOVERED");
        verify(frameNoBackfillTxService, times(1)).restore(eq(RAW_SN), any());
        verify(deidentFrameAttacher, times(1)).attachDeidentFrames(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("비식별_경로가_이미_채워진_영상은_대상에서_제외된다")
    void alreadyRecoveredVideoIsExcluded() {
        givenVideo();
        givenFrames(framesRecovered(0L, 30L));

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        verifyNoInteractions(procLogRepository, markingRepository, frameNoBackfillTxService);
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("ALREADY_RECOVERED");
    }

    // ────────────────────────── 도메인 이벤트 미발행 (시나리오 2) ──────────────────────────

    @Test
    @DisplayName("복구는_도메인_이벤트를_발행하지_않는다_export재생성과_관제통지_차단")
    void publishesNoDomainEvent() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(frameNoBackfillTxService.restore(eq(RAW_SN), any())).thenReturn(2);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(2);

        service.recover(RAW_SN);

        // 이 서비스는 ApplicationEventPublisher 를 생성자에서 아예 받지 않는다 — 발행 표면 자체가 없다.
        // (재사용하는 DeidentFrameAttacher 도 마찬가지라, export 재생성·관제 재통지가 유발되지 않는다.)
        assertThat(java.util.Arrays.stream(DeidentFrameRecoveryService.class.getDeclaredFields())
                .map(f -> f.getType().getName()))
                .noneMatch(n -> n.contains("ApplicationEventPublisher"));
        assertThat(java.util.Arrays.stream(DeidentFrameAttacher.class.getDeclaredFields())
                .map(f -> f.getType().getName()))
                .noneMatch(n -> n.contains("ApplicationEventPublisher"));
    }

    // ────────────────────────── 전체 모드 ──────────────────────────

    @Test
    @DisplayName("전체_모드는_1회_상한만큼만_조회한다")
    void wholeModeAppliesPerRunCap() {
        when(srcRepository.findRawSnsMissingDeidFramePath(any())).thenReturn(List.of());

        DeidentFrameRecoveryResponse response = service.preview(null);

        verify(srcRepository).findRawSnsMissingDeidFramePath(
                org.springframework.data.domain.PageRequest.of(0, DeidentFrameRecoveryService.MAX_VIDEOS_PER_RUN));
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("영상이_없으면_사유를_보고하고_다음으로_넘어간다")
    void skipsMissingVideo() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::reason)
                .isEqualTo("VIDEO_NOT_FOUND");
    }

    // ────────────────────────── 비식별 누락 신고 게이트 (F1 · CWE-359) ──────────────────────────

    @Test
    @DisplayName("비식별_신고_구간_영상은_복구하지_않고_사유를_보고한다")
    void skipsVideoUnderDeidentReport() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(deidentReportGate.isUnderDeidentReport(RAW_SN)).thenReturn(true);

        DeidentFrameRecoveryResponse response = service.recover(RAW_SN);

        // 신고 구간의 비식별본은 마스킹 실패가 확인된 것이다. 여기서 프레임을 뽑아 경로를 채우면
        // 그 값이 V_COMPLETED_FRAME.DEIDENTIFIED_PATH 로 관제에 노출된다 — 없던 노출을 새로 만든다.
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        verifyNoInteractions(frameNoBackfillTxService);
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result,
                        DeidentFrameRecoveryResponse.Item::reason)
                .containsExactly("SKIPPED", "UNDER_DEIDENT_REPORT");
        assertThat(response.skippedVideoCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dry_run도_신고_구간_영상을_대상에서_제외한다")
    void previewExcludesVideoUnderDeidentReport() {
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(deidentReportGate.isUnderDeidentReport(RAW_SN)).thenReturn(true);

        DeidentFrameRecoveryResponse response = service.preview(RAW_SN);

        // 미리 걸러 보여주지 않으면 운영자가 "대상에 있다"고 믿고 실행한다.
        assertThat(response.items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result,
                        DeidentFrameRecoveryResponse.Item::reason)
                .containsExactly("SKIPPED", "UNDER_DEIDENT_REPORT");
        assertThat(response.items().get(0).deidFrameAttached()).isZero();
    }

    @Test
    @DisplayName("신고_구간_영상을_건너뛰어도_다른_영상_처리는_계속된다")
    void continuesWithOtherVideosWhenOneIsUnderDeidentReport() {
        long reported = 99L;
        when(srcRepository.findRawSnsMissingDeidFramePath(any())).thenReturn(List.of(reported, RAW_SN));
        when(deidentReportGate.isUnderDeidentReport(reported)).thenReturn(true);
        givenVideo();
        givenFrames(framesWithoutVideoFrameNo(2));
        givenDeidVideoPath();
        givenMarks(0, 30);
        when(frameNoBackfillTxService.restore(eq(RAW_SN), any())).thenReturn(2);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(2);

        DeidentFrameRecoveryResponse response = service.recover(null);

        // 차단은 그 영상 하나로 끝난다 — 예외로 전체 실행을 끊지 않는다(부분 진행 허용).
        assertThat(response.items())
                .extracting(DeidentFrameRecoveryResponse.Item::rawSn,
                        DeidentFrameRecoveryResponse.Item::result,
                        DeidentFrameRecoveryResponse.Item::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(reported, "SKIPPED", "UNDER_DEIDENT_REPORT"),
                        org.assertj.core.groups.Tuple.tuple(RAW_SN, "RECOVERED", null));
        // 신고 영상은 파일 I/O 이전에 끊긴다 — 그 영상의 비식별 영상 경로조차 읽지 않는다.
        verify(procLogRepository, never()).findLatestSuccessByDataRawSn(reported);
        verify(deidentFrameAttacher, times(1)).attachDeidentFrames(any(), any(), anyBoolean());
    }

    // ────────────────────────── 동시 실행 가드 (F2/F3 · CWE-770/362) ──────────────────────────

    @Test
    @DisplayName("같은_영상에_복구가_진행_중이면_409로_거절한다")
    void rejectsConcurrentRecoveryOfSameVideo() throws Exception {
        givenVideo();
        givenFrames(framesWithVideoFrameNo(0L, 30L));
        givenDeidVideoPath();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 2;
        });

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                service.recover(RAW_SN);
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        });
        first.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            // ffmpeg 프로세스와 DB 커넥션 점유가 곱해지는 것을 입구에서 끊는다(재클릭·다중 탭).
            assertThatThrownBy(() -> service.recover(RAW_SN))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
        } finally {
            release.countDown();
            first.join(5_000);
        }
        assertThat(firstFailure.get()).isNull();
        // 같은 출력 경로에 두 ffmpeg 이 동시에 write 하지 않는다(부분 기록 JPEG 확정 방지).
        verify(deidentFrameAttacher, times(1)).attachDeidentFrames(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("복구가_끝나면_같은_영상을_다시_실행할_수_있다")
    void releasesClaimAfterRun() {
        givenVideo();
        givenFrames(framesWithVideoFrameNo(0L, 30L));
        givenDeidVideoPath();
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenReturn(2);

        service.recover(RAW_SN);

        // 클레임이 finally 로 해제되지 않으면 그 영상은 재기동 전까지 영구히 409 가 된다.
        assertThat(service.recover(RAW_SN).items()).singleElement()
                .extracting(DeidentFrameRecoveryResponse.Item::result)
                .isEqualTo("RECOVERED");
    }

    @Test
    @DisplayName("실행이_실패해도_클레임이_해제된다")
    void releasesClaimWhenRunThrows() {
        when(srcRepository.findRawSnsMissingDeidFramePath(any()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.recover(null)).isInstanceOf(IllegalStateException.class);

        assertThat(service.recover(null).items()).isEmpty();
    }

    @Test
    @DisplayName("dry_run은_동시_실행_가드를_타지_않는다")
    void previewIsNotBlockedByRunningRecovery() throws Exception {
        givenVideo();
        givenFrames(framesWithVideoFrameNo(0L, 30L));
        givenDeidVideoPath();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deidentFrameAttacher.attachDeidentFrames(any(), any(), anyBoolean())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 2;
        });
        Thread first = new Thread(() -> service.recover(RAW_SN));
        first.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            // dry-run 은 읽기 전용이라 ffmpeg·쓰기 경합을 만들지 않는다 — 진행 중 상태를 볼 수 있어야 한다.
            assertThat(service.preview(RAW_SN).dryRun()).isTrue();
        } finally {
            release.countDown();
            first.join(5_000);
        }
    }

    // ────────────────────────── fixtures ──────────────────────────

    private void givenVideo() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "C-" + RAW_SN, "CCTV", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, storage.resolve("orgnl.mp4").toString(),
                LocalDateTime.now(), 30);
        setField(raw, "rawSn", RAW_SN);
        raw.markDeidentified("Y");
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
    }

    private void givenFrames(List<LsDataSrc> frames) {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(frames);
    }

    private void givenDeidVideoPath() {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                RAW_SN, "req-" + RAW_SN, storage.resolve("orgnl.mp4").toString(), "system");
        procLog.succeed(deidVideo.toString());
        when(procLogRepository.findLatestSuccessByDataRawSn(RAW_SN)).thenReturn(Optional.of(procLog));
    }

    private void givenMarks(int... frameIndexes) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < frameIndexes.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"frameIndex\":").append(frameIndexes[i]).append(",\"timestamp\":\"00:00\"}");
        }
        json.append(']');
        LsMarking marking = LsMarking.createAuto(RAW_SN, 30, json.toString(), "1", 30.0);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of(marking));
    }

    /** 구간 A 재현 — 비식별 경로도 VDO_FRM_NO 도 없는 레거시 프레임. */
    private List<LsDataSrc> framesWithoutVideoFrameNo(int count) {
        List<LsDataSrc> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LsDataSrc src = LsDataSrc.create(RAW_SN, i, "/frames/raw/" + RAW_SN + "/frame-" + i + ".jpg",
                    LocalDateTime.now());
            setField(src, "srcSn", 101L + i);
            frames.add(src);
        }
        return frames;
    }

    /** 구간 B 재현 — VDO_FRM_NO 는 있고 비식별 경로만 비어 있는 프레임. */
    private List<LsDataSrc> framesWithVideoFrameNo(Long... videoFrameNos) {
        List<LsDataSrc> frames = new ArrayList<>(videoFrameNos.length);
        for (int i = 0; i < videoFrameNos.length; i++) {
            LsDataSrc src = LsDataSrc.create(RAW_SN, i, videoFrameNos[i],
                    "/frames/raw/" + RAW_SN + "/frame-" + i + ".jpg", LocalDateTime.now());
            setField(src, "srcSn", 101L + i);
            frames.add(src);
        }
        return frames;
    }

    /** 복구 완료 상태 — 비식별 경로까지 채워진 프레임. */
    private List<LsDataSrc> framesRecovered(Long... videoFrameNos) {
        List<LsDataSrc> frames = framesWithVideoFrameNo(videoFrameNos);
        for (int i = 0; i < frames.size(); i++) {
            frames.get(i).attachDeidPath("/frames/deid/" + RAW_SN + "/frame-" + i + ".jpg");
        }
        return frames;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
