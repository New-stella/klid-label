package kr.co.cudo.authoring.evntanno.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_EVNT_ANNO_REVIEW: 이벤트 어노테이션(event_annotation) 검토 상태.
 *
 * <p>{@link LsEvntAnno} 는 payload 원문만 저장하고, 본 테이블에서 자동/외부 생성 여부 +
 * 검토 상태(AUTO_GENERATED / PENDING / APPROVED / REJECTED)를 관리한다.
 * {@code kr.co.cudo.authoring.meta.entity.LsDataMetaReview} 와 동일한 검토 상태 머신을 따른다.
 *
 * <ul>
 *   <li>META_TYPE_CD: 메타 유형(VLM 등).</li>
 *   <li>RVW_STTS_CD: 검토 상태. PENDING/AUTO_GENERATED 에서만 승인/반려 전이 가능.</li>
 * </ul>
 */
@Entity
@Table(name = "LS_EVNT_ANNO_REVIEW")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsEvntAnnoReview {

    public static final String STTS_AUTO_GENERATED = "AUTO_GENERATED";
    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_APPROVED = "APPROVED";
    public static final String STTS_REJECTED = "REJECTED";

    public static final String META_TYPE_VLM = "VLM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RVW_SN")
    private Long rvwSn;

    @Column(name = "EVNT_ANNO_SN", nullable = false)
    private Long evntAnnoSn;

    @Column(name = "RVW_STTS_CD", nullable = false, length = 20)
    private String rvwSttsCd;

    @Column(name = "META_TYPE_CD", length = 20)
    private String metaTypeCd;

    @Column(name = "RVW_ID", length = 30)
    private String rvwId;

    @Column(name = "RVW_DT")
    private LocalDateTime rvwDt;

    @Column(name = "RJCT_RSN", length = 4000)
    private String rjctRsn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * 낙관적 잠금 (CWE-362 상태전이 Race Condition 방어).
     * 동시 두 REVIEWER 가 같은 event_annotation 을 승인/반려 시도할 때 1건만 성공 →
     * 다른 1건은 OptimisticLockException 으로 거부되어 last-writer-wins(이중 성공)를 막는다.
     * 형제 {@code LsRawDataStatus} 의 @Version 선례와 동일 패턴.
     */
    @Version
    @Column(name = "VER", nullable = false)
    private Long ver;

    /**
     * 자동/외부 생성 event_annotation 에 대한 검토 row 생성.
     *
     * @param evntAnnoSn 대상 event_annotation ID
     * @param metaTypeCd 메타 유형(VLM 등)
     * @param rvwSttsCd  초기 검토 상태(null 이면 AUTO_GENERATED)
     * @param regId      등록자 ID
     */
    public static LsEvntAnnoReview createAuto(Long evntAnnoSn, String metaTypeCd,
                                              String rvwSttsCd, String regId) {
        if (evntAnnoSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "evntAnnoSn 은 필수입니다.");
        }
        LsEvntAnnoReview review = new LsEvntAnnoReview();
        review.evntAnnoSn = evntAnnoSn;
        review.metaTypeCd = metaTypeCd;
        review.rvwSttsCd = rvwSttsCd == null ? STTS_AUTO_GENERATED : rvwSttsCd;
        review.regId = regId;
        review.regDt = LocalDateTime.now();
        return review;
    }

    /** REVIEWER 가 event_annotation 을 승인. PENDING/AUTO_GENERATED 상태에서만 가능. */
    public void approve(String rvwId) {
        ensureReviewable();
        LocalDateTime now = LocalDateTime.now();
        this.rvwSttsCd = STTS_APPROVED;
        this.rvwId = rvwId;
        this.rvwDt = now;
        this.mdfcnId = rvwId;
        this.mdfcnDt = now;
    }

    /** REVIEWER 가 event_annotation 을 반려. 사유 필수. PENDING/AUTO_GENERATED 상태에서만 가능. */
    public void reject(String rvwId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        ensureReviewable();
        LocalDateTime now = LocalDateTime.now();
        this.rvwSttsCd = STTS_REJECTED;
        this.rjctRsn = sanitize(reason);
        this.rvwId = rvwId;
        this.rvwDt = now;
        this.mdfcnId = rvwId;
        this.mdfcnDt = now;
    }

    /**
     * 반려된 검토를 재제출 대기(PENDING)로 되돌린다. WORKER 가 payload 를 재저장(수정)할 때 호출되어
     * REJECTED 고정으로 인한 재승인 데드엔드를 해소한다(라벨 검수 재제출 REJECTED→PENDING 정책과 정합).
     * REJECTED 가 아닌 상태(AUTO_GENERATED/PENDING/APPROVED)에서는 무시한다 — 승인 완료본은 유지.
     */
    public void resubmit() {
        if (STTS_REJECTED.equals(rvwSttsCd)) {
            this.rvwSttsCd = STTS_PENDING;
            this.mdfcnDt = LocalDateTime.now();
        }
    }

    private void ensureReviewable() {
        if (STTS_APPROVED.equals(rvwSttsCd) || STTS_REJECTED.equals(rvwSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 검토 완료된 event_annotation 입니다. status=" + rvwSttsCd);
        }
    }

    /** Log/저장 안전 — 개행/탭/캐리지리턴 제어문자 제거. */
    private static String sanitize(String raw) {
        return raw.replaceAll("[\\r\\n\\t]", " ");
    }
}
