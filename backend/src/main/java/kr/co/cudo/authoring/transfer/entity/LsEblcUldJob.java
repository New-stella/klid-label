package kr.co.cudo.authoring.transfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 일괄업로드작업 ({@code LS_EBLC_ULD_JOB}) — 마킹이 끝난 영상 묶음을 폴더째 받아 올리는 작업 한 건.
 *
 * <h3>왜 원장이 필요한가</h3>
 * <p>적재 요청은 <b>곧바로 반환</b>하고 실제 적재는 뒤에서 항목별로 진행한다(API-217). 그러면 끝나는
 * 시점을 응답으로 알 수 없으므로 「어디까지 되었는지와 무엇이 왜 실패했는지」를 담아 둘 자리가 있어야
 * 한다. 그 자리를 메모리에 두면 노드가 다시 뜨는 순간 통째로 사라진다 — 그래서 행으로 남긴다.
 *
 * <h3>대상 건수는 만들 때 정해지고 바뀌지 않는다</h3>
 * <p>{@link #trgtNocs} 는 진행률의 <b>분모</b>다. 도중에 바꾸면 사람이 보고 있던 진행률이 뒤로 가거나
 * 100%를 넘는다. 성공·실패 건수만 앞으로 움직인다.
 *
 * <h3>사람이 지정한 값을 여기 담는다</h3>
 * <p>{@link #dmndCn} 에 이벤트 유형·지자체 코드·카메라 식별자·개인정보 유형·촬영일시를 담는다.
 * 이 값들은 항목마다 같은 값으로 붙는데, 처리가 <b>요청 이후</b>에 일어나므로 요청 본문이 사라진
 * 뒤에도 읽을 수 있어야 한다. 특히 노드가 다시 떠서 남은 항목을 이어 처리할 때는 이 행이 그 값의
 * <b>유일한 원천</b>이다.
 *
 * @design DOMAIN-017
 * @design ERD-032
 * @design ADR-053
 * @design API-217
 * @design API-218
 */
@Entity
@Table(name = "LS_EBLC_ULD_JOB")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsEblcUldJob {

    /** 작업상태 — 진행중. */
    public static final String JOB_STTS_RUNNING = "RUNNING";

    /** 작업상태 — 완료(모든 항목이 끝나고 실패가 없다). */
    public static final String JOB_STTS_COMPLETED = "COMPLETED";

    /** 작업상태 — 실패(모든 항목이 끝났으나 실패가 남았다). */
    public static final String JOB_STTS_FAILED = "FAILED";

    /** 작업상태 — 취소(사람이 중단시켰다). */
    public static final String JOB_STTS_CANCELED = "CANCELED";

    /** {@code ORGNL_FLDR_PATH_NM} 컬럼 폭 — 표준 폴더경로명 도메인 명V300. */
    public static final int FLDR_PATH_NM_MAX = 300;

    /** {@code REG_ID} 컬럼 폭 — 표준 아이디 도메인. */
    public static final int REG_ID_MAX = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EBLC_ULD_JOB_SN")
    private Long eblcUldJobSn;

    /** 원본폴더경로명 — 훑은 폴더의 위치. 허용된 저장소 범위 밖이면 입구에서 거부한다. */
    @Column(name = "ORGNL_FLDR_PATH_NM", nullable = false, length = FLDR_PATH_NM_MAX)
    private String orgnlFldrPathNm;

    @Column(name = "JOB_STTS_CD", nullable = false, length = 20)
    private String jobSttsCd;

    /** 대상건수 — 진행률의 분모. 만들 때 정해지고 바뀌지 않는다. */
    @Column(name = "TRGT_NOCS", nullable = false)
    private Integer trgtNocs;

    /** 성공건수 — 적재에 성공한 항목 수. */
    @Column(name = "SCS_NOCS", nullable = false)
    private Integer scsNocs;

    /** 실패건수 — 실패했거나 건너뛴 항목 수. */
    @Column(name = "FAIL_NOCS", nullable = false)
    private Integer failNocs;

    /**
     * 요구내용 — 마킹 문서에서 얻을 수 없어 사람이 지정한 값.
     *
     * <p>⚠ 여기에 개인정보나 인증 정보를 담지 않는다. 담기는 것은 코드값과 카메라 식별자·촬영일시뿐이다.
     */
    @Column(name = "DMND_CN", columnDefinition = "TEXT")
    private String dmndCn;

    /** 시작일시 — 첫 항목의 처리를 시작한 시각. 아직 시작하지 않았으면 비어 있다. */
    @Column(name = "BGNG_DT")
    private LocalDateTime bgngDt;

    /** 완료일시 — 마지막 항목이 끝나 작업이 종결된 시각. */
    @Column(name = "CMPTN_DT")
    private LocalDateTime cmptnDt;

    /** 등록아이디 — 이 작업을 실행한 사람. */
    @Column(name = "REG_ID", length = REG_ID_MAX)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsEblcUldJob(String orgnlFldrPathNm, int trgtNocs, String dmndCn, String regId) {
        this.orgnlFldrPathNm = requireWithin(orgnlFldrPathNm, FLDR_PATH_NM_MAX, "원본폴더경로명");
        this.jobSttsCd = JOB_STTS_RUNNING;
        this.trgtNocs = trgtNocs;
        this.scsNocs = 0;
        this.failNocs = 0;
        this.dmndCn = dmndCn;
        this.regId = truncate(regId, REG_ID_MAX);
        this.regDt = LocalDateTime.now();
        this.mdfcnDt = this.regDt;
    }

    /**
     * 작업을 연다 — 항목을 넣기 전에 이 행이 먼저 있어야 항목이 어디에 속하는지 정해진다.
     *
     * @param trgtNocs 다루기로 한 항목 수 — 진행률의 분모
     * @param dmndCn   사람이 지정한 값(직렬화된 문자열)
     */
    public static LsEblcUldJob open(String orgnlFldrPathNm, int trgtNocs, String dmndCn, String regId) {
        return new LsEblcUldJob(orgnlFldrPathNm, trgtNocs, dmndCn, regId);
    }

    /** 이 작업이 이미 끝났는가(어느 종결 상태든). */
    public boolean isFinished() {
        return !JOB_STTS_RUNNING.equals(this.jobSttsCd);
    }

    private static String requireWithin(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "는 필수입니다.");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(label + "가 허용 길이를 넘습니다.");
        }
        return value;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
