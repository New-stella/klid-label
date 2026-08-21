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
 * 외부데이터셋이관이력 ({@code LS_OTSD_DATST_TRNSF_HSTRY}) — 언제 누가 어떤 산출물 폴더를 가져와
 * 몇 건이 들어왔는지의 <b>감사 기록</b>.
 *
 * <h3>중복 반입 거부의 주체는 이 표가 아니다</h3>
 * <p>같은 산출물을 두 번 가져오는 것을 실제로 막는 것은 {@code LS_DATA_RAW.VMS_CLIP_ID} 의 유일
 * 제약({@code UK_LS_DATA_RAW_VMS_CLIP})이다. 이 표는 <b>사람이 경위를 되짚는 기록</b>이고, 동시에
 * 두 번 들어오는 것까지 막는 것은 그 제약이다. 이 표의 조회 결과로 사전 판정만 하고 DB 제약을
 * 두지 않으면 두 노드가 같은 순간에 통과한다(check-then-act).
 *
 * <h3>영상이 지워져도 이력은 남는다</h3>
 * <p>{@code RAW_SN} FK 는 {@code ON DELETE SET NULL} 이다. {@code CASCADE} 로 지우면 무엇을
 * 가져왔는지의 기록이 함께 사라진다.
 *
 * <h3>실패도 기록이다</h3>
 * <p>{@link #start} 로 {@code PROCESSING} 행을 먼저 남기고 결과에 따라 {@link #succeed}/{@link #fail}
 * 로 마감한다. 성공했을 때만 행을 만들면 <b>실패한 이관은 아무 흔적도 남지 않아</b> 왜 안 들어왔는지
 * 되짚을 수 없다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design ADR-048
 */
@Entity
@Table(name = "LS_OTSD_DATST_TRNSF_HSTRY")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsOtsdDatstTrnsfHstry {

    /** 이관상태 — 진행중. */
    public static final String TRNSF_STTS_PROCESSING = "PROCESSING";

    /** 이관상태 — 성공. */
    public static final String TRNSF_STTS_SUCCESS = "SUCCESS";

    /** 이관상태 — 실패. */
    public static final String TRNSF_STTS_FAILED = "FAILED";

    /** {@code ORGNL_FLDR_PATH_NM}·{@code ORGNL_FLDR_NM} 컬럼 폭(V14) — 표준 폴더경로명 도메인 명V300. */
    public static final int FLDR_PATH_NM_MAX = 300;

    /** {@code OTSD_DATST_ID} 컬럼 폭(V14). */
    public static final int OTSD_DATST_ID_MAX = 50;

    /** {@code FAIL_RSN} 컬럼 폭(V14) — 넘는 사유는 잘라 담는다(적재 실패로 기록 자체를 잃지 않게). */
    public static final int FAIL_RSN_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TRNSF_SN")
    private Long trnsfSn;

    /** 원본폴더경로명 — 가져온 산출물 폴더의 위치. 허용된 저장소 범위 밖이면 입구에서 거부한다. */
    @Column(name = "ORGNL_FLDR_PATH_NM", nullable = false, length = FLDR_PATH_NM_MAX)
    private String orgnlFldrPathNm;

    /** 원본폴더명 — 폴더 식별자. 외부데이터셋아이디와 함께 같은 산출물인지 판정하는 축이다. */
    @Column(name = "ORGNL_FLDR_NM", length = FLDR_PATH_NM_MAX)
    private String orgnlFldrNm;

    /** 외부데이터셋아이디 — 산출물 문서가 밝힌 데이터셋 식별자 원문. */
    @Column(name = "OTSD_DATST_ID", length = OTSD_DATST_ID_MAX)
    private String otsdDatstId;

    /** 원시영상일련번호 — 이 이관으로 만들어진 영상. 적재 전·실패·영상 삭제 시 {@code null}. */
    @Column(name = "RAW_SN")
    private Long rawSn;

    @Column(name = "TRNSF_STTS_CD", nullable = false, length = 20)
    private String trnsfSttsCd;

    /** 프레임건수 — <b>실제로 적재된</b> 프레임 수. 문서가 선언한 수가 아니다. */
    @Column(name = "FRME_CNT")
    private Integer frmeCnt;

    /** 라벨건수 — 실제로 적재된 라벨 수. */
    @Column(name = "LBL_CNT")
    private Integer lblCnt;

    @Column(name = "FAIL_RSN", length = FAIL_RSN_MAX)
    private String failRsn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    private LsOtsdDatstTrnsfHstry(String orgnlFldrPathNm, String orgnlFldrNm, String otsdDatstId,
                                  String regId) {
        this.orgnlFldrPathNm = requireWithin(orgnlFldrPathNm, FLDR_PATH_NM_MAX, "원본폴더경로명");
        this.orgnlFldrNm = truncate(orgnlFldrNm, FLDR_PATH_NM_MAX);
        this.otsdDatstId = truncate(otsdDatstId, OTSD_DATST_ID_MAX);
        this.trnsfSttsCd = TRNSF_STTS_PROCESSING;
        this.regId = regId;
        this.regDt = LocalDateTime.now();
    }

    /** 이관 시작 — {@code PROCESSING} 행을 먼저 남긴다(실패해도 흔적이 남게). */
    public static LsOtsdDatstTrnsfHstry start(String orgnlFldrPathNm, String orgnlFldrNm,
                                              String otsdDatstId, String regId) {
        return new LsOtsdDatstTrnsfHstry(orgnlFldrPathNm, orgnlFldrNm, otsdDatstId, regId);
    }

    /**
     * 이관 성공 — 만들어진 영상과 <b>실제 적재 건수</b>를 기록한다.
     *
     * @param frmeCnt 실제 적재된 프레임 수(문서 선언값이 아니다 — AC-047)
     * @param lblCnt  실제 적재된 라벨 수
     */
    public void succeed(Long rawSn, int frmeCnt, int lblCnt) {
        this.rawSn = rawSn;
        this.frmeCnt = frmeCnt;
        this.lblCnt = lblCnt;
        this.trnsfSttsCd = TRNSF_STTS_SUCCESS;
        this.failRsn = null;
    }

    /**
     * 이관 실패 — 사유를 남긴다. 사유는 컬럼 폭에 맞춰 잘라 담는다(길이 초과로 <b>기록 자체가
     * 사라지는</b> 것이 사유가 잘리는 것보다 나쁘다).
     */
    public void fail(String reason) {
        this.trnsfSttsCd = TRNSF_STTS_FAILED;
        this.failRsn = truncate(reason, FAIL_RSN_MAX);
    }

    /** 이 이관이 끝났는가(성공이든 실패든). */
    public boolean isFinished() {
        return TRNSF_STTS_SUCCESS.equals(this.trnsfSttsCd) || TRNSF_STTS_FAILED.equals(this.trnsfSttsCd);
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
