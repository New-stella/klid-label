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
import kr.co.cudo.authoring.common.util.LabelCoordinateScaler;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LS_DATA_LBL: 현재 라벨 좌표/속성.
 *  - srcSn: LS_DATA_SRC FK (프레임 단위)
 *  - lblTypeCd: BBOX / POLYGON / SEGMENT / TRACK / SKELETON
 *    (SKELETON = 17-keypoint COCO 포즈 — POINT_CN 에 삼중값 [[x,y,v], x17], KeypointSerializer type-route)
 *  - pointCn: 좌표 직렬화 (Jackson 안전 모드 — enableDefaultTyping 사용 금지)
 *
 * <h3>AI 메타 5필드는 이 테이블이 직접 보유한다 (V6 흡수)</h3>
 * 자동/수동 여부·모델명·모델버전·신뢰도·라벨 출처는 구 {@code LS_DATA_LBL_AI_INFO} 에 1:1 로
 * 분리돼 있었고, 그때 {@code autoLblYn}/{@code confScore}/{@code lblSrcCd} 는 "생성 직후 서비스가
 * 분리 테이블을 저장하기 위한" {@code @Transient} 값이었다. 응답이 이 셋을 <b>항상 함께</b>
 * 내려주므로 라벨을 읽을 때마다 조인이 붙었고, 지금은 전부 <b>실 컬럼</b>이다.
 *
 * <p><b>NULL 의 의미</b>: {@code lblSrcCd}/{@code autoLblYn} 이 {@code null} 이면 "AI 가 만들지 않은
 * 라벨"이다 — 흡수 전의 "AI 정보 행 부재"를 그대로 옮긴 표현이며 {@code 'N'} 과 <b>다른 사실</b>이다
 * (DB DEFAULT 를 걸지 않는 이유 — V6 헤더 참조). 응답에서 {@code null}→{@code 'N'} 치환은
 * {@code LabelResponse.Item.from} 한 곳이 담당한다.
 *
 * @req R4
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
    /** 17-keypoint COCO 포즈. POINT_CN 에 삼중값 [[x,y,v], x17] 저장 (KeypointSerializer type-route). */
    public static final String TYPE_SKELETON = "SKELETON";

    /**
     * {@code LBL_SRC_CD} 값 — 구 {@code LsDataLblAiInfo} 상수를 흡수와 함께 옮겨 왔다(V6).
     *
     * <p>⚠ 옮기면서 <b>잠복 결함 1건</b>을 함께 바로잡았다: 구 {@code LsDataLbl.SRC_INTERPOLATED} 는
     * 값이 {@code "INTERPOLATED"}(과거분사)였는데, 그 필드가 {@code @Transient} 라 <b>DB 에 한 번도
     * 닿지 않았고</b> 실제로 적재되던 값은 보간 스텝이 별도로 만들던 {@code "INTERPOLATE"} 였다.
     * 조회 술어({@code findInterpolatedLblSnsByRawSn})도 {@code 'INTERPOLATE'} 를 본다. 흡수 후에는
     * 그 transient 가 곧 적재값이 되므로, 이름만 두고 값을 옮겼다면 보간 산출물이 조회에서 통째로
     * 사라졌을 것이다. 그래서 상수는 {@link #SRC_INTERPOLATE} 하나로 통일하고 옛 이름은 제거한다.
     */
    public static final String SRC_YOLO = "YOLO";
    /** @see #SRC_YOLO */
    public static final String SRC_SAM2 = "SAM2";
    /** 트랙 보간으로 자동 생성된 라벨의 출처. @see #SRC_YOLO */
    public static final String SRC_INTERPOLATE = "INTERPOLATE";
    /** @see #SRC_YOLO */
    public static final String SRC_VLM = "VLM";

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
    @Column(name = "LBL_NM", nullable = false, length = 80)
    private String labelNm;

    // MariaDB → PostgreSQL: @Lob + String 은 PG 에서 large object(oid/CLOB) 타입으로 매핑되어
    // "Large Objects may not be used in auto-commit mode" 오류를 유발한다. 마이그레이션이
    // POINT_CN 을 TEXT 로 생성하므로 columnDefinition="TEXT" 로 평문 텍스트 매핑한다.
    @Column(name = "POINT_CN", columnDefinition = "TEXT")
    private String pointCn;

    /**
     * 자동라벨여부 Y/N. {@code null} = AI 가 만들지 않은 라벨(흡수 전의 "AI 정보 행 부재").
     * DEFAULT 를 두지 않는다 — 클래스 주석 「NULL 의 의미」 참조.
     */
    @Column(name = "AUTO_LBL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String autoLblYn;

    /** 신뢰도점수 0.0~1.0. 수동 라벨은 {@code null}. */
    @Column(name = "CONF_SCORE", precision = 6, scale = 5)
    private BigDecimal confScore;

    /**
     * 모델명 — 자동 라벨을 만든 추론 모델. 흡수 시점 기준 <b>적재 경로가 없어 전량 {@code null}</b>
     * 이다(구 분리 테이블에서도 그랬다). 값을 채우는 것은 별건이며 여기서 지어내지 않는다.
     */
    @Column(name = "MDL_NM", length = 100)
    private String mdlNm;

    /** 모델버전. {@link #mdlNm} 과 같은 이유로 현재 전량 {@code null}. */
    @Column(name = "MDL_VER", length = 50)
    private String mdlVer;

    @Transient
    private Long dataAugSn;

    /**
     * 자동라벨링 트래커(ultralytics BoT-SORT 등) 부여 객체 ID — Phase 3.
     * <p>NULL 허용:
     *  - 수동 라벨 (사용자가 직접 그린 라벨)
     *  - 트래커가 저신뢰 detection 에 ID 미부여한 경우 (fallback)
     *  - V18 마이그레이션 이전 legacy row
     */
    @Column(name = "TRCK_ID", length = 30)
    private String trackId;

    /**
     * 라벨출처코드 — {@link #SRC_YOLO}/{@link #SRC_SAM2}/{@link #SRC_INTERPOLATE}/{@link #SRC_VLM}.
     * <p>{@code null} = AI 가 만들지 않은 라벨(사람이 그린 것). 클래스 주석 「NULL 의 의미」 참조.
     */
    @Column(name = "LBL_SRC_CD", length = 20)
    private String lblSrcCd;

    /**
     * 등록사용자번호 — 이 라벨을 만든 사용자. 내부 채널에서는 작업자이고, 포털 업로드 자산의 라벨에서는
     * 그 라벨의 <b>소유자이자 인가 판정의 키</b>라 반드시 채워진다.
     *
     * <p>★ 자료형이 숫자가 아니라 문자인 이유(V28): 포털이 발급한 토큰의 주체 식별자를 담아야 한다.
     * 숫자로 두면 파싱 실패로 조용히 {@code null} 이 되어 <b>소유자 없는 라벨</b>이 저장되고, 그 순간
     * 소유자 스코프 조회가 남의 라벨을 함께 집거나 자기 라벨을 못 집는다. 폭 100 은 공통표준도메인
     * 번호V100 이며 {@code LS_MARKING.REG_USER_NO}(V27)와 같다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    @Column(name = "REG_USER_NO", length = 100)
    private String regUserNo;

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
                .lblSrcCd(SRC_INTERPOLATE)
                .build();
    }

    /**
     * 트랙 보간으로 자동 생성된 POLYGON 라벨 — 폴리곤 트랙 보간(R1 SFR-08-01).
     * <p>{@code LBL_SRC_CD=}{@link #SRC_INTERPOLATE}{@code ('INTERPOLATE')}, {@code AUTO_LBL_YN='Y'},
     * {@code LBL_TYPE_CD='POLYGON'} 고정. ⚠ 구 javadoc 의 {@code 'INTERPOLATED'}(과거분사)는 <b>오기</b>였다
     * — 조회 술어가 보는 값은 {@code 'INTERPOLATE'} 다(상수 선언부의 잠복 결함 설명 참조).
     * {@link #createAutoInterpolatedBbox} 와 동일 정책이며 타입만 POLYGON 이다.
     *
     * @param pointsJson 보간된 폴리곤 정점 JSON ({@code [[x,y], ...]} 정규형)
     */
    public static LsDataLbl createAutoInterpolatedPolygon(Long srcSn, Long labelId, String label, String pointsJson,
                                                          BigDecimal confScore, String trackId) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(TYPE_POLYGON)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(AUTO_YES)
                .confScore(confScore)
                .trackId(trackId)
                .lblSrcCd(SRC_INTERPOLATE)
                .build();
    }

    /**
     * 트랙 보간으로 자동 생성된 BBOX 라벨 — Phase 3.
     * <p>{@code LBL_SRC_CD=}{@link #SRC_INTERPOLATE}{@code ('INTERPOLATE')}, {@code AUTO_LBL_YN='Y'},
     * {@code LBL_TYPE_CD='BBOX'} 고정. ⚠ 구 javadoc 의 {@code 'INTERPOLATED'} 는 <b>오기</b>였다
     * — 조회 술어가 보는 값은 {@code 'INTERPOLATE'} 다(상수 선언부의 잠복 결함 설명 참조).
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
     *
     * <p><b>AI 메타 3필드를 {@code null} 로 둔다</b>({@code 'N'} 이 아니다). 흡수 전 이 경로는
     * {@code LS_DATA_LBL_AI_INFO} 행을 <b>만들지 않았고</b>, 그 부재를 흡수 후에 옮기는 표현이
     * {@code null} 이기 때문이다. {@code 'N'} 을 적으면 사람이 그린 라벨과 "AI 가 만들었는데 자동
     * 플래그가 N"(버전 롤백 복원)이 같은 값이 되어 영구히 구분되지 않는다.
     * 응답이 {@code 'N'} 으로 보이는 것은 종전과 같다 — 치환은 {@code LabelResponse.Item.from} 담당.
     */
    public static LsDataLbl createManual(Long srcSn, String lblTypeCd, Long labelId, String label,
                                         String pointsJson, String regUserNo) {
        LsDataLbl entity = LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(lblTypeCd)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(null)
                .confScore(null)
                .build();
        entity.regUserNo = regUserNo;
        return entity;
    }

    /**
     * 버전 롤백 복원 전용 — 스냅샷 라벨을 <b>새 PK 발급</b>으로 되살린다(D-ISSUE-23).
     *
     * <p>{@link #createManual} 은 {@code autoLblYn='N'}·{@code confScore=null} 을 강제하고 {@code trackId}
     * 인자가 없어 복원에 부적합하다(트랙 연속성·자동라벨 여부 유실). 본 팩토리는 스냅샷이 보유한
     * {@code TRCK_ID} 와 AI 메타를 그대로 전달받는다(V6 흡수 이후 둘 다 이 행의 실 컬럼이라, 호출자가
     * 별도 테이블에 동반 적재하던 단계가 사라졌다). {@code createManual} 의 동작은 다른 경로가
     * 의존하므로 변경하지 않는다.
     *
     * <p>{@code LBL_SN} 은 IDENTITY 재발급이다. 스냅샷의 옛 {@code LBL_SN} 을 보존하는 경로는
     * {@code LsDataLblRepositoryCustom#insertRestoredWithExplicitIds}(네이티브 명시 삽입)이며, 본 팩토리는
     * PK 충돌 등으로 그 경로가 실패했을 때의 <b>폴백</b>과 id 없는 옛 스냅샷 복원에 사용된다.
     *
     * @param autoLblYn 스냅샷의 자동라벨 여부 (null 허용 — 옛 스냅샷 하위호환)
     * @param confScore 스냅샷의 신뢰도 (null 허용)
     * @param trackId   스냅샷의 트랙 ID (null 허용)
     * @param lblSrcCd  스냅샷의 라벨 출처 코드 (null 허용)
     */
    public static LsDataLbl createRestored(Long srcSn, String lblTypeCd, Long labelId, String label,
                                          String pointsJson, String autoLblYn, BigDecimal confScore,
                                          String trackId, String lblSrcCd) {
        return LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd(lblTypeCd)
                .labelId(labelId)
                .label(label)
                .pointsJson(pointsJson)
                .autoLblYn(autoLblYn)
                .confScore(confScore)
                .trackId(trackId)
                .lblSrcCd(lblSrcCd)
                .build();
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
     * V6 흡수 — 자동 라벨의 출처·신뢰도를 <b>저장 직전</b>에 부여한다.
     *
     * <p>흡수 전에는 라벨을 먼저 저장해 {@code LBL_SN} 을 얻은 뒤 그 PK 로 AI 정보 행을 따로 만들었다.
     * 이제 같은 행이라 저장 전에 채워 넣는다. 배치 오토라벨 경로({@code AutoLabelBatchPersister})가
     * 유일한 호출자이며, 출처를 라벨 종류가 아니라 <b>호출 스텝</b>이 정한다는 점은 종전과 같다.
     *
     * <p>{@code AUTO_LBL_YN='Y'} 를 함께 세운다 — 구 {@code LsDataLblAiInfo.create} 가 정확히 그렇게
     * 강제했다("AI 정보 행을 만든다 = 그 라벨은 자동 생성이다"). 그 불변식을 흡수 후에도 한 메서드 안에
     * 묶어 둬야 호출부가 둘 중 하나만 세우는 어긋남이 생기지 않는다.
     *
     * @param lblSrcCd 출처 코드({@link #SRC_YOLO}/{@link #SRC_SAM2})
     * @param score    AI 정보에 적재되던 신뢰도 원본값. 0.0~1.0 범위를 벗어나면 거부한다.
     */
    public void applyAiSource(String lblSrcCd, BigDecimal score) {
        this.lblSrcCd = lblSrcCd;
        this.confScore = clampScore(score);
        this.autoLblYn = AUTO_YES;
    }

    /**
     * V2.0 증강 새 영상용 라벨 복사. DB 에서 로드한 원본 라벨의 영구 필드만 복사한다.
     *
     * <p><b>AI 메타(autoLblYn/confScore/lblSrcCd/mdlNm/mdlVer)는 복사하지 않는다</b> — 흡수 전
     * 이 경로가 AI 정보 행을 만들지 않아 파생 라벨이 "AI 정보 없음"이었던 동작을 그대로 유지한다.
     * (흡수로 필드가 실 컬럼이 됐다는 이유만으로 복사 범위를 넓히면 파생영상의 생산이력이
     * 원본에서 <b>날조</b>된다 — 그 파생 라벨을 만든 것은 추론이 아니라 복사다.)
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

    /**
     * Phase 1 (해상도 파생영상) — 좌표를 해상도 비율로 스케일한 복제본 반환.
     * <p>원본 라벨의 영구 필드는 {@link #copyForNewSrc} 와 동일하게 복제하되, POINT_CN 만
     * {@link LabelCoordinateScaler} 로 {@code scaleX}(x축)·{@code scaleY}(y축) 리스케일한다.
     * {@code scaleX==scaleY==1.0} 이면 {@link #copyForNewSrc}(좌표 그대로) 와 등가다.
     *
     * @param newSrcSn 파생영상 프레임의 SRC_SN
     * @param original 복사 원본 라벨(DB 로드 상태, null 금지)
     * @param scaleX   x축 배율(양수)
     * @param scaleY   y축 배율(양수)
     */
    public static LsDataLbl copyForNewSrcScaled(Long newSrcSn, LsDataLbl original, double scaleX, double scaleY) {
        return copyForNewSrcScaled(newSrcSn, original, scaleX, scaleY, 0d, 0d);
    }

    /**
     * 좌표 배율 + <b>레터박스 오프셋</b>까지 반영해 복사한다(G-1 종횡비 보존).
     * 파생 프레임은 목표 해상도 안 {@code (offsetX, offsetY)} 위치에 그려지므로 좌표도 같은 변환을 받는다.
     */
    public static LsDataLbl copyForNewSrcScaled(Long newSrcSn, LsDataLbl original, double scaleX, double scaleY,
                                                double offsetX, double offsetY) {
        if (original == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "복사 원본 라벨이 null 입니다.");
        }
        String scaledPointCn = LabelCoordinateScaler.scalePointCn(
                original.getPointCn(), original.getLblTypeCd(), scaleX, scaleY, offsetX, offsetY);
        return LsDataLbl.builder()
                .srcSn(newSrcSn)
                .lblTypeCd(original.getLblTypeCd())
                .labelId(original.getLabelId())
                .label(original.getLabelNm())
                .pointsJson(scaledPointCn)
                .trackId(original.getTrackId())
                .build();
    }

    /**
     * Phase 4 트랙 병합 — 트랙 ID 재지정(다른 트랙으로 이관). 좌표/라벨/타입은 불변.
     * <p>{@code TrackMergeService} 가 fromTrack 의 원 키프레임을 toTrack 으로 옮길 때 사용한다.
     * 좌표를 건드리지 않으므로 IDOR/좌표검증 재수행 불필요(같은 영상 내 재그룹핑).
     *
     * @param newTrackId 병합 대상 트랙 ID (NotBlank — 빈 값이면 트랙 소실 방지 위해 거부)
     */
    public void reassignTrack(String newTrackId) {
        if (newTrackId == null || newTrackId.isBlank()) {
            throw new IllegalArgumentException("병합 대상 TRCK_ID 는 필수입니다.");
        }
        this.trackId = newTrackId;
        this.mdfcnDt = LocalDateTime.now();
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
