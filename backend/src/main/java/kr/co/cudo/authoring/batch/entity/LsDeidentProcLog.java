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

    /**
     * 요청 종류(REQ_KIND_CD) — 비식별 처리 경로 구분. null=기존 배치 비식별 경로(무영향),
     * {@code REDEIDENT}=검수완료 영상 재비식별(Approved Re-deidentification) 경로.
     */
    public static final String REQ_KIND_REDEIDENT = "REDEIDENT";

    /** 폴링 상태(POLL_STTS_CD) — KPST 위탁 후 다운로드까지의 폴링 진행 단계. null=콜백 경로/미사용. */
    public static final String POLL_WAITING = "WAITING";
    public static final String POLL_POLLING = "POLLING";
    public static final String POLL_DOWNLOADED = "DOWNLOADED";
    /**
     * 폴링 실패 종료값 — 타임아웃/불완전 산출물로 'F' 마킹된 폴링 건의 종료 상태.
     * {@code findByPollSttsCdIn([WAITING,POLLING])} 에 포함되지 않아 재폴링·중복 다운로드를 차단한다
     * (DEV_FIX HIGH).
     */
    public static final String POLL_FAILED = "FAILED";

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
    @Column(name = "OTSD_JOB_ID", length = 128)
    private String externalJobId;

    @Column(name = "ORGNL_FILE_PATH_NM", length = 1000, nullable = false)
    private String orgnlFilePathNm;

    @Column(name = "DE_IDNTF_FILE_PATH_NM", length = 1000)
    private String deIdntfFilePathNm;

    @Column(name = "PROC_STTS_CD", length = 20, nullable = false)
    private String procSttsCd;

    @Column(name = "REQ_DT", nullable = false)
    private LocalDateTime reqDt;

    @Column(name = "RES_DT")
    private LocalDateTime resDt;

    @Column(name = "ERR_CD", length = 50)
    private String errorCd;

    @Column(name = "ERR_MSG_CN", length = 1000)
    private String errorMsg;

    /** KPST 프로젝트 ID — 영상 1건 = 프로젝트 1개 (Phase 2 폴링 연동). */
    @Column(name = "KPST_PRJ_ID")
    private Long kpstPrjId;

    /** KPST 데이터셋 ID — 파일 단위 (Phase 2 폴링 연동). */
    @Column(name = "KPST_DATASET_ID")
    private Long kpstDatasetId;

    /** 폴링 상태: WAITING/POLLING/DOWNLOADED. null=콜백 경로 또는 미사용. */
    @Column(name = "POLL_STTS_CD", length = 20)
    private String pollSttsCd;

    /** 마지막 폴링 시각. */
    @Column(name = "POLL_LAST_DT")
    private LocalDateTime pollLastDt;

    /** 폴링 시도 횟수(타임아웃 판정용). */
    @Column(name = "POLL_ATTEMPT_CNT")
    private Integer pollAttemptCnt;

    /** 요청 종류: null=기존 배치 비식별 경로, {@link #REQ_KIND_REDEIDENT}=검수완료 재비식별 경로. */
    @Column(name = "REQ_KIND_CD", length = 20)
    private String reqKindCd;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    public static LsDeidentProcLog request(Long rawSn, String reqId, String orgnlFilePath, String regId) {
        return request(rawSn, reqId, orgnlFilePath, regId, null);
    }

    /**
     * externalJobId 포함 생성 — Phase 2 webhook 인계 시 사용.
     */
    public static LsDeidentProcLog request(Long rawSn, String reqId, String orgnlFilePath, String regId, String externalJobId) {
        if (rawSn == null) throw new IllegalArgumentException("rawSn 은 필수입니다.");
        if (orgnlFilePath == null || orgnlFilePath.isBlank()) throw new IllegalArgumentException("orgnlFilePath 는 필수입니다.");
        LsDeidentProcLog log = new LsDeidentProcLog();
        log.dataRawSn = rawSn;
        log.reqId = reqId;
        log.externalJobId = externalJobId;
        log.orgnlFilePathNm = orgnlFilePath;
        log.procSttsCd = REQUESTED;
        log.reqDt = LocalDateTime.now();
        log.regId = regId;
        log.regDt = log.reqDt;
        return log;
    }

    public void succeed(String resultPath) {
        this.procSttsCd = SUCCEEDED;
        this.deIdntfFilePathNm = resultPath;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    /**
     * KPST 위탁 직후 — 프로젝트 ID 기록 + 폴링 대기 진입.
     * PROC_STTS_CD 는 REQUESTED 유지(콜백/폴링 어느 쪽이든 진행 중).
     *
     * <p>규격(§22.3.3)상 {@code /project} 응답엔 {@code prj_id} 만 있고 datasetId 는 미상이다.
     * datasetId 는 첫 {@code retrieve_progress} 응답의 {@code dsStatus[0].dsId} 로 {@link #recordDatasetId}
     * 에서 보충한다. 따라서 위탁 시점엔 null 을 허용한다(prjId 만 필수).
     */
    public void markKpstSubmitted(Long kpstPrjId, Long kpstDatasetId) {
        if (kpstPrjId == null) throw new IllegalArgumentException("kpstPrjId 는 필수입니다.");
        this.kpstPrjId = kpstPrjId;
        this.kpstDatasetId = kpstDatasetId;
        this.pollSttsCd = POLL_WAITING;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 첫 폴링에서 datasetId 보충 기록 — 이미 채워졌으면 덮어쓰지 않는다(idempotent).
     */
    public void recordDatasetId(Long kpstDatasetId) {
        if (kpstDatasetId != null && this.kpstDatasetId == null) {
            this.kpstDatasetId = kpstDatasetId;
            this.mdfcnDt = LocalDateTime.now();
        }
    }

    /**
     * 폴링 진행 — 상태를 POLLING 으로 두고 시도 횟수/마지막 시각 갱신.
     */
    public void markPolling() {
        this.pollSttsCd = POLL_POLLING;
        this.pollAttemptCnt = (this.pollAttemptCnt == null ? 0 : this.pollAttemptCnt) + 1;
        this.pollLastDt = LocalDateTime.now();
        this.mdfcnDt = this.pollLastDt;
    }

    /**
     * KPST 비식별 결과 다운로드/저장 완료 — POLL_STTS=DOWNLOADED + PROC_STTS=SUCCEEDED.
     */
    public void markDownloaded(String deidFilePath) {
        if (deidFilePath == null || deidFilePath.isBlank()) {
            throw new IllegalArgumentException("deidFilePath 는 필수입니다.");
        }
        this.pollSttsCd = POLL_DOWNLOADED;
        this.procSttsCd = SUCCEEDED;
        this.deIdntfFilePathNm = deidFilePath;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    /**
     * 처리 실패 — PROC_STTS=FAILED 전이. 폴링 경로(POLL_STTS 가 진행 상태)인 경우 POLL_STTS 도
     * 종료값({@link #POLL_FAILED})으로 전이해 재폴링 대상에서 제외한다(DEV_FIX HIGH — 무한 재폴링/중복
     * 다운로드 차단). 콜백 경로(POLL_STTS=null)는 그대로 두어 무영향이다.
     */
    public void fail(String errorCd, String errorMsg) {
        this.procSttsCd = FAILED;
        if (POLL_WAITING.equals(this.pollSttsCd) || POLL_POLLING.equals(this.pollSttsCd)) {
            this.pollSttsCd = POLL_FAILED;
        }
        this.errorCd = errorCd;
        this.errorMsg = errorMsg;
        this.resDt = LocalDateTime.now();
        this.mdfcnDt = this.resDt;
    }

    /** 검수완료 재비식별 경로 여부. null(기존 배치 경로)이면 false. */
    public boolean isRedeident() {
        return REQ_KIND_REDEIDENT.equals(reqKindCd);
    }

    /** 이 로그를 검수완료 재비식별 경로로 표시한다. */
    public void markRedeident() {
        this.reqKindCd = REQ_KIND_REDEIDENT;
    }
}
