package kr.co.cudo.authoring.label.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_DEIDENT_REPORT: 영상 단위 비식별 요청/결과 이력.
 * 비식별 결과 영상 경로는 LS_DATA_RAW 에 붙이지 않고 본 테이블에만 저장한다.
 */
@Entity
@Table(name = "LS_DEIDENT_REPORT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDeidentReport {

    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_OPEN = STATUS_REQUESTED;
    public static final String STATUS_RESOLVED = STATUS_SUCCEEDED;
    public static final String STATUS_DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DEIDENT_REPORT_SN")
    private Long deidentReportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "REQ_ID", length = 64)
    private String reqId;

    @Column(name = "ORGN_FILE_PATH", nullable = false, length = 1000)
    private String orgnFilePath;

    @Column(name = "DE_IDNTF_FILE_PATH", length = 1000)
    private String deIdntfFilePath;

    @Column(name = "PROC_STTS_CD", nullable = false, length = 20)
    private String procSttsCd;

    @Column(name = "REQ_DT")
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

    private LsDeidentReport(Long rawSn, String reqId, String originalFilePath, String regId) {
        this.dataRawSn = rawSn;
        this.reqId = reqId;
        this.orgnFilePath = originalFilePath;
        this.procSttsCd = STATUS_REQUESTED;
        this.reqDt = LocalDateTime.now();
        this.regId = regId;
        this.regDt = this.reqDt;
    }

    public static LsDeidentReport create(Long rawSn, Long reporterNo, String reason) {
        return request(rawSn, null, reason, reporterNo == null ? null : String.valueOf(reporterNo));
    }

    public static LsDeidentReport request(Long rawSn, String reqId, String originalFilePath, String regId) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (originalFilePath == null || originalFilePath.isBlank()) {
            throw new IllegalArgumentException("originalFilePath 는 필수입니다.");
        }
        return new LsDeidentReport(rawSn, reqId, originalFilePath, regId);
    }

    public void succeed(String resultPath) {
        this.procSttsCd = STATUS_SUCCEEDED;
        this.deIdntfFilePath = resultPath;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    public void fail(String errorCd, String errorMsg) {
        this.procSttsCd = STATUS_FAILED;
        this.errorCd = errorCd;
        this.errorMsg = errorMsg;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    public void resolve() {
        succeed(this.deIdntfFilePath);
    }

    public void dismiss() {
        this.procSttsCd = STATUS_DISMISSED;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    public Long getRprtSn() {
        return deidentReportSn;
    }

    public Long getRawSn() {
        return dataRawSn;
    }

    public String getSttsCd() {
        return procSttsCd;
    }
}
