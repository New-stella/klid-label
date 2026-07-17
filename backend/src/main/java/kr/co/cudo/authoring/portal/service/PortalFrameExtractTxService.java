package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    private final LsPortalUldRepository uldRepository;
    private final LsPortalUldFrmeRepository frmeRepository;

    /**
     * 러너 진입 — UPLOADED → PROCESSING 원자 전이(시나리오 #4). 전이 성공 시 자산 스냅샷 반환,
     * 이미 삭제됐거나 UPLOADED 가 아니면 empty(러너가 즉시 중단).
     */
    @Transactional("controlTransactionManager")
    public Optional<LsPortalUld> beginProcessing(Long uldSn) {
        int transitioned = uldRepository.transitionToProcessing(uldSn, LocalDateTime.now());
        if (transitioned != 1) {
            return Optional.empty();
        }
        return uldRepository.findById(uldSn);
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
        return uldRepository.touchProcessing(uldSn, LocalDateTime.now()) == 1;
    }

    /**
     * 추출 완료 — PROCESSING → READY 조건부 전이 + 프레임행 전체 영속(시나리오 #5, 원자 커밋).
     *
     * <p>스윕이 이미 FAILED 시킨 자산(또는 삭제된 자산)은 조건부 UPDATE 가 0행이 되어 READY 로
     * 되살아나지 않는다(adversarial #1). 전이가 성공(1행)한 경우에만 프레임을 저장한다 — 전이 실패
     * 시 프레임 고아 INSERT 를 방지한다.
     *
     * @param frames 저장할 프레임 엔티티(미영속) — 200단위 saveAll 배치.
     * @return READY 전이 성공 여부(false 면 러너가 프레임 파일 정리)
     */
    @Transactional("controlTransactionManager")
    public boolean completeReady(Long uldSn, List<LsPortalUldFrme> frames,
                                 Double durationSec, Double fps) {
        int transitioned = uldRepository.transitionToReady(
                uldSn, durationSec, fps, frames.size(), LocalDateTime.now());
        if (transitioned != 1) {
            log.warn("[PortalFrame] READY transition blocked (not PROCESSING or gone) uldSn={}", uldSn);
            return false;
        }
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
        int n = uldRepository.failIfInStatus(
                uldSn, reason, List.of(LsPortalUld.STTS_PROCESSING), LocalDateTime.now());
        if (n != 1) {
            log.info("[PortalFrame] FAILED transition skipped (not PROCESSING) uldSn={}", uldSn);
        }
    }
}
