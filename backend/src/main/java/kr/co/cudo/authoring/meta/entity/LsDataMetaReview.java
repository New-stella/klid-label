package kr.co.cudo.authoring.meta.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_DATA_META_REVIEW: 외부/자동 생성 메타데이터 검토 상태.
 *  - LS_DATA_META 는 값 (K/V) 만 저장하고, 본 테이블에서 외부 생성 여부 + 검토 상태 (PENDING/APPROVED/REJECTED) 를 관리한다.
 *  - META_TYPE_CD: 메타 유형 (VLM / EXTERNAL).
 *  - SRC_SYS_CD: 생성 시스템 (AI_SERVER / CONTROL_SERVER / PORTAL).
 *  - RVW_STTS_CD: 검토 상태 (AUTO_GENERATED / PENDING / APPROVED / REJECTED).
 */
@Entity
@Table(name = "LS_DATA_META_REVIEW")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMetaReview {

    public static final String STTS_AUTO_GENERATED = "AUTO_GENERATED";
    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_APPROVED = "APPROVED";
    public static final String STTS_REJECTED = "REJECTED";

    public static final String META_TYPE_VLM = "VLM";
    public static final String META_TYPE_EXTERNAL = "EXTERNAL";

    public static final String SRC_AI_SERVER = "AI_SERVER";
    public static final String SRC_CONTROL_SERVER = "CONTROL_SERVER";
    public static final String SRC_PORTAL = "PORTAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_META_REVIEW_SN")
    private Long dataMetaReviewSn;

    @Column(name = "DATA_META_SN", nullable = false)
    private Long dataMetaSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    @Column(name = "META_TYPE_CD", nullable = false, length = 20)
    private String metaTypeCd;

    @Column(name = "SRC_SYS_CD", length = 20)
    private String srcSysCd;

    @Column(name = "RVW_STTS_CD", nullable = false, length = 20)
    private String rvwSttsCd;

    @Column(name = "RVW_ID", length = 30)
    private String rvwId;

    @Column(name = "RVW_DT")
    private LocalDateTime rvwDt;

    @Column(name = "RJCT_RSN", length = 4000)
    private String rejectRsn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * 자동 생성/외부 송신 메타에 대한 검토 row 생성.
     * - VLM 자동 생성 시 RVW_STTS_CD='AUTO_GENERATED' 또는 'PENDING'.
     * - 외부 시스템 송신 메타는 'PENDING'.
     */
    public static LsDataMetaReview createAuto(Long dataMetaSn, Long dataRawSn, Long dataSrcSn,
                                              String metaTypeCd, String srcSysCd, String rvwSttsCd) {
        LsDataMetaReview review = new LsDataMetaReview();
        review.dataMetaSn = dataMetaSn;
        review.dataRawSn = dataRawSn == null ? 0L : dataRawSn;
        review.dataSrcSn = dataSrcSn;
        review.metaTypeCd = metaTypeCd;
        review.srcSysCd = srcSysCd;
        review.rvwSttsCd = rvwSttsCd == null ? STTS_AUTO_GENERATED : rvwSttsCd;
        review.regDt = LocalDateTime.now();
        return review;
    }

    /** REVIEWER 가 메타를 승인. PENDING/AUTO_GENERATED 상태에서만 가능. */
    public void approve(String reviewerId, LocalDateTime at) {
        ensureReviewable();
        this.rvwSttsCd = STTS_APPROVED;
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    /** REVIEWER 가 메타를 반려. 사유 필수. */
    public void reject(String reason, String reviewerId, LocalDateTime at) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        ensureReviewable();
        this.rvwSttsCd = STTS_REJECTED;
        this.rejectRsn = sanitize(reason);
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    /**
     * 외부 재위탁으로 메타 <b>본문이 실제로 갱신</b>됐을 때 확정된 검토를 되돌려 재승인을 강제한다. [req: R13]
     *
     * <p>필요한 이유: 데이터마트 뷰 {@code V_COMPLETED_META} 는 <b>라이브</b> {@code LS_DATA_META} 를 조인하고
     * 노출 게이트는 이 테이블의 {@code RVW_STTS_CD='APPROVED'} 뿐이다. 값만 갱신하고 상태를 APPROVED 로 두면
     * REVIEWER 가 한 번도 보지 않은 새 서술이 그대로 관제로 나간다.
     *
     * <p>이미 {@code PENDING} 이면 아무것도 하지 않는다(멱등 — 재검수·통지 폭주 방지). {@code REJECTED} 도
     * 되돌린다: 반려 판단은 <b>바뀌기 전 본문</b>에 대한 것이라 새 본문에는 적용되지 않는다
     * ({@code autoApproveOnVideoApproval} 의 "반려 존중"은 본문이 그대로일 때의 규칙이다).
     *
     * <p>확정 흔적({@code RVW_ID}/{@code RVW_DT}/{@code RJCT_RSN})은 지운다 — 남겨 두면 "누가 언제 승인했다"가
     * 지금 상태(PENDING)와 모순된다. 수정자 식별자는 사람이 아니라 외부 콜백이므로 채우지 않는다.
     *
     * @return 실제로 되돌렸으면 true, 이미 PENDING 이라 no-op 이면 false
     */
    public boolean reopenForRecheck() {
        if (STTS_PENDING.equals(rvwSttsCd)) {
            return false;
        }
        this.rvwSttsCd = STTS_PENDING;
        this.rvwId = null;
        this.rvwDt = null;
        this.rejectRsn = null;
        this.mdfcnId = null;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    private void ensureReviewable() {
        if (STTS_APPROVED.equals(rvwSttsCd) || STTS_REJECTED.equals(rvwSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 검토 완료된 메타입니다. status=" + rvwSttsCd);
        }
    }

    /** Log Injection / 제어문자 sanitize — 개행/탭/캐리지리턴 제거. */
    private static String sanitize(String raw) {
        return raw.replaceAll("[\\r\\n\\t]", " ");
    }
}
