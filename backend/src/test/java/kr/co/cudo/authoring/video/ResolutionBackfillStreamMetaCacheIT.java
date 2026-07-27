package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.video.dto.ResolutionBackfillResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.ResolutionBackfillService;
import kr.co.cudo.authoring.video.service.ResolutionBackfillTxService;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해상도 파생 백필의 <b>stream-meta 캐시 무효화</b> 통합 검증 (실 DB + 실 캐시).
 *
 * <h3>재현한 결함</h3>
 * <p>백필이 파생영상의 비식별 영상 경로를 새 서브트리로 옮겨도 {@code stream-meta} 캐시(TTL 5분)에는
 * <b>구 경로</b>가 남아, 백필 이전에 한 번이라도 스트리밍된 rawSn 만 {@code FileNotFoundException} 으로
 * 500 이 됐다(백필 이후 첫 접근 rawSn 은 206). 원인은 파일/DB 가 아니라 캐시 stale 이다.
 *
 * <h3>검증 방식 (mock 호출 단언 금지)</h3>
 * <p>"evict 를 호출했다"를 mock 으로 단언하지 않는다. <b>실제 캐시에 값을 넣고</b> → 경로를 커밋으로 바꾸고
 * → <b>다시 조회</b>해 새 값이 나오는지 확인한다. 재조회는 서빙과 동일한 원천
 * ({@code LS_DEIDENT_PROC_LOG} 최신 SUCCEEDED 경로 = {@code VideoStreamService.resolveDeidPath})을
 * 로더로 사용한다 — 무효화가 없으면 캐시 히트로 <b>구 경로</b>가 반환되어 이 테스트는 실패한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ResolutionBackfillStreamMetaCacheIT {

    /** 시드 격리 마커 — cleanup 이 이 접두사로만 정리한다. */
    private static final String SEED_CLIP_PREFIX = "cache-it-clip-";

    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final LsDataSrcRepository srcRepository;
    private final ResolutionBackfillTxService txService;
    private final ResolutionBackfillService backfillService;
    private final CacheManager cacheManager;

    private Long parentRawSn;
    private Long derivativeRawSn;
    private String oldVideoPath;
    private String newVideoPath;
    private Long frameSrcSn;
    private String originalRawPath;
    private String originalDeidPath;
    private Object originalGrace;

    @TempDir
    Path storage;

    @Autowired
    ResolutionBackfillStreamMetaCacheIT(VideoRepository videoRepository,
                                        LsDeidentProcLogRepository procLogRepository,
                                        LsDataSrcRepository srcRepository,
                                        ResolutionBackfillTxService txService,
                                        ResolutionBackfillService backfillService,
                                        CacheManager cacheManager) {
        this.videoRepository = videoRepository;
        this.procLogRepository = procLogRepository;
        this.srcRepository = srcRepository;
        this.txService = txService;
        this.backfillService = backfillService;
        this.cacheManager = cacheManager;
    }

    @BeforeEach
    void seed() {
        String unique = SEED_CLIP_PREFIX + System.nanoTime();
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                unique, "cctv-1", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/" + unique + ".mp4", null, 30));
        parentRawSn = parent.getRawSn();

        // 구 경로 = {deidBase}/videos/resolution/{parent}/RESL_480P.mp4 (파생 rawSn 없는 구 규약)
        oldVideoPath = "/nas/deid/videos/resolution/" + parentRawSn + "/RESL_480P.mp4";
        LsDataRaw derivative = videoRepository.save(
                LsDataRaw.createFromResolution(parent, oldVideoPath, "RESL_480P"));
        derivativeRawSn = derivative.getRawSn();
        // 신 경로 = 파생 rawSn 이 키에 포함된 규약(백필 목적지)
        newVideoPath = "/nas/deid/videos/resolution/" + parentRawSn + "/" + derivativeRawSn + "/RESL_480P.mp4";

        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                derivativeRawSn, null, oldVideoPath, "cache-it");
        procLog.succeed(oldVideoPath);
        procLogRepository.save(procLog);

        streamMetaCache().evict(derivativeRawSn);
    }

    @AfterEach
    void cleanup() {
        // 공유 싱글턴 빈에 주입한 테스트용 경로/유예를 반드시 원복한다(컨텍스트 캐시 오염 방지).
        if (originalRawPath != null) {
            ReflectionTestUtils.setField(backfillService, "storageRawPath", originalRawPath);
            ReflectionTestUtils.setField(backfillService, "storageDeidentifiedPath", originalDeidPath);
            ReflectionTestUtils.setField(backfillService, "staleGraceMinutes", originalGrace);
            originalRawPath = null;
        }
        if (frameSrcSn != null) {
            srcRepository.deleteById(frameSrcSn);
            frameSrcSn = null;
        }
        if (derivativeRawSn != null) {
            streamMetaCache().evict(derivativeRawSn);
            procLogRepository.findAllByDataRawSnOrderByReqDtDesc(derivativeRawSn)
                    .forEach(procLogRepository::delete);
            videoRepository.deleteById(derivativeRawSn);
        }
        if (parentRawSn != null) {
            videoRepository.deleteById(parentRawSn);
        }
    }

    @Test
    @DisplayName("백필_커밋후_stream-meta_캐시가_실제로_무효화되어_재조회시_새_경로가_반환된다")
    void backfillCommitInvalidatesStreamMetaCache() {
        Cache cache = streamMetaCache();
        // given — 백필 이전에 한 번 스트리밍된 상태(캐시에 구 경로 메타가 적재됨)를 재현한다.
        cache.put(derivativeRawSn, meta(oldVideoPath, 1_000L));
        assertThat(resolvedPathFromCache()).isEqualTo(oldVideoPath);

        // when — 백필의 DB 커밋 단계(파일 이관 후 경로 원자 커밋).
        txService.commitRelocation(derivativeRawSn, Map.of(), newVideoPath);

        // then 1 — DB 는 새 경로로 갱신됐다(영상 경로 + 비식별 procLog 경로).
        assertThat(videoRepository.findById(derivativeRawSn).orElseThrow().getRawFilePathNm())
                .isEqualTo(newVideoPath);
        assertThat(procLogRepository.findLatestSuccessByDataRawSn(derivativeRawSn).orElseThrow()
                .getDeIdntfFilePathNm()).isEqualTo(newVideoPath);

        // then 2 — 캐시 엔트리가 실제로 제거됐다(구 값 잔존 금지).
        assertThat(cache.get(derivativeRawSn)).isNull();

        // then 3 — 재조회(캐시 미스 → 서빙과 동일 원천에서 로드)하면 <b>새 경로</b>가 나온다.
        //          무효화가 없으면 여기서 캐시 히트로 구 경로가 반환되어 실패한다(500 재현 지점).
        VideoStreamService.StreamMeta reloaded =
                cache.get(derivativeRawSn, () -> meta(resolveDeidPathFromDb(), 2_000L));
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.path().toString()).isEqualTo(newVideoPath);
    }

    @Test
    @DisplayName("영상경로_변경이_없는_프레임전용_커밋에서도_캐시는_stale로_남지_않는다")
    void frameOnlyRelocationAlsoInvalidatesCache() {
        Cache cache = streamMetaCache();
        cache.put(derivativeRawSn, meta(oldVideoPath, 1_000L));

        // 프레임만 이관(newVideoPath=null) — 영상 경로는 불변이지만 파생 산출물이 옮겨진 상태다.
        txService.commitRelocation(derivativeRawSn, Map.of(), null);

        assertThat(cache.get(derivativeRawSn)).isNull();
    }

    @Test
    @DisplayName("백필_전체실행이_migrateOne을_실제로_태워_실파일_이관후_캐시를_비우고_구파일은_유예후에만_삭제한다")
    void fullRunMigratesRealFilesEvictsCacheAndDefersDeletion() throws Exception {
        // given — 서비스가 실제로 파일을 만지도록 저장소 base 를 임시 디렉토리로 바꾼다(원복은 @AfterEach).
        originalRawPath = (String) ReflectionTestUtils.getField(backfillService, "storageRawPath");
        originalDeidPath = (String) ReflectionTestUtils.getField(backfillService, "storageDeidentifiedPath");
        originalGrace = ReflectionTestUtils.getField(backfillService, "staleGraceMinutes");
        ReflectionTestUtils.setField(backfillService, "storageRawPath", storage.toString());
        ReflectionTestUtils.setField(backfillService, "storageDeidentifiedPath", storage.toString());
        ReflectionTestUtils.setField(backfillService, "staleGraceMinutes", 15L);

        // 구(레거시) 위치의 실제 파생 프레임 파일 + 그 경로를 가리키는 LS_DATA_SRC 행.
        Path legacyFrame = storage.resolve("resolution/" + derivativeRawSn + "/frames/frame-0.jpg");
        Files.createDirectories(legacyFrame.getParent());
        Files.writeString(legacyFrame, "DEID-FRAME-BYTES");
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(derivativeRawSn, 0L, 0L, legacyFrame.toString(), legacyFrame.toString(), null));
        frameSrcSn = frame.getSrcSn();

        // 백필 이전에 한 번 스트리밍된 상태(캐시에 구 경로 메타 적재).
        Cache cache = streamMetaCache();
        cache.put(derivativeRawSn, meta(oldVideoPath, 1_000L));

        // when — dryRun=false 전체 실행(= migrateOne 을 실제로 통과).
        ResolutionBackfillResponse res = backfillService.run(false);

        // then 1 — 실파일이 비식별 서브트리로 이관됐고 DB 도 새 경로를 가리킨다.
        Path newFrame = storage.resolve("frames/deid/" + derivativeRawSn + "/frame-0.jpg");
        assertThat(res.migratedRawSns()).contains(derivativeRawSn);
        assertThat(newFrame).exists();
        assertThat(srcRepository.findById(frameSrcSn).orElseThrow().getDeIdntfSrcFilePathNm())
                .isEqualTo(newFrame.toString());

        // then 2 — 커밋 후 캐시가 실제로 비워졌다(evict 가 없으면 구 경로가 히트한다).
        assertThat(cache.get(derivativeRawSn)).isNull();

        // then 3 — <b>구 파일은 아직 살아 있다</b>. 2노드 stale 캐시가 TTL 동안 옛 경로를 해석해도
        //          바이트 동일 사본이 있어 정상 재생된다(즉시 삭제였다면 그 노드는 500).
        assertThat(legacyFrame).exists();
        assertThat(Files.readString(legacyFrame)).isEqualTo(Files.readString(newFrame));
        assertThat(res.stalePendingCount()).isGreaterThanOrEqualTo(1);
        assertThat(res.staleDeletedCount()).isZero();

        // then 4 — 유예가 지나면(주기 스윕) 비로소 삭제된다. 새 위치 산출물은 보존.
        ReflectionTestUtils.setField(backfillService, "staleGraceMinutes", 0L);
        ResolutionBackfillService.StaleSweepResult swept = backfillService.sweepPendingDeletions();

        assertThat(swept.deleted()).isGreaterThanOrEqualTo(1);
        assertThat(legacyFrame).doesNotExist();
        assertThat(newFrame).exists();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Cache streamMetaCache() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_STREAM_META);
        assertThat(cache).as("stream-meta 캐시가 구성돼 있어야 한다").isNotNull();
        return cache;
    }

    /** 캐시에 남아 있는 해석 경로(없으면 null). */
    private String resolvedPathFromCache() {
        Cache.ValueWrapper wrapper = streamMetaCache().get(derivativeRawSn);
        if (wrapper == null || wrapper.get() == null) {
            return null;
        }
        return ((VideoStreamService.StreamMeta) wrapper.get()).path().toString();
    }

    /** 서빙과 동일 원천 — 최신 SUCCEEDED procLog 의 비식별 영상 경로. */
    private String resolveDeidPathFromDb() {
        return procLogRepository.findLatestSuccessByDataRawSn(derivativeRawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .orElseThrow();
    }

    private static VideoStreamService.StreamMeta meta(String path, long contentLength) {
        Path resolved = Paths.get(path);
        return new VideoStreamService.StreamMeta(resolved, contentLength, MediaType.parseMediaType("video/mp4"));
    }
}
