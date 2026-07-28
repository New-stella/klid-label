package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.video.dto.ResolutionBackfillResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.ResolutionBackfillService;
import kr.co.cudo.authoring.video.service.ResolutionBackfillTxService;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생 이관 백필 단위 테스트 — <b>실제 파일시스템({@code @TempDir})</b> 기반으로 검증한다.
 * 파일 계층을 우회하면 GREEN 이 거짓 신호가 되기 때문이다(Copy→Verify→커밋→유예등록→유예삭제 순서 검증).
 *
 * <p>raw base 와 deid base 는 <b>같은 디렉토리</b>다 — 운영(prd) 동일-base 환경에서도 서브트리
 * 재배치가 실제로 일어나야 한다는 요구를 재현한다.
 *
 * <p><b>유예 삭제</b>: 기본 유예는 15분(운영)이라 대부분의 테스트는 "구 파일이 <b>살아 있고</b> 대기열에
 * 올라간다"를 단언한다. 유예 경과 후 삭제는 유예를 0 으로 낮춰(={@code graceMinutes=0}) 검증한다 —
 * 운영 설정에서는 TTL 미만 유예가 기동 거부되므로 별도 테스트로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolutionBackfillServiceTest {

    private static final long PARENT = 17L;
    private static final long DERIVATIVE = 19L;

    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock ResolutionBackfillTxService txService;

    ResolutionBackfillService service;
    CacheManager cacheManager;

    @TempDir Path base;

    @BeforeEach
    void setup() {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cacheManager = manager;
        // mock 이 아닌 <b>실제 캐시 + 실제 evictor</b> — "evict 를 호출했다"가 아니라 캐시 상태를 관측한다.
        service = new ResolutionBackfillService(videoRepository, srcRepository, txService,
                new StreamMetaCacheEvictor(manager));
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", base.toString());
        // 운영(prd)처럼 raw base 와 deid base 가 같은 디렉토리인 환경을 재현한다.
        ReflectionTestUtils.setField(service, "storageRawPath", base.toString());
        // 기본 유예 = 운영 기본값(15분). 유예 경과 시나리오는 개별 테스트가 0 으로 낮춘다.
        ReflectionTestUtils.setField(service, "staleGraceMinutes", 15L);
    }

    /** 유예를 0 으로 낮춰 "유예 경과" 상태를 만든다(스윕이 즉시 삭제 가능). */
    private void elapseGrace() {
        ReflectionTestUtils.setField(service, "staleGraceMinutes", 0L);
    }

    /** 유예 대기열 마커 수. */
    private long pendingMarkers() throws IOException {
        Path dir = base.resolve(".pending-delete");
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var s = Files.list(dir)) {
            return s.count();
        }
    }

    /** 구(잘못된) 위치의 파생 산출물 — {rawBase}/resolution/{rawSn}/frames/frame-0.jpg 등. */
    private Path seedLegacyArtifacts(long derivativeRawSn, long parentRawSn) throws IOException {
        Path legacyFrame = base.resolve("resolution/" + derivativeRawSn + "/frames/frame-0.jpg");
        Files.createDirectories(legacyFrame.getParent());
        Files.writeString(legacyFrame, "deid-frame-bytes");
        Path legacyVideo = base.resolve("resolution/" + parentRawSn + "/RESL_480P/video/RESL_480P.mp4");
        Files.createDirectories(legacyVideo.getParent());
        Files.writeString(legacyVideo, "deid-video-bytes");
        return legacyFrame;
    }

    private void stubTarget(long derivativeRawSn, long parentRawSn, String augTypeCd) {
        when(videoRepository.findResolutionDerivativeTargets())
                .thenReturn(List.<Object[]>of(new Object[]{derivativeRawSn, parentRawSn, augTypeCd}));
        stubAudit(auditRow(1L, derivativeRawSn, base.resolve("resolution/gone.jpg").toString()));
    }

    /** 감사 대상 프레임 행 — {@code [srcSn, rawSn, deidPath]}. */
    private Object[] auditRow(long srcSn, long rawSn, String deidPath) {
        return new Object[]{srcSn, rawSn, deidPath};
    }

    /** 감사 커서 조회 스텁 — 1페이지만 반환(페이지 크기 미만이면 루프 종료). */
    private void stubAudit(Object[]... rows) {
        when(videoRepository.findDeidFramePathsAfter(anyLong(), anyInt())).thenReturn(List.of(rows));
    }

    private LsDataSrc frame(long srcSn, String deidPath) {
        LsDataSrc f = LsDataSrc.create(DERIVATIVE, 0L, 0L, deidPath, deidPath, null);
        ReflectionTestUtils.setField(f, "srcSn", srcSn);
        return f;
    }

    private LsDataRaw derivativeRaw(String videoPath) {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, videoPath, null, 30);
        ReflectionTestUtils.setField(raw, "rawSn", DERIVATIVE);
        ReflectionTestUtils.setField(raw, "orgnlRawSn", PARENT);
        return raw;
    }

    @Test
    @DisplayName("백필은_파생_산출물을_deid_서브트리로_복사검증하고_커밋후_구파일을_유예대기열에_올린다_즉시삭제금지")
    void migratesArtifactsCopyVerifyCommitDefer() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));

        ResolutionBackfillResponse res = service.run(false);

        assertThat(res.migratedRawSns()).containsExactly(DERIVATIVE);
        assertThat(res.failures()).isEmpty();
        assertThat(res.unmatchedRawSns()).isEmpty();

        // 새 위치에 파일이 생기고, 구 파일은 <b>살아 있다</b> — 2노드 stale 캐시가 옛 경로를 TTL 동안
        // 계속 해석하므로 즉시 지우면 그 노드가 500 이 된다(유예 삭제의 핵심 계약).
        Path newFrame = base.resolve("frames/deid/" + DERIVATIVE + "/frame-0.jpg");
        Path newVideo = base.resolve("videos/resolution/" + PARENT + "/" + DERIVATIVE + "/RESL_480P.mp4");
        assertThat(newFrame).exists();
        assertThat(newVideo).exists();
        assertThat(legacyFrame).exists();
        assertThat(legacyVideo).exists();
        // 두 파일은 바이트 동일 사본이라 stale 을 보는 노드도 정상 재생된다.
        assertThat(Files.readString(legacyFrame)).isEqualTo(Files.readString(newFrame));
        // 대기열에 올라가 있고, 응답이 그 수를 노출한다(운영자가 상태를 볼 수 있어야 한다).
        assertThat(pendingMarkers()).isGreaterThanOrEqualTo(2L);
        assertThat(res.stalePendingCount()).isGreaterThanOrEqualTo(2);
        assertThat(res.staleDeletedCount()).isZero();

        // DB 커밋은 새 경로로 이뤄진다(원본 경로 NULL 화는 TxService 쿼리 책임).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(txService).commitRelocation(eq(DERIVATIVE), captor.capture(), eq(newVideo.toString()));
        assertThat(captor.getValue()).containsEntry(500L, newFrame.toString());
    }

    @Test
    @DisplayName("유예가_지나면_다음_스윕이_구파일을_실제로_삭제한다_DB미참조_확인후")
    void sweepDeletesAfterGraceElapsed() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));
        // 이관 후 DB 는 새 경로를 가리킨다 = 구 경로 참조 0.
        when(videoRepository.countReferencesToFilePath(anyString())).thenReturn(0L);

        service.run(false);
        assertThat(legacyFrame).exists(); // 유예 중

        // 유예 경과(운영에선 15분) — 주기 스윕이 정리한다.
        elapseGrace();
        ResolutionBackfillService.StaleSweepResult swept = service.sweepPendingDeletions();

        assertThat(legacyFrame).doesNotExist();
        assertThat(legacyVideo).doesNotExist();
        assertThat(swept.deleted()).isGreaterThanOrEqualTo(2);
        assertThat(swept.pending()).isZero();
        assertThat(pendingMarkers()).isZero();
        // 새 위치 산출물은 보존된다.
        assertThat(base.resolve("frames/deid/" + DERIVATIVE + "/frame-0.jpg")).exists();
    }

    @Test
    @DisplayName("유예가_지나도_DB가_아직_그_파일을_참조하면_삭제하지_않는다_삭제직전_재판정")
    void sweepKeepsStillReferencedFileEvenAfterGrace() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));
        // 다른 행이 아직 이 경로를 쓰고 있다(구 규약 공유 파일) — 유예가 지나도 지우면 안 된다.
        when(videoRepository.countReferencesToFilePath(anyString())).thenReturn(1L);

        service.run(false);
        elapseGrace();
        ResolutionBackfillService.StaleSweepResult swept = service.sweepPendingDeletions();

        assertThat(legacyFrame).exists();
        assertThat(legacyVideo).exists();
        assertThat(swept.deleted()).isZero();
        assertThat(swept.pending()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("이관_완료후_stream-meta_캐시가_실제로_비워진다_커밋시점_evict_이후_재설치분_포함")
    void migrationEvictsStreamMetaCacheAfterCompletion() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_STREAM_META);

        // 커밋 시점(TxService)은 mock 이라 evict 하지 않는다. 게다가 <b>커밋 전에 읽은 요청</b>이 커밋
        // 직후 옛 값을 다시 put 하는 상황을 재현한다 — migrateOne 완료 후의 재-evict 가 없으면 남는다.
        doAnswer(inv -> {
            cache.put(DERIVATIVE, new VideoStreamService.StreamMeta(
                    Paths.get(legacyVideo.toString()), 1_000L, MediaType.parseMediaType("video/mp4")));
            return 1;
        }).when(txService).commitRelocation(eq(DERIVATIVE), any(), any());

        service.run(false);

        assertThat(cache.get(DERIVATIVE)).isNull();
    }

    @Test
    @DisplayName("백필_재실행시_이미_이관된_rawSn은_재복사하지_않는다")
    void reRunSkipsAlreadyMigrated() throws IOException {
        Path newFrame = base.resolve("frames/deid/" + DERIVATIVE + "/frame-0.jpg");
        Files.createDirectories(newFrame.getParent());
        Files.writeString(newFrame, "deid-frame-bytes");
        Path newVideo = base.resolve("videos/resolution/" + PARENT + "/" + DERIVATIVE + "/RESL_480P.mp4");
        Files.createDirectories(newVideo.getParent());
        Files.writeString(newVideo, "deid-video-bytes");

        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        stubAudit();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, newFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(newVideo.toString())));

        ResolutionBackfillResponse res = service.run(false);

        assertThat(res.skippedRawSns()).containsExactly(DERIVATIVE);
        assertThat(res.migratedRawSns()).isEmpty();
        verify(txService, never()).commitRelocation(anyLong(), any(), any());
    }

    @Test
    @DisplayName("백필_1건_실패시_해당_rawSn만_스킵되고_나머지는_이관되며_경로는_raw_로_유지됨")
    void isolatesFailurePerTargetAndLeavesPathUnchanged() throws IOException {
        long okRawSn = 18L;
        // 정상 대상(18) — 구 위치 파일 존재.
        Path okFrame = base.resolve("resolution/" + okRawSn + "/frames/frame-0.jpg");
        Files.createDirectories(okFrame.getParent());
        Files.writeString(okFrame, "ok-frame");
        Path okVideo = base.resolve("resolution/" + PARENT + "/RESL_720P/video/RESL_720P.mp4");
        Files.createDirectories(okVideo.getParent());
        Files.writeString(okVideo, "ok-video");
        // 실패 대상(19) — 구 위치 파일이 실제로 없어 이관 실패.
        Path missingFrame = base.resolve("resolution/" + DERIVATIVE + "/frames/frame-0.jpg");

        when(videoRepository.findResolutionDerivativeTargets()).thenReturn(List.<Object[]>of(
                new Object[]{DERIVATIVE, PARENT, "RESL_480P"},
                new Object[]{okRawSn, PARENT, "RESL_720P"}));
        stubAudit(auditRow(1L, DERIVATIVE, base.resolve("resolution/gone.jpg").toString()),
                auditRow(2L, okRawSn, base.resolve("resolution/gone2.jpg").toString()));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, missingFrame.toString())));
        LsDataSrc okSrc = LsDataSrc.create(okRawSn, 0L, 0L, okFrame.toString(), okFrame.toString(), null);
        ReflectionTestUtils.setField(okSrc, "srcSn", 600L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(okRawSn)).thenReturn(List.of(okSrc));
        LsDataRaw okRaw = derivativeRaw(okVideo.toString());
        ReflectionTestUtils.setField(okRaw, "rawSn", okRawSn);
        when(videoRepository.findById(okRawSn)).thenReturn(Optional.of(okRaw));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(base.resolve("resolution/x.mp4").toString())));

        ResolutionBackfillResponse res = service.run(false);

        // 실패 1건 격리 — 나머지는 정상 이관.
        assertThat(res.failures()).hasSize(1);
        assertThat(res.failures().get(0).rawSn()).isEqualTo(DERIVATIVE);
        assertThat(res.migratedRawSns()).containsExactly(okRawSn);
        // 실패 대상은 DB 경로를 건드리지 않는다(raw 경로 유지 — NULL 로 만들지 않는다).
        verify(txService, never()).commitRelocation(eq(DERIVATIVE), any(), any());
        verify(txService).commitRelocation(eq(okRawSn), any(), anyString());
    }

    @Test
    @DisplayName("백필은_ACCEPTED_가_아닌_예약을_건드리지_않는다")
    void doesNotTouchNonAcceptedReservations() {
        // 대상 조인이 ACCEPTED 만 반환하므로 PENDING/FAILED 예약의 파생 RAW 는 목록에 없다.
        when(videoRepository.findResolutionDerivativeTargets()).thenReturn(List.of());
        stubAudit(auditRow(1L, 999L, base.resolve("resolution/gone.jpg").toString()));

        ResolutionBackfillResponse res = service.run(false);

        assertThat(res.targetCount()).isZero();
        assertThat(res.migratedRawSns()).isEmpty();
        // 대상 밖인데 서브트리 밖 경로를 가진 행은 조용히 넘기지 않고 unmatched 로 드러낸다.
        assertThat(res.unmatchedRawSns()).containsExactly(999L);
        verify(txService, never()).commitRelocation(anyLong(), any(), any());
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("dryRun은_대상만_산출하고_파일과_DB를_변경하지_않는다")
    void dryRunChangesNothing() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");

        ResolutionBackfillResponse res = service.run(true);

        assertThat(res.targetCount()).isEqualTo(1);
        assertThat(res.migratedRawSns()).isEmpty();
        assertThat(legacyFrame).exists();
        assertThat(base.resolve("frames/deid/" + DERIVATIVE)).doesNotExist();
        verify(txService, never()).commitRelocation(anyLong(), any(), any());
    }

    // ---------------------------------------------------------------------
    // H-3 / M-2 / M-3 — 원천 검증 · 손상 파일 · 레거시 잔존 재시도
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("복사_원천이_원본프레임_서브트리면_복사도_삭제도_하지_않고_실패로_격리된다")
    void rejectsSourcePointingAtRawFrameSubtree() throws IOException {
        // given — DB 가 오염돼 파생의 비식별 경로가 <부모 원본 프레임>을 가리킨다.
        Path parentOriginal = base.resolve("frames/raw/" + PARENT + "/000001.jpg");
        Files.createDirectories(parentOriginal.getParent());
        Files.writeString(parentOriginal, "PARENT-ORIGINAL-PIXELS");
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        Files.createDirectories(legacyVideo.getParent());
        Files.writeString(legacyVideo, "video");

        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, parentOriginal.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));

        ResolutionBackfillResponse res = service.run(false);

        // then — 실패로 격리되고 <부모 원본은 살아있으며> 비식별 벌로 복사되지 않았다.
        assertThat(res.failures()).hasSize(1);
        assertThat(res.migratedRawSns()).isEmpty();
        assertThat(parentOriginal).exists();
        assertThat(base.resolve("frames/deid/" + DERIVATIVE + "/000001.jpg")).doesNotExist();
        verify(txService, never()).commitRelocation(anyLong(), any(), any());
    }

    @Test
    @DisplayName("직전_실행이_남긴_0바이트_목적지는_이관완료로_승인되지_않는다")
    void zeroByteDestinationIsNotAcceptedAsMigrated() throws IOException {
        // given — 원천은 이미 사라졌고 목적지에는 0바이트(절단) 파일만 남았다.
        Path missingSrc = base.resolve("resolution/" + DERIVATIVE + "/frames/frame-0.jpg");
        Path dst = base.resolve("frames/deid/" + DERIVATIVE + "/frame-0.jpg");
        Files.createDirectories(dst.getParent());
        Files.write(dst, new byte[0]);
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        Files.createDirectories(legacyVideo.getParent());
        Files.writeString(legacyVideo, "video");

        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, missingSrc.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));

        ResolutionBackfillResponse res = service.run(false);

        assertThat(res.failures()).hasSize(1);
        assertThat(res.migratedRawSns()).isEmpty();
        verify(txService, never()).commitRelocation(anyLong(), any(), any());
    }

    @Test
    @DisplayName("이미_이관된_대상도_레거시_잔존파일_유예등록_정리를_재시도한다")
    void reRunRetriesLegacyLeftoverDeletion() throws IOException {
        // given — DB 는 이미 새 경로를 가리키지만 구 위치 파일이 잔존한다(직전 실행 삭제 실패).
        Path newFrame = base.resolve("frames/deid/" + DERIVATIVE + "/frame-0.jpg");
        Files.createDirectories(newFrame.getParent());
        Files.writeString(newFrame, "deid-frame-bytes");
        Path newVideo = base.resolve("videos/resolution/" + PARENT + "/" + DERIVATIVE + "/RESL_480P.mp4");
        Files.createDirectories(newVideo.getParent());
        Files.writeString(newVideo, "deid-video-bytes");
        Path leftoverFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path leftoverVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");

        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        stubAudit();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, newFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(newVideo.toString())));
        when(videoRepository.countReferencesToFilePath(anyString())).thenReturn(0L);

        // 유예 중 실행 — skip 이어도 잔존물이 대기열에 오르고, 아직 지워지지 않는다.
        ResolutionBackfillResponse res = service.run(false);
        assertThat(res.skippedRawSns()).containsExactly(DERIVATIVE);
        assertThat(leftoverFrame).exists();
        assertThat(res.stalePendingCount()).isGreaterThanOrEqualTo(2);

        // 유예 경과 후 재실행 — 백필 재실행 자체가 스윕을 수행한다(스케줄러가 꺼져 있어도 정리됨).
        elapseGrace();
        ResolutionBackfillResponse second = service.run(false);

        assertThat(leftoverFrame).doesNotExist();
        assertThat(leftoverVideo).doesNotExist();
        assertThat(second.staleDeletedCount()).isGreaterThanOrEqualTo(2);
        // 새 위치 산출물은 보존된다.
        assertThat(newFrame).exists();
        assertThat(newVideo).exists();
    }

    @Test
    @DisplayName("감사는_서빙과_같은_판정기로_수행되어_rawSn별_사유를_집계한다")
    void auditAggregatesViolationsWithSameDecider() throws IOException {
        // given — 정상 비식별 프레임 1건 + 부재 1건 + 원본 서브트리 1건
        Path okDeid = base.resolve("frames/deid/26/000001.jpg");
        Files.createDirectories(okDeid.getParent());
        Files.writeString(okDeid, "deid");
        Path rawFrame = base.resolve("frames/raw/27/000001.jpg");
        Files.createDirectories(rawFrame.getParent());
        Files.writeString(rawFrame, "orig");

        when(videoRepository.findResolutionDerivativeTargets()).thenReturn(List.of());
        stubAudit(auditRow(1L, 26L, okDeid.toString()),
                auditRow(2L, 27L, rawFrame.toString()),
                auditRow(3L, 28L, base.resolve("frames/deid/28/none.jpg").toString()));

        ResolutionBackfillResponse res = service.run(true);

        assertThat(res.auditScannedFrames()).isEqualTo(3L);
        assertThat(res.auditViolations()).hasSize(2);
        assertThat(res.auditViolations()).noneMatch(v -> v.rawSn() == 26L);
        assertThat(res.auditViolations()).anyMatch(v -> v.rawSn() == 27L
                && v.verdict().equals("OUTSIDE_DEID_SUBTREE"));
        assertThat(res.auditViolations()).anyMatch(v -> v.rawSn() == 28L && v.verdict().equals("MISSING"));
        // A-2 — 전수 스캔이면 잘림 플래그가 서지 않는다.
        assertThat(res.auditTruncated()).isFalse();
    }

    // ---------------------------------------------------------------------
    // A-2 — 감사 상한 도달 시 "부분 스캔" 을 결과에 드러낸다
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A2_감사가_상한에서_잘리면_auditTruncated가_true로_보고된다")
    void auditReportsTruncationWhenRowLimitReached() {
        when(videoRepository.findResolutionDerivativeTargets()).thenReturn(List.of());
        // 페이지가 계속 가득 차 상한(200,000행)에 도달한다. 경로는 base 밖이라 파일시스템 접근 없이 판정된다.
        List<Object[]> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            fullPage.add(auditRow(i + 1L, 777L, "/outside-base/x.jpg"));
        }
        when(videoRepository.findDeidFramePathsAfter(anyLong(), eq(500))).thenReturn(List.copyOf(fullPage));
        // 상한 도달 후 1행 프로브 — 남은 행이 존재한다(=잘림).
        when(videoRepository.findDeidFramePathsAfter(anyLong(), eq(1)))
                .thenReturn(List.<Object[]>of(auditRow(999_999L, 777L, "/outside-base/x.jpg")));

        ResolutionBackfillResponse res = service.run(true);

        assertThat(res.auditScannedFrames()).isEqualTo(200_000L);
        // 잘린 상태에서는 "위반 0" 이든 아니든 전수 근거가 아니다 — 반드시 표시된다.
        assertThat(res.auditTruncated()).isTrue();
    }

    @Test
    @DisplayName("A2_남은_행이_없으면_상한에_도달해도_잘림으로_보고하지_않는다")
    void auditDoesNotReportTruncationWhenNoRowsRemain() {
        when(videoRepository.findResolutionDerivativeTargets()).thenReturn(List.of());
        List<Object[]> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            fullPage.add(auditRow(i + 1L, 777L, "/outside-base/x.jpg"));
        }
        when(videoRepository.findDeidFramePathsAfter(anyLong(), eq(500))).thenReturn(List.copyOf(fullPage));
        when(videoRepository.findDeidFramePathsAfter(anyLong(), eq(1))).thenReturn(List.of());

        ResolutionBackfillResponse res = service.run(true);

        assertThat(res.auditScannedFrames()).isEqualTo(200_000L);
        assertThat(res.auditTruncated()).isFalse();
    }

    // ---------------------------------------------------------------------
    // A-4 — AUG 예약행이 없는 파생도 발견되고, 프리셋 미확정을 결과에 드러낸다
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A4_AUG행이_없어_프리셋을_못구한_파생도_대상이_되고_프레임은_이관되며_사실이_보고된다")
    void discoversDerivativeWithoutAugAndReportsUnresolvedPreset() throws IOException {
        Path legacyFrame = base.resolve("resolution/" + DERIVATIVE + "/frames/frame-0.jpg");
        Files.createDirectories(legacyFrame.getParent());
        Files.writeString(legacyFrame, "deid-frame-bytes");
        Path legacyVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        Files.createDirectories(legacyVideo.getParent());
        Files.writeString(legacyVideo, "deid-video-bytes");

        // 발견 쿼리는 파생 축이라 AUG 행이 없어도 행을 돌려준다(augTypeCd=null).
        when(videoRepository.findResolutionDerivativeTargets())
                .thenReturn(List.<Object[]>of(new Object[]{DERIVATIVE, PARENT, null}));
        stubAudit();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(legacyVideo.toString())));

        ResolutionBackfillResponse res = service.run(false);

        // 발견됨 + 프레임 이관 수행 + 프리셋 미확정 보고(침묵 누락 아님)
        assertThat(res.targetCount()).isEqualTo(1);
        assertThat(res.unresolvedPresetRawSns()).containsExactly(DERIVATIVE);
        assertThat(res.migratedRawSns()).containsExactly(DERIVATIVE);
        assertThat(base.resolve("frames/deid/" + DERIVATIVE + "/frame-0.jpg")).exists();
        // 영상 파일은 프리셋 없이는 경로 규약을 만들 수 없으므로 손대지 않는다(경로 유지).
        assertThat(legacyVideo).exists();
        verify(txService).commitRelocation(eq(DERIVATIVE), any(), eq(null));
    }

    // ---------------------------------------------------------------------
    // A-6 — 공유 영상 파일 오삭제 방지
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A6_구_영상파일을_다른_파생이_아직_참조중이면_유예가_지나도_삭제하지_않는다")
    void keepsStaleVideoWhenStillReferencedByAnotherDerivative() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        // 구 규약은 (부모,프리셋) 키라 이 파일을 다른 파생(예: 30)도 가리키고 있다.
        Path sharedVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(sharedVideo.toString())));
        // 이관된 프레임 경로는 참조 0, 공유 영상은 다른 행이 아직 참조.
        when(videoRepository.countReferencesToFilePath(anyString())).thenReturn(0L);
        when(videoRepository.countReferencesToFilePath(sharedVideo.toString())).thenReturn(1L);

        ResolutionBackfillResponse res = service.run(false);
        elapseGrace();
        service.sweepPendingDeletions();

        assertThat(res.migratedRawSns()).containsExactly(DERIVATIVE);
        // 새 위치(파생 rawSn 키)에 사본이 생기되, 아직 참조 중인 공유 원본은 유예가 지나도 살아남는다.
        assertThat(base.resolve("videos/resolution/" + PARENT + "/" + DERIVATIVE + "/RESL_480P.mp4")).exists();
        assertThat(sharedVideo).exists();
        // 참조가 없는 프레임은 정리된다(공유 보존이 전체 정리를 막지 않는다).
        assertThat(legacyFrame).doesNotExist();
    }

    @Test
    @DisplayName("A6_마지막_참조가_옮겨가면_유예_경과후_구_영상파일이_삭제된다")
    void deletesStaleVideoWhenNoOtherReferenceRemains() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path sharedVideo = base.resolve("resolution/" + PARENT + "/RESL_480P/video/RESL_480P.mp4");
        stubTarget(DERIVATIVE, PARENT, "RESL_480P");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(DERIVATIVE))
                .thenReturn(List.of(frame(500L, legacyFrame.toString())));
        when(videoRepository.findById(DERIVATIVE))
                .thenReturn(Optional.of(derivativeRaw(sharedVideo.toString())));
        when(videoRepository.countReferencesToFilePath(anyString())).thenReturn(0L);

        service.run(false);
        assertThat(sharedVideo).exists(); // 유예 중에는 남는다.
        elapseGrace();
        service.sweepPendingDeletions();

        assertThat(base.resolve("videos/resolution/" + PARENT + "/" + DERIVATIVE + "/RESL_480P.mp4")).exists();
        assertThat(sharedVideo).doesNotExist();
    }

    // ---------------------------------------------------------------------
    // 유예 설정 가드 · 스윕 안전장치
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("유예가_stream-meta_TTL보다_짧으면_기동을_거부한다_설정실수_차단")
    void rejectsGraceShorterThanCacheTtl() {
        ReflectionTestUtils.setField(service, "staleGraceMinutes",
                CacheConfig.STREAM_META_TTL.toMinutes() - 1);

        assertThatThrownBy(() -> service.validateGracePeriod())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-grace-minutes");
    }

    @Test
    @DisplayName("유예가_TTL이상이면_기동을_통과한다")
    void acceptsGraceAtLeastCacheTtl() {
        ReflectionTestUtils.setField(service, "staleGraceMinutes", CacheConfig.STREAM_META_TTL.toMinutes());

        service.validateGracePeriod(); // 예외 없음
    }

    @Test
    @DisplayName("스윕은_원본프레임_서브트리를_가리키는_대기열_항목을_절대_삭제하지_않는다")
    void sweepNeverDeletesRawFrameSubtree() throws IOException {
        Path parentOriginal = base.resolve("frames/raw/" + PARENT + "/000001.jpg");
        Files.createDirectories(parentOriginal.getParent());
        Files.writeString(parentOriginal, "PARENT-ORIGINAL-PIXELS");
        // 대기열에 오염된 항목이 직접 주입된 상황(마커 내용은 신뢰 대상이 아니다).
        Path pendingDir = base.resolve(".pending-delete");
        Files.createDirectories(pendingDir);
        Files.writeString(pendingDir.resolve("deadbeef.pending"), parentOriginal.toString());
        elapseGrace();

        ResolutionBackfillService.StaleSweepResult swept = service.sweepPendingDeletions();

        assertThat(parentOriginal).exists();
        assertThat(swept.deleted()).isZero();
        // 마커는 버려 무한 재시도를 만들지 않는다.
        assertThat(pendingMarkers()).isZero();
    }

    @Test
    @DisplayName("dryRun은_대기열을_건드리지_않고_대기중_잔존물_수만_보고한다")
    void dryRunReportsPendingWithoutDeleting() throws IOException {
        Path legacyFrame = seedLegacyArtifacts(DERIVATIVE, PARENT);
        Path pendingDir = base.resolve(".pending-delete");
        Files.createDirectories(pendingDir);
        Files.writeString(pendingDir.resolve("cafe01.pending"), legacyFrame.toString());
        when(videoRepository.findResolutionDerivativeTargets()).thenReturn(List.of());
        stubAudit();
        elapseGrace();

        ResolutionBackfillResponse res = service.run(true);

        assertThat(res.stalePendingCount()).isEqualTo(1);
        assertThat(res.staleDeletedCount()).isZero();
        assertThat(legacyFrame).exists();
        assertThat(pendingMarkers()).isEqualTo(1L);
    }
}
