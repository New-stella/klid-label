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

    /**
     * 단계 미수행(skip) 감사 행의 처리상태 코드 — 진행 조회에서 제외되는 유일한 값.
     *
     * <p>{@code LS_BATCH_PROC_LOG.PROC_STTS_CD} 는 코드 도메인 {@code VARCHAR(20)} 자유값이며
     * (CHECK 제약 없음), 기존 값은 STARTED/COMPLETED/FAILED 3종이다. skip 은 이 중 어디에도 해당하지
     * 않으므로 별도 값으로 추가한다({@code BatchStage.SKIPPED} 와 이름이 같지만 축이 다르다 —
     * 이쪽은 <b>처리상태</b>, 저쪽은 진입 가드의 <b>반환 단계값</b>).
     */
    static final String STTS_SKIPPED = "SKIPPED";

    private final LsBatchProcLogRepository repository;

    /** 파이프라인 진행 행(=SKIPPED 감사 행 제외 최신 행) 조회 — 모든 상태 갱신/조회의 단일 진입점. */
    private Optional<LsBatchProcLog> latestProgressLog(Long rawSn) {
        return repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(rawSn, STTS_SKIPPED);
    }

    @Transactional("controlTransactionManager")
    public void markStage(Long rawSn, BatchStage stage) {
        if (rawSn == null || stage == null) return;
        LsBatchProcLog log = latestProgressLog(rawSn)
                .map(existing -> { existing.updateStage(stage); return existing; })
                .orElseGet(() -> LsBatchProcLog.create(rawSn, stage));
        repository.save(log);
    }

    /**
     * VLM 단계를 <b>수행하지 않고 건너뛴 사실</b>을 사유와 함께 영속한다 (B-ISSUE-24).
     *
     * <p>과거 skip 경로는 애플리케이션 로그만 남기고 DB 에 아무 흔적도 남기지 않아, VLM 비활성/장애
     * 구간에 처리된 영상이 "메타 없음 + 무기록" 으로 남았다. 그 결과 재처리 대상 식별이 로그 보존기간에
     * 종속됐다. 이제 {@code PROC_STEP_CD='VLM' / PROC_STTS_CD='SKIPPED'} 감사 행 1건을 적재한다.
     *
     * @param reason 건너뛴 사유(예: {@code vlm.client.enabled=false})
     */
    @Transactional("controlTransactionManager")
    public void recordVlmSkipped(Long rawSn, String reason) {
        if (rawSn == null) return;
        repository.save(LsBatchProcLog.createSkipped(rawSn, BatchStage.VLM, reason));
        log.info("[Batch] stage skipped recorded rawSn={} stage={}", rawSn, BatchStage.VLM);
    }

    /**
     * 해당 단계가 <b>지정한 사유로 건너뛴(SKIPPED) 감사 행</b>을 갖고 있는가 — 보류 작업 재개 판정용.
     *
     * <p>{@link #recordVlmSkipped} 가 남긴 흔적을 되읽는 <b>대칭 진입점</b>이다. 신고 구간 보류처럼
     * "실패가 아니라서 재시도 큐가 집지 않는" 작업은 해제 시점에 누군가 이 흔적을 보고 재개해야 한다
     * ({@code VlmWithheldResumeRunner}). {@code PROC_STTS_CD} 리터럴을 호출부로 흘리지 않도록 판정은
     * 여기(로그 축의 소유자)에서 한다.
     *
     * @param reason 기록 시 사용한 사유 문자열(단일 원천은 각 스텝의 상수)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isStageSkippedWithReason(Long rawSn, BatchStage stage, String reason) {
        if (rawSn == null || stage == null || reason == null) return false;
        return repository.existsByDataRawSnAndProcStepCdAndProcSttsCdAndErrorMsg(
                rawSn, stage.name(), STTS_SKIPPED, reason);
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
        LsBatchProcLog logEntry = latestProgressLog(rawSn)
                .orElseGet(() -> LsBatchProcLog.create(rawSn, BatchStage.VLM));
        logEntry.setResPayloadCn(resPayloadJson);
        repository.save(logEntry);
    }

    @Transactional("controlTransactionManager")
    public void markFailed(Long rawSn, Throwable cause) {
        if (rawSn == null) return;
        Optional<LsBatchProcLog> existing = latestProgressLog(rawSn);
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
        return latestProgressLog(rawSn)
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
        return latestProgressLog(rawSn)
                .map(l -> BatchStageProgressMapper.build(l.getStageCd(), l.getProcSttsCd(), videoCompleted))
                .orElseGet(List::of);
    }
}
