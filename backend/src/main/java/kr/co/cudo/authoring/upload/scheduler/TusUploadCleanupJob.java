package kr.co.cudo.authoring.upload.scheduler;

import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * TUS 만료 세션 정리 잡 (HIGH-5 TTL).
 *
 * <p>{@code EXPIRES_AT < now} + 미완료 세션의 임시 파일을 삭제하고 행을 정리한다.
 * {@code authoring.batch.enabled=false} 인 로컬에서도 빈 등록은 안전하며,
 * {@code @Scheduled} 는 폴링만 수행하므로 외부 의존 없이 동작한다.
 */
@Slf4j
@Component
public class TusUploadCleanupJob {

    private final LsTusUploadRepository uploadRepository;
    /** 보안 LOW(CWE-22): 삭제 대상이 이 root 하위인지 재검증한다. */
    private final Path storageRawPath;

    public TusUploadCleanupJob(
            LsTusUploadRepository uploadRepository,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.uploadRepository = uploadRepository;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    /** 1시간 간격 정리. */
    @Scheduled(fixedDelayString = "${authoring.upload.tus.cleanup.interval-ms:3600000}",
               initialDelayString = "${authoring.upload.tus.cleanup.initial-delay-ms:600000}")
    public void run() {
        try {
            int cleaned = cleanupExpired();
            if (cleaned > 0) {
                log.info("[TusCleanup] expired sessions cleaned count={}", cleaned);
            }
        } catch (RuntimeException e) {
            log.error("[TusCleanup] cleanup failed reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 만료된 미완료 세션의 임시 파일 삭제 + 행 제거.
     *
     * @return 정리된 세션 수
     */
    @Transactional("controlTransactionManager")
    public int cleanupExpired() {
        List<LsTusUpload> expired = uploadRepository.findExpired(LocalDateTime.now());
        for (LsTusUpload session : expired) {
            deleteQuietly(session.getFilePath());
            uploadRepository.delete(session);
        }
        return expired.size();
    }

    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        // 보안 LOW(CWE-22 심층방어): 삭제 직전 storage root 하위 재검증.
        Path target = Paths.get(path).toAbsolutePath().normalize();
        if (!target.startsWith(storageRawPath)) {
            log.warn("[TusCleanup] delete blocked — path outside storage root");
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // best-effort — 행은 그대로 삭제 (재시도 시 deleteIfExists 멱등)
        }
    }
}
