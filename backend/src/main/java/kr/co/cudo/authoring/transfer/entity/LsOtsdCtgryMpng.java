package kr.co.cudo.authoring.transfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 외부분류대응 ({@code LS_OTSD_CTGRY_MPNG}) — 외부 산출물이 쓰는 분류 이름을 저작도구의 라벨 체계
 * 또는 이벤트 유형에 잇는다.
 *
 * <h3>왜 한 번 정해 두고 재사용하는가</h3>
 * <p>대응은 <b>종류 단위</b>이며 산출물 항목마다 다시 정하지 않는다. 같은 분류가 나올 때마다 사람이
 * 다시 골라야 한다면 규모가 큰 산출물에서는 쓸 수 없다. 그래서 유일 제약은
 * {@code (MPNG_KND_CD, OTSD_CTGRY_CD)} 다 — 한 외부 분류는 한 축에서 <b>하나</b>에만 대응한다.
 *
 * <h3>두 축을 한 테이블에 둔 것은 의도다</h3>
 * <p>라벨 축과 이벤트유형 축은 수명·조회 경로·확정 동선이 같고, <b>미확정이 남으면 적재를 막는
 * 판정도 하나</b>다. 나누면 그 판정이 두 곳으로 갈린다. {@link #mpngKndCd} 가 어느 축인지 가르고
 * 그 축의 참조 컬럼만 채운다(반대쪽은 {@code null}).
 *
 * <h3>fail-closed — 짐작으로 연결하지 않는다</h3>
 * <p>처음 보는 분류는 이름이 비슷한 후보를 제시하고 <b>사람이 확인해 확정</b>한다. 이름만 보고
 * 짐작해 연결하면 다른 분류로 저장되고, 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다.
 * 미확정 분류가 하나라도 남으면 적재하지 않는다. 이 엔티티에는 <b>자동 확정 통로가 없다</b> —
 * 생성 팩토리가 대응 대상({@code lblId} / {@code evntTypeCd})을 반드시 받는다.
 *
 * <h3>해제는 삭제가 아니라 표시다</h3>
 * <p>쓰지 않게 된 대응은 지우지 않고 {@link #disable()} 로 표시만 바꾼다. 지우면 과거 이관이 어느
 * 대응으로 적재됐는지 되짚을 수 없다.
 *
 * <p>{@code @DynamicUpdate}: 이 행은 사람이 대응을 바꾸는 경로와 사용여부를 토글하는 경로가 서로
 * 다른 컬럼만 건드리므로, 전체 컬럼 UPDATE 로 서로의 값을 stale 로 되돌리지 않게 한다(CWE-362).
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design ADR-048
 */
@Entity
@Table(name = "LS_OTSD_CTGRY_MPNG",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_OTSD_CTGRY_MPNG",
                columnNames = {"MPNG_KND_CD", "OTSD_CTGRY_CD"}))
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsOtsdCtgryMpng {

    /** 대응종류 — 저작도구 <b>라벨</b>({@code LS_LABEL.LBL_ID})에 대응한다. */
    public static final String MPNG_KND_LABEL = "LABEL";

    /** 대응종류 — 저작도구 <b>이벤트 유형</b>({@code LS_EVNT_TYPE.EVNT_TYPE_CD})에 대응한다. */
    public static final String MPNG_KND_EVNT_TYPE = "EVNT_TYPE";

    /** 사용여부 — 쓰는 대응. */
    public static final String USE_YES = "Y";

    /** 사용여부 — 해제된 대응(행은 남기고 표시만 바꾼다). */
    public static final String USE_NO = "N";

    /** {@code OTSD_CTGRY_CD} 컬럼 폭(V14) — 표준 코드 도메인 코드V20. 넘는 값은 입구에서 거부한다. */
    public static final int OTSD_CTGRY_CD_MAX = 20;

    /** {@code OTSD_CTGRY_NM} 컬럼 폭(V14) — 명V200. */
    public static final int OTSD_CTGRY_NM_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MPNG_SN")
    private Long mpngSn;

    /** 대응종류코드 — {@link #MPNG_KND_LABEL} / {@link #MPNG_KND_EVNT_TYPE}. */
    @Column(name = "MPNG_KND_CD", nullable = false, length = 20)
    private String mpngKndCd;

    /**
     * 외부카테고리코드 — 외부 산출물이 준 분류 식별 문자열 <b>원문</b>. 저작도구가 정한 코드가 아니다.
     * 폭이 표준 코드 도메인({@value #OTSD_CTGRY_CD_MAX})이라 넘는 값은 입구에서 거부한다.
     */
    @Column(name = "OTSD_CTGRY_CD", nullable = false, length = OTSD_CTGRY_CD_MAX)
    private String otsdCtgryCd;

    /** 외부카테고리명 — 외부 산출물이 준 표시 이름. 후보 제시의 근거이자 사후 판독의 근거다. */
    @Column(name = "OTSD_CTGRY_NM", length = OTSD_CTGRY_NM_MAX)
    private String otsdCtgryNm;

    /** 라벨아이디 — 대응종류가 이벤트유형이면 {@code null}. */
    @Column(name = "LBL_ID")
    private Long lblId;

    /** 이벤트유형코드 — 대응종류가 라벨이면 {@code null}. */
    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    @Column(name = "USE_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String useYn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsOtsdCtgryMpng(String mpngKndCd, String otsdCtgryCd, String otsdCtgryNm,
                            Long lblId, String evntTypeCd, String regId) {
        this.mpngKndCd = mpngKndCd;
        this.otsdCtgryCd = require(otsdCtgryCd, "외부카테고리코드");
        this.otsdCtgryNm = otsdCtgryNm;
        this.lblId = lblId;
        this.evntTypeCd = evntTypeCd;
        this.useYn = USE_YES;
        this.regId = regId;
        this.regDt = LocalDateTime.now();
    }

    /**
     * 라벨 축 대응을 확정한다. {@code lblId} 는 <b>사람이 확인한 값</b>이어야 한다 — 이름 유사도만으로
     * 호출하지 말 것(그 판정은 저장 뒤에 되돌릴 수 없다).
     */
    public static LsOtsdCtgryMpng forLabel(String otsdCtgryCd, String otsdCtgryNm, Long lblId, String regId) {
        if (lblId == null) {
            throw new IllegalArgumentException("라벨 대응에는 라벨 아이디가 필요합니다.");
        }
        return new LsOtsdCtgryMpng(MPNG_KND_LABEL, otsdCtgryCd, otsdCtgryNm, lblId, null, regId);
    }

    /** 이벤트유형 축 대응을 확정한다. {@code evntTypeCd} 는 사람이 확인한 값이어야 한다. */
    public static LsOtsdCtgryMpng forEventType(String otsdCtgryCd, String otsdCtgryNm,
                                               String evntTypeCd, String regId) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            throw new IllegalArgumentException("이벤트유형 대응에는 이벤트유형코드가 필요합니다.");
        }
        return new LsOtsdCtgryMpng(MPNG_KND_EVNT_TYPE, otsdCtgryCd, otsdCtgryNm, null, evntTypeCd, regId);
    }

    /** 라벨 축 대응 대상을 바꾼다(사람이 확인한 값만). 축이 다르면 거부한다. */
    public void remapToLabel(Long lblId, String mdfrId) {
        if (!MPNG_KND_LABEL.equals(this.mpngKndCd)) {
            throw new IllegalStateException("라벨 대응이 아닙니다.");
        }
        if (lblId == null) {
            throw new IllegalArgumentException("라벨 대응에는 라벨 아이디가 필요합니다.");
        }
        this.lblId = lblId;
        touch(mdfrId);
    }

    /** 이벤트유형 축 대응 대상을 바꾼다(사람이 확인한 값만). 축이 다르면 거부한다. */
    public void remapToEventType(String evntTypeCd, String mdfrId) {
        if (!MPNG_KND_EVNT_TYPE.equals(this.mpngKndCd)) {
            throw new IllegalStateException("이벤트유형 대응이 아닙니다.");
        }
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            throw new IllegalArgumentException("이벤트유형 대응에는 이벤트유형코드가 필요합니다.");
        }
        this.evntTypeCd = evntTypeCd;
        touch(mdfrId);
    }

    /** 표시 이름을 갱신한다(외부 산출물이 이름만 바꾼 경우). 대응 대상은 건드리지 않는다. */
    public void renameExternalCategory(String otsdCtgryNm, String mdfrId) {
        this.otsdCtgryNm = otsdCtgryNm;
        touch(mdfrId);
    }

    /** 이 대응을 더 쓰지 않는다 — 행은 남기고 표시만 바꾼다(멱등). */
    public void disable(String mdfrId) {
        this.useYn = USE_NO;
        touch(mdfrId);
    }

    /** 해제했던 대응을 다시 쓴다(멱등). */
    public void enable(String mdfrId) {
        this.useYn = USE_YES;
        touch(mdfrId);
    }

    /** 지금도 쓰는 대응인가. */
    public boolean isActive() {
        return USE_YES.equals(this.useYn);
    }

    private void touch(String mdfrId) {
        this.mdfrId = mdfrId;
        this.mdfcnDt = LocalDateTime.now();
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "는 필수입니다.");
        }
        if (value.length() > OTSD_CTGRY_CD_MAX) {
            // 컬럼 폭을 넘는 값은 INSERT 시점 DB 오류(500)가 되기 전에 입구에서 거부한다.
            throw new IllegalArgumentException(label + "가 허용 길이를 넘습니다.");
        }
        return value;
    }
}
