package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.upload.PortalTusSessionRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 포털 업로드 스윕의 <b>트랜잭션 경계 전용</b> 서비스.
 *
 * <p>{@link kr.co.cudo.authoring.portal.scheduler.PortalUploadSweepJob}(스케줄 오케스트레이션)이
 * 벌크 UPDATE/DELETE({@code @Modifying @Query})를 별 빈의 실 트랜잭션으로 위임하도록 분리했다.
 * 스윕 잡과 같은 클래스에 두면 {@code @Scheduled} 프록시 내부 self-invocation 으로
 * {@code @Transactional} 이 무효화되어({@code TransactionRequiredException}) 벌크 쿼리가 활성
 * 트랜잭션 없이 실행되므로 {@link PortalFrameExtractTxService} 와 동일 패턴으로 별 빈에 둔다.
 *
 * <p>파일 정리(비-tx best-effort)는 스윕 잡이 반환값을 받아 트랜잭션 밖에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalUploadSweepTxService {

    private final PortalTusSessionRepository tusRepository;
    private final PortalUploadAssetRepository assetRepository;

    /**
     * #13 + database 🔴1 / security M-4: 만료된 진행 중 TUS 세션 행을 조건부 벌크 삭제하고,
     * 이 노드가 삭제 책임을 획득(1행)한 세션의 임시파일 경로를 반환한다.
     *
     * <p>2노드 Active-Active 에서 중복 실행돼도 안전하도록 조건부 벌크 삭제
     * ({@link PortalTusSessionRepository#deleteExpiredInProgress})로 멱등화한다 — 한 노드가 먼저
     * 삭제하면 다른 노드는 0행이 되어 반환 목록에서 제외되므로 파일 삭제도 소유 노드만 수행한다.
     *
     * @return 이 노드가 행 삭제에 성공한 세션의 임시파일 경로 목록(스윕 잡이 트랜잭션 밖에서 정리)
     */
    @Transactional("controlTransactionManager")
    public List<String> claimExpiredSessions() {
        List<LsTusUpload> expired = tusRepository.findExpired(LocalDateTime.now());
        List<String> claimedFilePaths = new ArrayList<>();
        for (LsTusUpload session : expired) {
            int removed = tusRepository.deleteExpiredInProgress(session.getUploadId());
            if (removed == 1) {
                claimedFilePaths.add(session.getFilePath());
            }
        }
        return claimedFilePaths;
    }

    /**
     * #4/#5 + adversarial #3: N분 이상 고착된 UPLOADED/PROCESSING 자산을 조건부 UPDATE 로 FAILED
     * 전이하고, 이 노드가 전이에 성공(1행)한 자산의 uldSn 목록을 반환한다.
     *
     * <p>조건부 갱신({@link PortalUploadAssetRepository#failStuck})으로 2노드 중복 실행에도 멱등하다
     * (한쪽만 1행, 다른 쪽 0행). 라벨링 가능으로 이미 완료된 자산은 출발 상태 집합에 없어 덮지 않는다.
     *
     * @return FAILED 전이에 성공한 자산의 uldSn 목록(스윕 잡이 프레임 디렉토리를 트랜잭션 밖에서 정리)
     */
    @Transactional("controlTransactionManager")
    public List<Long> failStuckUploads(long stuckTimeoutMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckTimeoutMinutes);
        // 후보 조회가 「상태 행이 없거나 값이 업로드됨·후처리 중」 형태다 — 부재를 업로드됨으로 읽는
        // 확정이 여기서 조회 모양을 정한다. 부재를 빼면 상태를 기록하기 전 자산이 영영 방치로 남는다.
        List<Long> stuck = assetRepository.findStuck(cutoff);
        String reason = "처리 시간 초과(" + stuckTimeoutMinutes + "분) — 스윕 잡 강제 실패 전이";
        List<Long> failedUldSns = new ArrayList<>();
        for (Long uldSn : stuck) {
            // 출발 상태 집합에 <부재>가 들어 있어 삽입 겸 조건부 갱신이다(단순 UPDATE 로는 0행).
            if (assetRepository.failStuck(uldSn) == 1) {
                assetRepository.upsertMeta(uldSn, PortalUploadLedger.KEY_FAIL_REASON,
                        PortalUploadLedger.truncateFailReason(reason));
                failedUldSns.add(uldSn);
            }
        }
        return failedUldSns;
    }
}
