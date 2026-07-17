package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.event.PortalVideoUploadedEvent;
import kr.co.cudo.authoring.portal.repository.LsPortalTusUploadRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.upload.service.TusChunkStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 포털 TUS 업로드의 <b>트랜잭션 경계 전용</b> 서비스 (security M-1/M-2 분리).
 *
 * <p>완료 검증(매직바이트 + ffprobe, 최대 30s)은 커넥션/락을 오래 점유하므로 {@link
 * PortalVideoUploadService}(오케스트레이터, 비트랜잭션)가 검증을 트랜잭션 밖에서 수행하고, 짧은
 * 트랜잭션 단위(청크 write / 완료 확정 / 거부 확정)만 이 빈에 위임한다. 같은 빈의 self-invocation
 * 은 {@code @Transactional} 프록시가 무효화되므로 별 빈으로 둔다.
 *
 * <ul>
 *   <li>{@link #appendChunkTx} — 행 잠금(PESSIMISTIC_WRITE) 구간에서 offset 검증 + 파일 write +
 *       offset 전진만. 완료 후보 감지 시 스냅샷을 반환하되 검증은 하지 않는다(락 해제 후 수행).</li>
 *   <li>{@link #finalizeCompleted} — 검증 통과 후 짧은 tx: 조건부 완료 전이 + LS_PORTAL_ULD 생성 +
 *       이벤트. 동시 cancel 경합은 조건부 UPDATE 가 최종 심판.</li>
 *   <li>{@link #finalizeRejected} — 검증 거부 시 독립 tx(REQUIRES_NEW): CANCELLED 영속 커밋 +
 *       파일 삭제. 이후 호출자가 400 을 던져도 취소가 롤백되지 않아 세션 고착을 예방.</li>
 * </ul>
 */
@Slf4j
@Service
public class PortalVideoUploadTxService {

    private final LsPortalTusUploadRepository tusRepository;
    private final LsPortalUldRepository uldRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Path storageRoot;
    private final long maxChunkBytes;

    public PortalVideoUploadTxService(LsPortalTusUploadRepository tusRepository,
                                      LsPortalUldRepository uldRepository,
                                      PortalUploadProperties properties,
                                      ApplicationEventPublisher eventPublisher,
                                      @Value("${portal.upload.max-chunk-bytes:16777216}") long maxChunkBytes) {
        this.tusRepository = tusRepository;
        this.uldRepository = uldRepository;
        this.eventPublisher = eventPublisher;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
        this.maxChunkBytes = maxChunkBytes > 0 ? maxChunkBytes : 16L * 1024 * 1024;
    }

    /**
     * PATCH 청크 append — 행 잠금 하에 offset 검증 + write + 전진(락 해제 후 검증하도록 완료 후보만
     * 표시). 완료 검증(ffprobe)은 이 트랜잭션 안에서 수행하지 않는다.
     */
    @Transactional("controlTransactionManager")
    public AppendOutcome appendChunkTx(UUID uldId, String portalUserNo, long expectedOffset,
                                       InputStream chunk, long contentLength) {
        // #11: PESSIMISTIC_WRITE 로 세션 잠금 — 동일 세션의 동시 PATCH·cancel 직렬화.
        LsPortalTusUpload session = tusRepository.findByUldIdForUpdate(uldId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (!session.isOwnedBy(portalUserNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        if (session.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        // 취소된 세션에 PATCH → 거부(409). 완료보다 먼저 평가.
        if (session.isCancelled()) {
            throw new CustomException(ErrorCode.CONFLICT, "취소된 업로드 세션입니다.");
        }
        // #10: 이미 완료된 세션에 마지막 청크 재전송 → 멱등 응답.
        if (session.isCompleted()) {
            return AppendOutcome.alreadyCompleted(session.getLengthBytes(), session.getUldSn());
        }
        if (contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 본문이 비어 있습니다.");
        }
        if (contentLength > maxChunkBytes) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "청크 크기가 허용 한도(" + maxChunkBytes + " bytes) 를 초과했습니다.");
        }
        if (expectedOffset < 0 || expectedOffset > session.getLengthBytes()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 값이 범위를 벗어났습니다.");
        }
        if (expectedOffset != session.getOffsetBytes()) {
            throw new CustomException(ErrorCode.CONFLICT, "Upload-Offset 이 서버 상태와 일치하지 않습니다.");
        }
        if (expectedOffset + contentLength > session.getLengthBytes()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 길이가 잔여 용량을 초과합니다.");
        }

        long newOffset = TusChunkStore.writeChunkAtomically(
                Paths.get(session.getFilePathNm()), expectedOffset, chunk, contentLength, maxChunkBytes);
        session.advanceOffset(newOffset);

        try {
            tusRepository.saveAndFlush(session);
        } catch (OptimisticLockingFailureException e) {
            TusChunkStore.truncateTo(session.getFilePathNm(), expectedOffset);
            log.warn("[PortalTus] concurrent PATCH conflict uldId={}", uldId);
            throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
        }

        if (session.isFullyUploaded()) {
            // 완료 후보 — 검증은 락 해제(tx 종료) 후 오케스트레이터가 수행.
            return AppendOutcome.completionCandidate(newOffset,
                    session.getPortalUserNo(), session.getOrgnlFileNm(),
                    session.getFilePathNm(), session.getLengthBytes());
        }
        return AppendOutcome.inProgress(newOffset);
    }

    /**
     * 완료 확정 — LS_PORTAL_ULD 생성 + 조건부 완료 전이 + 이벤트(짧은 tx). 동시 cancel 등으로 세션이
     * 더 이상 IN_PROGRESS 가 아니면(조건부 UPDATE 0행) 방금 만든 ULD 를 보상 삭제한다.
     *
     * @return 완료된 LS_PORTAL_ULD.ULD_SN
     */
    @Transactional("controlTransactionManager")
    public Long finalizeCompleted(UUID uldId, String portalUserNo, String orgnlFileNm,
                                  String filePath, long lengthBytes, String mime) {
        LsPortalUld uld = LsPortalUld.createVideo(portalUserNo, orgnlFileNm, filePath, lengthBytes, mime);
        LsPortalUld saved = uldRepository.save(uld);
        Long uldSn = saved.getUldSn();

        // #10: 완료 전이를 DB 조건부 UPDATE 로 강제. affectedRows==1 만 ULD 를 보존하고 이벤트 발행.
        int transitioned = tusRepository.markCompletedIfInProgress(uldId, uldSn, LocalDateTime.now());
        if (transitioned != 1) {
            uldRepository.delete(saved);
            LsPortalTusUpload current = tusRepository.findById(uldId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
            if (current.isCompleted()) {
                log.info("[PortalTus] completion already claimed uldId={} existingUldSn={}",
                        uldId, current.getUldSn());
                return current.getUldSn();
            }
            // 검증 도중 취소된 세션 — 완료 불가(409).
            throw new CustomException(ErrorCode.CONFLICT, "업로드가 취소되어 완료할 수 없습니다.");
        }
        // AFTER_COMMIT 브릿지가 프레임 추출을 트리거 — 롤백 시 미발행(경쟁 차단).
        eventPublisher.publishEvent(new PortalVideoUploadedEvent(uldSn));
        log.info("[PortalTus] completed uldId={} uldSn={}", uldId, uldSn);
        return uldSn;
    }

    /**
     * 완료 거부 확정 — CANCELLED 영속 + 임시파일 삭제(독립 tx, REQUIRES_NEW). 이후 호출자가 400 을
     * 던져도 이 커밋은 롤백되지 않으므로 세션이 IN_PROGRESS 로 고착되지 않는다(security M-2).
     * 이미 완료된 세션은 상태를 덮지 않는다(파일은 영구 영상이므로 삭제하지 않음).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finalizeRejected(UUID uldId, String reason) {
        LsPortalTusUpload session = tusRepository.findByUldIdForUpdate(uldId).orElse(null);
        if (session == null || session.isCancelled()) {
            return;
        }
        if (session.isCompleted()) {
            // 이미 완료된 세션의 파일은 LS_PORTAL_ULD 영구 영상 — 삭제 금지.
            return;
        }
        TusChunkStore.deleteQuietly(session.getFilePathNm(), storageRoot);
        session.markCancelled();
        tusRepository.save(session);
        log.warn("[PortalTus] completion rejected uldId={} reason={}", uldId, reason);
    }

    /** appendChunkTx 결과 — 새 오프셋 + 완료 후보 여부 + 검증에 필요한 세션 스냅샷. */
    public record AppendOutcome(long newOffset, boolean completionCandidate,
                                Long alreadyCompletedUldSn,
                                String portalUserNo, String orgnlFileNm,
                                String filePathNm, long lengthBytes) {

        static AppendOutcome inProgress(long newOffset) {
            return new AppendOutcome(newOffset, false, null, null, null, null, 0L);
        }

        static AppendOutcome alreadyCompleted(long newOffset, Long uldSn) {
            return new AppendOutcome(newOffset, false, uldSn, null, null, null, 0L);
        }

        static AppendOutcome completionCandidate(long newOffset, String portalUserNo, String orgnlFileNm,
                                                 String filePathNm, long lengthBytes) {
            return new AppendOutcome(newOffset, true, null, portalUserNo, orgnlFileNm, filePathNm, lengthBytes);
        }
    }
}
