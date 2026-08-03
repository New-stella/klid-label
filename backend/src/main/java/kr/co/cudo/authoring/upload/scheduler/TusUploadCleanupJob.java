package kr.co.cudo.authoring.upload.scheduler;

import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.upload.service.InternalUploadIngestTerminator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

    /** tick 당 정리 상한 — 만료 세션이 대량이어도 한 트랜잭션이 무한정 길어지지 않게 한다(무제한 조회 금지). */
    static final int CLEANUP_BATCH_SIZE = 200;

    /**
     * 만료 세션의 인입 행 종결 사유 — 사용자 취소와 구분한다(둘 다 "파일이 영영 오지 않는다"이지만
     * 원인이 다르다: 취소는 명시적 동선, 만료는 <b>브라우저를 닫고 떠난</b> 경우다).
     */
    static final String EXPIRY_REASON = "업로드 세션 만료(TTL) — 파일이 도착하지 않아 종결";

    private final LsTusUploadRepository uploadRepository;
    /**
     * 인입 행 종결 통로 (DEV_FIX M2).
     *
     * <p>인입 행은 <b>세션 생성 시점</b>에 만들어지므로 세션이 만료로 버려지면 <b>파일이 영영 오지
     * 않는</b> 행이 남는다. 구 구현은 취소({@code DELETE})에만 종결을 배선해, 정작 <b>TUS 의 주
     * 시나리오인 "브라우저를 닫고 떠나기"</b> 에서는 그 행이 미도착 대기 상한(기본 24시간) 내내
     * 폴링 후보로 남았다.
     */
    private final InternalUploadIngestTerminator ingestTerminator;
    /** 보안 LOW(CWE-22): 삭제 대상이 이 root 하위인지 재검증한다. */
    private final Path storageRawPath;

    public TusUploadCleanupJob(
            LsTusUploadRepository uploadRepository,
            InternalUploadIngestTerminator ingestTerminator,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.uploadRepository = uploadRepository;
        this.ingestTerminator = ingestTerminator;
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
     * 만료된 미완료 세션의 행 제거 + 임시 파일 삭제.
     *
     * <p><b>원자 클레임(Phase 9-B)</b>: 후보를 상한과 함께 조회한 뒤, 행 삭제 자체를 조건부 DELETE
     * ({@link LsTusUploadRepository#deleteExpiredById})로 수행해 <b>1행을 지운 노드만</b> 파일을 지운다.
     * 2노드 Active-Active 에서 이 {@code @Scheduled} 잡은 양 노드에서 동시에 발화하는데(Quartz
     * 클러스터링과 무관), 구 구현("조회 후 delete(entity)")은 같은 행을 두 노드가 지우려다 낙관적 잠금
     * 예외로 tick 이 통째로 실패하고 파일 삭제도 중복 시도됐다.
     *
     * <p><b>DB 먼저, 파일 나중(의도) — 단, 이 메서드 전체가 단일 {@code @Transactional} 이라 행
     * DELETE 는 메서드 종료 시점에야 커밋된다.</b> 파일 삭제({@link #deleteQuietly})는 비트랜잭션
     * 즉시 실행이므로, 루프 중간에 노드가 죽거나 {@code deleteExpiredById} 가 던지면 <b>이미 지운
     * 파일은 영구 소실 + 해당 행은 롤백되어 살아남는다</b> — 즉 "행은 살아 있는데 파일이 사라진
     * 세션"을 완전히 막지는 못한다(Phase 9 QA 정정: 구 javadoc 은 이를 막는다고 주장했으나 사실과
     * 달랐다). 대상이 만료된 <b>미완료</b> TUS 세션이라 영향은 재개 불가에 그치고, 정합성 결함으로
     * 이어지지는 않는다. 트랜잭션당 상한({@link #CLEANUP_BATCH_SIZE})으로 노출 구간도 짧다.
     *
     * @return 이번 호출이 <b>실제로 클레임해</b> 정리한 세션 수
     */
    @Transactional("controlTransactionManager")
    public int cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<LsTusUpload> expired = uploadRepository.findExpired(now, PageRequest.of(0, CLEANUP_BATCH_SIZE));
        int cleaned = 0;
        for (LsTusUpload session : expired) {
            if (uploadRepository.deleteExpiredById(session.getUploadId(), now) != 1) {
                // 다른 노드가 이미 정리했다 — 파일 삭제를 중복 시도하지 않는다.
                continue;
            }
            deleteQuietly(session.getFilePath());
            // ★DEV_FIX M2 — 세션만 지우면 인입 행이 남아 24시간 내내 폴링 후보가 된다.
            //   단 [H2-b] 와 <같은 원칙>을 쓴다: 파일이 이미 도착했으면 종결하지 않는다
            //   (행만 죽이면 아무도 지우지 않는 비식별 전 원본이 남는다). 클레임(1행 DELETE)에
            //   성공한 노드만 여기 도달하므로 2노드 동시 발화에도 중복 종결되지 않는다.
            ingestTerminator.terminateIfFileAbsent(
                    session.getVmsClipId(), EXPIRY_REASON, session.getUploadId());
            cleaned++;
        }
        return cleaned;
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
