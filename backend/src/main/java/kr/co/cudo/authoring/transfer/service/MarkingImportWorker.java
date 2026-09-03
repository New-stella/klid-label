package kr.co.cudo.authoring.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.transfer.config.MarkingImportConfig;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJob;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 일꾼 — 원장에서 항목을 하나씩 집어 처리하고, 남은 것이 없으면 작업을 종결한다.
 *
 * <h3>집기와 처리를 나눈다</h3>
 * <p>집기는 짧은 트랜잭션이고 처리는 트랜잭션 밖의 파일 입출력이다. 한 덩어리로 묶으면 백 건의 복사
 * 시간 동안 커넥션을 쥐고 있게 되어 커넥션이 마른다. 또 <b>항목마다 따로 트랜잭션</b>이라야 한 건이
 * 실패해도 나머지가 이어진다(SEQ-030).
 *
 * <h3>여러 일꾼이 같은 작업에 붙는다</h3>
 * <p>동시 처리 수만큼 일꾼이 뜨고, 2노드 Active-Active 라 다른 노드의 일꾼도 같은 원장을 본다.
 * 그래서 집기는 <b>조건부 UPDATE</b> 이고, 「집지 못했다」와 「남은 것이 없다」를 구분한다 — 구분하지
 * 않으면 경합이 잦은 순간에 일꾼이 전부 물러나 <b>대기 항목이 남았는데 아무도 처리하지 않는</b>
 * 작업이 생긴다.
 *
 * <h3>한 항목의 실패가 일꾼을 멈추지 않는다</h3>
 * <p>처리기가 예외를 밖으로 내지 않으므로 어떤 결과든 마감으로 이어진다. 그럼에도 여기서 한 겹 더
 * 감싸는 이유는 <b>마감 자체가 실패</b>할 수 있기 때문이다(커넥션 끊김 등). 그 경우에도 일꾼이 멈추면
 * 남은 항목이 통째로 지연되므로 다음 항목으로 넘어간다 — 마감되지 못한 항목은 처리중으로 남아
 * 되돌리기 잡이 회수한다.
 *
 * @design DOMAIN-017
 * @design API-217
 * @design AC-1032
 * @design AC-1033
 * @design SEQ-030
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingImportWorker {

    /**
     * 집기에 잇달아 실패했을 때 물러나기까지의 횟수.
     *
     * <p>0이면 경합 한 번에 물러나 처리가 멈추고, 크게 잡으면 다른 일꾼이 다 집어 간 상황에서 의미
     * 없이 원장을 두드린다. 이 값만큼 시도해도 못 집으면 <b>다른 일꾼이 처리하고 있다</b>고 보고
     * 물러난다 — 그쪽이 끝에 작업을 종결한다.
     */
    private static final int CLAIM_ATTEMPT_LIMIT = 5;

    private final MarkingImportJobTxService jobTxService;
    private final MarkingImportItemProcessor processor;
    private final ImportHistoryTxService historyTxService;
    private final ObjectMapper objectMapper;

    /**
     * 작업 하나가 끝날 때까지 항목을 집어 처리한다.
     *
     * <p>여러 일꾼이 같은 작업 식별번호로 들어와도 안전하다 — 항목 소유권은 원장이 준다.
     */
    @Async(MarkingImportConfig.EXECUTOR)
    public void work(long jobSn) {
        LsEblcUldJob job = jobTxService.findJob(jobSn).orElse(null);
        if (job == null) {
            log.warn("[MarkingImport] job not found jobSn={}", jobSn);
            return;
        }
        if (job.isFinished()) {
            return;
        }
        MarkingImportJobMeta meta = readMeta(job);
        if (meta == null) {
            // 지정값을 읽지 못하면 무엇을 붙여 적재할지 알 수 없다. 남은 항목을 실패로 마감해
            // 작업이 진행중인 채로 영원히 남지 않게 한다.
            failAllRemaining(jobSn, "적재에 필요한 지정값을 읽지 못했다.");
            finishAndCloseHistory(jobSn, null);
            return;
        }

        int emptyClaims = 0;
        while (emptyClaims < CLAIM_ATTEMPT_LIMIT) {
            Optional<LsEblcUldJobArtcl> claimed;
            try {
                claimed = jobTxService.claimNext(jobSn);
            } catch (RuntimeException e) {
                log.warn("[MarkingImport] claim failed jobSn={} cause={}", jobSn, e.getClass().getSimpleName());
                break;
            }
            if (claimed.isEmpty()) {
                if (!jobTxService.hasPending(jobSn)) {
                    break;
                }
                emptyClaims++;
                continue;
            }
            emptyClaims = 0;
            handle(jobSn, claimed.get(), meta, job.getRegId());
        }

        finishAndCloseHistory(jobSn, meta);
    }

    private void handle(long jobSn, LsEblcUldJobArtcl item, MarkingImportJobMeta meta, String actorId) {
        long artclSn = item.getEblcUldJobArtclSn();
        try {
            MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item, meta, actorId);
            if (outcome.succeeded()) {
                jobTxService.completeSuccess(jobSn, artclSn, outcome.rawSn());
            } else {
                jobTxService.completeUnfinished(jobSn, artclSn, outcome.status(),
                        outcome.rawSn(), outcome.reason());
            }
        } catch (RuntimeException e) {
            // 마감 자체가 실패했다 — 항목은 처리중으로 남고 되돌리기 잡이 회수한다.
            log.warn("[MarkingImport] item close failed jobSn={} artclSn={} cause={}",
                    jobSn, artclSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 남은 항목을 모두 실패로 마감한다 — 지정값을 읽지 못한 경우처럼 <b>어느 항목도 처리할 수 없는</b>
     * 상황에서만 쓴다.
     */
    private void failAllRemaining(long jobSn, String reason) {
        Optional<LsEblcUldJobArtcl> claimed;
        while ((claimed = jobTxService.claimNext(jobSn)).isPresent()) {
            jobTxService.completeUnfinished(jobSn, claimed.get().getEblcUldJobArtclSn(),
                    LsEblcUldJobArtcl.ARTCL_STTS_FAILED, null, reason);
        }
    }

    /**
     * 남은 항목이 없으면 작업을 종결하고 <b>이관 이력 줄을 마감</b>한다.
     *
     * <p>이력은 두 갈래 공통 표시 대상이라 마킹 갈래도 한 줄을 남긴다. 종결을 얻은 일꾼이 한 명뿐이라
     * 이력도 한 번만 마감된다.
     *
     * <p>⚠ 이력의 프레임·라벨 건수는 <b>0</b>이다. 이 갈래는 프레임도 라벨도 적재하지 않는다 —
     * 시작점만 받아 앞 단계를 밟으므로 프레임은 뒤의 추출 단계가 만든다. 건별 결과는 진행 조회에서 본다.
     */
    private void finishAndCloseHistory(long jobSn, MarkingImportJobMeta meta) {
        Optional<String> finished;
        try {
            finished = jobTxService.finishIfDone(jobSn);
        } catch (RuntimeException e) {
            log.warn("[MarkingImport] job finish failed jobSn={} cause={}", jobSn, e.getClass().getSimpleName());
            return;
        }
        if (finished.isEmpty()) {
            return;
        }
        log.info("[MarkingImport] job finished jobSn={} status={}", jobSn, finished.get());

        Long historySn = meta == null ? null : meta.historySn();
        if (historySn == null) {
            return;
        }
        if (LsEblcUldJob.JOB_STTS_COMPLETED.equals(finished.get())) {
            historyTxService.succeed(historySn, null, 0, 0);
        } else {
            historyTxService.failQuietly(historySn, "일부 항목을 적재하지 못했다. 건별 결과는 진행 조회에서 확인한다.");
        }
    }

    private MarkingImportJobMeta readMeta(LsEblcUldJob job) {
        if (job.getDmndCn() == null || job.getDmndCn().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(job.getDmndCn(), MarkingImportJobMeta.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[MarkingImport] job meta unreadable jobSn={} cause={}",
                    job.getEblcUldJobSn(), e.getClass().getSimpleName());
            return null;
        }
    }
}
