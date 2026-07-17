package kr.co.cudo.authoring.portal.scheduler;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.service.PortalUploadSweepTxService;
import kr.co.cudo.authoring.upload.service.TusChunkStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 포털 업로드 정리 스윕 잡.
 *
 * <ul>
 *   <li>#13 만료 TUS 세션 정리 — {@code EXPRY_DT < now} + 진행 중 세션의 임시파일 삭제 + 행 제거
 *       (관제 {@code TusUploadCleanupJob} 패턴 + storage root 가드).</li>
 *   <li>#4/#5 고착 자산 → FAILED — UPLOADED/PROCESSING 상태로 N분 이상 갱신이 멈춘
 *       LS_PORTAL_ULD 를 FAILED 전이(영구 로딩 방지).</li>
 * </ul>
 *
 * <p>이 잡은 <b>스케줄 오케스트레이션만</b> 담당한다 — 벌크 UPDATE/DELETE 는 {@code @Transactional}
 * 별 빈({@link PortalUploadSweepTxService})에 위임하고, 파일 정리(비-tx best-effort)만 잡에서 수행한다.
 * 트랜잭션 경계 메서드를 이 잡의 self-invocation 으로 호출하면 {@code @Scheduled} 프록시가 우회돼
 * 트랜잭션이 적용되지 않으므로({@code TransactionRequiredException}) 반드시 별 빈을 통한다.
 */
@Slf4j
@Component
public class PortalUploadSweepJob {

    private final PortalUploadSweepTxService txService;
    /** 보안 LOW(CWE-22): TUS 임시파일 삭제 대상이 storage root 하위인지 재검증. */
    private final Path storageRoot;
    private final long stuckTimeoutMinutes;

    public PortalUploadSweepJob(
            PortalUploadSweepTxService txService,
            PortalUploadProperties properties,
            @Value("${portal.upload.stuck-timeout-minutes:30}") long stuckTimeoutMinutes) {
        this.txService = txService;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
        this.stuckTimeoutMinutes = stuckTimeoutMinutes > 0 ? stuckTimeoutMinutes : 30L;
    }

    /** 30분 간격 스윕. */
    @Scheduled(fixedDelayString = "${portal.upload.sweep.interval-ms:1800000}",
               initialDelayString = "${portal.upload.sweep.initial-delay-ms:600000}")
    public void run() {
        try {
            int sessions = cleanupExpiredSessions();
            int stuck = failStuckUploads();
            if (sessions > 0 || stuck > 0) {
                log.info("[PortalSweep] expiredSessions={} stuckFailed={}", sessions, stuck);
            }
        } catch (RuntimeException e) {
            log.error("[PortalSweep] sweep failed reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * #13 + database 🔴1 / security M-4: 만료 TUS 세션 벌크 삭제(트랜잭션 위임) + 소유 세션의
     * 임시파일 정리(비-tx best-effort). 파일 삭제는 {@code deleteQuietly} 라 멱등.
     */
    public int cleanupExpiredSessions() {
        List<String> claimedFilePaths = txService.claimExpiredSessions();
        for (String filePathNm : claimedFilePaths) {
            TusChunkStore.deleteQuietly(filePathNm, storageRoot);
        }
        return claimedFilePaths.size();
    }

    /**
     * #4/#5 + adversarial #3: 고착 자산 FAILED 전이(트랜잭션 위임) + FAILED 전이에 성공한 자산의
     * 부분 추출 프레임 디렉토리 정리(비-tx best-effort). 원본 영상은 보존(삭제 API 가 정리).
     */
    public int failStuckUploads() {
        List<Long> failedUldSns = txService.failStuckUploads(stuckTimeoutMinutes);
        for (Long uldSn : failedUldSns) {
            cleanupFrameDir(uldSn);
        }
        return failedUldSns.size();
    }

    /** 고착 FAILED 자산의 부분 추출 프레임 디렉토리 정리(best-effort, storage root 가드). */
    private void cleanupFrameDir(Long uldSn) {
        Path framesDir = storageRoot.resolve("frames").resolve(String.valueOf(uldSn)).normalize();
        if (!framesDir.startsWith(storageRoot)) {
            log.warn("[PortalSweep] frame dir outside storage root — skip uldSn={}", uldSn);
            return;
        }
        if (!Files.exists(framesDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(framesDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException e) {
                    log.warn("[PortalSweep] frame file cleanup failed uldSn={} cause={}",
                            uldSn, e.getClass().getSimpleName());
                }
            });
        } catch (java.io.IOException e) {
            log.warn("[PortalSweep] frame dir walk failed uldSn={} cause={}",
                    uldSn, e.getClass().getSimpleName());
        }
    }
}
