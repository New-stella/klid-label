package kr.co.cudo.authoring.augment.entity;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

@Entity
@Table(name = "LS_DATA_AUG_RVW")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAugRvw {

    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_ACCEPTED = "ACCEPTED";
    public static final String STTS_REJECTED = "REJECTED";

    /**
     * ★ <b>"최신 검수 행" 의 단일 정의</b> — 오름차순 비교자(가장 <b>큰</b> 원소가 최신).
     *
     * <h3>왜 정의를 여기 한 곳에 두는가 (DEV_FIX MEDIUM ①)</h3>
     * <p>정의가 <b>둘</b> 있었다. 복구 경로({@code AugmentDiscardService})는 리포지토리의
     * {@code REG_DT DESC} 첫 행을, 결과 조회 경로({@code AugmentResultViewService})는 메모리에서
     * {@code RVW_DT} 비교로 고른 행을 봤다. 이 테이블에는 {@code DATA_AUG_SN} 유니크가 <b>없고</b>
     * 중복 행 정리 마이그레이션도 <b>금지</b>돼 있어(백필 금지 정책 · {@code AugmentReviewService}
     * 의 행 잠금 javadoc 참조) 한 증강에 검수 행이 2건 이상 공존할 수 있다. 그때 두 선택자가
     * <b>서로 다른 행</b>을 골라, 화면은 "반려됨 → 복구 가능" 을 그리고 복구 API 는 다른 행을 보고
     * 404 를 냈다 — 재조회해도 같은 값이라 무한 재시도다.
     *
     * <h3>정본을 {@code REG_DT} 로 맞춘 이유</h3>
     * <p>화면은 <b>BE 가 실제로 행동할 대상</b>을 보여줘야 한다. 다른 행에서 파생한 {@code decision}
     * 을 보여주는 것 자체가 오도이므로, 복구·결정 경로가 쓰던 {@code REG_DT} 축을 정본으로 삼고
     * 조회를 거기에 맞춘다.
     *
     * <p><b>{@code REG_DT} 동률의 tie-break 는 PK</b>다. {@code REG_DT} 는 밀리초 단위라 같은 증강에
     * 동시 INSERT 된 두 행이 같은 값을 가질 수 있는데, tie-break 가 없으면 DB 정렬(비결정)과
     * 메모리 정렬(입력 순서)이 갈려 <b>정의를 합쳐놓고도 다시 어긋난다</b>.
     *
     * <p><b>null 안전</b>: {@code REG_DT} 는 DB {@code NOT NULL} 이라 정상 형상에 null 이 없지만,
     * 구 {@code isNewer} 가 갖고 있던 null 내성을 잃지 않도록 {@code nullsFirst} 로 감싼다
     * (null = "가장 오래된 것" 으로 취급 — 값이 있는 행이 항상 이긴다).
     */
    public static final Comparator<LsDataAugRvw> RECENCY_ORDER = Comparator
            .comparing(LsDataAugRvw::getRegDt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(LsDataAugRvw::getDataAugRvwSn,
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * 같은 증강의 검수 행들 중 <b>최신 1행</b> — {@link #RECENCY_ORDER} 정의 그대로.
     *
     * <p>DB 정렬에 기대지 않고 이 비교자로만 고른다. 그래야 "리포지토리 단건 조회" 와 "배치 조회 후
     * 메모리 집계" 두 경로가 <b>같은 규칙</b>을 공유한다(규칙을 SQL 과 자바에 각각 쓰면 그 둘이
     * 드리프트하는 것이 이번 결함의 원인이었다).
     */
    public static Optional<LsDataAugRvw> latestOf(Collection<LsDataAugRvw> rows) {
        return rows.stream().max(RECENCY_ORDER);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_RVW_SN")
    private Long dataAugRvwSn;

    @Column(name = "DATA_AUG_SN", nullable = false)
    private Long dataAugSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN", nullable = false)
    private Long dataSrcSn;

    @Column(name = "RVW_STTS_CD", nullable = false, length = 20)
    private String rvwSttsCd;

    @Column(name = "LBL_INTGRT_PCT", precision = 5, scale = 2)
    private BigDecimal lblIntgrtPct;

    @Column(name = "RJCT_RSN", length = 4000)
    private String rejectRsn;

    @Column(name = "RVW_ID", length = 30)
    private String rvwId;

    @Column(name = "RVW_DT")
    private LocalDateTime rvwDt;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * {@code DATA_RAW_SN} 미해석(null) 거부 — <b>센티널 {@code 0L} 금지</b>.
     *
     * <p>이 컬럼은 DB {@code NOT NULL} 이고 V146(DB-ISSUE-01)부터 {@code LS_DATA_RAW} 를 FK 로
     * 참조한다. 구 구현은 null 을 {@code 0L} 로 치환했는데 그 값은 <b>존재하지 않는 영상 참조</b>라,
     * FK 이전에는 "어느 영상의 검수 이력인지 알 수 없는 행" 이 조용히 쌓였고 FK 이후에는 INSERT 가
     * 거부돼 accept/reject 가 500 이 됐다. null 은 저장 대상이 아니므로 여기서 즉시 거부한다.
     *
     * <p>정상 경로는 {@code AugmentReviewService} 가 호출 전에 SRC_SN → RAW_SN 역해석을 선검증하므로
     * 여기까지 null 이 오지 않는다(본 guard 는 신규 호출처가 생겼을 때의 최후 방어).
     */
    private static Long requireRawSn(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "증강 검수 이력에 기록할 원본 영상 정보가 없습니다.");
        }
        return rawSn;
    }

    public static LsDataAugRvw pending(Long dataAugSn, Long rawSn, Long srcSn,
                                       BigDecimal integrityPct, String regId) {
        LsDataAugRvw review = new LsDataAugRvw();
        review.dataAugSn = dataAugSn;
        review.dataRawSn = requireRawSn(rawSn);
        review.dataSrcSn = srcSn;
        review.rvwSttsCd = STTS_PENDING;
        review.lblIntgrtPct = integrityPct;
        review.regId = regId;
        review.regDt = LocalDateTime.now();
        return review;
    }

    /**
     * 승인된 검수 row 를 한 번에 생성 (PENDING row 사전 등록 없이 직접 INSERT).
     */
    public static LsDataAugRvw createAccepted(Long dataAugSn, Long dataRawSn, Long dataSrcSn,
                                              BigDecimal labelIntegrityPct, String rvwId, LocalDateTime rvwDt) {
        LsDataAugRvw review = new LsDataAugRvw();
        review.dataAugSn = dataAugSn;
        review.dataRawSn = requireRawSn(dataRawSn);
        review.dataSrcSn = dataSrcSn;
        review.rvwSttsCd = STTS_ACCEPTED;
        review.lblIntgrtPct = labelIntegrityPct;
        review.rvwId = rvwId;
        review.rvwDt = rvwDt;
        review.regId = rvwId;
        review.regDt = rvwDt == null ? LocalDateTime.now() : rvwDt;
        review.mdfcnId = rvwId;
        review.mdfcnDt = rvwDt;
        return review;
    }

    /**
     * 반려된 검수 row 를 한 번에 생성. 반려 사유는 필수.
     */
    public static LsDataAugRvw createRejected(Long dataAugSn, Long dataRawSn, Long dataSrcSn,
                                              String rejectRsn, String rvwId, LocalDateTime rvwDt) {
        if (rejectRsn == null || rejectRsn.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        LsDataAugRvw review = new LsDataAugRvw();
        review.dataAugSn = dataAugSn;
        review.dataRawSn = requireRawSn(dataRawSn);
        review.dataSrcSn = dataSrcSn;
        review.rvwSttsCd = STTS_REJECTED;
        review.rejectRsn = rejectRsn;
        review.rvwId = rvwId;
        review.rvwDt = rvwDt;
        review.regId = rvwId;
        review.regDt = rvwDt == null ? LocalDateTime.now() : rvwDt;
        review.mdfcnId = rvwId;
        review.mdfcnDt = rvwDt;
        return review;
    }

    public void accept(String reviewerId, LocalDateTime at) {
        ensurePending();
        this.rvwSttsCd = STTS_ACCEPTED;
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    public void reject(String reason, String reviewerId, LocalDateTime at) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        ensurePending();
        this.rvwSttsCd = STTS_REJECTED;
        this.rejectRsn = reason;
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    /**
     * <b>반려를 되돌린다</b> — REJECTED → PENDING (Phase 7 폐기 복구).
     *
     * <h3>왜 표식 해제만으로는 부족한가 (사용자 확정 설계)</h3>
     * <p>파생영상 등재 게이트({@code DerivativeWorkEligibility})는 {@code EXISTS(RVW_STTS_CD='ACCEPTED')}
     * 축이다. 폐기 표식만 지우고 검수를 {@code REJECTED} 로 두면 게이트가 계속 닫혀 있어
     * <b>"복구했는데 여전히 안 보이는 반쪽 복구"</b> 가 된다. 그래서 {@link #ensurePending} 의 "재결정
     * 금지" 불변식을 <b>이 메서드에서만</b> 연다 — 복구 후에는 다시 채택/반려를 고를 수 있다.
     *
     * <h3>되돌린 이력은 어디에 남는가</h3>
     * <p><b>새 검수 행을 쌓지 않는다.</b> {@code findLatestByDataAugSn} 이 "1 aug = 1 review row" 전제로
     * 동작하고, 무엇보다 게이트가 {@code EXISTS(ACCEPTED)} 라 과거 ACCEPTED 행이 남으면 이후 반려해도
     * 게이트가 열린 채가 된다("사람은 폐기했는데 파생이 작업목록에 있다"). 되돌린 이력(누가·언제·왜)과
     * 원래 반려 사유 스냅샷은 {@code LS_DATA_AUG_DSCD}(폐기 원장)가 전담한다.
     *
     * <p>반려 사유({@code RJCT_RSN})는 여기서 지운다 — PENDING 인데 사유가 남아 있으면 화면이 "반려됨"
     * 으로 오인한다. 원문은 폐기 원장의 {@code DSCD_RSN} 에 보존된다.
     */
    public void reopen(String actorId, LocalDateTime at) {
        if (!STTS_REJECTED.equals(rvwSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "반려된 증강 검수만 되돌릴 수 있습니다. status=" + rvwSttsCd);
        }
        this.rvwSttsCd = STTS_PENDING;
        this.rejectRsn = null;
        this.rvwId = null;
        this.rvwDt = null;
        this.mdfcnId = actorId;
        this.mdfcnDt = at;
    }

    private void ensurePending() {
        if (!STTS_PENDING.equals(rvwSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 증강 검수입니다. status=" + rvwSttsCd);
        }
    }
}
