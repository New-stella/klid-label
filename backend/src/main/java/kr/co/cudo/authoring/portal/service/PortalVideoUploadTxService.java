package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.upload.PortalTusSessionRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.service.TusChunkStore;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>{@link #finalizeCompleted} — 검증 통과 후 짧은 tx: <b>조건부 완료 전이가 먼저</b>이고, 그
 *       전이에 성공한 호출만 자산을 보존한다. 동시 cancel 경합은 그 조건부 UPDATE 가 최종 심판이다.
 *       ★ 여기서 <b>어떤 이벤트도 발행하지 않는다</b> — 추출은 마킹 저장이 연다(순서 반전). 발행기를
 *       주입조차 받지 않으므로 「업로드가 추출을 깨우지 않는다」가 배선으로 보장된다.</li>
 *   <li>{@link #finalizeRejected} — 검증 거부 시 독립 tx(REQUIRES_NEW): CANCELLED 영속 커밋 +
 *       파일 삭제. 이후 호출자가 400 을 던져도 취소가 롤백되지 않아 세션 고착을 예방.</li>
 * </ul>
 */
@Slf4j
@Service
public class PortalVideoUploadTxService {

    private final PortalTusSessionRepository tusRepository;
    private final PortalUploadAssetRepository assetRepository;
    private final Path storageRoot;
    private final long maxChunkBytes;

    public PortalVideoUploadTxService(PortalTusSessionRepository tusRepository,
                                      PortalUploadAssetRepository assetRepository,
                                      PortalUploadProperties properties) {
        this.tusRepository = tusRepository;
        this.assetRepository = assetRepository;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
        long maxChunkBytes = properties.maxChunkBytes();
        this.maxChunkBytes = maxChunkBytes > 0 ? maxChunkBytes : 16L * 1024 * 1024;
    }

    /**
     * 포털 TUS 세션 미존재·소유자 불일치 공통 404 — <b>같은 코드 + 같은 문구</b>여야 오라클이 남지 않는다.
     *
     * <p><b>★ 소유자 불일치는 404 다 (구 403 폐기)</b>: 403 을 내면 "그 세션은 있는데 네 것이 아니다"가
     * 되어 응답 자체가 <b>세션 존재 오라클</b>이 된다(CWE-209). 미존재와 구분 불가능해야 하므로 코드뿐
     * 아니라 <b>메시지도 같아야</b> 한다 — 코드만 맞추고 문구가 갈리면 판별이 메시지로 옮겨갈 뿐이다.
     *
     * <p>팩토리를 두는 이유: 두 사유를 각각 인라인으로 만들면 다음 수정에서 한쪽 문구만 바뀌어
     * 조용히 오라클이 되살아난다. 판정은 호출처가 하되 <b>응답은 이 한 곳</b>에서만 만든다.
     * HEAD/DELETE 를 담당하는 {@link PortalVideoUploadService} 도 이 팩토리를 쓴다(포털 TUS 4경로 공통).
     *
     * <p>⚠ <b>인증(401)·역할(403)은 그대로다</b> — 포털 토큰이 없으면 401, PORTAL_USER 가 아니면
     * {@code SecurityConfig} 가 403 이다. 바뀐 것은 <b>인가를 통과한 포털 사용자가 남의 세션을 지목한
     * 경우</b> 하나뿐이다.
     */
    static CustomException sessionNotFound() {
        return new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다.");
    }

    /**
     * PATCH 청크 append — 행 잠금 하에 offset 검증 + write + 전진(락 해제 후 검증하도록 완료 후보만
     * 표시). 완료 검증(ffprobe)은 이 트랜잭션 안에서 수행하지 않는다.
     *
     * <p>소유자 불일치는 {@link #sessionNotFound()} 로 <b>미존재와 같은 404</b>다. 거부는 offset·완료
     * 검사보다 <b>먼저</b> 평가되므로 청크는 한 바이트도 기록되지 않는다.
     */
    @Transactional("controlTransactionManager")
    public AppendOutcome appendChunkTx(UUID uldId, String portalUserNo, long expectedOffset,
                                       InputStream chunk, long contentLength) {
        // #11: PESSIMISTIC_WRITE 로 세션 잠금 — 동일 세션의 동시 PATCH·cancel 직렬화.
        LsTusUpload session = tusRepository.findPortalSessionForUpdate(uldId)
                .orElseThrow(PortalVideoUploadTxService::sessionNotFound);
        // 소유자 불일치 = 미존재와 동일한 404 (존재 오라클 차단, 위 javadoc).
        if (!session.isOwnedBy(portalUserNo)) {
            throw sessionNotFound();
        }
        // ★ 포털 축 만료 판정 — 취소된 세션은 만료가 아니라 충돌(409)로 답한다(바로 아래 분기).
        if (session.isPortalExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        // 취소된 세션에 PATCH → 거부(409). 완료보다 먼저 평가.
        if (session.isCancelled()) {
            throw new CustomException(ErrorCode.CONFLICT, "취소된 업로드 세션입니다.");
        }
        // #10: 이미 완료된 세션에 마지막 청크 재전송 → 멱등 응답.
        if (session.isCompleted()) {
            return AppendOutcome.alreadyCompleted(session.getUploadLength(), session.getRawSn());
        }
        if (contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 본문이 비어 있습니다.");
        }
        if (contentLength > maxChunkBytes) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "청크 크기가 허용 한도(" + maxChunkBytes + " bytes) 를 초과했습니다.");
        }
        if (expectedOffset < 0 || expectedOffset > session.getUploadLength()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 값이 범위를 벗어났습니다.");
        }
        if (expectedOffset != session.getUploadOffset()) {
            throw new CustomException(ErrorCode.CONFLICT, "Upload-Offset 이 서버 상태와 일치하지 않습니다.");
        }
        if (expectedOffset + contentLength > session.getUploadLength()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 길이가 잔여 용량을 초과합니다.");
        }

        long newOffset = TusChunkStore.writeChunkAtomically(
                Paths.get(session.getFilePath()), expectedOffset, chunk, contentLength, maxChunkBytes);
        session.advanceOffset(newOffset);

        try {
            tusRepository.saveAndFlush(session);
        } catch (OptimisticLockingFailureException e) {
            TusChunkStore.truncateTo(session.getFilePath(), expectedOffset);
            log.warn("[PortalTus] concurrent PATCH conflict uldId={}", uldId);
            throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
        }

        if (session.isFullyUploaded()) {
            // 완료 후보 — 검증은 락 해제(tx 종료) 후 오케스트레이터가 수행.
            return AppendOutcome.completionCandidate(newOffset,
                    session.getUserNo(), session.getFileName(),
                    session.getFilePath(), session.getUploadLength());
        }
        return AppendOutcome.inProgress(newOffset);
    }

    /**
     * 완료 확정 — 포털 자산 적재 + 조건부 완료 전이 + 이벤트(짧은 tx). 동시 취소 등으로 세션이 더 이상
     * 진행 중이 아니면(조건부 UPDATE 0행) 방금 만든 자산을 보상 삭제한다.
     *
     * @return 완료된 자산 식별자({@code RAW_SN}) — 창구·이벤트의 이름은 {@code uldSn} 그대로다
     */
    @Transactional("controlTransactionManager")
    public Long finalizeCompleted(UUID uldId, String portalUserNo, String orgnlFileNm,
                                  String filePath, long lengthBytes, String mime,
                                  Double durationSec, Double fps) {
        // 자산을 먼저 만들고 전이에 실패하면 되돌린다 — 순서를 뒤집으면(전이 먼저) 완료로 표시된
        // 세션이 자산 없이 남는 창이 생긴다. 되돌리기는 소유자 조건이 걸린 삭제라 남의 행을 못 지운다.
        Long uldSn = assetRepository.insertUploaded(
                portalUserNo, filePath, orgnlFileNm, mime, lengthBytes);

        // ★ 순서 반전(2026-09-02) — 길이·프레임률을 <b>업로드 확정 시점</b>에 적재한다.
        //   구 흐름에서는 추출 러너가 끝낸 뒤에야 채워졌는데, 이제 마킹이 추출보다 앞서므로 그때는
        //   이미 늦다: 자동 마킹은 길이·프레임률이 있어야 지점을 산출하고, 마킹 화면도 총 길이와
        //   「프레임 간격이 이 영상에서 몇 초인가」를 그 값으로 계산한다. 업로드 완료 검증이 이미
        //   프로브를 돌렸으므로 <b>추가 비용 없이</b> 그 결과를 그대로 담는다.
        assetRepository.applyVideoDuration(uldSn, durationSec);
        if (fps != null && fps > 0d) {
            assetRepository.upsertMeta(uldSn, PortalUploadLedger.KEY_FPS, String.valueOf(fps));
        }

        // #10: 완료 전이를 DB 조건부 UPDATE 로 강제. affectedRows==1 만 자산을 보존하고 이벤트를 발행.
        int transitioned = tusRepository.markCompletedIfInProgress(uldId, uldSn, LocalDateTime.now());
        if (transitioned != 1) {
            assetRepository.deleteOwned(uldSn, portalUserNo);
            LsTusUpload current = tusRepository.findPortalSession(uldId)
                    .orElseThrow(PortalVideoUploadTxService::sessionNotFound);
            if (current.isCompleted()) {
                log.info("[PortalTus] completion already claimed uldId={} existingUldSn={}",
                        uldId, current.getRawSn());
                return current.getRawSn();
            }
            // 검증 도중 취소된 세션 — 완료 불가(409).
            throw new CustomException(ErrorCode.CONFLICT, "업로드가 취소되어 완료할 수 없습니다.");
        }
        // ★ 여기서 프레임 추출을 트리거하지 않는다 (2026-09-02 순서 반전). 자산은 「마킹 대기」로
        //   남고, 추출은 마킹 저장이 연다({@code PortalMarkingCompletedEvent}). 업로드 직후 자동
        //   추출을 되살리면 마킹하지 않은 자산이 프레임을 갖게 되어 「마킹 지점으로만 뽑는다」가 깨지고,
        //   사용자가 고른 지점으로 다시 뽑을 수단이 없다(재추출을 제공하지 않는다).
        log.info("[PortalTus] completed uldId={} uldSn={} — awaiting marking", uldId, uldSn);
        return uldSn;
    }

    /**
     * 완료 거부 확정 — CANCELLED 영속 + 임시파일 삭제(독립 tx, REQUIRES_NEW). 이후 호출자가 400 을
     * 던져도 이 커밋은 롤백되지 않으므로 세션이 IN_PROGRESS 로 고착되지 않는다(security M-2).
     * 이미 완료된 세션은 상태를 덮지 않는다(파일은 영구 영상이므로 삭제하지 않음).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finalizeRejected(UUID uldId, String reason) {
        LsTusUpload session = tusRepository.findPortalSessionForUpdate(uldId).orElse(null);
        if (session == null || session.isCancelled()) {
            return;
        }
        if (session.isCompleted()) {
            // 이미 완료된 세션의 파일은 자산의 영구 영상 — 삭제 금지.
            return;
        }
        TusChunkStore.deleteQuietly(session.getFilePath(), storageRoot);
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
