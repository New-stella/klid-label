package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 분할 위탁 job 집계 → 증강 1건 확정(<b>롤업</b>) 규칙 — Phase 8-A 에서 단일 원천으로 분리.
 *
 * <p>증강 1건({@code LS_DATA_AUG})은 입력 100장 상한 때문에 여러 job 으로 분할 위탁되므로,
 * <b>전 job 종결 후에만</b> 최종 처리({@link AugmentResultService})를 수행한다. 이 판정에 진입하는
 * 경로가 둘이라 규칙을 한 곳에 둔다:
 * <ul>
 *   <li>{@link GenAiCallbackService} — 웹훅으로 job 이 종결될 때</li>
 *   <li>{@code AugmentJobExpiryTxService} — 만료 스윕이 비종결 job 을 회수할 때</li>
 * </ul>
 * 규칙이 두 벌이면 한쪽만 고쳐져 "만료된 job 이 섞였는데 성공 확정" 같은 드리프트가 난다.
 *
 * <h3>부분 실패 = 전체 실패 (fail-closed)</h3>
 * <p>job 1건이라도 성공이 아니면(FAILED/CANCELED/만료) 증강을 성공 처리하지 않는다. 부분 결과로
 * 증강 영상을 만들면 프레임이 빠진 불완전한 학습데이터가 되기 때문이다.
 *
 * <h3>호출 규약 (동시성)</h3>
 * <p>호출자는 <b>증강 행({@code LS_DATA_AUG})을 {@code FOR UPDATE} 로 잠근 뒤</b> job 목록을 다시
 * 읽어 이 메서드에 넘겨야 한다. 잠금 없이 부르면 두 트랜잭션이 서로의 미커밋 갱신을 못 봐서
 * 양쪽 다 "아직 남은 job 있음" 으로 판정하고 <b>롤업이 통째로 유실</b>된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentJobRollup {

    private final AugmentResultService augmentResultService;

    /**
     * 전 job 종결 여부를 판정하고 증강 1건을 확정한다.
     *
     * @param jobs          잠금 이후 다시 읽은 job 전량(갱신이 반영된 스냅샷)
     * @param externalJobId 재전송 멱등 앵커로 적재할 외부 job_id (없으면 null)
     * @return {@link AugmentApplyResult#DEFERRED} = 비종결 job 이 남아 보류 / 그 외 = 인계 처리 결과
     *         (정책 보류 {@code WITHHELD_*} 도 그대로 전달한다 — 호출부가 응답에 반영해야 한다)
     */
    public AugmentApplyResult rollUpIfAllTerminal(Long dataAugSn, List<LsDataAugJob> jobs,
                                                  String externalJobId) {
        List<Integer> pending = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (LsDataAugJob job : jobs) {
            if (!job.isTerminal()) {
                pending.add(job.getJobSeq());
            } else if (!LsDataAugJob.STTS_SUCCEEDED.equals(job.getJobSttsCd())) {
                failed.add(job.getJobSeq());
            }
        }
        if (!pending.isEmpty()) {
            log.info("[Webhook][GenAi] rollup deferred dataAugSn={} pendingJobSeqs={}", dataAugSn, pending);
            return AugmentApplyResult.DEFERRED;
        }

        if (failed.isEmpty()) {
            AugmentApplyResult result =
                    augmentResultService.handle(AugmentOutcome.succeeded(dataAugSn, externalJobId));
            log.info("[Webhook][GenAi] rollup succeeded dataAugSn={} jobCount={} result={}",
                    dataAugSn, jobs.size(), result);
            return result;
        }

        // 부분 실패 = 전체 실패. 부분 결과로 증강본을 만들면 프레임이 빠진 불완전 산출물이 된다.
        AugmentApplyResult result =
                augmentResultService.handle(AugmentOutcome.failed(dataAugSn, externalJobId));
        log.warn("[Webhook][GenAi] rollup failed (partial failure — fail-closed) dataAugSn={} "
                + "jobCount={} failedJobSeqs={}", dataAugSn, jobs.size(), failed);
        return result;
    }
}
