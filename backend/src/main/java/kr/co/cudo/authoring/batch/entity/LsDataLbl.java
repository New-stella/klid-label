package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LS_DATA_LBL: 라벨 (자동/수동 통합).
 *  - srcSn: LS_DATA_SRC FK (프레임 단위)
 *  - lblTypeCd: BBOX / POLYGON / SEGMENT / TRACK
 *  - pointsJson: 좌표 직렬화 (Jackson 안전 모드 — enableDefaultTyping 사용 금지)
 *  - autoLblYn: 'Y' = YOLO/SAM2/VLM 자동 산출, 'N' = 사람 입력
 *  - confScore: 0.0 ~ 1.0 (VLM 검증 결과로 갱신될 수 있음)
 *  - dataAugSn: 증강 데이터 FK (Phase 10)
 *
 *  본 Phase 5 에서는 자동 산출 라벨 INSERT 와 confScore UPDATE 가 주된 사용처.
 */
@Entity
@Table(name = "LS_DATA_LBL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataLbl {

    public static final String AUTO_YES = "Y";
    public static final String AUTO_NO = "N";

    public static final String TYPE_BBOX = "BBOX";
    public static final String TYPE_POLYGON = "POLYGON";
    public static final String TYPE_SEGMENT = "SEGMENT";
    public static final String TYPE_TRACK = "TRACK";

    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = BigDecimal.ONE;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LBL_SN")
    private Long lblSn;

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "LBL_TYPE_CD", nullable = false, length = 16)
    private String lblTypeCd;

    @Column(name = "LABEL", nullable = false, length = 255)
    private String label;

    @Lob
    @Column(name = "POINTS_JSON")
    private String pointsJson;

    @Column(name = "AUTO_LBL_YN", nullable = false, length = 1)
    private String autoLblYn;

    @Column(name = "CONF_SCORE", precision = 5, scale = 4)
    private BigDecimal confScore;

    @Column(name = "DATA_AUG_SN")
    private Long dataAugSn;

    @Column(name = "REG_USER_NO")
    private Long regUserNo;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataLbl(Long srcSn, String lblTypeCd, String label, String pointsJson,
                      String autoLblYn, BigDecimal confScore) {
        this.srcSn = srcSn;
        this.lblTypeCd = lblTypeCd;
        this.label = label;
        this.pointsJson = pointsJson;
        this.autoLblYn = autoLblYn;
        this.confScore = clampScore(confScore);
        this.regDt = LocalDateTime.now();
    }

    /** YOLO 자동 라벨링 결과를 저장할 때 사용. AUTO_LBL_YN='Y' 강제. */
    public static LsDataLbl createAutoBbox(Long srcSn, String label, String pointsJson, BigDecimal confScore) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(TYPE_BBOX)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_YES)
                .confScore(confScore)
                .build();
    }

    /** SAM2 segment 결과를 저장할 때 사용. POLYGON 타입. */
    public static LsDataLbl createAutoPolygon(Long srcSn, String label, String pointsJson, BigDecimal confScore) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(TYPE_POLYGON)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_YES)
                .confScore(confScore)
                .build();
    }

    /**
     * 사용자(WORKER/REVIEWER) 가 직접 그린 라벨 — Phase 6.
     * AUTO_LBL_YN='N' 강제, confScore 는 null.
     */
    public static LsDataLbl createManual(Long srcSn, String lblTypeCd, String label,
                                         String pointsJson, Long regUserNo) {
        LsDataLbl entity = LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(lblTypeCd)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_NO)
                .confScore(null)
                .build();
        entity.regUserNo = regUserNo;
        return entity;
    }

    /**
     * 사용자가 기존 라벨의 좌표/라벨명/타입을 수정 — Phase 6.
     * AUTO_LBL_YN 은 변경되지 않음 (정책: 자동 라벨은 사용자가 수정해도 'Y' 유지).
     */
    public void updateUserContent(String lblTypeCd, String label, String pointsJson) {
        if (lblTypeCd == null || lblTypeCd.isBlank()) {
            throw new IllegalArgumentException("LBL_TYPE_CD 는 필수입니다.");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("LABEL 은 필수입니다.");
        }
        this.lblTypeCd = lblTypeCd;
        this.label = label;
        this.pointsJson = pointsJson;
        this.updDt = LocalDateTime.now();
    }

    /** VLM 객체 검증 결과 등 신뢰도만 갱신. 0.0~1.0 범위 강제. */
    public void updateConfScore(BigDecimal newScore) {
        this.confScore = clampScore(newScore);
        this.updDt = LocalDateTime.now();
    }

    /**
     * 0.0 ~ 1.0 범위 강제. null 입력은 유지(=null), 범위 초과는 IllegalArgumentException.
     * (CWE-20 입력 검증 — Fortify Range Check.)
     */
    private static BigDecimal clampScore(BigDecimal score) {
        if (score == null) {
            return null;
        }
        if (score.compareTo(MIN_SCORE) < 0 || score.compareTo(MAX_SCORE) > 0) {
            throw new IllegalArgumentException("CONF_SCORE 는 0.0~1.0 범위여야 합니다: " + score);
        }
        return score;
    }
}
