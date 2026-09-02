package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 포털 프레임 추출의 <b>트랜잭션 경계 전용</b> 서비스.
 *
 * <p>{@link PortalFrameExtractRunner}(@Async, 비트랜잭션 ffmpeg 루프)가 상태 전이·프레임 영속만
 * 짧은 별도 트랜잭션으로 위임하도록 분리했다. 러너와 같은 클래스에 두면 {@code @Async} 프록시 내부
 * self-invocation 으로 {@code @Transactional} 이 무효화되므로 별 빈으로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalFrameExtractTxService {

    /**
     * 프레임 {@code saveAll} 배치 크기. 단일 트랜잭션 내에서 saveAll 호출을 200행 단위로 쪼갤 뿐
     * 커밋 경계는 아니다 — 전체 프레임 저장과 READY 전이가 하나의 트랜잭션으로 원자 커밋된다.
     */
    private static final int SAVE_CHUNK = 200;

    private final PortalUploadAssetRepository assetRepository;
    private final PortalUploadFrameRepository frmeRepository;

    /**
     * 러너 진입 — UPLOADED → PROCESSING 원자 전이(시나리오 #4). 전이 성공 시 자산 스냅샷 반환,
     * 이미 삭제됐거나 UPLOADED 가 아니면 empty(러너가 즉시 중단).
     */
    @Transactional("controlTransactionManager")
    public Optional<PortalUploadAsset> beginProcessing(Long uldSn) {
        // 상태 행이 아직 없을 수 있어(부재 = 업로드됨) 단순 UPDATE 가 아니라 삽입 겸 조건부 갱신이다.
        int transitioned = assetRepository.transitionToProcessing(uldSn);
        if (transitioned != 1) {
            return Optional.empty();
        }
        return assetRepository.findPortalAsset(uldSn);
    }

    /**
     * 진행 중 하트비트 겸 삭제/전이 감지(adversarial #2). PROCESSING 인 동안 mdfcnDt 를 갱신해
     * 장시간 정상 추출이 스윕 고착 판정 대상에서 제외되게 하고, 갱신 행이 0 이면(삭제/전이됨) 러너에
     * 중단 신호를 준다.
     *
     * @return 여전히 PROCESSING 이면 true, 삭제/전이됐으면 false
     */
    @Transactional("controlTransactionManager")
    public boolean touchProcessing(Long uldSn) {
        return assetRepository.touchProcessing(uldSn) == 1;
    }

    /**
     * 추출 완료 — 후처리 중 → 라벨링 가능 조건부 전이 + 프레임행 전체 영속(원자 커밋).
     *
     * <p>스윕이 이미 FAILED 시킨 자산(또는 삭제된 자산)은 조건부 UPDATE 가 0행이 되어 READY 로
     * 되살아나지 않는다(adversarial #1). 전이가 성공(1행)한 경우에만 프레임을 저장한다 — 전이 실패
     * 시 프레임 고아 INSERT 를 방지한다.
     *
     * @param frames 저장할 프레임 엔티티(미영속) — 200단위 saveAll 배치.
     * @return READY 전이 성공 여부(false 면 러너가 프레임 파일 정리)
     */
    @Transactional("controlTransactionManager")
    public boolean completeReady(Long uldSn, List<LsDataSrc> frames,
                                 Double durationSec, Double fps) {
        // 여기는 <단순 UPDATE> 다 — 상태 행이 없는 자산(= 업로드됨)이 후처리를 건너뛰고 완료로
        // 점프하지 않게 한다. 스윕이 이미 실패로 마감한 자산의 부활도 같은 조건이 막는다.
        int transitioned = assetRepository.transitionToReady(uldSn);
        if (transitioned != 1) {
            log.warn("[PortalFrame] READY transition blocked (not PROCESSING or gone) uldSn={}", uldSn);
            return false;
        }
        // 프레임 수는 보관하지 않는다 — 프레임 원장 행을 세어 얻는다(ERD-028). 길이·프레임률만 적재.
        assetRepository.applyVideoDuration(uldSn, durationSec);
        assetRepository.upsertMeta(uldSn, PortalUploadLedger.KEY_FPS,
                fps == null ? null : String.valueOf(fps));
        // 성공 전이 뒤에는 남은 실패 사유가 거짓말이 된다 — 값을 비우는 대신 행을 지운다.
        assetRepository.deleteMeta(uldSn, PortalUploadLedger.KEY_FAIL_REASON);
        for (int i = 0; i < frames.size(); i += SAVE_CHUNK) {
            frmeRepository.saveAll(frames.subList(i, Math.min(i + SAVE_CHUNK, frames.size())));
        }
        return true;
    }

    /**
     * 추출 실패 — PROCESSING 일 때만 FAILED 조건부 전이(adversarial #1 역케이스). 이미 READY/삭제된
     * 자산은 0행이 되어 덮지 않는다(best-effort no-op).
     */
    @Transactional("controlTransactionManager")
    public void markFailed(Long uldSn, String reason) {
        int n = assetRepository.failFromProcessing(uldSn);
        if (n != 1) {
            log.info("[PortalFrame] FAILED transition skipped (not PROCESSING) uldSn={}", uldSn);
            return;
        }
        // 사유는 전이에 성공한 호출만 남긴다 — 그러지 않으면 이미 다른 상태인 자산에 사유만 덧씌워진다.
        // 저장 폭이 좁아 잘릴 수 있고, 잘렸으면 그 사실이 값에 드러나야 한다(ADR-058 이 수용한 대가).
        assetRepository.upsertMeta(uldSn, PortalUploadLedger.KEY_FAIL_REASON,
                PortalUploadLedger.truncateFailReason(reason));
    }
}
