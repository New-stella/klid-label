package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.dto.BatchStageProgress;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 배치 단계별 DB 기반 상태 추적.
 * LsBatchProcLog 신규 스키마 기준으로 영상별 최신 로그를 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchStatusService {

    private final LsBatchProcLogRepository repository;

    @Transactional("controlTransactionManager")
    public void markStage(Long rawSn, BatchStage stage) {
        if (rawSn == null || stage == null) return;
        LsBatchProcLog log = repository.findTopByDataRawSnOrderByRegDtDesc(rawSn)
                .map(existing -> { existing.updateStage(stage); return existing; })
                .orElseGet(() -> LsBatchProcLog.create(rawSn, stage));
        repository.save(log);
    }

    @Transactional("controlTransactionManager")
    public void markCompleted(Long rawSn) {
        markStage(rawSn, BatchStage.COMPLETED);
    }

    @Transactional("controlTransactionManager")
    public void markFailed(Long rawSn, Throwable cause) {
        if (rawSn == null) return;
        Optional<LsBatchProcLog> existing = repository.findTopByDataRawSnOrderByRegDtDesc(rawSn);
        LsBatchProcLog log = existing.orElseGet(() -> LsBatchProcLog.create(rawSn, BatchStage.FAILED));
        log.fail(cause);
        if (existing.isPresent()) {
            log.incrementRetry();
        }
        repository.save(log);
    }

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public BatchStage currentStage(Long rawSn) {
        return repository.findTopByDataRawSnOrderByRegDtDesc(rawSn)
                .map(l -> BatchStage.valueOf(l.getStageCd()))
                .orElse(BatchStage.PENDING);
    }

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<BatchStageProgress> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findTop100ByOrderByMdfcnDtDescRegDtDesc().stream()
                .limit(safeLimit)
                .map(this::toDto)
                .toList();
    }

    private BatchStageProgress toDto(LsBatchProcLog e) {
        return new BatchStageProgress(
                e.getRawSn(),
                e.getStageCd(),
                e.getStartedAt(),
                e.getUpdatedAt(),
                e.getRetryCnt(),
                e.getErrMsg()
        );
    }
}
