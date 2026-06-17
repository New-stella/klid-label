package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * VLM 시계열 외부 위탁 응답(externalJobId/status) 을 최신 로그의 RES_PAYLOAD_CN 에 기록.
     *
     * <p>Phase 2 결과 수신 webhook 에서 externalJobId 로 영상을 역추적할 때 사용한다.
     * 별도 컬럼 추가 없이 기존 {@code RES_PAYLOAD_CN} JSON 컬럼에 적재한다.
     * 로그가 없으면 새로 생성한다.
     */
    @Transactional("controlTransactionManager")
    public void recordVlmTimeseriesResult(Long rawSn, String resPayloadJson) {
        if (rawSn == null) return;
        LsBatchProcLog logEntry = repository.findTopByDataRawSnOrderByRegDtDesc(rawSn)
                .orElseGet(() -> LsBatchProcLog.create(rawSn, BatchStage.VLM));
        logEntry.setResPayloadCn(resPayloadJson);
        repository.save(logEntry);
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
}
