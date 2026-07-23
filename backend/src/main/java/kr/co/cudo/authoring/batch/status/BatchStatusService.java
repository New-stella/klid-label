package kr.co.cudo.authoring.batch.status;

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

    /**
     * VLM 시계열 외부 위탁 응답(externalJobId/status) 을 최신 로그의 RESP_PAYLOAD_CN 에 기록.
     *
     * <p>Phase 2 결과 수신 webhook 에서 externalJobId 로 영상을 역추적할 때 사용한다.
     * 별도 컬럼 추가 없이 기존 {@code RESP_PAYLOAD_CN} JSON 컬럼에 적재한다.
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

    // 관측 전용 — 현재 소비 API 없음(진행률 화면 연결 시 사용 예정).
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public BatchStage currentStage(Long rawSn) {
        return repository.findTopByDataRawSnOrderByRegDtDesc(rawSn)
                .map(l -> BatchStage.valueOf(l.getStageCd()))
                .orElse(BatchStage.PENDING);
    }

    /**
     * 영상 상세 진행률 표시용 — 최신 배치 로그를 canonical 단계 순서로 펼친 상태 리스트.
     *
     * <p>로그가 없으면(배치 미진행/기존 영상) 빈 리스트를 반환해 FE 가 기존 배지로 폴백하게 한다
     * (하위호환, 예외 없음). 단건 상세 조회에서만 호출하므로 영상당 1쿼리 이내(N+1 아님).
     *
     * @param videoCompleted 영상이 이미 COMPLETED 인지(LS_DATA_RAW.DATA_STTS_CD) — true 면 전 단계 DONE.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<BatchStageProgressMapper.StageStatus> stagesFor(Long rawSn, boolean videoCompleted) {
        if (rawSn == null) {
            return List.of();
        }
        return repository.findTopByDataRawSnOrderByRegDtDesc(rawSn)
                .map(l -> BatchStageProgressMapper.build(l.getStageCd(), l.getProcSttsCd(), videoCompleted))
                .orElseGet(List::of);
    }
}
