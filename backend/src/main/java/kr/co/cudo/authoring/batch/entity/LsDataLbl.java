package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LS_DATA_LBL: 현재 라벨 좌표/속성.
 *  - srcSn: LS_DATA_SRC FK (프레임 단위)
 *  - lblTypeCd: BBOX / POLYGON / SEGMENT / TRACK
 *  - pointCn: 좌표 직렬화 (Jackson 안전 모드 — enableDefaultTyping 사용 금지)
 *
 * 자동/수동 여부, 모델명, 신뢰도, 보간 출처 등 저작도구 전용 AI 메타는
 * LS_DATA_LBL_AI_INFO 에 분리 저장한다. 본 엔티티의 autoLblYn/confScore/lblSrcCd 는
 * 생성 직후 서비스가 AI 정보 테이블을 저장하기 위한 transient 값이다.
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

    /** Phase 3 트랙 보간: LBL_SRC_CD 값 — 트랙 보간으로 자동 생성된 row. */
    public static final String SRC_INTERPOLATED = "INTERPOLATED";

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

    /**
     * LS_LABEL 마스터 FK — Phase 2 (CVAT-Like 라벨 풀 포팅).
     * <p>NULL 허용:
     *  - V32 마이그레이션에서 best-effort 매칭에 실패한 legacy row
     *  - 외부 시스템에서 INSERT 된 자유 텍스트 라벨 (Phase 4 이후 정리)
     * <p>FK 강제는 Phase 4 (AutoLabel preset 매핑) 완료 후 별도 마이그레이션으로 NOT NULL 검토.
     */
    @Column(name = "LBL_ID")
    private Long labelId;

    /**
     * LS_LABEL FK 도입(V32) 이후 labelId 사용 권장.
     * 호환 위해 유지. 응답에서는 LS_LABEL.LABEL_NM (labelName) 을 우선 노출.
     */
    @Column(name = "LBL_NM", nullable = false, length = 255)
    private String labelNm;

    // MariaDB → PostgreSQL: @Lob + String 은 PG 에서 large object(oid/CLOB) 타입으로 매핑되어
    // "Large Objects may not be used in auto-commit mode" 오류를 유발한다. 마이그레이션이
    // POINT_CN 을 TEXT 로 생성하므로 columnDefinition="TEXT" 로 평문 텍스트 매핑한다.
    @Column(name = "POINT_CN", columnDefinition = "TEXT")
    private String pointCn;

    @Transient
    private String autoLblYn;

    @Transient
    private BigDecimal confScore;

    @Transient
    private Long dataAugSn;

    /**
     * 자동라벨링 트래커(ultralytics BoT-SORT 등) 부여 객체 ID — Phase 3.
     * <p>NULL 허용:
     *  - 수동 라벨 (사용자가 직접 그린 라벨)
     *  - 트래커가 저신뢰 detection 에 ID 미부여한 경우 (fallback)
     *  - V18 마이그레이션 이전 legacy row
     */
    @Column(name = "TRCK_ID", length = 64)
    private String trackId;

    /**
     * 라벨 출처 — Phase 3 트랙 보간 도입.
     * <p>NULL = DETECTED (YOLO detection 직접 발견 + 사용자 수동 입력 등 기본 경로),
     * 'INTERPOLATED' = 트랙 보간으로 추정·생성된 BBOX row.
     * <p>값 화이트리스트: NULL | INTERPOLATED. 향후 'AUGMENTED', 'IMPORTED' 확장 여지.
     */
    @Transient
    private String lblSrcCd;

    @Column(name = "REG_USER_NO")
    private Long regUserNo;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    @Builder
    private LsDataLbl(Long srcSn, String lblTypeCd, Long labelId, String label, String pointsJson,
                      String autoLblYn, BigDecimal confScore, String trackId, String lblSrcCd) {
        this.srcSn = srcSn;
        this.lblTypeCd = lblTypeCd;
        this.labelId = labelId;
        this.labelNm = label;
        this.pointCn = pointsJson;
        this.autoLblYn = autoLblYn;
        this.confScore = clampScore(confScore);
        this.trackId = trackId;
        this.lblSrcCd = lblSrcCd;
        this.regDt = LocalDateTime.now();
    }

    /**
     * Phase 2 — YOLO 자동 라벨링 결과 (LS_LABEL FK 포함).
     * <p>{@code labelId} null 허용 (Phase 4 이전 호환). Phase 4 이후 매핑 강제 예정.
     * AUTO_LBL_YN='Y' 강제. trackId 는 null 허용 (트래커 저신뢰 detection fallback).
     */
    public static LsDataLbl createAutoBbox(Long srcSn, Long labelId, String label, String pointsJson,
                                           BigDecimal confScore, String trackId) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(TYPE_BBOX)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_YES)
                .confScore(confScore)
                .trackId(trackId)
                .build();
    }

    /**
     * Phase 2 — 트랙 보간 자동 생성 BBOX (LS_LABEL FK 포함).
     */
    public static LsDataLbl createAutoInterpolatedBbox(Long srcSn, Long labelId, String label, String pointsJson,
                                                       BigDecimal confScore, String trackId) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(TYPE_BBOX)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_YES)
                .confScore(confScore)
                .trackId(trackId)
                .lblSrcCd(SRC_INTERPOLATED)
                .build();
    }

    /**
     * 트랙 보간으로 자동 생성된 BBOX 라벨 — Phase 3.
     * <p>{@code LBL_SRC_CD='INTERPOLATED'}, {@code AUTO_LBL_YN='Y'}, {@code LBL_TYPE_CD='BBOX'} 고정.
     * trackId 는 원본 detection 과 동일한 값으로 전달되어야 한다 (FE 가 같은 트랙으로 인식하도록).
     *
     * @param srcSn      대상 프레임의 LS_DATA_SRC.SRC_SN
     * @param label      원본 detection 의 라벨 (e.g. "person", "car")
     * @param pointsJson 보간된 BBOX 좌표 JSON
     * @param confScore  보간 신뢰도. 0.0 권장 (보간이므로 detection 점수 없음). null 도 허용.
     * @param trackId    원본 트랙 ID (NON-NULL 권장 — 보간 대상 자체가 trackId 있는 라벨로 한정됨)
    /** Phase 2 — SAM2 segment 결과 (LS_LABEL FK 포함). */
    public static LsDataLbl createAutoPolygon(Long srcSn, Long labelId, String label, String pointsJson, BigDecimal confScore) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(TYPE_POLYGON)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_YES)
                .confScore(confScore)
                .build();
    }

    /**
     * Phase 2 — 사용자가 직접 그린 라벨 (LS_LABEL FK 포함).
     * AUTO_LBL_YN='N' 강제, confScore 는 null.
     */
    public static LsDataLbl createManual(Long srcSn, String lblTypeCd, Long labelId, String label,
                                         String pointsJson, Long regUserNo) {
        LsDataLbl entity = LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(lblTypeCd)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_NO)
                .confScore(null)
                .build();
        entity.regUserNo = regUserNo;
        return entity;
    }

    /**
     * Phase 2 — 사용자가 기존 라벨의 좌표/라벨명/타입 + LABEL_ID FK 수정.
     * AUTO_LBL_YN 은 변경되지 않음 (정책: 자동 라벨은 사용자가 수정해도 'Y' 유지).
     *
     * @param labelId LS_LABEL FK. null 이면 기존 값 유지(변경 안 함). Service 레이어에서 사전 검증 필요.
     */
    public void updateUserContent(String lblTypeCd, Long labelId, String label, String pointsJson) {
        if (lblTypeCd == null || lblTypeCd.isBlank()) {
            throw new IllegalArgumentException("LBL_TYPE_CD 는 필수입니다.");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("LABEL 은 필수입니다.");
        }
        this.lblTypeCd = lblTypeCd;
        if (labelId != null) {
            this.labelId = labelId;
        }
        this.labelNm = label;
        this.pointCn = pointsJson;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * V2.0 증강 새 영상용 라벨 복사. DB 에서 로드한 원본 라벨의 영구 필드만 복사한다.
     * Transient 필드(autoLblYn, confScore, lblSrcCd)는 DB 미저장이므로 복사 대상 아님.
     */
    public static LsDataLbl copyForNewSrc(Long newSrcSn, LsDataLbl original) {
        if (original == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "복사 원본 라벨이 null 입니다.");
        }
        return LsDataLbl.builder()
                .srcSn(newSrcSn)
                .lblTypeCd(original.getLblTypeCd())
                .labelId(original.getLabelId())
                .label(original.getLabelNm())
                .pointsJson(original.getPointCn())
                .trackId(original.getTrackId())
                .build();
    }

    /** VLM 객체 검증 결과 등 신뢰도만 갱신. 0.0~1.0 범위 강제. */
    public void updateConfScore(BigDecimal newScore) {
        this.confScore = clampScore(newScore);
        this.mdfcnDt = LocalDateTime.now();
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
