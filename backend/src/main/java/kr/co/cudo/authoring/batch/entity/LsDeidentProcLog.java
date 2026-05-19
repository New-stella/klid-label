package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "LS_DEIDENT_PROC_LOG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDeidentProcLog {

    public static final String REQUESTED = "REQUESTED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PROC_LOG_SN")
    private Long procLogSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "REQ_ID", length = 64)
    private String reqId;

    /**
     * 외부 시스템(Deidentify SW)의 작업 ID — Phase 2 보강 (DEV_FIX H-3).
     * UNIQUE 제약으로 동일 externalJobId 재인계 시 upsert 단일 row 갱신을 보장한다.
     */
    @Column(name = "EXTERNAL_JOB_ID", length = 128)
    private String externalJobId;

    @Column(name = "ORGN_FILE_PATH", length = 1000, nullable = false)
    private String orgnFilePath;

    @Column(name = "DE_IDNTF_FILE_PATH", length = 1000)
    private String deIdntfFilePath;

    @Column(name = "PROC_STTS_CD", length = 20, nullable = false)
    private String procSttsCd;

    @Column(name = "REQ_DT", nullable = false)
    private LocalDateTime reqDt;

    @Column(name = "RES_DT")
    private LocalDateTime resDt;

    @Column(name = "ERROR_CD", length = 50)
    private String errorCd;

    @Column(name = "ERROR_MSG", length = 1000)
    private String errorMsg;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    public static LsDeidentProcLog request(Long rawSn, String reqId, String orgnFilePath, String regId) {
        return request(rawSn, reqId, orgnFilePath, regId, null);
    }

    /**
     * externalJobId 포함 생성 — Phase 2 webhook 인계 시 사용.
     */
    public static LsDeidentProcLog request(Long rawSn, String reqId, String orgnFilePath, String regId, String externalJobId) {
        if (rawSn == null) throw new IllegalArgumentException("rawSn 은 필수입니다.");
        if (orgnFilePath == null || orgnFilePath.isBlank()) throw new IllegalArgumentException("orgnFilePath 는 필수입니다.");
        LsDeidentProcLog log = new LsDeidentProcLog();
        log.dataRawSn = rawSn;
        log.reqId = reqId;
        log.externalJobId = externalJobId;
        log.orgnFilePath = orgnFilePath;
        log.procSttsCd = REQUESTED;
        log.reqDt = LocalDateTime.now();
        log.regId = regId;
        log.regDt = log.reqDt;
        return log;
    }

    public void succeed(String resultPath) {
        this.procSttsCd = SUCCEEDED;
        this.deIdntfFilePath = resultPath;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    public void fail(String errorCd, String errorMsg) {
        this.procSttsCd = FAILED;
        this.errorCd = errorCd;
        this.errorMsg = errorMsg;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }
}
