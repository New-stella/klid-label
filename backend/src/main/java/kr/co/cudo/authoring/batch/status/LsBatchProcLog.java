package kr.co.cudo.authoring.batch.status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_BATCH_PROC_LOG: 배치 파이프라인 단계별 처리 이력.
 */
@Entity
@Table(name = "LS_BATCH_PROC_LOG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsBatchProcLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BATCH_PROC_LOG_SN")
    private Long batchProcLogSn;

    @Column(name = "JOB_ID", nullable = false, length = 64)
    private String jobId;

    @Column(name = "DATA_RAW_SN")
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    @Column(name = "PROC_STEP_CD", nullable = false, length = 30)
    private String procStepCd;

    @Column(name = "PROC_STTS_CD", nullable = false, length = 20)
    private String procSttsCd;

    @Column(name = "BGNG_DT")
    private LocalDateTime startDt;

    @Column(name = "END_DT")
    private LocalDateTime endDt;

    @Column(name = "RTRY_NMTM", nullable = false)
    private int rtryCnt;

    @Column(name = "ERR_CD", length = 50)
    private String errorCd;

    @Column(name = "ERR_MSG_CN", length = 4000)
    private String errorMsg;

    @Column(name = "REQ_PAYLOAD_CN", columnDefinition = "TEXT")
    private String reqPayloadCn;

    @Column(name = "RESP_PAYLOAD_CN", columnDefinition = "TEXT")
    private String resPayloadCn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    public static LsBatchProcLog create(Long rawSn, BatchStage stage) {
        LsBatchProcLog log = new LsBatchProcLog();
        log.jobId = "RAW-" + rawSn;
        log.dataRawSn = rawSn;
        log.procStepCd = stage.name();
        log.procSttsCd = "STARTED";
        LocalDateTime now = LocalDateTime.now();
        log.startDt = now;
        log.regDt = now;
        log.rtryCnt = 0;
        return log;
    }

    /**
     * 단계 <b>미수행(skip)</b> 감사 행 생성 — B-ISSUE-24.
     *
     * <p>진행 행을 갱신하지 않고 별도 행으로 적재한다(append-only). 진행 행에 기록하면 다음 단계 전이가
     * 즉시 덮어써 흔적이 사라지기 때문이다. {@code PROC_STTS_CD='SKIPPED'} 로 남으므로 진행 조회
     * ({@code findTopByDataRawSnAndProcSttsCdNot...})는 이 행을 건너뛴다.
     *
     * <p>사유는 별도 컬럼을 신설하지 않고 {@code ERR_MSG_CN}(자유 서술 사유 컬럼)에 적재한다 — 실패가
     * 아님은 {@code PROC_STTS_CD} 로 구분되며, 스키마 추가 없이 "왜 건너뛰었는지"를 영속한다.
     *
     * @param reason 건너뛴 사유(운영 재처리 대상 식별용). 비어 있으면 안 된다.
     */
    public static LsBatchProcLog createSkipped(Long rawSn, BatchStage stage, String reason) {
        LsBatchProcLog log = create(rawSn, stage);
        log.procSttsCd = "SKIPPED";
        log.errorMsg = reason;
        log.endDt = log.startDt;
        return log;
    }

    /**
     * REVIEWER 수동 스킵/해제 <b>표식</b> 행 생성. [@design API-198] [@design API-200]
     *
     * <p>{@link #createSkipped} 와 같은 {@code PROC_STTS_CD='SKIPPED'} 축을 쓰므로 진행 조회가 이 행을
     * 건너뛴다 — 화면 단계 표시({@link BatchStageProgressMapper})는 영향받지 않는다. 구분자는
     * {@code ERR_CD}({@link ManualStageSkip#MARKER_ERR_CDS})이고, 사유는 접두가 강제된 채
     * {@code ERR_MSG_CN} 에 들어가 재개 사유 상수와 정확 일치할 수 없다.
     *
     * <p>★{@code PROC_STEP_CD} 에는 개별 단계가 아니라 <b>작업 묶음 코드</b>가 들어간다 — 한 행이 한
     * 묶음의 결정을 통째로 담아 <b>부분 상태를 표현 불가능</b>하게 만든다(근거는 {@link ManualStageSkip}
     * Javadoc 「저장 축」 절). 이 값이 {@code BatchStage} 상수와 일치할 필요는 없다.
     *
     * <p>행위자는 별도 컬럼 신설 없이 기존 {@code REG_ID} 에 남긴다(누가·언제·왜의 "누가").
     *
     * @param bundle   대상 작업 묶음 — {@code PROC_STEP_CD} 로 저장된다
     * @param errCd    {@link ManualStageSkip#ERR_CD_SKIPPED} 또는 {@link ManualStageSkip#ERR_CD_CLEARED}
     * @param reason   접두가 이미 붙은 사유 문자열(정제·상한 적용은 호출자 책임)
     * @param actorId  행위자 식별자(없으면 {@code null})
     */
    public static LsBatchProcLog createManualSkipMarker(
            Long rawSn, BatchStageBundle bundle, String errCd, String reason, String actorId) {
        LsBatchProcLog log = new LsBatchProcLog();
        log.jobId = "RAW-" + rawSn;
        log.dataRawSn = rawSn;
        log.procStepCd = bundle.name();
        log.procSttsCd = "SKIPPED";
        log.errorCd = errCd;
        log.errorMsg = reason;
        log.regId = actorId;
        log.rtryCnt = 0;
        LocalDateTime now = LocalDateTime.now();
        log.startDt = now;
        log.regDt = now;
        log.endDt = now;
        return log;
    }

    /**
     * 수동 재기동·재수행 <b>선점 표식</b> 행 생성 — 고착 회수의 유일한 판정 근거.
     *
     * <p>저장 축·열림/닫힘 판정 규칙은 {@link ReprocessClaimMarker} 가 소유한다. 이 팩토리는 그 규칙대로
     * 행을 조립할 뿐이며 {@code PROC_STTS_CD='SKIPPED'} 라 진행 조회에서 제외된다
     * ({@link #createManualSkipMarker} 와 동일 성질).
     *
     * @param errCd  {@link ReprocessClaimMarker#ERR_CD_OPEN} / {@code ..._CLOSED} / {@code ..._RECLAIMED}
     * @param detail 열림 표식이면 {@link ReprocessClaimOrigin#name()}(복구 목표 판정의 입력),
     *               닫힘 표식이면 종료 사유 문구. 사용자 입력·경로·PII 를 담지 않는다(CWE-117/532)
     * @param regId  시스템 기록 주체 고정값({@link ReprocessClaimMarker#REG_ID} 또는 {@code RECLAIM_REG_ID})
     */
    public static LsBatchProcLog createReprocessClaimMarker(
            Long rawSn, String errCd, String detail, String regId) {
        LsBatchProcLog log = new LsBatchProcLog();
        log.jobId = "RAW-" + rawSn;
        log.dataRawSn = rawSn;
        log.procStepCd = ReprocessClaimMarker.PROC_STEP_CD;
        log.procSttsCd = "SKIPPED";
        log.errorCd = errCd;
        log.errorMsg = detail;
        log.regId = regId;
        log.rtryCnt = 0;
        LocalDateTime now = LocalDateTime.now();
        log.startDt = now;
        log.regDt = now;
        log.endDt = now;
        return log;
    }

    public void updateStage(BatchStage stage) {
        this.procStepCd = stage.name();
        this.procSttsCd = stage == BatchStage.COMPLETED ? "COMPLETED" : "STARTED";
        this.mdfcnDt = LocalDateTime.now();
        if (stage == BatchStage.COMPLETED) {
            this.endDt = this.mdfcnDt;
        }
    }

    public void fail(Throwable cause) {
        // PROC_STEP_CD(실패 단계)는 보존한다 — 진행률 화면이 "어느 단계에서 실패했는지"를
        // 표시하려면 실패 시점의 단계가 필요하다. 실패 여부는 PROC_STTS_CD='FAILED' 로 판정한다.
        // (구: procStepCd 를 FAILED 로 덮어써 실패 단계 정보가 소실됐다.)
        this.procSttsCd = "FAILED";
        this.errorCd = cause.getClass().getSimpleName();
        this.errorMsg = cause.getMessage();
        this.endDt = LocalDateTime.now();
        this.mdfcnDt = this.endDt;
    }

    public void incrementRetry() {
        this.rtryCnt++;
    }

    /**
     * RESP_PAYLOAD_CN 컬럼에 외부 응답 JSON 을 적재.
     *
     * <p>DEV_FIX-1: VLM 시계열 외부 위탁 응답({@code externalJobId}, {@code status}) 영속화에 사용.
     * Phase 2 webhook 에서 externalJobId 로 영상 역추적할 때 활용된다.
     */
    public void setResPayloadCn(String resPayloadCn) {
        this.resPayloadCn = resPayloadCn;
        this.mdfcnDt = LocalDateTime.now();
    }

    public Long getRawSn() {
        return dataRawSn;
    }

    public String getStageCd() {
        return procStepCd;
    }

    public LocalDateTime getStartedAt() {
        return startDt;
    }

    public LocalDateTime getUpdatedAt() {
        return mdfcnDt == null ? regDt : mdfcnDt;
    }

    public int getRetryCnt() {
        return rtryCnt;
    }

    public String getErrMsg() {
        return errorMsg;
    }
}
