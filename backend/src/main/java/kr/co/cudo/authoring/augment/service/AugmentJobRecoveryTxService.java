package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobRollup;
import kr.co.cudo.authoring.webhook.service.AugmentJobSuccessApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <b>결과조회 회수</b>(INT-030)의 트랜잭션 경계 — 웹훅이 유실된 job 을 조회 결과로 종결시킨다.
 *
 * <h3>웹훅 경로와 <b>같은 잠금 순서·같은 멱등 앵커</b>를 쓴다 (S14)</h3>
 * <p>①증강 행 {@code FOR UPDATE} → ②job 전량 재조회 → ③대상 job 갱신 → ④롤업. 순서를 뒤집으면
 * (job 먼저 갱신) 두 트랜잭션이 서로의 미커밋 갱신을 못 봐 <b>롤업이 통째로 유실</b>된다
 * ({@code GenAiCallbackService} 클래스 주석과 동일 규칙).
 *
 * <p>이중 적용(웹훅 + 회수)은 세 겹으로 막힌다:
 * <ol>
 *   <li>증강 행 {@code FOR UPDATE} — 두 경로가 같은 행을 잠가 직렬화된다.</li>
 *   <li>{@code job.isTerminal()} 재확인 — 그 사이 웹훅이 종결시켰으면 아무것도 하지 않는다.</li>
 *   <li>{@code AugmentResultService} 의 non-PENDING 앵커 + {@code uk_aug_external_job_id} UNIQUE —
 *       증강 1건이 두 번 확정되지 않으므로 <b>파생 영상도 1건만</b> 생성된다.</li>
 * </ol>
 *
 * <h3>★ 롤업은 {@link AugmentJobRollup} 을 그대로 쓴다 — {@code handleInNewTransaction} 이 아니다</h3>
 * <p>{@code AugmentResultService#handleInNewTransaction}({@code REQUIRES_NEW})은 <b>커밋 후 콜백
 * ({@code AFTER_COMMIT}) 문맥에서 부를 때</b>를 위한 진입점이다. 여기서 그것을 쓰면 이 트랜잭션이
 * 이미 잡고 있는 증강 행 잠금을 <b>새 트랜잭션이 다시 얻으려다 자기 자신과 교착</b>한다
 * (같은 커넥션 풀·같은 행 {@code FOR UPDATE}). 회수는 요청 스레드의 일반 트랜잭션 안에서 일어나므로
 * {@code REQUIRED} 로 참여하는 {@code AugmentJobRollup} → {@code AugmentResultService#handle} 경로가
 * 정확하며, 이는 만료 스윕({@code AugmentJobExpiryTxService})이 쓰는 것과 <b>같은 배선</b>이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentJobRecoveryTxService {

    /** 회수로 종결시킬 때 남기는 사유 — 내부 경로/식별정보 없이 원인만(CWE-209). */
    static final String RECOVERY_REASON =
            "웹훅 미수신 — 외부 결과조회(INT-030)로 회수 종결";

    private final LsDataAugRepository augRepository;
    private final LsDataAugJobRepository jobRepository;
    private final AugmentJobSuccessApplier successApplier;
    private final AugmentJobRollup rollup;

    /**
     * 조회로 확인한 외부 종결 상태를 로컬 job 에 반영하고 롤업까지 이어 붙인다.
     *
     * @param dataAugSn      증강 PK
     * @param augJobSn       대상 청크 job PK
     * @param externalJobId  외부 job_id (오배송 차단 대조 + 멱등 앵커)
     * @param externalStatus 외부 §3.2 종결 상태(SUCCEEDED/FAILED/CANCELED)
     * @param outputs        SUCCEEDED 일 때 <b>허용루트 검증을 통과한</b> 산출 경로(그 외 무시)
     * @param errorCode      FAILED 일 때 외부 오류 코드(선택)
     * @param errorMessage   FAILED 일 때 외부 오류 메시지(선택)
     * @return true = 이번 호출이 회수함 / false = 대상 아님(이미 종결·오배송·행 부재)
     */
    @Transactional("controlTransactionManager")
    public boolean recover(Long dataAugSn, Long augJobSn, String externalJobId,
                           String externalStatus, List<String> outputs,
                           String errorCode, String errorMessage) {
        // 1) 롤업 직렬화 — job 갱신 전에 증강 행을 잠근다(웹훅 경로와 동일 순서).
        if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) {
            return false;
        }
        // 2) 잠금 이후 전량 재조회 — 선행 트랜잭션(웹훅)의 커밋이 반영된 스냅샷이어야 한다.
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn);
        LsDataAugJob target = jobs.stream()
                .filter(j -> augJobSn.equals(j.getAugJobSn()))
                .findFirst()
                .orElse(null);
        if (target == null || target.isTerminal()) {
            // 멱등 — 그 사이 웹훅이 도착해 종결시켰다(정상 시나리오).
            return false;
        }
        // 3) 오배송 차단 — 202 로 받아 둔 job_id 와 다르면 남의 결과다.
        if (target.getExternalJobId() != null && !target.getExternalJobId().equals(externalJobId)) {
            log.warn("[Augment][Recovery] job_id mismatch — 회수 중단 dataAugSn={} jobSeq={}",
                    dataAugSn, target.getJobSeq());
            return false;
        }

        if (!applyTerminal(target, externalJobId, externalStatus, outputs, errorCode, errorMessage)) {
            return false;
        }
        jobRepository.save(target);
        jobRepository.flush();

        AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobId);
        log.warn("[Augment][Recovery] 웹훅 미수신분 회수 dataAugSn={} jobSeq={} externalStatus={} rollup={}",
                dataAugSn, target.getJobSeq(), externalStatus, rolledUp);
        return true;
    }

    /** 외부 종결 상태별 로컬 반영. 알 수 없는 상태는 <b>아무것도 하지 않는다</b>(fail-closed). */
    private boolean applyTerminal(LsDataAugJob target, String externalJobId, String externalStatus,
                                  List<String> outputs, String errorCode, String errorMessage) {
        if (LsDataAugJob.STTS_SUCCEEDED.equals(externalStatus)) {
            try {
                // 건수 불일치면 applier 가 이 job 을 FAILED 로 종결한다(fail-closed) — 그것도 회수다.
                successApplier.applySucceeded(target, externalJobId, outputs, RECOVERY_REASON);
            } catch (CustomException e) {
                log.warn("[Augment][Recovery] 성공 산출물 적용 거부 — 회수 중단 jobSeq={} code={}",
                        target.getJobSeq(), e.getErrorCode());
                return false;
            }
            return true;
        }
        if (LsDataAugJob.STTS_CANCELED.equals(externalStatus)) {
            target.markCanceled(externalJobId, RECOVERY_REASON);
            return true;
        }
        if (LsDataAugJob.STTS_FAILED.equals(externalStatus)) {
            target.markFailed(externalJobId, errorCode, errorMessage);
            return true;
        }
        return false;
    }
}
