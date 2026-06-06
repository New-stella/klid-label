package kr.co.cudo.authoring.batch.status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
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

    @Column(name = "ERR_MSG_CN", length = 1000)
    private String errorMsg;

    @Column(name = "REQ_PAYLOAD_CN")
    private String reqPayloadCn;

    @Column(name = "RES_PAYLOAD_CN")
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

    public void updateStage(BatchStage stage) {
        this.procStepCd = stage.name();
        this.procSttsCd = stage == BatchStage.COMPLETED ? "COMPLETED" : "STARTED";
        this.mdfcnDt = LocalDateTime.now();
        if (stage == BatchStage.COMPLETED) {
            this.endDt = this.mdfcnDt;
        }
    }

    public void fail(Throwable cause) {
        this.procStepCd = BatchStage.FAILED.name();
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
     * RES_PAYLOAD_CN 컬럼에 외부 응답 JSON 을 적재.
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
