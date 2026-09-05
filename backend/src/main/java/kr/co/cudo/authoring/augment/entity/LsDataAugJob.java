package kr.co.cudo.authoring.augment.entity;

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
 * 증강 외부 위탁 작업 (LS_DATA_AUG_JOB, V140) — Phase 7-A1.
 *
 * <p>「생성형 AI API 연동명세서 v1.3」의 {@code POST /api/genai/jobs} 위탁 1건을 나타낸다.
 * {@link LsDataAug} 1행(증강 결과 1건)은 {@code input_files} 상한(100장) 때문에 여러 job 으로
 * 분할 위탁될 수 있으므로 1:N 이다.
 *
 * <h3>job_id 발급 주체</h3>
 * <p>본 도구는 {@code request_id}(= {@link #idempotencyKey}, Idempotency-Key 헤더)만 발급하고,
 * {@code job_id}({@link #externalJobId})는 <b>외부가 202 응답으로 발급</b>한다. 따라서 위탁 전
 * 선기록 시점에는 {@code externalJobId} 가 null 이며 202 수신 후 채워진다.
 *
 * <h3>상태 코드 공간</h3>
 * <p>{@link #jobSttsCd} 는 명세서 §3.2 상태머신(RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED)이며,
 * {@link LsDataAug#getAugProcSttsCd()}(PENDING/ACCEPTED/REJECTED = 검수 결과 축)와 <b>다른 코드
 * 공간</b>이다. 두 축을 섞지 않는다.
 */
@Entity
@Table(name = "LS_DATA_AUG_JOB")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAugJob {

    /** 위탁 선기록 / 외부 접수 완료. */
    public static final String STTS_RECEIVED  = "RECEIVED";
    /** 외부 처리중(웹훅 수신 시 전이 — A2 스코프). */
    public static final String STTS_RUNNING   = "RUNNING";
    /** 외부 처리 성공(웹훅 수신 시 전이 — A2 스코프). */
    public static final String STTS_SUCCEEDED = "SUCCEEDED";
    /** 위탁 실패 또는 외부 처리 실패. */
    public static final String STTS_FAILED    = "FAILED";
    /** 취소됨. */
    public static final String STTS_CANCELED  = "CANCELED";

    /** 비식별 프레임 경로가 없어 위탁 자체를 거부한 경우의 내부 오류 코드(fail-closed). */
    public static final String ERR_DEID_PATH_MISSING = "DEID_PATH_MISSING";
    /** 외부 위탁 호출 실패(4xx/5xx/네트워크)의 내부 오류 코드. */
    public static final String ERR_SUBMIT_FAILED = "SUBMIT_FAILED";
    /**
     * 위탁 <b>도중</b> 비식별 누락 신고({@code DE_IDNTF_YN='F'})가 관측돼 남은 청크를 중단한 경우의
     * 내부 오류 코드(DEV_FIX HIGH-2).
     *
     * <p>부분 프레임셋으로 증강본이 확정되는 것을 막기 위해 terminal 로 남긴다(부분 실패 = 전체 실패).
     */
    public static final String ERR_DEIDENT_REPORT = "DEIDENT_REPORT";
    /**
     * 위탁 <b>전</b> 비식별 누락 신고({@code DE_IDNTF_YN='F'}) 구간이라 한 건도 위탁하지 않고
     * 거부한 경우의 내부 오류 코드 (2026-07-29 정책).
     *
     * <p>구 정책은 이 경우를 <b>보류</b>로 두고 신고 해소 시 재개했으나, 파생 생성이 원본 신고와
     * 무관해지면서 재개 배선 자체가 철회됐다. 재개 트리거가 없는 보류는 PENDING 영구 고착이므로
     * <b>거부(실패 확정)</b> 로 종결하고, 해소 후 재요청이 정상 동선이다.
     */
    public static final String ERR_DEID_REPORT_OPEN = "DEID_REPORT_OPEN";
    /**
     * SUCCEEDED 콜백의 {@code results} 건수가 위탁 {@code input_files} 건수와 다를 때의 내부 오류 코드.
     * 순서로만 입력↔결과를 대응시키는 계약이라 건수가 어긋나면 어느 프레임에 무엇을 붙일지 알 수 없다
     * → 성공으로 접수하지 않고 실패로 종결한다(fail-closed).
     */
    public static final String ERR_RESULT_COUNT_MISMATCH = "RESULT_COUNT_MISMATCH";
    /**
     * 청크 선기록({@code recordIssued}) 실패로 <b>전 청크 위탁을 중단</b>한 경우의 내부 오류 코드
     * (DEV_FIX 2차 MEDIUM-2).
     *
     * <p>선기록은 위탁 <b>전에</b> 전량 수행되므로 이 코드가 붙은 시점에 외부로 나간 청크는 없다.
     * 이미 선기록된 앞 청크에 이 코드를 남기지 않으면 그 행이 비종결로 떠 있어 롤업이 영원히
     * 보류되거나(무한 대기), 반대로 그 행이 아예 없으면 롤업이 <b>부분 프레임셋을 전량으로 오인</b>해
     * 증강을 성공 확정한다.
     */
    public static final String ERR_ISSUE_RECORD_FAILED = "ISSUE_RECORD_FAILED";
    /**
     * <b>만료 종결</b> — 임계 시간 동안 아무 갱신(웹훅)도 없어 스윕이 회수한 경우의 내부 오류 코드
     * (Phase 8-A).
     *
     * <p>비종결로 남는 실제 경로는 셋이다: ①취소({@code CANCELED})는 계약상 진행·결과 웹훅
     * 이벤트가 아니라 우리에게 통보되지 않는다 ②콜백 검증 실패(400)는 재전송 여지를 남기려고
     * 상태를 바꾸지 않는데 외부가 재시도를 포기할 수 있다 ③외부 무응답. 어느 경우든 job 이 비종결로
     * 남으면 롤업이 무기한 보류돼 증강 1건이 PENDING 에 고착된다.
     *
     * <p><b>만료는 성공이 아니다</b> — 이 코드는 {@code FAILED} 로만 붙으므로 롤업의 "1건이라도
     * 실패 = 전체 실패" 규칙에 따라 증강이 성공으로 둔갑하지 않는다(fail-closed).
     */
    public static final String ERR_EXPIRED = "EXPIRED";

    /**
     * <b>사용자 취소 종결</b>의 사유 코드 — 오류가 아니라 <b>종결 사유</b>다({@link #ERR_EXPIRED} 와 동일 성격).
     *
     * <p>{@code ERR_CD} 를 종결 사유 칸으로 겸용하는 것은 이 테이블의 기존 관행이며, 취소를 별도
     * 컬럼으로 표현하려면 스키마 추가가 필요한데 판정 축({@code JOB_STTS_CD=CANCELED})이 이미 있으므로
     * 사유 문자열만 남긴다.
     */
    public static final String ERR_CANCELED = "CANCELED";

    /** 오류 메시지 컬럼 길이(내용V1000) — 초과분은 절단 저장한다. */
    private static final int ERR_MSG_MAX = 1000;

    /**
     * 오류 코드 컬럼 길이(코드V50) — 초과분은 절단 저장한다 (DEV_FIX MED-3 안전망).
     *
     * <p>계약 검증은 <b>입구</b>(웹훅 DTO {@code @Size(max=50)} / 조회 {@code validateStatus})가 담당하고
     * 여기는 마지막 방어선이다. 이 절단이 없으면 어느 경로 하나라도 검증을 빠뜨렸을 때
     * PostgreSQL {@code 22001}(value too long)로 <b>트랜잭션 전체가 롤백</b>되어, 삼켜진 예외 뒤에서
     * 그 job 이 영구히 종결되지 못한다(고착). 값이 잘리는 것보다 훨씬 나쁜 결과다.
     */
    private static final int ERR_CD_MAX = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUG_JOB_SN")
    private Long augJobSn;

    @Column(name = "DATA_AUG_SN", nullable = false)
    private Long dataAugSn;

    @Column(name = "JOB_SEQ", nullable = false)
    private Integer jobSeq;

    /** 저작도구가 발급한 request_id(= Idempotency-Key). 외부 job_id 가 아니다. */
    @Column(name = "IDMP_KEY", nullable = false, length = 128)
    private String idempotencyKey;

    /** 외부가 202 응답으로 발급한 job_id. 위탁 전/실패 시 null. */
    @Column(name = "OTSD_JOB_ID", length = 200)
    private String externalJobId;

    @Column(name = "JOB_STTS_CD", nullable = false, length = 20)
    private String jobSttsCd;

    /** 이 job 으로 위탁한 입력 파일 건수(최대 100). 거부 기록은 0. */
    @Column(name = "TOT_NOCS", nullable = false)
    private Integer totalCount;

    @Column(name = "ERR_CD", length = 50)
    private String errorCode;

    @Column(name = "ERR_MSG_CN", length = ERR_MSG_MAX)
    private String errorMessage;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    private LsDataAugJob(Long dataAugSn, int jobSeq, String idempotencyKey,
                         String jobSttsCd, int totalCount) {
        LocalDateTime now = LocalDateTime.now();
        this.dataAugSn = dataAugSn;
        this.jobSeq = jobSeq;
        this.idempotencyKey = idempotencyKey;
        this.jobSttsCd = jobSttsCd;
        this.totalCount = totalCount;
        this.regDt = now;
        this.mdfcnDt = now;
    }

    /**
     * 위탁 <b>직전</b> 선기록 — 멱등키를 DB 에 확보한 뒤 외부 호출한다.
     *
     * <p>선기록을 먼저 하는 이유: 외부 호출 도중 프로세스가 죽어도 "보냈을 수도 있는 요청"이
     * 흔적 없이 사라지지 않게 하기 위함이다(요청 유실 방지).
     */
    public static LsDataAugJob createIssued(Long dataAugSn, int jobSeq,
                                            String idempotencyKey, int totalCount) {
        return new LsDataAugJob(dataAugSn, jobSeq, idempotencyKey, STTS_RECEIVED, totalCount);
    }

    /**
     * 위탁 자체를 하지 않고 사유와 함께 실패로 남기는 기록(fail-closed 거부 등).
     * 외부로 아무것도 보내지 않았음을 {@code totalCount=0} 으로 표현한다.
     */
    public static LsDataAugJob createRejected(Long dataAugSn, int jobSeq, String idempotencyKey,
                                              String errorCode, String errorMessage) {
        LsDataAugJob job = new LsDataAugJob(dataAugSn, jobSeq, idempotencyKey, STTS_FAILED, 0);
        job.errorCode = truncateCode(errorCode);
        job.errorMessage = truncate(errorMessage);
        return job;
    }

    /** 외부 202 수락 — 외부가 발급한 job_id 를 적재한다. */
    public void markAccepted(String externalJobId) {
        this.externalJobId = externalJobId;
        this.jobSttsCd = STTS_RECEIVED;
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 위탁 실패 — 사유를 남긴다(조용한 유실 금지). */
    public void markFailed(String errorCode, String errorMessage) {
        this.jobSttsCd = STTS_FAILED;
        this.errorCode = truncateCode(errorCode);
        this.errorMessage = truncate(errorMessage);
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 종결 상태인가 — 롤업(증강 1건 확정) 판정의 기준.
     *
     * <p>{@code RECEIVED}(접수) / {@code RUNNING}(처리중)은 아직 결과가 정해지지 않았으므로
     * 하나라도 남아 있으면 증강 1건을 확정하지 않는다.
     */
    public boolean isTerminal() {
        return STTS_SUCCEEDED.equals(jobSttsCd)
                || STTS_FAILED.equals(jobSttsCd)
                || STTS_CANCELED.equals(jobSttsCd);
    }

    /** 웹훅 {@code RUNNING} 수신 — 진행 상태만 갱신한다(결과 처리 없음). */
    public void markRunning(String externalJobId) {
        applyExternalJobId(externalJobId);
        this.jobSttsCd = STTS_RUNNING;
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 웹훅 {@code SUCCEEDED} 수신 — 이 job 의 산출이 완료됐다. */
    public void markSucceeded(String externalJobId) {
        applyExternalJobId(externalJobId);
        this.jobSttsCd = STTS_SUCCEEDED;
        this.errorCode = null;
        this.errorMessage = null;
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 웹훅 {@code FAILED} 수신 — 외부가 준 사유를 그대로 남긴다. */
    public void markFailed(String externalJobId, String errorCode, String errorMessage) {
        applyExternalJobId(externalJobId);
        markFailed(errorCode, errorMessage);
    }

    /**
     * <b>취소 종결</b> — 외부 §4.4 취소가 성립했거나(사용자 취소) 조회로 외부 CANCELED 를 회수했을 때.
     *
     * <h3>호출 규약 — 증강 행 {@code FOR UPDATE} 안에서 호출한다</h3>
     * <p>콜백 경로도 같은 행을 잠그므로, 잠금 안에서 전이해야 "우리가 CANCELED 로 쓰는 사이 웹훅이
     * SUCCEEDED 로 덮어쓰는" 경합이 <b>그 트랜잭션 구간에서만큼은</b> 직렬화된다.
     *
     * <p>이미 종결된 job 에는 호출하지 않는다(호출자가 {@link #isTerminal()} 로 거른다) — 성공한
     * 청크를 취소로 덮으면 산출물이 있는데 없는 것처럼 보인다.
     *
     * <h3>⚠ 잠금만으로 (aug=CANCELED, job=SUCCEEDED) 가 <b>막히지는 않는다</b> (DEV_FIX MED-6a 정정)</h3>
     * <p>구 주석은 "이 순서만 지키면 경합이 직렬화된다" 고 단언했으나 사실이 아니다. 사용자 취소는
     * <b>두 트랜잭션</b>으로 나뉘고({@code AugmentCancelTxService#claim} → 외부 HTTP 왕복 →
     * {@code #markJobsCanceled}) 그 사이 창에 SUCCEEDED 웹훅이 커밋되면, 확정 단계는 그 job 을
     * <b>의도적으로 건너뛴다</b>(위 문단 — 성공을 취소로 덮지 않는다). 즉 그 조합은 남을 수 있다.
     *
     * <p>남아도 <b>결과가 인계되지는 않는다</b>: 증강 행이 이미 non-PENDING 이라
     * {@code AugmentResultService} 의 앵커가 그 결과를 폐기하고(파생영상 0건·롤업 없음), 화면도
     * {@code AugmentProgressCalculator} 가 증강 상태(CANCELED)를 최우선으로 읽는다. 잔존 영향은
     * <b>관측상의 불일치</b>(job 행이 SUCCEEDED 로 보임)뿐이며, 이를 없애려고 성공 청크를 CANCELED 로
     * 덮으면 "산출물이 있는데 없는 것처럼" 보이는 더 나쁜 왜곡이 된다.
     */
    public void markCanceled(String externalJobId, String reason) {
        applyExternalJobId(externalJobId);
        this.jobSttsCd = STTS_CANCELED;
        this.errorCode = ERR_CANCELED;
        this.errorMessage = truncate(reason);
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 외부 job_id 보정 — 202 응답을 놓친 경우(선기록 직후 프로세스 종료 등) 웹훅이 처음 알려준다.
     * 이미 값이 있으면 덮어쓰지 않는다(오배송 판정은 호출자가 수행).
     */
    private void applyExternalJobId(String externalJobId) {
        if (this.externalJobId == null && externalJobId != null && !externalJobId.isBlank()) {
            this.externalJobId = externalJobId;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERR_MSG_MAX ? value : value.substring(0, ERR_MSG_MAX);
    }

    /** {@link #ERR_CD_MAX} 절단 — 적재 시점 {@code 22001} 로 트랜잭션이 죽는 것을 막는 마지막 방어선. */
    private static String truncateCode(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERR_CD_MAX ? value : value.substring(0, ERR_CD_MAX);
    }
}
