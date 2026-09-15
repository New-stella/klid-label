package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 원시 영상 (LS_DATA_RAW). 관제서버로부터 수신한 라벨링 대상 영상 메타.
 * - VMS_CLIP_ID 가 UK 로 잡혀 있어 동일 클립 재수신 시 upsert.
 * - PRVC_TYPE_CD 값에 따라 PRVC_YN 이 자동 산출 (ANONY -> N, PRVC/PSDO -> Y).
 *
 * <p><b>{@code @DynamicUpdate} 적용 근거 (CWE-362, lost-update 양방향 방어)</b>: 본 엔티티는
 * {@code @Version} 이 없어 Hibernate 기본 정적 UPDATE 가 flush 시 <b>전체 컬럼</b>을 SET 한다. 적재 직후
 * 같은 {@code VideoIngestedEvent} 로 여러 full-entity writer(선두 비식별 {@code DeidentifyStep.runMock},
 * 배치 상태 전이 {@code BatchTransitionService}, {@code KpstDeidentTxService} 등)와 조건부 단일 컬럼
 * back-fill({@code VideoRepository#backfillDurationSecIfBlank})이 <b>동시</b> 실행된다. 전체 컬럼 UPDATE 는
 * 로드 시점의 stale 값을 다른 writer 가 그대로 다시 써버려, ①비식별이 커밋한 {@code DE_IDENT_YN='Y'} 되돌림
 * (PII 재노출) ②back-fill 한 {@code VDO_LEN_SEC} 를 stale null 로 되돌림(NIA export·데이터마트 길이 누락)
 * 양방향 회귀를 낳을 수 있다. {@code @DynamicUpdate} 는 flush 시 <b>실제 dirty 필드만</b> SET 절에 포함하므로,
 * 자신이 변경하지 않은 컬럼을 stale 값으로 덮어쓰지 않는다 — 이 회귀 클래스를 시스템 차원에서 차단한다.
 */
@Entity
@Table(name = "LS_DATA_RAW",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_RAW_VMS_CLIP", columnNames = "VMS_CLIP_ID"))
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataRaw {

    public static final String PRVC_TYPE_ANONY = "ANONY";
    public static final String PRVC_TYPE_PRVC = "PRVC";
    public static final String PRVC_TYPE_PSDO = "PSDO";
    /**
     * 개인정보 처리 유형 미상 — 마이그레이션/외부 적재로 분류가 확정되지 않은 영상의 잠정값.
     * 검수완료 재비식별(REDEIDENT)이 수행되면 개인정보 처리가 실제로 일어난 것이므로
     * {@link #correctPrvcTypeIfUnknown()} 가 PRVC 로 정정한다.
     */
    public static final String PRVC_TYPE_UNKNOWN = "UNKNOWN";

    /**
     * 출처유형 — 저작도구가 만든 파생영상(증강 · 해상도 변환본). 설계 §4-2 허용값 5종 중 하나이며
     * 경계축은 "관제가 만들었나 / 저작도구가 만들었나"다(외부 위탁 여부가 아니다).
     */
    public static final String SRC_TYPE_AUGMENTED = "AUGMENTED";

    /**
     * 출처유형 — 관제가 <b>생성형 AI 로 제작해 인입</b>한 영상(연동 규격서 §3-2 허용값 5종 중 하나).
     * 관제가 이 값으로 보내 주어야 {@code gen_ai_yn} 이 정확히 채워진다({@code ORIGINAL} 로 오면
     * 저작도구는 구분할 수단이 없다).
     */
    public static final String SRC_TYPE_GENERATED = "GENERATED";

    /**
     * 출처유형 — <b>외부에서 이미 라벨링이 끝난 상태로 가져온 영상</b>(외부 산출물 이관, ADR-048).
     * 관제가 보낸 것도 저작도구가 만든 것도 아니라 기존 경계축 어느 쪽에도 넣을 수 없어 신설했다.
     *
     * <p>⚠ 이 값은 {@link LsDataIngest#ALLOWED_SRC_TYPES}(적재면)·{@code UPLOAD_SRC_TYPES}(입력면)
     * <b>어느 쪽에도 넣지 않는다</b>. 적재면은 "인입 원장에 실려 온 값을 복사해도 되는가"를 판정하는데
     * 이관 경로는 <b>인입 원장을 거치지 않으므로</b> 그 목록에 넣으면 "관제가 이 값을 보낼 수 있다"는
     * 없는 사실이 생긴다. 입력면은 내부 업로드 폼이 고를 수 있는 값이라 역시 다른 축이다.
     * 이 값을 채우는 곳은 이관 적재 팩토리 하나뿐이다.
     *
     * <p>{@code gen_ai_yn} 은 이 값과 무관하게 {@code N} 이다 — 그 판정은 {@code GENERATED}·
     * {@code AUGMENTED} 두 값만 보므로 관제 계약·데이터마트 뷰가 깨지지 않는다.
     */
    public static final String SRC_TYPE_IMPORTED = "IMPORTED";

    /**
     * 출처유형 — <b>포털 사용자가 직접 올린 본인 자산</b>(ADR-058 흡수). 포털 채널 전용 표를 두지 않고
     * 이 원장에 앉히며, 관제가 만든 것도 저작도구가 만든 것도 아니라 기존 값 어느 쪽에도 넣을 수 없어
     * 값을 하나 더했다. 판별 <b>축</b>이 새로 생긴 것이 아니라 이미 있던 출처 축에 값이 하나 는 것이다.
     *
     * <p>⚠ 이 값은 {@link LsDataIngest#ALLOWED_SRC_TYPES}(적재면)·{@code UPLOAD_SRC_TYPES}(입력면)
     * <b>어느 쪽에도 넣지 않는다</b> — 포털 업로드는 관제 인입 원장을 거치지 않고 내부 업로드 폼이
     * 고를 수 있는 값도 아니다({@link #SRC_TYPE_IMPORTED} 와 같은 이유).
     *
     * <p>★ <b>포털 증강 파생물도 같은 값을 쓴다</b>(ADR-058) — 파생이라는 사실은 {@code ORGNL_RAW_SN}
     * 이 말한다. 파생 전용 값을 새로 만들면 포털을 가르는 모든 자리가 두 값을 열거해야 하고, 한 곳만
     * 잊으면 파생물이 조회·집계·<b>보존기간 만료 삭제</b>에서 조용히 새거나 사라진다.
     *
     * <p>★ <b>보존기간 만료 자동 삭제의 판별자 셋 중 하나</b>다(나머지 둘: {@code PORTAL_USER_NO} 보유,
     * 보존기간 경과). 하나만 빠뜨리면 관제 영상이 함께 지워진다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    public static final String SRC_TYPE_PORTAL_ULD = "PORTAL_ULD";

    /**
     * 출처유형 — <b>포털 데이터셋 소재에서 등록한 영상</b>(ADR-068). 관제·저작도구 생산물이 아니고
     * 사용자 자산도 아니다 — 데이터셋에 들어온 포털 사용자가 함께 쓰는 소재라 소유자
     * ({@code PORTAL_USER_NO})가 비어 있다. 판별 <b>축</b>을 새로 만든 것이 아니라 출처 축에 값 하나를
     * 더한 것이다({@link #SRC_TYPE_PORTAL_ULD} 를 더한 방식과 같다).
     *
     * <p>⚠ 이 값은 {@link LsDataIngest#ALLOWED_SRC_TYPES}(적재면)·{@code UPLOAD_SRC_TYPES}(입력면)
     * <b>어느 쪽에도 넣지 않는다</b> — 관제 인입 원장을 거치지 않고 내부 업로드 폼이 고를 수 있는 값도
     * 아니다({@link #SRC_TYPE_IMPORTED}·{@link #SRC_TYPE_PORTAL_ULD} 와 같은 이유). 이 값을 채우는 곳은
     * {@link #createPortalDataset} 하나뿐이다.
     *
     * <p>{@code gen_ai_yn} 은 {@code N} 이다({@link #genAiYnOf} 는 {@code GENERATED}·{@code AUGMENTED}
     * 두 값만 본다).
     *
     * <p>★ <b>포털 채널 출처가 둘이 됐다.</b> 영상 원장을 읽으며 포털 채널을 가르는 자리는 두 값을 따로
     * 열거하지 말고 {@link #PORTAL_CHANNEL_SRC_TYPES} 한 집합을 쓴다 — 한 값만 적으면 다른 값의 영상이
     * 작업보드·배정·통계에 조용히 섞인다.
     *
     * <p>⚠ <b>보존기간 만료 자동 삭제의 대상이 아니다</b> — 그 삭제의 판별자는
     * {@link #SRC_TYPE_PORTAL_ULD} + 소유자 보유 + 보존기간 경과이며, 이 출처는 소유자가 없다.
     *
     * @design ADR-068
     * @design ERD-012
     */
    public static final String SRC_TYPE_PORTAL_DATASET = "PORTAL_DATASET";

    /**
     * <b>포털 채널 출처 집합</b> — 관제 채널 작업 범위에서 빼야 하는 출처 값의 단일 원천(ADR-058 · ADR-068).
     *
     * <p>본인 업로드({@link #SRC_TYPE_PORTAL_ULD})와 데이터셋 소재({@link #SRC_TYPE_PORTAL_DATASET})
     * 둘이다. 가시 범위 술어({@code InternalWorkScope})가 이 집합으로 판정한다.
     *
     * <p>⚠ JPQL 조각은 애너테이션에 이어 붙이는 <b>컴파일 타임 상수</b>여야 해 이 집합을 쓸 수 없고
     * 두 상수를 직접 이어 붙인다 — 그 조각이 이 집합의 원소를 빠짐없이 담는지는 시험이 대조한다.
     *
     * @design ADR-068
     */
    public static final List<String> PORTAL_CHANNEL_SRC_TYPES =
            List.of(SRC_TYPE_PORTAL_ULD, SRC_TYPE_PORTAL_DATASET);

    public static final String STATUS_PENDING = "PENDING";

    /**
     * 적재 직후 자동 비식별이 성공해 마킹 단계로 진입 가능한 상태 (Phase 2).
     * <p>흐름: PENDING → (선두 비식별 성공) MARKING_READY → (마킹완료→배치) COMPLETED.
     * marking-ready 신호는 LS_RAW_DATA_STATUS 가 아닌 본 LS_DATA_RAW.DATA_STTS_CD 에 둔다
     * (작업 상태 row 는 배정 시점 lazy 생성이라 적재 직후 전이 불가).
     */
    public static final String DATA_STTS_MARKING_READY = "MARKING_READY";

    /**
     * 마킹 완료로 트리거된 배치가 진행 중인 상태 (Bug 2 — '처리중' 도입).
     * <p>흐름: MARKING_READY → (배치 시작) PROCESSING → (성공) COMPLETED / (실패) FAILED.
     * 이 상태가 없으면 마킹 완료~배치 완료 구간 내내 MARKING_READY("마킹 대기")로 남아
     * 사용자가 "마킹 안 됨"으로 오인한다.
     */
    public static final String DATA_STTS_PROCESSING = "PROCESSING";

    /** 배치 완료(성공) — 배치 단계 종결 상태. */
    public static final String DATA_STTS_COMPLETED = "COMPLETED";

    /**
     * 배치 실패 상태 (Bug 2 — MARKING_READY 고착 방지).
     * <p>배치 실패 시 LS_DATA_RAW.DATA_STTS_CD 가 MARKING_READY 로 남아 영구 "마킹 대기"로
     * 고착되던 결함을 막기 위해 도입. PROCESSING → FAILED 로 전이한다.
     */
    public static final String DATA_STTS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RAW_SN")
    private Long rawSn;

    /**
     * 영상 식별자 — {@code UK_LS_DATA_RAW_VMS_CLIP UNIQUE}(V2). 파생영상은 부모 값에 파생 마커를 덧붙여
     * 만든다({@link #createFromAugment} / {@link #createFromResolution}).
     */
    @Column(name = "VMS_CLIP_ID", nullable = false, length = 128)
    private String vmsClipId;

    /**
     * {@code VMS_CLIP_ID} 컬럼 길이 — 파생 식별자 조립 시 이 상한을 넘기지 않는다(V2 스키마와 동일 값).
     *
     * <p>외부 산출물 이관도 이 컬럼에 자기 식별자를 조립해 넣으므로 같은 상한을 <b>참조</b>한다
     * ({@code ImportPathPolicy}). 상한을 두 곳에 적으면 한쪽만 갱신돼 한쪽 경로만 적재가 깨진다.
     */
    public static final int VMS_CLIP_ID_MAX = 128;

    /**
     * VMS CCTV 아이디 — 인입({@code LS_DATA_INGEST.VMS_CCTV_ID}) 복사값. <b>NULL 가능</b>
     * (V185 · {@code @design ERD-012}).
     *
     * <p>관제서버팀 회신(2026-08-12): CCTV 식별자가 없는 영상(수동 업로드 등)이 존재한다. 인입만
     * NULL 을 허용하면 적재가 여기서 제약 위반으로 터지므로 <b>두 테이블을 함께</b> 풀었다.
     *
     * <p>화면 표시명은 CCTV명 → 이 값 → {@code 영상 #{rawSn}} 순으로 폴백한다
     * ({@code CctvDisplayNamePolicy} — 이 값이 비어도 화면이 빈칸을 그리지 않는다).
     */
    @Column(name = "VMS_CCTV_ID", length = 64)
    private String vmsCctvId;

    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    @Column(name = "LCLGV_CD", length = 20)
    private String lclgvCd;

    @Column(name = "PRVC_TYPE_CD", nullable = false, length = 16)
    private String prvcTypeCd;

    @Column(name = "PRVC_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcYn;

    @Column(name = "DE_IDENT_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String deIdntfYn;

    @Column(name = "RAW_FILE_PATH_NM", nullable = false, length = 500)
    private String rawFilePathNm;

    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;

    @Column(name = "VDO_LEN_SEC")
    private Integer durationSec;

    /**
     * 영상길이(밀리초) — {@link #durationSec} 의 정밀도 짝. 초 컬럼이 정수 도메인이라 소수 이하가
     * 잘리는데, 그 손실을 되찾을 자리가 이 컬럼이다(V1 이 만들어 뒀으나 매핑이 없어 비어 있었다).
     *
     * <p>지금 채우는 통로는 <b>포털 업로드 자산</b> 하나다(ADR-058) — 화면이 영상 길이를 소수로
     * 표시하고 프레임 추출 간격 계산이 그 값을 쓰므로 초 반올림만으로는 부족하다. 관제 인입 축은
     * 여전히 비어 있고, 그쪽의 밀리초는 기술메타 {@code video.duration_ms} 가 따로 갖는다.
     *
     * <p>⚠ 이 컬럼을 관제 축의 두 번째 진실원으로 만들지 말 것 — 읽는 쪽은 <b>포털 자산</b>에서만
     * 이 값을 보고, 비어 있으면 초 컬럼으로 떨어진다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    @Column(name = "VDO_LEN_MS")
    private Long durationMs;

    @Column(name = "ORGNL_RAW_SN")
    private Long orgnlRawSn;

    /**
     * 출처유형 (V148, {@code ORIGINAL}/{@code RELAY}/{@code USER_ULD}/{@code GENERATED}/{@code AUGMENTED}).
     *
     * <p>경계축은 <b>"관제가 만들었나 / 저작도구가 만들었나"</b>이며 외부 위탁 여부가 아니다. 관제 인입분은
     * {@code LS_DATA_INGEST.SRC_TYPE} 을 그대로 복사한다(허용값 검증은 적재 경로가 수행 —
     * {@code TrainingVideoIngestTx}). 백필 전 기존 행은 null 이다.
     */
    @Column(name = "SRC_TYPE", length = 20)
    private String srcType;

    /**
     * 증강유형코드 (V148, {@code WINTER}/{@code NIGHT}/{@code RAIN}/{@code RESL_1080P}/{@code RESL_720P}/
     * {@code RESL_480P}). 값 체계는 {@code LS_DATA_AUG.AUG_TYPE_CD} 와 동일하며 <b>원본 영상은 null</b> 이다.
     *
     * <p>파생영상이 <b>자기 종류를 직접 보유</b>하는 <b>판별 단일 원천</b>이다 — 구 {@code VMS_CLIP_ID}
     * 마커 역파싱(구 {@code video/util/AugTypeParser}, 제거됨)을 대체한다. {@code LS_DATA_AUG} 에는
     * 파생 RAW 연결 컬럼이 없어 파생 RAW → 종류 역참조가 불가능하다.
     * 과거 행은 V149 백필이 채우고, 신규 행은 파생 생성 팩토리
     * ({@link #createFromAugment} / {@link #createFromResolution})가 생성 시점에 채운다.
     */
    @Column(name = "AUG_TYPE_CD", length = 20)
    private String augTypeCd;

    /**
     * 촬영 날씨(작업자 수동입력, V130). null = 미입력(조회 시 파생 폴백 대상).
     * <p>값 변경 로직·수동입력 API 는 후속 Phase — 본 Phase 는 스키마+매핑만 담당한다.
     * <p>길이 20 = 표준도메인 '명V20'(명 계열에 32 크기 도메인은 존재하지 않음).
     */
    @Column(name = "WTHR_NM", length = 20)
    private String wthrNm;

    /** 촬영 시간대 코드 주간/야간(작업자 수동입력, V130). null = 미입력(파생 폴백 대상). */
    @Column(name = "DAY_NGT_CD", length = 20)
    private String dayNgtCd;

    /** 촬영 계절 코드(작업자 수동입력, V130). null = 미입력(파생 폴백 대상). */
    @Column(name = "SESN_CD", length = 20)
    private String sesnCd;

    /**
     * 영상 익명정보 포함여부(검수자/작업자 수동입력, V163). null = 미입력.
     *
     * <p>학습데이터 export JSON 의 <b>video 블록</b> 개인정보 3필드 원천이다
     * ({@code ExportPrivacyPolicy} — 비식별 산출물만 판정하며 미입력이면 기본상수 프리필).
     * 프레임 단위 대응 컬럼은 {@code LS_DATA_SRC.ANONY_INCL_YN}(V130) 이며 <b>입도가 다른 별개 축</b>이다.
     * <p>여부(YN) 도메인은 프로젝트 표준(V85·공공 여부C1) CHAR(1) — {@code @JdbcTypeCode(CHAR)}.
     */
    @Column(name = "ANONY_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String anonyInclYn;

    /** 영상 가명정보 포함여부(검수자/작업자 수동입력, V163). null = 미입력. */
    @Column(name = "PSDO_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String psdoInclYn;

    /** 영상 개인정보 포함여부(검수자/작업자 수동입력, V163). null = 미입력. */
    @Column(name = "PRVC_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcInclYn;

    /**
     * 포털사용자번호 (V28) — 이 영상이 <b>포털 사용자 본인 업로드 자산</b>일 때만 채워지는 소유자 키.
     * 관제 인입 영상에는 소유자 개념이 없어 <b>비어 있다</b>(원장 안에서 항상 부분적으로만 채워진
     * 컬럼 하나가 생기는 것은 ADR-058 이 인지·수용한 대가다).
     *
     * <p>포털 경로의 인가는 전적으로 이 값 기반이다 — 본인 자산만 조회·수정·내려받기. 값이 비어 있으면
     * 그 행은 포털 자산이 아니므로, 소유자 스코프 조회는 이 컬럼의 <b>일치</b>를 조건으로 삼아야 하고
     * "null 이면 통과" 같은 완화를 두면 관제 영상이 포털 채널로 샌다.
     *
     * <p>자료형이 숫자가 아닌 이유는 포털이 발급한 토큰의 주체 식별자가 숫자가 아니기 때문이다.
     * 폭 100 은 공통표준도메인 번호V100 이며 {@code LS_MARKING.REG_USER_NO}(V27)와 같다.
     * 물리명은 사업표준용어 등록분({@code 포털사용자번호 / PORTAL_USER_NO})이다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    @Column(name = "PORTAL_USER_NO", length = 100)
    private String portalUserNo;

    @Column(name = "DATA_STTS_CD", nullable = false, length = 20)
    private String dataSttsCd;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    @Builder
    private LsDataRaw(String vmsClipId, String vmsCctvId, String evntTypeCd, String lclgvCd,
                      String prvcTypeCd, String rawFilePathNm, LocalDateTime shtDt, Integer durationSec,
                      String srcType) {
        this.vmsClipId = vmsClipId;
        this.vmsCctvId = vmsCctvId;
        this.evntTypeCd = evntTypeCd;
        this.lclgvCd = lclgvCd;
        this.prvcTypeCd = prvcTypeCd;
        this.prvcYn = derivePrvcYn(prvcTypeCd);
        this.deIdntfYn = "N";
        this.rawFilePathNm = rawFilePathNm;
        this.shtDt = shtDt;
        this.durationSec = durationSec;
        this.srcType = srcType;
        // 비식별 축 개인정보 3필드 적재 기본값 (2026-08-04) — 상수 javadoc 참조.
        //   빌더가 이 3필드를 인자로 받지 않으므로 여기가 <유일한 적재 지점>이다(누락 불가).
        this.anonyInclYn = DEID_ANONYMITY_ON_INSERT;
        this.psdoInclYn = DEID_PSEUDONYMITY_ON_INSERT;
        this.prvcInclYn = DEID_PRIVACY_INCLUDED_ON_INSERT;
        this.dataSttsCd = STATUS_PENDING;
        this.regDt = LocalDateTime.now();
    }

    /** 출처유형 미상 적재(포털 업로드·개발 시드 등 기존 호출부). {@code SRC_TYPE} 은 null 로 남는다. */
    public static LsDataRaw createFromIngest(String vmsClipId, String vmsCctvId, String evntTypeCd,
                                              String lclgvCd, String prvcTypeCd, String rawFilePathNm,
                                              LocalDateTime shtDt, Integer durationSec) {
        return createFromIngest(vmsClipId, vmsCctvId, evntTypeCd, lclgvCd, prvcTypeCd,
                rawFilePathNm, shtDt, durationSec, null);
    }

    /**
     * 관제 인입({@code LS_DATA_INGEST}) 적재 — 출처유형까지 함께 보유한다.
     *
     * <p>{@code srcType} 은 인입 행의 값을 <b>허용값 검증 후</b> 그대로 복사한 것이다(검증 주체는
     * {@code TrainingVideoIngestTx} — 관제 수신값은 신뢰 경계 밖이라 미지의 값이 작업 테이블의
     * 분기축으로 들어오면 안 된다). 검증에 걸리면 null 로 적재된다(fail-closed).
     */
    public static LsDataRaw createFromIngest(String vmsClipId, String vmsCctvId, String evntTypeCd,
                                              String lclgvCd, String prvcTypeCd, String rawFilePathNm,
                                              LocalDateTime shtDt, Integer durationSec, String srcType) {
        return LsDataRaw.builder()
                .vmsClipId(vmsClipId)
                .vmsCctvId(vmsCctvId)
                .evntTypeCd(evntTypeCd)
                .lclgvCd(lclgvCd)
                .prvcTypeCd(prvcTypeCd)
                .rawFilePathNm(rawFilePathNm)
                .shtDt(shtDt)
                .durationSec(durationSec)
                .srcType(srcType)
                // 비식별 축 개인정보 3필드는 @Builder 생성자가 적재 기본값으로 채운다(아래 상수 javadoc).
                .build();
    }

    /**
     * ★ 비식별 축 개인정보 3필드 <b>적재 기본값</b> (2026-08-04 사용자 확정) — 영상 생성 시
     * {@code 익명 Y / 가명 N / 개인정보 N} 을 <b>실제 값으로 INSERT</b> 하고, 이후 <b>라벨링 화면</b>
     * ({@code PUT /v1/videos/{rawSn}/privacy-meta})에서 수정한다. 값의 단일 원천은
     * {@code ExportPrivacyPolicy.DEID_DEFAULT_*} 이며 여기서 <b>참조</b>만 한다(상수 복제 금지).
     *
     * <p><b>왜 DB 컬럼 DEFAULT 가 아니라 팩토리인가</b>: 이 엔티티에는 {@code @DynamicInsert} 가 없어
     * Hibernate 가 모든 컬럼을 명시적으로 INSERT 하므로(값이 없으면 명시적 NULL) DB DEFAULT 는 주
     * 적재 경로에서 <b>절대 적용되지 않는다</b>. 반면 관제 인입 축({@code LS_DATA_INGEST}, V170)은
     * 관제가 우리 코드를 거치지 않고 직접 INSERT 하므로 DB DEFAULT 가 유일한 수단이다.
     *
     * <p>⚠ 값이 항상 실재하므로 <b>"사람이 Y 로 판정함"과 "적재 기본값"이 구분되지 않는다</b>
     * (사용자 인지·수용 — 되돌리지 말 것). {@code null} 이 남는 경로는 <b>레거시 기존 행 하나뿐</b>
     * 이며, 그래서 {@code ExportPrivacyPolicy} 의 프리필 상수를 제거하지 않고 유지한다.
     * (구 서술의 "② 비식별 누락 신고 리셋" 경로는 <b>폐기</b>됐다 — 2026-08-04 사용자 확정으로 신고가
     * 개인정보 3필드를 리셋하지 않는다. 경위는 {@code DeidentReportService} 참조.)
     */
    private static final String DEID_ANONYMITY_ON_INSERT =
            kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY;
    private static final String DEID_PSEUDONYMITY_ON_INSERT =
            kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY;
    private static final String DEID_PRIVACY_INCLUDED_ON_INSERT =
            kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED;

    /**
     * V2.0 증강 결과 수신 시 새 영상 생성. 원본 메타를 계승하되 PENDING 상태로 시작.
     *
     * <h3>식별자는 <b>시각이 아니라 증강 행 PK({@code dataAugSn})</b>로 유일화한다 (2026-07-31)</h3>
     * <p>구 구현은 {@code {부모}_AUG_{종류}_{System.currentTimeMillis()}} 였다. 같은 (영상 × 종류)
     * 재요청이 <b>정책적으로 허용</b>되면서(2026-07-31) 두 콜백이 <b>같은 밀리초</b>에 도달하면 식별자가
     * 충돌해 {@code UK_LS_DATA_RAW_VMS_CLIP} 위반 → 콜백 트랜잭션 롤백 → 파생 미생성 + 증강행 PENDING
     * 잔류 → 만료 스윕 FAILED 로 <b>이미 만들어진 외부 결과물이 유실</b>된다(2노드 Active-Active +
     * 벤더 동시 콜백에서 실재). {@code dataAugSn} 은 요청 1건마다 IDENTITY 로 발급되고 파생 생성은
     * 증강 행당 1회(행 잠금 + non-PENDING 멱등 skip)이므로 <b>결정적으로 유일</b>하다.
     *
     * <p>포맷 {@code {부모}_AUG_{종류}_{접미}} 는 그대로라 데이터마트 뷰·동결 메타(문자열 passthrough)는
     * 영향받지 않는다. 증강 종류 판별은 {@code VMS_CLIP_ID} 가 아니라 아래에서 함께 확정하는
     * {@code AUG_TYPE_CD} 컬럼이 단일 원천이므로(구 마커 역파서는 제거됨) 접미 변경과 무관하다.
     *
     * <p>촬영환경(날씨·시간대·계절) 수동값도 함께 계승한다 — 증강은 <b>같은 영상 소스</b>의 파생물이라
     * 촬영 당시 환경이 동일하다. 복사하지 않으면 부모는 수동값(예: 실내/터널이라 NGT)으로 동결되고
     * 파생본만 촬영일시 파생값(DAY)으로 동결돼 같은 소스의 export 가 서로 어긋난다.
     * 복사는 이 팩토리 내부에서만 수행하고 빌더/setter 를 외부에 노출하지 않는다(CWE-915 방어 유지).
     *
     * <p><b>영상 단위 개인정보 수동값(V163) 도 같은 지점에서 함께 계승</b>한다
     * ({@link #copyPrivacyMetaFrom}) — 프레임 축은 이미 복사되는데 영상 축만 빠지면 같은 문서에서
     * {@code image="Y"} / {@code video="N"} 로 갈려 개인정보가 <b>과소 신고</b>된다.
     *
     * <p><b>출처유형·증강종류를 생성 시점에 확정한다</b>(V149 짝): {@code SRC_TYPE='AUGMENTED'} +
     * {@code AUG_TYPE_CD=augType}. 이 배선이 없으면 백필(과거 행) 이후 생성되는 <b>신규 파생이 영구
     * NULL</b> 로 남는다(마커 역파서가 제거돼 재계산 소스도 없다). 값은 {@code VMS_CLIP_ID} 에 심는
     * 마커와 같은 문자열이라 백필된 과거 행과 값 체계가 일치한다.
     *
     * @param dataAugSn 이 파생을 만든 증강 행 PK({@code LS_DATA_AUG.DATA_AUG_SN}) — 식별자 유일성의 근거
     */
    public static LsDataRaw createFromAugment(LsDataRaw parent, String rawFilePathNm, String augType,
                                              long dataAugSn) {
        LsDataRaw raw = new LsDataRaw();
        raw.vmsClipId = derivativeClipId(parent.getVmsClipId(), "_AUG_", augType, dataAugSn);
        raw.vmsCctvId = parent.getVmsCctvId();
        raw.evntTypeCd = parent.getEvntTypeCd();
        raw.lclgvCd = parent.getLclgvCd();
        raw.prvcTypeCd = parent.getPrvcTypeCd();
        raw.prvcYn = derivePrvcYn(parent.getPrvcTypeCd());
        raw.deIdntfYn = "N";
        raw.rawFilePathNm = rawFilePathNm;
        raw.shtDt = parent.getShtDt();
        raw.durationSec = parent.getDurationSec();
        raw.orgnlRawSn = parent.getRawSn();
        raw.srcType = SRC_TYPE_AUGMENTED;
        raw.augTypeCd = augType;
        raw.copyShootingEnvironmentFrom(parent);
        raw.copyPrivacyMetaFrom(parent);
        raw.dataSttsCd = STATUS_PENDING;
        raw.regDt = LocalDateTime.now();
        return raw;
    }

    /**
     * Phase 2 (해상도 파생영상) — 원본(APPROVED·비식별) 영상에서 목표 해상도의 새 파생영상을 생성한다.
     * 원본 메타를 계승하되 PENDING 으로 시작하고 ORGNL_RAW_SN 으로 원본을 참조한다.
     *
     * <p>{@code createFromAugment} 와 공통 골격이나 구분점:
     * <ul>
     *   <li>VMS_CLIP_ID = 원본 + {@code "_RESL_"} + 목표 해상도 코드 + 타임스탬프 (UNIQUE 보장)</li>
     *   <li>비식별 계승 — 원본이 비식별 완료('Y')된 영상만 파생 대상이므로 파생본도 산출 확정 시 'Y' 로 마감된다.
     *       생성 시점 기본값은 'N'(추출/복사 성공 전까지 스트리밍/마킹 진입 차단, {@code createFromAugment} 동일).</li>
     * </ul>
     *
     * <p>촬영환경(날씨·시간대·계절) 수동값은 {@code createFromAugment} 와 동일하게 계승한다 —
     * 해상도만 다른 같은 영상 소스라 촬영 당시 환경이 동일하기 때문이다. <b>영상 단위 개인정보 수동값
     * (V163) 도 동일하게 계승</b>한다({@link #copyPrivacyMetaFrom} — 리스케일은 픽셀만 바꾸므로 개인정보
     * 잔존 여부라는 사실 자체는 부모와 같다).
     *
     * <p>{@code createFromAugment} 와 동일하게 출처유형·증강종류를 생성 시점에 확정한다(V149 짝):
     * {@code SRC_TYPE='AUGMENTED'} + {@code AUG_TYPE_CD=goalResCd}(= {@code ResolutionPreset.name()},
     * {@code RESL_} 접두 포함). 해상도 파생도 저장모델상 증강과 통합돼 있으므로 종류 값 체계가 같다.
     *
     * @param parent        원본 RAW (검수완료·비식별, ORGNL_RAW_SN=null)
     * @param rawFilePathNm 파생영상(비식별 비디오 복사본) 파일 경로
     * @param goalResCd     목표 해상도 코드 (예: RESL_720P)
     */
    public static LsDataRaw createFromResolution(LsDataRaw parent, String rawFilePathNm, String goalResCd) {
        LsDataRaw raw = new LsDataRaw();
        raw.vmsClipId = parent.getVmsClipId() + "_RESL_" + goalResCd + "_" + System.currentTimeMillis();
        raw.vmsCctvId = parent.getVmsCctvId();
        raw.evntTypeCd = parent.getEvntTypeCd();
        raw.lclgvCd = parent.getLclgvCd();
        raw.prvcTypeCd = parent.getPrvcTypeCd();
        raw.prvcYn = derivePrvcYn(parent.getPrvcTypeCd());
        raw.deIdntfYn = "N";
        raw.rawFilePathNm = rawFilePathNm;
        raw.shtDt = parent.getShtDt();
        raw.durationSec = parent.getDurationSec();
        raw.orgnlRawSn = parent.getRawSn();
        raw.srcType = SRC_TYPE_AUGMENTED;
        raw.augTypeCd = goalResCd;
        raw.copyShootingEnvironmentFrom(parent);
        raw.copyPrivacyMetaFrom(parent);
        raw.dataSttsCd = STATUS_PENDING;
        raw.regDt = LocalDateTime.now();
        return raw;
    }

    /**
     * <b>외부 산출물 이관</b> 적재 — 외부에서 이미 라벨링이 끝난 상태로 가져온 영상(ADR-048).
     *
     * <h3>왜 {@link #createFromIngest} 를 쓸 수 없는가</h3>
     * <p>그 팩토리는 {@code DE_IDENT_YN} 을 {@code 'N'}(미수행)으로 <b>내부에서 고정</b>한다. 이관은
     * 가져올 때 사람이 지정한 값에 따라 <b>이미 비식별이 끝난 영상</b>일 수 있고, 그 경우 미수행으로
     * 적재하면 이미 비식별된 영상에 비식별을 다시 태우거나 스트리밍이 막힌다. 그래서 이 경로만
     * 그 값을 인자로 받는다.
     *
     * <h3>비식별 여부는 "무엇을 받았는가"가 정한다</h3>
     * <ul>
     *   <li>비식별이 끝난 것으로 지정해 받았으면 <b>그 영상이 곧 비식별 영상</b>이다 —
     *       {@code 'Y'}(성공)로 적재하고 저작도구는 비식별 단계를 태우지 않는다. 이때
     *       <b>원본은 우리에게 없으므로</b> {@code rawFilePathNm} 에는 자기 비식별 사본 경로를 넣고
     *       원본 경로 폴백을 두지 않는다(파생영상과 같은 형태).</li>
     *   <li>원본으로 받았으면 {@code 'N'}(미수행)으로 적재한다. 영상 파일을 함께 받았으면 저작도구가
     *       비식별 단계를 태우고, 파일이 없어 프레임만 받았으면 외부 비식별 산출물을 받아 기록하는
     *       별도 행위가 뒤를 잇는다.</li>
     * </ul>
     * <p>어느 쪽이든 <b>검수 승인을 붙잡아 두는 값은 이 컬럼이 아니다</b> —
     * {@code LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN} 이 그 일을 한다. 이 컬럼의
     * {@code 'F'}(비식별 누락 신고)는 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성을
     * 함께 닫으므로, 승인만 막으려는 의도를 여기 실으면 의도보다 넓게 닫힌다(ADR-048).
     *
     * <h3>{@code rawFilePathNm} 은 비울 수 없다</h3>
     * <p>비식별 산출물과 학습데이터 산출물의 저장 위치가 이 값의 디렉터리 부분에서 파생되고,
     * 학습데이터 산출물의 영상 파일명이 이 경로의 마지막 이름에서 나온다. 조립은
     * {@code ImportPathPolicy} 한 곳에서 한다.
     *
     * <h3>출처유형</h3>
     * <p>{@link #SRC_TYPE_IMPORTED} 를 넣는다. 생성형AI여부는 이 값과 무관하게 {@code N} 이므로
     * ({@link #genAiYnOf}) 관제 계약과 데이터마트 뷰가 달라지지 않는다.
     *
     * @param lclgvCd      산출물이 준 법정동코드({@code video.stdg_cd})
     * @param deidentified 가져올 때 사람이 지정한 값 — {@code true} 면 준 영상이 곧 비식별 영상이다
     * @design DOMAIN-017
     * @design ERD-031
     * @design ADR-048
     */
    public static LsDataRaw createFromImport(String vmsClipId, String evntTypeCd, String lclgvCd,
                                             String prvcTypeCd, String rawFilePathNm,
                                             LocalDateTime shtDt, Integer durationSec,
                                             boolean deidentified) {
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            // 비우면 비식별·산출물 저장 위치 도출이 입력 오류로 끝나고, 산출물 생성이 예외 없이
            // 실패로만 마감돼 밖에서 원인을 알 수 없다. 입구에서 막는다.
            throw new IllegalArgumentException("이관 영상 파일 경로는 비울 수 없습니다.");
        }
        LsDataRaw raw = LsDataRaw.builder()
                .vmsClipId(vmsClipId)
                .evntTypeCd(evntTypeCd)
                .lclgvCd(lclgvCd)
                .prvcTypeCd(prvcTypeCd)
                .rawFilePathNm(rawFilePathNm)
                .shtDt(shtDt)
                .durationSec(durationSec)
                .srcType(SRC_TYPE_IMPORTED)
                .build();
        if (deidentified) {
            raw.deIdntfYn = "Y";
        }
        return raw;
    }

    /**
     * <b>외부 마킹 산출물 일괄 가져오기</b> 적재 — 외부가 이벤트 시점까지만 찍어 준 영상(ADR-053).
     *
     * <h3>{@link #createFromImport} 와 무엇이 다른가</h3>
     * <p>같은 이관 화면의 <b>다른 갈래</b>다. 그쪽은 라벨링이 끝난 결과를 받아 검수만 하지만 이쪽은
     * 시작점만 받아 비식별부터 라벨링까지 앞 단계를 전부 밟는다. 계약을 합치지 않는 이유는 ADR-053 에
     * 있고, 팩토리를 나누는 이유는 <b>인자가 다르기</b> 때문이다.
     * <ul>
     *   <li><b>{@code vmsCctvId} 를 받는다</b> — 이 갈래는 사람이 화면에서 카메라 식별자를 지정해
     *       항목마다 같은 값으로 붙인다. 라벨링 완료 갈래에는 그 입력이 없다.</li>
     *   <li><b>비식별 여부가 분기하지 않는다</b> — 받은 영상은 <b>언제나 비식별되지 않은 원본</b>이라
     *       {@code DE_IDENT_YN} 은 {@code 'N'} 으로 고정이고, 적재 뒤 저작도구가 비식별 선두 단계를
     *       태운다(EVT-005 의 "원본이라고 지정하고 영상 파일을 함께 가져온 산출물" 예외).
     *       그래서 {@code deidentified} 인자 자체를 두지 않는다 — 있으면 {@code true} 를 넘길 길이
     *       생기고, 그 순간 비식별되지 않은 원본이 비식별 완료로 적재된다(CWE-359).</li>
     *   <li><b>{@code durationSec} 를 받지 않는다</b> — 이 경로는 영상 파일을 실제로 갖고 있어
     *       적재 직후 발행하는 {@code VideoIngestedEvent} 가 기술메타 추출을 태우고, 그 결과가
     *       {@code VDO_LEN_SEC} 를 back-fill 한다({@code VideoMetaService}). 사람이 지정하지 않는
     *       값을 인자로 두면 호출부가 무엇이든 채워 넣게 되고 실측값과 갈린다.</li>
     * </ul>
     *
     * <h3>{@code rawFilePathNm} 은 <b>이미 저작도구 저장소로 복사된</b> 위치다</h3>
     * <p>복사는 이관 쪽이 하고 이 팩토리는 그 결과 위치만 받는다. 외부 폴더를 그대로 가리키면 비식별본과
     * 프레임과 학습데이터 산출물이 남의 폴더 옆에 쌓이고 그 폴더가 치워지면 영상이 깨진다(ADR-053).
     * 비울 수 없는 이유는 {@link #createFromImport} 와 같다.
     *
     * <h3>출처유형은 {@link #SRC_TYPE_IMPORTED} 다 — 값을 새로 만들지 않는다</h3>
     * <p>두 갈래 모두 "외부에서 가져왔다"는 같은 사실을 말한다. 갈래를 가르는 값을 하나 더 만들면
     * 이관을 판별하는 모든 자리가 두 값을 열거해야 하고, 한 곳만 잊으면 이 갈래의 영상이 조회·집계에서
     * 조용히 샌다({@link #SRC_TYPE_PORTAL_ULD} 가 파생물에 같은 값을 쓰는 것과 같은 이유).
     *
     * @param vmsClipId  영상 파일 이름에서 확장자를 뗀 값 — {@code UK_LS_DATA_RAW_VMS_CLIP} 로 중복
     *                   반입을 막는 실제 근거다
     * @param vmsCctvId  사람이 화면에서 지정한 카메라 식별자(선택)
     * @param evntTypeCd 사람이 화면에서 지정한 이벤트 유형 코드
     * @param lclgvCd    사람이 화면에서 지정한 지자체 코드
     * @param prvcTypeCd 사람이 화면에서 지정한 개인정보 유형({@code ANONY}|{@code PRVC}|{@code PSDO})
     * @param shtDt      사람이 화면에서 지정한 촬영일시(선택 — 미지정이면 {@code null}, 대용값 금지)
     * @design ADR-053
     * @design DFEAT-060
     * @design EVT-005
     */
    public static LsDataRaw createFromMarkingImport(String vmsClipId, String vmsCctvId,
                                                    String evntTypeCd, String lclgvCd,
                                                    String prvcTypeCd, String rawFilePathNm,
                                                    LocalDateTime shtDt) {
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            throw new IllegalArgumentException("마킹 이관 영상 파일 경로는 비울 수 없습니다.");
        }
        return LsDataRaw.builder()
                .vmsClipId(vmsClipId)
                .vmsCctvId(vmsCctvId)
                .evntTypeCd(evntTypeCd)
                .lclgvCd(lclgvCd)
                .prvcTypeCd(prvcTypeCd)
                .rawFilePathNm(rawFilePathNm)
                .shtDt(shtDt)
                .srcType(SRC_TYPE_IMPORTED)
                // DE_IDENT_YN 은 빌더 생성자가 'N'(미수행)으로 고정한다 — 이 경로는 언제나 원본이다.
                .build();
    }

    /**
     * 파생영상 {@code VMS_CLIP_ID} 조립 — {@code {부모}_{마커}{종류}_{유일접미}} 이며 결과는 항상
     * {@value #VMS_CLIP_ID_MAX}자 이하다.
     *
     * <p><b>왜 자르는가</b>: 컬럼이 {@code VARCHAR(128)} 인데 조립은 부모 값 길이에 비례해 늘어난다.
     * 파생본도 검수 승인되면 다시 증강 대상이 될 수 있어(파생의 파생) 마커가 누적되므로, 상한을 넘기면
     * 적재가 거부돼 콜백이 500 으로 끝난다. 넘칠 때만 <b>앞쪽(부모 부분)을 잘라</b> 접미를 보존한다.
     *
     * <p><b>잘라도 유일하다</b>: 유일성의 근거는 접미의 {@code uniqueSuffix}(증강 행 PK 등 전역 유일
     * 식별자)이지 부모 프리픽스가 아니다. 프리픽스는 사람이 읽을 때의 계보 힌트일 뿐이다.
     */
    private static String derivativeClipId(String parentClipId, String marker, String type,
                                           long uniqueSuffix) {
        String suffix = marker + type + "_" + uniqueSuffix;
        int room = VMS_CLIP_ID_MAX - suffix.length();
        String prefix = parentClipId == null ? "" : parentClipId;
        if (room <= 0) {
            // 접미만으로도 상한을 넘는 비정상 입력(종류 코드가 비정상적으로 긴 경우) — 뒤쪽을 남긴다.
            return suffix.substring(suffix.length() - VMS_CLIP_ID_MAX);
        }
        return (prefix.length() <= room ? prefix : prefix.substring(0, room)) + suffix;
    }

    /**
     * 이 영상이 <b>파생영상</b>(증강 · 해상도 변환본)인가 — 판정의 단일 원천.
     *
     * <p>파생 여부는 {@code ORGNL_RAW_SN}(부모 참조) 보유로만 정의된다. 이 컬럼은 파생 생성 팩토리
     * ({@link #createFromAugment} / {@link #createFromResolution})에서만 대입되고 이후 어떤 setter·벌크
     * UPDATE 로도 바뀌지 않는(생성 후 불변) 값이라, 이 술어는 영상의 <b>영구 속성</b>이다.
     *
     * <p><b>정책 결합점</b>: 비식별 누락 신고는 파생영상에서 접수하지 않는다(2026-07-29 사용자 확정 —
     * 파생본은 원본 비식별 산출물의 사본이라 재비식별 수단이 원본에만 있다. 반대로 <b>원본의 신고도
     * 파생에 영향을 주지 않는다</b> — 파생은 신고 체계 바깥의 독립 영상으로 다룬다). 그 판정을 호출부마다
     * {@code getOrgnlRawSn() != null} 로 재구현하면 정책이 갈라지므로 여기 한 곳으로 모은다
     * ({@code DeidentReportService.report} · {@code VideoDetailResponse.derivative} 참조).
     */
    public boolean isDerivative() {
        return this.orgnlRawSn != null;
    }

    // ------------------------------------------------------------------
    // 포털 업로드 자산 (ADR-058 흡수) — 값 규약과 생성/갱신 통로
    // ------------------------------------------------------------------

    /**
     * 포털 업로드 자산의 <b>클립 식별자 접두</b>. 최종 형태는 {@code PORTAL_ULD_{RAW_SN}} 이다.
     *
     * <p>{@code VMS_CLIP_ID} 는 NOT NULL + UNIQUE 인데 포털 업로드에는 관제 클립 개념이 없다. 그래서
     * <b>자기 원장 행의 기본키로 유일화</b>하는 선례(ADR-044 파생영상)를 따른다 — 시각 기반으로 만들면
     * 동시 적재가 같은 밀리초에 충돌해 자산이 유실된다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    public static final String PORTAL_ULD_CLIP_ID_PREFIX = "PORTAL_ULD_";

    /**
     * 포털 업로드 자산 적재 — <b>값 규약 셋</b>을 이 한 곳에서 채운다(ADR-058).
     *
     * <ul>
     *   <li>클립 식별자 — PK 가 확정된 뒤 {@link #assignPortalClipId()} 로 {@code PORTAL_ULD_{RAW_SN}}
     *       을 배정한다. 여기서는 <b>임시 유일값</b>을 넣어 NOT NULL·UNIQUE 를 통과시킨다.</li>
     *   <li>개인정보 유형 — {@link #PRVC_TYPE_UNKNOWN}(미상). ⚠ {@code ANONY} 를 쓰지 않는다 —
     *       그건 「비식별이 불필요하다」는 <b>판정 결과</b>인데 포털 자산에 그 판정을 한 적이 없다.
     *       하지 않은 처리를 했다고 적지 않는다.</li>
     *   <li>파일 경로 — 업로드된 파일의 저장 경로를 그대로 채운다.</li>
     * </ul>
     *
     * <p>출처 판별자는 {@link #SRC_TYPE_PORTAL_ULD} 이며 새 축이 아니라 기존 값역에 값 하나를 더한 것이다.
     * 소유자({@link #portalUserNo})는 <b>반드시</b> 채워진다 — 포털 인가가 전적으로 이 값 기반이고,
     * 보존기간 만료 자동 삭제의 판별자 셋 중 하나이기도 하다.
     *
     * <p>배치 단계({@code DATA_STTS_CD})는 빌더 기본값 {@code PENDING} 그대로 둔다. 포털은 자기
     * 파이프라인을 가지며 그 진행 상태는 메타 원장의 {@code portal.upload_status} 가 소유한다 —
     * 이 컬럼의 값역을 넓히는 안은 ADR-058 이 기각했다(두 값역이 {@code PROCESSING}·{@code FAILED} 를
     * 같은 이름으로 쓰는데 가리키는 파이프라인이 다르다).
     *
     * @param portalUserNo 포털 토큰 주체 — 비어 있으면 만들지 않는다(소유자 없는 포털 자산 금지)
     * @param filePathNm   업로드 파일 저장 경로
     * @design ADR-058
     * @design ERD-028
     */
    public static LsDataRaw createPortalUpload(String portalUserNo, String filePathNm) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new IllegalArgumentException("포털 업로드 자산은 소유자 없이 만들 수 없습니다.");
        }
        LsDataRaw raw = LsDataRaw.builder()
                // PK 확정 전이라 최종 식별자를 만들 수 없다 — 임시 유일값으로 제약만 통과시키고
                // assignPortalClipId() 가 곧바로 확정값으로 덮는다(같은 트랜잭션).
                .vmsClipId(PORTAL_ULD_CLIP_ID_PREFIX + java.util.UUID.randomUUID())
                .prvcTypeCd(PRVC_TYPE_UNKNOWN)
                .rawFilePathNm(filePathNm)
                .srcType(SRC_TYPE_PORTAL_ULD)
                .build();
        raw.portalUserNo = portalUserNo;
        return raw;
    }

    /**
     * 포털 업로드 자산의 클립 식별자를 <b>PK 확정 후</b> {@code PORTAL_ULD_{RAW_SN}} 으로 확정한다.
     * 포털 자산이 아니면 거부한다 — 관제 영상의 클립 식별자는 관제가 준 값이라 덮으면 인입 역참조가 끊긴다.
     *
     * @design ADR-058
     */
    public void assignPortalClipId() {
        if (!isPortalUpload()) {
            throw new IllegalStateException("포털 업로드 자산이 아닌 영상의 클립 식별자는 배정할 수 없습니다.");
        }
        if (this.rawSn == null) {
            throw new IllegalStateException("식별자가 확정되기 전에는 클립 식별자를 배정할 수 없습니다.");
        }
        this.vmsClipId = PORTAL_ULD_CLIP_ID_PREFIX + this.rawSn;
    }

    /**
     * 이 영상이 <b>포털 사용자 본인 업로드 자산</b>인가 — 채널 판별의 단일 지점.
     *
     * <p>판정축은 출처 유형 하나다. 소유자 보유는 <b>따로</b> 확인한다(둘을 한 메서드에 묶으면
     * 보존기간 삭제가 요구하는 「판별자 셋을 각각 건다」가 흐려진다).
     *
     * @design ADR-058
     */
    public boolean isPortalUpload() {
        return SRC_TYPE_PORTAL_ULD.equals(this.srcType);
    }

    /**
     * 포털 업로드 영상의 프로브 결과(길이)를 확정한다. 초는 반올림 정수({@code VDO_LEN_SEC}),
     * 밀리초 정밀도는 {@link #durationMs} 가 보존한다 — 자료형은 바꾸지 않는다(ERD-028).
     *
     * <p>포털 자산이 아니면 거부한다. 관제 영상의 길이는 인입·기술메타 축이 소유하며 여기서 덮으면
     * 두 번째 쓰기 통로가 된다.
     *
     * @param durationSeconds 프로브가 읽은 길이(초, 소수 허용). {@code null}·0 이하면 아무것도 하지 않는다
     * @design ADR-058
     * @design ERD-028
     */
    public void applyPortalVideoDuration(Double durationSeconds) {
        if (!isPortalUpload()) {
            throw new IllegalStateException("포털 업로드 자산이 아닌 영상의 길이는 여기서 확정하지 않습니다.");
        }
        if (durationSeconds == null || durationSeconds <= 0d || !Double.isFinite(durationSeconds)) {
            return;
        }
        this.durationSec = (int) Math.round(durationSeconds);
        this.durationMs = Math.round(durationSeconds * 1000d);
        this.mdfcnDt = LocalDateTime.now();
    }

    // ------------------------------------------------------------------
    // 포털 데이터셋 영상 (ADR-068) — 값 규약과 생성 통로
    // ------------------------------------------------------------------

    /**
     * 포털 데이터셋 영상의 <b>클립 식별자 접두</b>. 최종 형태는
     * {@code PORTAL_DATASET_{데이터셋 번호}_{영상 키}} 이며 조립은 {@link #portalDatasetClipId} 한 곳에서 한다.
     *
     * @design ADR-068
     * @design ERD-012
     */
    public static final String PORTAL_DATASET_CLIP_ID_PREFIX = "PORTAL_DATASET_";

    /**
     * 포털 데이터셋 영상의 클립 식별자를 조립한다 — {@code PORTAL_DATASET_{datasetId}_{videoKey}}.
     *
     * <p>★ <b>이 값이 등록의 멱등 키다</b>(ADR-068). {@code UK_LS_DATA_RAW_VMS_CLIP} 가 같은 데이터셋·같은
     * 영상의 재등록을 막으므로, 호출부는 이 값으로 먼저 조회해 있으면 건너뛰고 없으면
     * {@link #createPortalDataset} 로 만든다. 그래서 조립 규칙을 호출부에 두지 않는다 — 두 곳이 서로 다른
     * 모양을 만들면 조회는 못 찾고 INSERT 만 유일 제약에 걸린다.
     *
     * <p>파생영상 식별자({@link #derivativeClipId})와 달리 <b>넘쳐도 자르지 않고 거부</b>한다 — 자르면
     * 서로 다른 영상 키가 같은 식별자로 접혀 멱등 키가 다른 영상을 가리키게 된다. 파생은 유일 접미(PK)가
     * 유일성을 지키지만 여기는 영상 키 자체가 유일성의 근거다.
     *
     * @param datasetId 포털 데이터셋 번호
     * @param videoKey  해제본 안 영상 폴더 이름 — 비어 있으면 거부한다
     * @return 조립된 클립 식별자({@value #VMS_CLIP_ID_MAX}자 이하)
     * @throws IllegalArgumentException 영상 키가 비었거나 조립 결과가 컬럼 상한을 넘을 때
     * @design ADR-068
     * @design ERD-012
     */
    public static String portalDatasetClipId(long datasetId, String videoKey) {
        if (videoKey == null || videoKey.isBlank()) {
            throw new IllegalArgumentException("포털 데이터셋 영상 키는 비울 수 없습니다.");
        }
        String clipId = PORTAL_DATASET_CLIP_ID_PREFIX + datasetId + "_" + videoKey;
        if (clipId.length() > VMS_CLIP_ID_MAX) {
            throw new IllegalArgumentException("포털 데이터셋 영상 식별자가 허용 길이를 넘습니다.");
        }
        return clipId;
    }

    /**
     * <b>포털 데이터셋 영상</b> 적재 — 소재 해제본에서 등록하는 영상 1건(ADR-068). 값 규약을 이 한 곳에서 채운다.
     *
     * <ul>
     *   <li><b>출처</b> — {@link #SRC_TYPE_PORTAL_DATASET}.</li>
     *   <li><b>개인정보 유형</b> — {@link #PRVC_TYPE_UNKNOWN}(미상). ⚠ {@code ANONY} 를 쓰지 않는다 —
     *       그건 「비식별이 불필요하다」는 <b>판정 결과</b>인데 우리는 그 판정을 한 적이 없다
     *       ({@link #createPortalUpload} 와 같은 이유).</li>
     *   <li><b>비식별 여부</b> — {@code 'Y'}. 배포본의 프레임 이미지가 비식별본이기 때문이다.
     *       원본 이미지는 등록하지 않는다.</li>
     *   <li><b>소유자</b>({@code PORTAL_USER_NO}) — 비운다. 데이터셋은 한 사용자의 자산이 아니라 그
     *       데이터셋에 들어온 포털 사용자가 함께 쓰는 소재다. 그래서 보존기간 만료 자동 삭제(소유자 보유를
     *       판별자로 요구한다)에도 걸리지 않는다.</li>
     *   <li><b>원본 참조</b>({@code ORGNL_RAW_SN}) — 비운다. 파생이 아니다.</li>
     *   <li><b>배치 단계</b>({@code DATA_STTS_CD}) — 빌더 기본값 {@code PENDING} 그대로 둔다. 외부 이관
     *       적재({@link #createFromImport})와 같은 규약이다(ADR-048) — 배치는 원장 상태를 폴링하지 않고
     *       <b>적재 이벤트</b>로 시작하므로, 이 경로가 적재 이벤트를 발행하지 않는 한 파이프라인이 집어
     *       가지 않는다.</li>
     * </ul>
     *
     * <p>⚠ <b>검수 승인 상태를 꾸며 넣지 않는다</b> — 이 팩토리는 검수 워크플로 상태 행을 만들지 않고,
     * 호출부도 만들지 않는다. 만들면 관제 조회 뷰·통지·산출물 연동이 이 영상을 승인 영상으로 읽는다
     * (ADR-068). 포털 작업 허용 근거는 승인이 아니라 출처({@link #isPortalDataset()})다.
     *
     * @param vmsClipId     {@link #portalDatasetClipId} 로 조립한 값 — 그 접두가 아니면 거부한다
     *                      (관제 클립 식별자를 넘기면 인입 역참조와 멱등 키가 함께 어긋난다)
     * @param rawFilePathNm 해제본 안 그 영상 폴더의 위치 — 영상 파일이 배포본에 없어 원천 위치를
     *                      기록한다. 비울 수 없다(컬럼이 NOT NULL)
     * @throws IllegalArgumentException 식별자 접두가 맞지 않거나 경로가 비었을 때
     * @design ADR-068
     * @design ERD-012
     */
    public static LsDataRaw createPortalDataset(String vmsClipId, String rawFilePathNm) {
        if (vmsClipId == null || !vmsClipId.startsWith(PORTAL_DATASET_CLIP_ID_PREFIX)
                || vmsClipId.length() == PORTAL_DATASET_CLIP_ID_PREFIX.length()
                || vmsClipId.length() > VMS_CLIP_ID_MAX) {
            throw new IllegalArgumentException("포털 데이터셋 영상 식별자는 정해진 규칙으로 조립해야 합니다.");
        }
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            throw new IllegalArgumentException("포털 데이터셋 영상의 원천 위치는 비울 수 없습니다.");
        }
        LsDataRaw raw = LsDataRaw.builder()
                .vmsClipId(vmsClipId)
                .prvcTypeCd(PRVC_TYPE_UNKNOWN)
                .rawFilePathNm(rawFilePathNm)
                .srcType(SRC_TYPE_PORTAL_DATASET)
                .build();
        // 빌더 생성자는 비식별 여부를 'N'(미수행)으로 고정한다 — 배포본 이미지는 비식별본이라 덮는다.
        raw.deIdntfYn = "Y";
        return raw;
    }

    /**
     * 이 영상이 <b>포털 데이터셋 영상</b>인가 — 판정의 단일 지점(ADR-068).
     *
     * <p>포털 작업 가능 판정(「검수 승인 또는 이 출처」)이 이 헬퍼를 쓴다. 판별자 상수를 호출부에서
     * 다시 비교하지 않는다.
     *
     * @design ADR-068
     */
    public boolean isPortalDataset() {
        return SRC_TYPE_PORTAL_DATASET.equals(this.srcType);
    }

    /**
     * 생성형AI여부({@code Y}/{@code N}) 판정의 <b>단일 원천</b> — 판정축은 {@code SRC_TYPE} 하나다.
     *
     * <p>규칙(연동 규격서 §3-2): {@code SRC_TYPE IN ('GENERATED','AUGMENTED') → 'Y'}.
     * {@code GENERATED} 는 관제가 AI 로 제작해 인입한 영상이고, {@code AUGMENTED} 는 저작도구가 만든
     * 증강(WINTER/NIGHT/RAIN)·해상도 파생본이다.
     *
     * <p><b>미상({@code null})은 {@code 'N'}</b> 이다 — 관제 계약상 required 라 null 을 실을 수 없고,
     * 백필 전 레거시 행은 대부분 관제 인입 원본이다. 이는 "값을 지어내지 않는다"(D-ISSUE-41)의 예외가
     * 아니라 <b>판정식이 null 에서도 성립</b>하는 경우다(생성형 AI 산출물이라는 근거가 없으면 아니다).
     *
     * <p><b>같은 규칙이 SQL 로도 존재한다</b> — {@code V_COMPLETED_VIDEO.GEN_AI_YN}
     * ({@code V174__rebuild_completed_video_view.sql}, 설계결정 D2). 코드를 공유할 수 없으므로 두 판정이
     * 같은 값을 내는지는 {@code V174CompletedVideoViewContractIT} 가 실 DB 에서 대조해 고정한다 —
     * 한쪽만 바꾸면 그 테스트가 깨진다. <b>이 규칙을 호출부에 복제하지 말 것.</b>
     *
     * @param srcType {@code LS_DATA_RAW.SRC_TYPE}
     */
    public static String genAiYnOf(String srcType) {
        return SRC_TYPE_GENERATED.equals(srcType) || SRC_TYPE_AUGMENTED.equals(srcType) ? "Y" : "N";
    }

    /** 이 영상의 생성형AI여부({@code Y}/{@code N}) — {@link #genAiYnOf(String)} 규칙. */
    public String genAiYn() {
        return genAiYnOf(this.srcType);
    }

    /**
     * A-6 — 파생 RAW_SN 이 확정된 뒤 파생영상 파일 경로를 <b>파생별 고유 경로</b>로 배정한다
     * (예약 트랜잭션 전용). 경로 키에 파생 RAW_SN 이 들어가야 같은 (부모, 프리셋) 파생이 복수일 때
     * 파일 상호 덮어쓰기·공유 파일 오삭제가 발생하지 않는다.
     *
     * <p>파생({@code ORGNL_RAW_SN} non-null)에만 허용한다 — 원본 영상 경로는 어떤 경우에도 이 경로로
     * 바뀌어선 안 된다(원본 보존 원칙, {@code VideoRepository#updateDerivativeVideoPath} 와 동일 가드).
     */
    public void assignDerivativeVideoPath(String rawFilePathNm) {
        if (this.orgnlRawSn == null) {
            throw new IllegalStateException("파생 영상이 아닌 RAW 의 파일 경로는 배정할 수 없습니다.");
        }
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            throw new IllegalArgumentException("파생 영상 파일 경로가 비어 있습니다.");
        }
        this.rawFilePathNm = rawFilePathNm;
    }

    /**
     * 파생영상 생성 시 부모의 촬영환경 수동값 3필드를 복사한다(팩토리 전용 — 외부 노출 없음).
     * 부모가 미입력(null)이면 파생본도 null 이라 조회·동결 시 촬영일시 파생 폴백이 그대로 유지된다.
     * 부모 값은 이미 저장 시점에 화이트리스트 검증을 통과한 값이라 재검증하지 않는다.
     *
     * <p><b>스냅샷 시맨틱(의도, D — 문서화 전용)</b>: 복사는 <b>파생 생성 시점 1회</b>다. 파생본이 생성된
     * <b>이후</b> 부모의 촬영환경을 수정해도 파생본으로 <b>재전파하지 않는다</b>. 파생본은 생성 시점 부모 상태의
     * 독립 사본이며, 이후 부모·파생 각각 독립적으로 정정·검수될 수 있기 때문이다(별도 결정 사항 — 전파 구현 금지).
     *
     * <p><b>MEDIUM-4(파생 상태 필드 승격, 문서화 전용)</b>: 파생본의 촬영환경도 조회 시 수동값 우선 규칙을
     * 그대로 탄다. 파생본 화면에서 파생 프리필(DERIVED)을 그대로 되돌려 저장하면 DERIVED→MANUAL 승격이
     * 일어나므로, FE(Phase 4)는 파생 상태에서 사용자가 직접 고르지 않은 촬영환경 필드를 <b>null 로 전송</b>한다
     * (BE 변경 없이 현행 유지 — 확정 방침).
     */
    private void copyShootingEnvironmentFrom(LsDataRaw parent) {
        this.wthrNm = parent.getWthrNm();
        this.dayNgtCd = parent.getDayNgtCd();
        this.sesnCd = parent.getSesnCd();
    }

    /**
     * 파생영상 생성 시 부모의 <b>영상 단위 개인정보 수동값 3필드</b>(V163)를 복사한다(팩토리 전용).
     *
     * <p><b>왜 복사하는가</b> — {@link #copyShootingEnvironmentFrom} 과 <b>같은 근거</b>다: 복사하지 않으면
     * 같은 소스의 export 가 서로 어긋난다. 파생 프레임은 부모 프레임의 개인정보 수동값을 이미 복사받는데
     * ({@code AugmentExtractPersist} · {@code ResolutionPersistService} 의 프레임 축), 영상 축만 빠지면
     * 같은 {@code deid} 문서 안에서 {@code image="Y"} / {@code video="N"} 이 난다. 이는 정책이 정당화한
     * 방향("영상엔 있지만 이 프레임엔 없다")의 <b>역방향</b>이라 논리적으로 성립할 수 없는 조합이다
     * (부모가 "개인정보 잔존"으로 판정된 영상의 파생본이 영상 단위로는 "없음"이 되어 <b>과소 신고</b>된다).
     * 따라서 촬영환경 복사와 <b>같은 지점</b>에서 함께 복사해 두 축이 갈라지지 않게 한다.
     *
     * <p>부모가 미입력(null)이면 파생본도 null 이라 조회·export 시 비식별 기본상수 프리필이 그대로 유지된다.
     * 부모 값은 저장 시점에 이미 {@code Y}/{@code N} 화이트리스트를 통과한 값이라 재검증하지 않는다.
     *
     * <p><b>스냅샷 시맨틱</b>: 복사는 파생 생성 시점 1회이며, 이후 부모 값을 정정해도 파생본으로
     * 재전파하지 않는다({@link #copyShootingEnvironmentFrom} 과 동일 — 파생본은 독립적으로 정정·검수된다).
     */
    private void copyPrivacyMetaFrom(LsDataRaw parent) {
        // 부모가 미입력(= 이 변경 이전의 레거시 행)이면 적재 기본값으로 채운다 (2026-08-04) —
        // "값은 항상 실재한다" 규약을 파생에서도 유지한다. export 결과값은 프리필 상수와 같아
        // 산출물이 달라지지 않는다.
        this.anonyInclYn = orInsertDefault(parent.getAnonyInclYn(), DEID_ANONYMITY_ON_INSERT);
        this.psdoInclYn = orInsertDefault(parent.getPsdoInclYn(), DEID_PSEUDONYMITY_ON_INSERT);
        this.prvcInclYn = orInsertDefault(parent.getPrvcInclYn(), DEID_PRIVACY_INCLUDED_ON_INSERT);
    }

    /** 부모 계승값이 미입력(null/blank)이면 적재 기본값을 쓴다. */
    private static String orInsertDefault(String inherited, String insertDefault) {
        return (inherited == null || inherited.isBlank()) ? insertDefault : inherited;
    }

    /**
     * 관제서버로부터 동일 VMS_CLIP_ID 가 다시 송신되었을 때 변경 가능 메타만 갱신.
     * (RAW_SN, VMS_CLIP_ID 는 불변)
     */
    public void updateFromIngest(String vmsCctvId, String evntTypeCd, String lclgvCd, String prvcTypeCd,
                                  String rawFilePathNm, LocalDateTime shtDt, Integer durationSec) {
        this.vmsCctvId = vmsCctvId;
        this.evntTypeCd = evntTypeCd;
        this.lclgvCd = lclgvCd;
        this.prvcTypeCd = prvcTypeCd;
        this.prvcYn = derivePrvcYn(prvcTypeCd);
        this.rawFilePathNm = rawFilePathNm;
        this.shtDt = shtDt;
        this.durationSec = durationSec;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 비식별 처리가 필요한지 여부 (Phase 5).
     * - PRVC / PSDO 만 비식별 호출 대상. ANONY 는 원본 그대로 보존.
     */
    public boolean needsDeidentify() {
        return PRVC_TYPE_PRVC.equals(this.prvcTypeCd) || PRVC_TYPE_PSDO.equals(this.prvcTypeCd);
    }

    /**
     * <b>비식별 산출물(비식별 영상/프레임)이 이 영상에 존재할 수 있는가</b> — 파생영상(증강·해상도) 생성의
     * 물리적 전제 판정. 파생 생성 경로는 모두 이 헬퍼 하나만 쓴다(상수 문자열 비교를 흩지 않는다).
     *
     * <p><b>{@code 'F'} 는 의미가 둘이다</b>:
     * <ol>
     *   <li><b>비식별 누락 신고</b> — 비식별본은 디스크에 <b>존재</b>하고 마스킹만 실패한 상태.</li>
     *   <li><b>비식별 API 실패</b> — 비식별 산출물이 <b>아예 없음</b>.</li>
     * </ol>
     * 이 헬퍼는 1번을 통과시키기 위해 {@code 'F'} 를 포함한다("파생영상은 비식별 신고 체계 바깥"
     * 2026-07-29 확정 — 파생 생성은 원본 신고와 무관). 2번은 이 플래그만으로 구분할 수 없으므로
     * <b>산출물 경로/파일 실재 검증이 후속 단계에서 fail-closed</b> 로 걸러낸다
     * (Phase A: 최신 SUCCESS 비식별 procLog 경로 부재 → NOT_FOUND, 프레임 비식별 경로 부재 → CONFLICT,
     *  Phase B: 비식별 영상 파일 부재 → NOT_FOUND). 원본(비-비식별) 경로 폴백은 어디에도 없다.
     *
     * <p>{@code 'N'}(비식별 미수행)·null 은 산출물이 존재할 수 없으므로 false.
     */
    public boolean hasDeidentArtifact() {
        return "Y".equals(this.deIdntfYn) || "F".equals(this.deIdntfYn);
    }

    /**
     * 비식별 처리 결과를 마킹 (Phase 5).
     * - 'Y' = 성공 / 'F' = 실패 / 'N' = 미수행.
     * - 원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markDeidentified(String code) {
        if (code == null || (!"Y".equals(code) && !"F".equals(code) && !"N".equals(code))) {
            throw new IllegalArgumentException("DE_IDNTF_YN 은 Y/F/N 중 하나여야 합니다: " + code);
        }
        this.deIdntfYn = code;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 선두 비식별 성공 후 마킹 단계 진입 가능 상태로 전이 (Phase 2).
     * <p>원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markMarkingReady() {
        changeStatus(DATA_STTS_MARKING_READY);
    }

    /**
     * 마킹 완료로 트리거된 배치 시작 시 배치 단계 상태를 PROCESSING("처리중")으로 전이 (Bug 2).
     * <p>원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     *
     * <p><b>배치 진입에는 쓰지 말 것 (B-ISSUE-01)</b>: 이 mutator 는 현재 값을 판정하지 않는
     * read-modify-write 라 동일 rawSn 동시 진입을 막지 못한다(실측: 동시 5요청 → 파이프라인 5벌 병렬
     * 실행 + 외부 VLM 5중 위탁). 배치 진입 전이는 반드시 조건부 UPDATE
     * {@code VideoRepository#claimForProcessing}(= "PROCESSING 이 아닐 때만") 로 원자 클레임한다.
     */
    public void markProcessing() {
        changeStatus(DATA_STTS_PROCESSING);
    }

    /**
     * 배치 실패 시 배치 단계 상태를 FAILED("실패")로 전이 (Bug 2 — MARKING_READY 고착 방지).
     * <p>원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markBatchFailed() {
        changeStatus(DATA_STTS_FAILED);
    }

    /**
     * 배치 완료(성공) 시 배치 단계 상태를 COMPLETED("완료")로 전이.
     * <p>매직 스트링 제거 — {@link #DATA_STTS_COMPLETED} 상수를 사용하며,
     * {@code markProcessing()}/{@code markBatchFailed()} 와 동일한 도메인 메서드 형식으로 통일한다.
     * 원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markCompleted() {
        changeStatus(DATA_STTS_COMPLETED);
    }

    /**
     * 촬영환경(날씨·시간대·계절) 수동입력값을 <b>전체 교체</b>한다 (Phase 2, V130 3컬럼 전용).
     *
     * <p>빌더/setter 를 노출하지 않고 이 3필드만 바꾸는 전용 도메인 메서드를 둔다 — 영속 상태 엔티티의
     * dirty checking 으로만 저장되므로({@code @DynamicUpdate} 와 결합) 배치가 동시에 갱신하는
     * {@code DATA_STTS_CD}·{@code DE_IDENT_YN}·{@code VDO_LEN_SEC} 등 다른 컬럼을 stale 값으로
     * 덮어쓰지 않는다(CWE-362 lost update). 전체 {@code save()}/detached merge/빌더 재생성 금지.
     *
     * <p>null 인자는 "수동값 삭제"를 뜻하며 조회 시 촬영일시 파생값으로 폴백한다.
     * 허용값(화이트리스트) 검증은 호출 측 서비스 책임이다.
     */
    public void changeShootingEnvironment(String wthrNm, String dayNgtCd, String sesnCd) {
        this.wthrNm = wthrNm;
        this.dayNgtCd = dayNgtCd;
        this.sesnCd = sesnCd;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 영상 단위 개인정보(익명·가명·개인정보 포함여부) 수동입력값을 <b>전체 교체</b>한다 (V163 3컬럼 전용).
     *
     * <p>{@link #changeShootingEnvironment} 와 동일한 이유로 전용 도메인 메서드를 둔다 — 영속 엔티티의
     * dirty checking 으로 이 3필드만 UPDATE 되므로 배치가 동시에 갱신하는 {@code DATA_STTS_CD}·
     * {@code DE_IDENT_YN} 등을 stale 값으로 덮어쓰지 않는다(CWE-362 lost update).
     * 전체 {@code save()}/detached merge/빌더 재생성 금지.
     *
     * <p>null/blank 인자는 "수동값 삭제(미입력)"를 뜻하며 조회 시 비식별 기본상수 프리필로 폴백한다.
     * CHAR(1) 공백 패딩 오염을 막기 위해 blank 는 null 로 정규화한다.
     * 허용값(Y/N 화이트리스트) 검증은 호출 측 서비스 책임이다.
     */
    public void changePrivacyMeta(String anonyInclYn, String psdoInclYn, String prvcInclYn) {
        this.anonyInclYn = normalizeYn(anonyInclYn);
        this.psdoInclYn = normalizeYn(psdoInclYn);
        this.prvcInclYn = normalizeYn(prvcInclYn);
        this.mdfcnDt = LocalDateTime.now();
    }

    /** blank/null → null(미입력). CHAR(1) 저장 시 공백 패딩 오염 방지 위해 trim 후 판정. */
    private static String normalizeYn(String yn) {
        return (yn == null || yn.isBlank()) ? null : yn.trim();
    }

    /** 배치 상태 코드 갱신 (PROCESSING / COMPLETED / FAILED). */
    public void changeStatus(String dataSttsCd) {
        if (dataSttsCd == null || dataSttsCd.isBlank()) {
            throw new IllegalArgumentException("DATA_STTS_CD 는 필수입니다.");
        }
        this.dataSttsCd = dataSttsCd;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 검수완료 재비식별(REDEIDENT) 수행 시 개인정보 처리 유형 정정 — UNKNOWN(미상)이면 PRVC 로 보정.
     *
     * <p>재비식별을 실제로 수행했다는 것은 개인정보 처리가 일어났다는 의미이므로, 분류가 미상이던
     * 영상의 PRVC_TYPE_CD 를 PRVC 로 확정하고 PRVC_YN(파생값)도 함께 재산출한다. 이미 분류가
     * 확정된(ANONY/PRVC/PSDO) 영상은 변경하지 않는다(멱등·원본 보존).
     *
     * @return UNKNOWN→PRVC 정정이 실제 일어났으면 true.
     */
    public boolean correctPrvcTypeIfUnknown() {
        if (PRVC_TYPE_UNKNOWN.equals(this.prvcTypeCd)) {
            this.prvcTypeCd = PRVC_TYPE_PRVC;
            this.prvcYn = derivePrvcYn(this.prvcTypeCd);
            this.mdfcnDt = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /** PRVC_TYPE_CD 기반 PRVC_YN 산출 (단일 진실의 원천). */
    public static String derivePrvcYn(String prvcTypeCd) {
        if (prvcTypeCd == null) {
            return "N";
        }
        return switch (prvcTypeCd) {
            case PRVC_TYPE_PRVC, PRVC_TYPE_PSDO -> "Y";
            default -> "N";
        };
    }
}
