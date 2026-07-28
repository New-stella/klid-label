package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S7-STREAM (HIGH · CWE-359/525) — <b>부모 신고 구간에 파생영상 스트리밍이 차단</b>되는지 실 DB·실 캐시로 검증.
 *
 * <h3>재현한 결함</h3>
 * 해상도 파생영상은 부모의 <b>비식별 영상 파일을 그대로 복사</b>해 만들어지고({@code ResolutionFileMaterializer}),
 * 확정 시 자기 행에 {@code deIdntfYn='Y'} + 자기 SUCCESS procLog 가 커밋된다. 이후 부모에 비식별 누락 신고가
 * 들어오면 {@code 'F'} 는 <b>부모 행에만</b> 내려간다. 자기 행만 보던 {@code VideoStreamService} 게이트는
 * 파생본에서 fail-open 이 되어, <b>마스킹 실패한 그 영상 파일 전체</b>가 200/206 으로 나갔다.
 *
 * <h3>왜 mock 이 아니라 IT 인가</h3>
 * 두 번째 테스트는 <b>캐시({@code stream-meta})가 이미 채워진 뒤</b> 신고가 들어오는 경로를 검증한다.
 * 게이트를 캐시 <b>안쪽</b>(@Cacheable 메서드)에 두면 캐시 히트가 게이트를 건너뛰므로, 캐시가 실제로 동작하는
 * Spring 컨텍스트에서만 이 회귀를 잡을 수 있다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DeidentReportStreamGateIT {

    /** 시드 격리 마커 — cleanup 이 이 접두사 행만 정리한다. */
    private static final String SEED_CLIP_PREFIX = "stream-gate-it-";

    @Autowired private VideoStreamService videoStreamService;
    @Autowired private DeidentReportService deidentReportService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private LsDeidentReportRepository reportRepository;
    @Autowired private WorkLockService workLockService;
    @Autowired private CacheManager cacheManager;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidPath;

    private final TransactionTemplate txTemplate;

    DeidentReportStreamGateIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private final TokenClaims reviewer =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    private Long parentRawSn;
    private Long derivativeRawSn;
    private Long parentSrcSn;
    private Path seedDir;

    @BeforeEach
    void seed() throws IOException {
        String unique = SEED_CLIP_PREFIX + System.nanoTime();
        // 비식별 영상 파일은 반드시 허용 base(STORAGE_DEIDENTIFIED_PATH) 하위여야 Path Traversal 가드를 통과한다.
        seedDir = Paths.get(storageDeidPath).toAbsolutePath().normalize().resolve("videos").resolve(unique);
        Files.createDirectories(seedDir);
        Path parentVideo = seedDir.resolve("parent.mp4");
        Path derivativeVideo = seedDir.resolve("derivative-480p.mp4");
        // 실제 컨테이너 시그니처를 가진 최소 mp4 를 쓴다 — 0 으로 채운 더미는 resolve 게이트
        // (DeidentArtifactIntegrity: 크기 하한 + 컨테이너 시그니처)를 통과하지 못해 재개방 단계가
        // 409 로 막힌다. 게이트를 완화하지 않고 픽스처를 실제 산출물에 맞춘다(Phase 7 방어 유지).
        Files.write(parentVideo, TestVideoFixtures.tinyMp4Bytes());
        // 파생본은 부모 비식별 영상의 사본 — 부모 마스킹이 실패했다면 이 파일에도 그대로 남아 있다.
        Files.write(derivativeVideo, Files.readAllBytes(parentVideo));

        txTemplate.execute(s -> {
            LsDataRaw parent = LsDataRaw.createFromIngest(
                    unique, "CCTV-STREAM-GATE", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/" + unique + ".mp4", LocalDateTime.now(), 30);
            parent.markDeidentified("Y");
            parent = videoRepository.save(parent);
            parentRawSn = parent.getRawSn();

            // 신고 진입점은 프레임(srcSn) 기준이므로 부모에 프레임 1건을 시드한다.
            parentSrcSn = srcRepository.save(LsDataSrc.create(
                    parentRawSn, 0L, 0L, "/nas/raw/" + unique + "/f0.jpg",
                    seedDir.resolve("f0.jpg").toString(), LocalDateTime.now())).getSrcSn();

            LsDataRaw derivative = LsDataRaw.createFromResolution(
                    parent, derivativeVideo.toString(), "RESL_480P");
            // 파생 확정(ResolutionPersistService.persist)이 남기는 상태 — 자기 행은 'Y' 로 마감된다.
            derivative.markDeidentified("Y");
            derivative = videoRepository.save(derivative);
            derivativeRawSn = derivative.getRawSn();

            savedProcLog(parentRawSn, parentVideo.toString());
            savedProcLog(derivativeRawSn, derivativeVideo.toString());
            return null;
        });
        streamMetaCache().evict(parentRawSn);
        streamMetaCache().evict(derivativeRawSn);
    }

    private void savedProcLog(Long rawSn, String deidPath) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, deidPath, "stream-gate-it");
        procLog.succeed(deidPath);
        procLogRepository.save(procLog);
    }

    @AfterEach
    void cleanUp() {
        if (parentRawSn != null) {
            workLockService.releaseRaw(parentRawSn, "system", "TEST_CLEANUP");
        }
        txTemplate.execute(s -> {
            if (parentRawSn != null) {
                reportRepository.deleteAll(reportRepository.findAllByDataRawSnOrderByReportDtDesc(parentRawSn));
            }
            if (parentSrcSn != null) {
                srcRepository.deleteById(parentSrcSn);
            }
            for (Long rawSn : new Long[]{derivativeRawSn, parentRawSn}) {
                if (rawSn == null) {
                    continue;
                }
                streamMetaCache().evict(rawSn);
                procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn).forEach(procLogRepository::delete);
                videoRepository.deleteById(rawSn);
            }
            return null;
        });
        deleteQuietly(seedDir);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 정리 실패는 테스트 결과에 영향 없음
                }
            });
        } catch (IOException ignored) {
            // 디렉터리 부재 — 무시
        }
    }

    private Cache streamMetaCache() {
        return cacheManager.getCache(CacheConfig.CACHE_STREAM_META);
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    @Test
    @DisplayName("부모_신고중이면_파생영상_스트리밍과_서명URL이_차단되고_해소되면_재개방된다")
    void derivativeStreamBlockedWhileOriginUnderReport() throws IOException {
        // given — 신고 전에는 파생영상이 정상 서빙된다(파생 행은 'Y' + 자기 SUCCESS procLog 보유).
        assertThat(videoStreamService.stream(derivativeRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // when — <b>부모</b>에만 비식별 누락 신고. 'F' 는 부모 행에만 내려가고 파생 행은 'Y' 로 남는다.
        Long rprtSn = deidentReportService.report(parentSrcSn, "얼굴 미블러 노출", reviewer);
        assertThat(videoRepository.findById(derivativeRawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");

        // then — 파생영상 스트리밍·서명 URL 발급 모두 차단(엔드포인트 기존 규약과 동일한 404 정규화).
        assertThatThrownBy(() -> videoStreamService.stream(derivativeRawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> videoStreamService.issueSignedUrl(derivativeRawSn, "1", "nonce"))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
        // 부모 자신도 물론 차단된다(기존 게이트).
        assertThatThrownBy(() -> videoStreamService.stream(parentRawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);

        // when — 외부 솔루션 수동 재비식별 완료 → resolve('F'→'Y').
        deidentReportService.resolveManually(rprtSn, reviewer);

        // then — 별도 복원 절차 없이 파생·부모 모두 재개방된다(영구 폐쇄 아님).
        assertThat(videoStreamService.stream(derivativeRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(videoStreamService.stream(parentRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("캐시가_먼저_채워진_뒤_신고해도_파생영상_스트리밍이_차단된다 — 캐시 경유 우회 금지")
    void cachedStreamMetaDoesNotBypassGate() throws IOException {
        // given — 신고 이전에 재생되어 stream-meta 캐시가 채워진 상태(게이트를 캐시 안쪽에 두면 여기서 샌다).
        assertThat(videoStreamService.stream(derivativeRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(streamMetaCache().get(derivativeRawSn))
                .as("캐시가 실제로 채워져야 이 테스트가 우회 경로를 검증한다")
                .isNotNull();

        // when — 부모 신고.
        deidentReportService.report(parentSrcSn, "얼굴 미블러 노출", reviewer);

        // then 1 — 캐시 히트 경로가 아니라 게이트가 먼저 판정하므로 차단된다.
        assertThatThrownBy(() -> videoStreamService.stream(derivativeRawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);

        // then 2 — 파생영상의 캐시 엔트리도 함께 무효화된다(부모만 evict 하면 stale 경로/크기가 남는다).
        assertThat(streamMetaCache().get(derivativeRawSn)).isNull();
        assertThat(streamMetaCache().get(parentRawSn)).isNull();
    }
}
