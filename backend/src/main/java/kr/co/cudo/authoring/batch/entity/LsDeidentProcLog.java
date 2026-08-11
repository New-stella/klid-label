package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.*;
import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
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
    @Column(name = "OTSD_JOB_ID", length = 200)
    private String externalJobId;

    @Column(name = "ORGNL_FILE_PATH_NM", length = 1000, nullable = false)
    private String orgnlFilePathNm;

    @Column(name = "DE_IDNTF_FILE_PATH_NM", length = 1000)
    private String deIdntfFilePathNm;

    @Column(name = "PROC_STTS_CD", length = 20, nullable = false)
    private String procSttsCd;

    @Column(name = "REQ_DT", nullable = false)
    private LocalDateTime reqDt;

    @Column(name = "RSPNS_DT")
    private LocalDateTime resDt;

    @Column(name = "ERR_CD", length = 50)
    private String errorCd;

    @Column(name = "ERR_MSG_CN", length = 4000)
    private String errorMsg;

    /** KPST 프로젝트 ID — 영상 1건 = 프로젝트 1개 (Phase 2 폴링 연동). 물리 컬럼: DE_IDNTF_PJT_ID(V83). */
    @Column(name = "DE_IDNTF_PJT_ID")
    private Long kpstPrjId;

    /** KPST 데이터셋 ID — 파일 단위 (Phase 2 폴링 연동). 물리 컬럼: DE_IDNTF_DATST_ID(V83). */
    @Column(name = "DE_IDNTF_DATST_ID")
    private Long kpstDatasetId;

    /** 폴링 상태: WAITING/POLLING/DOWNLOADED. null=콜백 경로 또는 미사용. */
    @Column(name = "POLL_STTS_CD", length = 20)
    private String pollSttsCd;

    /** 마지막 폴링 시각. */
    @Column(name = "POLL_LAST_DT")
    private LocalDateTime pollLastDt;

    /** 폴링 시도 횟수(타임아웃 판정용). 물리 컬럼: POLL_ATMPT_CNT(V83). */
    @Column(name = "POLL_ATMPT_CNT")
    private Integer pollAttemptCnt;

    /** 요청 종류: null=기존 배치 비식별 경로, {@link #REQ_KIND_REDEIDENT}=검수완료 재비식별 경로. 물리 컬럼: REQ_KND_CD(V83). */
    @Column(name = "REQ_KND_CD", length = 20)
    private String reqKindCd;

    // ── 처리 결과 리포트 (KPST GET /retrieve_report, V184) ─────────────────── [req: R14]
    //
    // 이 테이블은 위탁 회차마다 새 행을 INSERT 하므로, 아래 6개 컬럼이 채워진 행들이 곧 영상 단위
    // <비식별 이력>이다(별도 이력 테이블 없음). 전부 nullable 이며 null 은 "리포트를 못 받았다"는
    // 뜻이다 — 0 으로 채우면 "0건 검출" 과 구분되지 않으므로 기본값을 두지 않는다.

    /** 얼굴 검출 수 — 리포트 {@code dsStatus[].faceCount}. */
    @Column(name = "FACE_DTCT_CNT")
    private Long faceDtctCnt;

    /** 번호판 검출 수 — 리포트 {@code dsStatus[].lpCount}. */
    @Column(name = "NOPLT_DTCT_CNT")
    private Long noPltDtctCnt;

    /** 비식별 처리 대상 총 프레임 수 — 리포트 {@code dsStatus[].totalFrame}. */
    @Column(name = "FRME_CNT")
    private Long frmeCnt;

    /** 외부 솔루션의 처리 시작 일시 — 리포트 {@code dsStatus[].startTime}(해석 불가 시 null). */
    @Column(name = "PRCS_BGNG_DT")
    private LocalDateTime prcsBgngDt;

    /** 외부 솔루션의 처리 종료 일시 — 리포트 {@code dsStatus[].endTime}(해석 불가 시 null). */
    @Column(name = "PRCS_END_DT")
    private LocalDateTime prcsEndDt;

    /**
     * 리포트가 회신한 파일 경로 — {@code dsStatus[].fileName}.
     *
     * <p><b>결과 파일이 아니라 원본 입력파일의 절대경로</b>가 오는 것이 실측 계약이다(진행조회와 동일).
     * 비식별 산출물 경로는 {@link #deIdntfFilePathNm} 이며 이 값과 혼동하면 안 된다.
     */
    @Column(name = "RPT_FILE_PATH_NM", length = 1000)
    private String rptFilePathNm;

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
     *
     * <p><b>Phase C-2 이후 프로덕션 ACK 경로는 이 메서드가 아니다</b> — 논블로킹 제출의 ACK 는 지각
     * 신호가 종결된 원장을 되살리지 못하도록 조건부 원자 UPDATE
     * ({@code LsDeidentProcLogRepository.claimSubmitAck})로만 기록한다. 본 메서드는 "ACK 를 이미 받은
     * 원장" 상태를 만들기 위한 도메인 표현(주로 테스트 픽스처)으로 남는다 — 새 프로덕션 경로에서
     * 이 메서드를 쓰면 그 원자성 가드를 우회하게 되므로 쓰지 말 것.
     */
    public void markKpstSubmitted(Long kpstPrjId, Long kpstDatasetId) {
        if (kpstPrjId == null) throw new IllegalArgumentException("kpstPrjId 는 필수입니다.");
        this.kpstPrjId = kpstPrjId;
        this.kpstDatasetId = kpstDatasetId;
        this.pollSttsCd = POLL_WAITING;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * KPST 위탁 <b>개시</b> 표시 (Phase C-2 — 논블로킹 제출의 선커밋 원장).
     *
     * <p>제출이 논블로킹이 되면서 {@code prj_id} 는 ACK 가 도착해야 채워진다. 그 전에도 위탁 사실이
     * durable 해야 하므로(노드 사망 시 회수 근거) 제출 <b>전에</b> 이 메서드로 {@code POLL_STTS=WAITING}
     * 만 세우고 원장을 커밋한다. {@code prj_id} 는 여전히 null 이며, 폴링 잡은 "WAITING + prjId null"
     * 을 <b>ACK 대기</b>로 해석해 진행조회를 호출하지 않는다(시도 카운터 미소모).
     *
     * <p>별도 코드값(예: SUBMITTING)을 새로 만들지 않는 이유: {@code POLL_STTS_CD IN ('WAITING','POLLING')}
     * 리터럴이 네이티브 SQL·잡 상수·엔티티 전이에 흩어져 있어 새 값은 4곳을 동시에 맞춰야 하고 하나라도
     * 빠지면 조용히 샌다. 또 "ACK 왔는가"의 진실원은 {@code prj_id} 유무 자체라, 상태값을 하나 더 두면
     * 파생 정보의 이중 진실원이 된다.
     */
    public void markKpstSubmitPending() {
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

    /**
     * 처리 결과 리포트 집계값 적재 — 완료 전이와 <b>같은 트랜잭션</b>에서 호출된다. [req: R14]
     *
     * <p>{@code summary} 가 {@code null} 이면 <b>no-op</b> 이다. 리포트 조회 실패는 정상 경로이며
     * (완료 흐름을 막지 않는다) 그때 이미 적재된 값을 지우면 앞선 회차 정보가 사라진다.
     *
     * <p>이 메서드는 비식별 완료 전이 필드({@code PROC_STTS_CD}/{@code POLL_STTS_CD}/
     * {@code DE_IDNTF_FILE_PATH_NM})를 <b>건드리지 않는다</b> — 리포트는 부가 정보이지 완료 판정의
     * 근거가 아니다.
     */
    public void recordReport(KpstDeidentReportSummary summary) {
        if (summary == null) {
            return;
        }
        this.faceDtctCnt = summary.faceCount();
        this.noPltDtctCnt = summary.lpCount();
        this.frmeCnt = summary.totalFrame();
        this.prcsBgngDt = summary.startedAt();
        this.prcsEndDt = summary.endedAt();
        this.rptFilePathNm = summary.reportFilePath();
        this.mdfcnDt = LocalDateTime.now();
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
