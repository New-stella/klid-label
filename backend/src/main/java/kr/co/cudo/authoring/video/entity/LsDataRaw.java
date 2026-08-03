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

    /** {@code VMS_CLIP_ID} 컬럼 길이 — 파생 식별자 조립 시 이 상한을 넘기지 않는다(V2 스키마와 동일 값). */
    private static final int VMS_CLIP_ID_MAX = 128;

    @Column(name = "VMS_CCTV_ID", nullable = false, length = 64)
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
     * 영상 익명정보 포함여부(검수자/작업자 수동입력, V161). null = 미입력.
     *
     * <p>학습데이터 export JSON 의 <b>video 블록</b> 개인정보 3필드 원천이다
     * ({@code ExportPrivacyPolicy} — 비식별 산출물만 판정하며 미입력이면 기본상수 프리필).
     * 프레임 단위 대응 컬럼은 {@code LS_DATA_SRC.ANONY_INCL_YN}(V130) 이며 <b>입도가 다른 별개 축</b>이다.
     * <p>여부(YN) 도메인은 프로젝트 표준(V85·공공 여부C1) CHAR(1) — {@code @JdbcTypeCode(CHAR)}.
     */
    @Column(name = "ANONY_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String anonyInclYn;

    /** 영상 가명정보 포함여부(검수자/작업자 수동입력, V161). null = 미입력. */
    @Column(name = "PSDO_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String psdoInclYn;

    /** 영상 개인정보 포함여부(검수자/작업자 수동입력, V161). null = 미입력. */
    @Column(name = "PRVC_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcInclYn;

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
                .build();
    }

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
     * <p><b>영상 단위 개인정보 수동값(V161) 도 같은 지점에서 함께 계승</b>한다
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
     * (V161) 도 동일하게 계승</b>한다({@link #copyPrivacyMetaFrom} — 리스케일은 픽셀만 바꾸므로 개인정보
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
     * 파생영상 생성 시 부모의 <b>영상 단위 개인정보 수동값 3필드</b>(V161)를 복사한다(팩토리 전용).
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
        this.anonyInclYn = parent.getAnonyInclYn();
        this.psdoInclYn = parent.getPsdoInclYn();
        this.prvcInclYn = parent.getPrvcInclYn();
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
     * 영상 단위 개인정보(익명·가명·개인정보 포함여부) 수동입력값을 <b>전체 교체</b>한다 (V161 3컬럼 전용).
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
