package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.support.TestVideoFixtures;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S7-STREAM (CWE-359/525) — 신고 게이트의 <b>스트리밍 적용 범위</b>를 실 DB·실 캐시로 고정한다.
 *
 * <h3>고정하는 정책 (2026-07-29 사용자 확정)</h3>
 * 신고 판정은 <b>자기 rawSn 행</b> 하나다. 따라서 부모(원본)를 신고해도 <b>파생영상 스트리밍은 계속
 * 200</b> 이고(파생본에는 재비식별 수단이 없어 전파해도 해소 경로가 없다 — 감수된 함의), 신고된 그
 * 영상만 차단된다. 구 IT 는 반대(파생까지 차단)를 단언했으나 그 전파가 철회되어 정책에 맞게 뒤집었다.
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
        // 비식별 산출물 무결성 판정(DeidentArtifactIntegrity)은 컨테이너 시그니처(ftyp/moov/RIFF …)를
        // 요구한다. 구 픽스처(new byte[2048] = 0바이트 나열)는 이 판정에 걸려 resolveManually 가 409 로
        // 끝났고, 그 결과 "해소 후 재개방" 단언이 사실상 미검증 상태였다(clean main 에서도 실패).
        // 실제 재생 가능한 최소 mp4 로 교체한다 — 단언은 그대로 둔다.
        TestVideoFixtures.writeTinyMp4(parentVideo);
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

    /**
     * 외부 솔루션의 수동 재비식별 완료 재현 — 부모 비식별 산출물 mtime 을 신고시각 이후로 옮긴다.
     *
     * <p>{@code resolveManually} 의 시간조건은 <b>신고 이후</b> 재비식별된 산출물만 통과시킨다
     * (B-ISSUE-42 로 클럭스큐 감산 관용이 제거되어, 신고 이전부터 있던 파일 = 신고를 유발한 그
     * 산출물은 통과하지 않는다). 시드 파일은 신고보다 먼저 만들어지므로 여기서 교체를 재현한다.
     */
    private void simulateExternalRedeident(Long rprtSn) throws IOException {
        LocalDateTime reportTime = reportRepository.findById(rprtSn).orElseThrow().getReportDt();
        Files.setLastModifiedTime(seedDir.resolve("parent.mp4"), FileTime.from(
                reportTime.plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    @DisplayName("★부모_신고중에도_파생영상_스트리밍은_200이고_부모만_차단된다 — 해소되면 부모도 재개방")
    void originReportBlocksOnlyItselfNotDerivative() throws IOException {
        // given — 신고 전에는 둘 다 정상 서빙된다.
        assertThat(videoStreamService.stream(derivativeRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // when — <b>부모</b>에만 비식별 누락 신고. 'F' 는 부모 행에만 내려가고 파생 행은 'Y' 로 남는다.
        Long rprtSn = deidentReportService.report(parentSrcSn, "얼굴 미블러 노출", reviewer);
        assertThat(videoRepository.findById(derivativeRawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");

        // then — 신고된 부모만 차단(엔드포인트 기존 규약과 동일한 404 정규화).
        assertThatThrownBy(() -> videoStreamService.stream(parentRawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> videoStreamService.issueSignedUrl(parentRawSn, "1", "nonce"))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
        // ★ 파생영상은 영향받지 않는다(확정 정책 — 조상 전파 철회 회귀 고정).
        assertThat(videoStreamService.stream(derivativeRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // when — 외부 솔루션 수동 재비식별 완료 → resolve('F'→'Y').
        simulateExternalRedeident(rprtSn);
        deidentReportService.resolveManually(rprtSn, reviewer);

        // then — 별도 복원 절차 없이 부모도 재개방된다(영구 폐쇄 아님).
        assertThat(videoStreamService.stream(parentRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("캐시가_먼저_채워진_뒤_신고해도_스트리밍이_차단된다 — 캐시 경유 우회 금지(CWE-525)")
    void cachedStreamMetaDoesNotBypassGate() throws IOException {
        // given — 신고 이전에 재생되어 stream-meta 캐시가 채워진 상태(게이트를 캐시 안쪽에 두면 여기서 샌다).
        assertThat(videoStreamService.stream(parentRawSn, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(streamMetaCache().get(parentRawSn))
                .as("캐시가 실제로 채워져야 이 테스트가 우회 경로를 검증한다")
                .isNotNull();

        // when — 그 영상에 신고 접수.
        deidentReportService.report(parentSrcSn, "얼굴 미블러 노출", reviewer);

        // then 1 — 캐시 히트 경로가 아니라 게이트가 먼저 판정하므로 차단된다.
        assertThatThrownBy(() -> videoStreamService.stream(parentRawSn, new HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportStreamGateIT::errorCodeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);

        // then 2 — 그 영상의 캐시 엔트리는 커밋 후 무효화된다(재비식별로 경로/크기가 바뀔 수 있다).
        assertThat(streamMetaCache().get(parentRawSn)).isNull();
    }
}
