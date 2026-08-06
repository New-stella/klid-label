package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관제 인입 (LS_DATA_INGEST, V147) — <b>관제서버가 학습용 영상 메타를 직접 INSERT 하는 수신 창구</b>.
 *
 * <p>총 <b>43컬럼</b> = <b>관제 수신 35</b> + <b>저작도구 운영 8</b>(V147 신설 37 + V166 관제 수신 4
 * + V168 관제 수신 2). 인입 행은 감사 추적을 위해
 * <b>영구 보존</b>하며(삭제 금지), 저작도구는 자기 운영 컬럼의 상태만 갱신한다.
 *
 * <h3>관제 소유값을 우리가 덮지 않는다 (CWE-915 Mass Assignment / CWE-362 lost update)</h3>
 * <ul>
 *   <li>관제 수신 29컬럼에 <b>setter 를 두지 않는다</b> — 이 계약은 {@code LsDataIngestTest} 의
 *       리플렉션 가드가 고정한다(@Data/@Setter 재도입 차단).</li>
 *   <li>{@code @DynamicUpdate} — setter 가 없어도 Hibernate 기본 <b>정적 UPDATE 는 전체 컬럼을 SET</b>
 *       하므로, 우리가 상태 전이만 하고 flush 해도 로드 시점 스냅샷의 관제 값이 그대로 다시 쓰인다.
 *       그 사이 관제가 같은 행을 갱신했다면 <b>stale 값으로 되돌린다</b>. dirty 필드만 SET 하도록 해
 *       이 회귀를 구조적으로 차단한다({@code LsDataIngestRepositoryIT} 가 고정).</li>
 * </ul>
 *
 * <h3>이 엔티티에 INSERT 팩토리를 두지 않는 이유</h3>
 * <p>행을 만드는 주체는 관제다. 저작도구는 읽고 상태만 바꾼다. 생성 통로를 열면 그 자체가
 * 관제 소유 컬럼의 쓰기 경로가 된다.
 *
 * <h3>매핑 주의</h3>
 * <ul>
 *   <li><b>{@code BIT}</b> — 색심도 표기('24bit')이며 비트레이트가 아니다. PostgreSQL 에서
 *       컬럼명으로는 무인용 사용이 가능하고(V147 실증), 물리명은 표준용어라 변경하지 않는다.
 *       Hibernate 물리 네이밍 전략이 소문자로 접어 실제 컬럼 {@code bit} 과 일치한다
 *       (프로젝트에 {@code globally_quoted_identifiers} 설정 없음 — 켜면 이 컬럼만 대문자 인용이
 *       강제돼 {@code ddl-auto=validate} 가 깨진다).</li>
 *   <li><b>{@code NUMERIC} 계열은 {@link BigDecimal}</b> — {@code VDO_LEN_SEC}/{@code FRME_CNT}/
 *       {@code WDTH}/{@code VRTC} 는 표준도메인 수N10 = {@code NUMERIC(10)} 이라 {@code Integer}
 *       로 매핑하면 스키마 검증이 타입 불일치로 실패한다(선존 {@code LsDatasetVideoMeta} 도
 *       {@code NUMERIC} 컬럼은 {@code BigDecimal}, {@code INT} 컬럼만 {@code Integer}).</li>
 *   <li><b>{@code BigDecimal} 7종은 {@code precision}/{@code scale} 을 명시한다</b> — 생략하면
 *       Hibernate 기본값 {@code NUMERIC(19,2)} 로 해석돼 V147 실제 정의와 어긋난다. 지금은
 *       {@code ddl-auto=validate} 가 EMF 에 도달하지 않아(부팅 검증 미수행) 무증상이지만,
 *       <b>그 결함이 고쳐지는 순간 앱이 기동하지 못한다</b>. 값은 V147 DDL 정본과 1:1
 *       ({@code NUMERIC(10)} 4종 · {@code DECIMAL(10,7)} 2종 · {@code DECIMAL(4,1)} 1종)이며
 *       {@code LsDataIngestRepositoryIT} 가 실제 {@code information_schema} 자릿수와 대조한다.</li>
 *   <li><b>{@code FILE_SZ} 는 {@code Long}</b> — 관제 수신 <b>바이트 수</b>(수B20 = BIGINT).
 *       '4800KB' 같은 표기는 export 직렬화 단계 산물이다.</li>
 * </ul>
 */
@Entity
@Table(name = "LS_DATA_INGEST",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_INGEST_CLIP", columnNames = "VMS_CLIP_ID"))
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataIngest {

    /** 미처리 — 폴링 대상. 관제가 처리상태를 채우지 않아도 DB DEFAULT 로 이 값이 된다. */
    public static final String PRCS_STTS_PENDING = "PENDING";
    /** 처리 착수 — 적재 트랜잭션이 이 행을 집었다. */
    public static final String PRCS_STTS_PROCESSING = "PROCESSING";
    /** 종결(성공) — 적재 완료 또는 기적재 확인. {@code RAW_SN} 으로 결과를 역추적한다. */
    public static final String PRCS_STTS_DONE = "DONE";
    /**
     * 종결(실패) — 사유를 {@code ERR_MSG} 에 남긴다.
     *
     * <p>파일 미도착처럼 <b>다음 주기에 재시도하면 되는 경우는 이 상태로 만들지 않는다</b>
     * (미처리 {@code PENDING} 으로 두고 다음 tick 이 다시 집는다 — 설계 §6-1 R4).
     */
    public static final String PRCS_STTS_FAILED = "FAILED";

    /** {@code ERR_MSG} 컬럼 길이(내용V4000) — 정제 후 초과분은 절단한다. */
    public static final int ERR_MSG_MAX = 4000;

    /**
     * 출처유형 — <b>저작도구 내부 관리 화면(TUS) 업로드</b>.
     *
     * <p>인입 행을 만드는 주체는 원칙적으로 관제지만, 내부 REVIEWER 업로드는 <b>저작도구가 유일하게
     * 정당한 origin</b> 인 예외 흐름이라 이 값으로 스스로 인입한다
     * ({@code InternalUploadIngestWriter}). 값은 적재 시 {@code SRC_TYPE} allowlist
     * ({@code TrainingVideoIngestTx#ALLOWED_SRC_TYPES})를 통과해야 {@code LS_DATA_RAW} 로 복사된다 —
     * 두 곳이 어긋나면 업로드분의 출처유형이 조용히 null 이 되므로 상수를 공유한다.
     */
    public static final String SRC_TYPE_USER_ULD = "USER_ULD";

    /**
     * <b>적재면</b> {@code SRC_TYPE} 허용값 (설계 §4-2) — 경계축은 "관제가 만들었나 / 저작도구가
     * 만들었나"다. 인입 행에 실린 값이 {@code LS_DATA_RAW} 로 복사되려면 이 목록을 통과해야 한다
     * ({@code TrainingVideoIngestTx#allowedSrcType} — 신뢰 경계 밖 수신값 fail-closed).
     *
     * <p><b>{@link #UPLOAD_SRC_TYPES}(입력면)와 구분한다</b> — 적재면은 "이미 인입 행에 들어와 있는
     * 값을 복사해도 되는가"를, 입력면은 "우리 화면에서 그 값을 <b>새로 만들어도</b> 되는가"를 판정한다.
     * {@code AUGMENTED} 가 그 차이다: 증강 파생본은 관제 인입으로 오지 않지만(V147 주석), 이미 그 값이
     * 들어온 행을 만나면 값을 지우지 않고 그대로 복사하는 편이 안전하다.
     */
    public static final java.util.Set<String> ALLOWED_SRC_TYPES =
            java.util.Set.of("ORIGINAL", "RELAY", SRC_TYPE_USER_ULD, "GENERATED", "AUGMENTED");

    /**
     * <b>입력면</b> {@code SRC_TYPE} 허용값 — 내부 업로드(REVIEWER TUS) 폼이 고를 수 있는 값.
     *
     * <p>적재면({@link #ALLOWED_SRC_TYPES})에서 <b>{@code AUGMENTED} 를 뺀 4종</b>이다. 증강 파생본은
     * <b>저작도구가 직접 만들고</b> {@code ORGNL_RAW_SN} 으로 부모를 가리킨다(V147 주석: "AUGMENTED 는
     * 저작도구 파생이라 인입으로 오지 않는다"). 인입으로 받으면 {@code ORGNL_RAW_SN} 이 null 인데
     * 출처만 파생인 {@code LS_DATA_RAW} 행이 생겨 <b>파생 판별 축과 어긋난다</b>.
     *
     * <p>이 집합이 입력 검증의 <b>단일 진실원</b>이다 — DTO {@code @AssertTrue} 와 서비스 2차 방어선이
     * 같은 값을 본다(리터럴 중복 금지: 두 곳이 갈라지면 한쪽만 통과하는 값이 생긴다).
     */
    public static final java.util.Set<String> UPLOAD_SRC_TYPES =
            ALLOWED_SRC_TYPES.stream()
                    .filter(t -> !"AUGMENTED".equals(t))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * <b>검증이벤트유형</b>({@link #vrfcEvntTypeCd}) 허용값 — 외부 VLM 검증 API 의 {@code event_type}
     * enum 6종이며 <b>단일 진실원</b>이다 (V176 — @req R5).
     *
     * <p>값은 벤더 규격 그대로 <b>소문자</b>다. 우리가 표기를 바꾸면 위탁 요청이 벤더 검증에서
     * 거부되므로 대문자·한글로 정규화하지 않는다.
     *
     * <p><b>이 집합을 리터럴로 복제하지 말 것</b> — DTO 검증({@code InternalUploadCreateRequest})과
     * 서비스 2차 방어선({@code TusUploadService})이 같은 값을 봐야 한다. 두 목록이 갈라지면 한쪽만
     * 통과하는 값이 조용히 생긴다({@link #UPLOAD_SRC_TYPES} 가 겪은 실사고와 동형).
     *
     * <p>이 판정은 <b>우리 쓰기 통로에만</b> 적용된다 — 관제는 우리 코드를 거치지 않고 직접
     * INSERT 하므로 DB CHECK 를 두지 않으며(관제 인입 전체가 값 하나에 멈춘다), 관제가 실은 미지의
     * 값은 <b>소비 시점</b>(VLM 위탁)이 fail-closed 로 거른다.
     */
    public static final java.util.Set<String> VRFC_EVNT_TYPES =
            java.util.Set.of("fire", "fall", "violence", "flooding", "car_accident", "kidnapping");

    /**
     * 검증이벤트유형 입력 정규화 — {@code trim} 후 <b>소문자</b>로 접는다 (@req R5).
     *
     * <p>빈 값(null·공백만)은 <b>미지정</b>이라 {@code null} 을 돌려준다. 빈 문자열을 그대로 실으면
     * 소비 시점이 "판정 없음"과 구분하지 못한다.
     *
     * <p>{@code Locale.ROOT} 고정 — 기본 로케일로 접으면 터키어 환경에서 {@code FIRE} 가
     * {@code fıre} 가 되어 allowlist 를 통과하지 못한다.
     *
     * <p>정규화와 판정을 <b>같은 함수로</b> 통과시켜야 검증을 우회하는 표기 변형이 생기지 않는다
     * (검증은 정규화 <b>결과</b>에 대해 한다).
     */
    public static String normalizeVrfcEvntType(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 검증이벤트유형 물리 상한 — 컬럼 {@code VRFC_EVNT_TYPE_CD VARCHAR(20)} (V176, 표준도메인 코드V20).
     *
     * <p>입력단 검증이 이 값을 <b>반드시</b> 봐야 한다 — 안 보면 21자 입력이 검증을 통과해 INSERT
     * 시점에 DB 오류(500)로 터진다. 400 으로 되돌려 주는 것이 입구 검증의 일이다.
     */
    public static final int VRFC_EVNT_TYPE_MAX_LENGTH = 20;

    /** 검증이벤트유형 허용 문자 — 벤더 enum 표기(소문자 스네이크)와 같은 문자 집합. */
    private static final java.util.regex.Pattern VRFC_EVNT_TYPE_FORMAT =
            java.util.regex.Pattern.compile("^[a-z0-9_]+$");

    /**
     * 검증이벤트유형 <b>형식</b> 판정 — 값 목록이 아니라 표기 규칙만 본다 (2026-08-06 정책 반전).
     *
     * <h3>★ 구 동작(6종 allowlist 판정) 폐기 — 되돌리지 말 것</h3>
     * <p>구 동작은 우리 쓰기 통로에서 {@link #VRFC_EVNT_TYPES} 6종만 통과시켰다. 같은 날 위탁
     * 게이트가 "조달값을 그대로 실어 항상 위탁 → 수용 여부는 벤더 응답이 정한다"로 반전
     * ({@code VlmTimeseriesStep.resolveEventType})했고, 그 뒤로 <b>우리 화면에서만 6종에 갇히는
     * 비대칭</b>이 남았다(관제 인입분은 우리 코드를 거치지 않아 어떤 값이든 들어온다). 사용자 확정으로
     * dev 업로드에 <b>직접 입력</b>을 열면서 이 판정도 형식 검사로 좁혔다.
     *
     * <h3>그래도 형식은 막는다 — 왜</h3>
     * <ul>
     *   <li><b>길이</b> — 컬럼이 {@code VARCHAR(20)} 이라 초과분은 어차피 저장되지 않는다. 입구에서
     *       400 을 주지 않으면 INSERT 시점 DB 오류(500)가 된다.</li>
     *   <li><b>문자 집합</b> — 이 값은 <b>외부 벤더 요청 바디와 로그에 그대로 실린다</b>. 공백·제어문자·
     *       개행이 섞이면 로그 인젝션(CWE-117)과 벤더측 파싱 오류가 된다. 벤더 enum 이 소문자
     *       스네이크이므로 같은 문자 집합으로 제한한다.</li>
     * </ul>
     *
     * <p>판정은 <b>정규화 결과</b>({@link #normalizeVrfcEvntType})에 대해 한다 — 표기 변형으로
     * 우회할 수 없게 하기 위해서다. 미지정({@code null})은 통과다(선택 입력).
     *
     * @param normalized {@link #normalizeVrfcEvntType} 를 통과한 값(또는 {@code null})
     * @return 저장·위탁해도 되는 형식이면 {@code true}
     */
    public static boolean isVrfcEvntTypeFormatValid(String normalized) {
        if (normalized == null) {
            return true;
        }
        return normalized.length() <= VRFC_EVNT_TYPE_MAX_LENGTH
                && VRFC_EVNT_TYPE_FORMAT.matcher(normalized).matches();
    }

    // ---------------------------------------------------------------------
    // 저작도구 운영 (8) — 우리가 갱신하는 유일한 컬럼군
    // ---------------------------------------------------------------------

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RCPTN_SN")
    private Long rcptnSn;

    /** 수신일시 — 폴링 순서(FIFO) 기준. 관제 미지정 시 DB DEFAULT. */
    @Column(name = "RCPTN_DT", nullable = false)
    private LocalDateTime rcptnDt;

    @Column(name = "PRCS_STTS_CD", nullable = false, length = 20)
    private String prcsSttsCd = PRCS_STTS_PENDING;

    /** 적재 결과 영상 식별자({@code LS_DATA_RAW.RAW_SN}). 적재 전 null. FK 없음(수신 기록 영구 보존). */
    @Column(name = "RAW_SN")
    private Long rawSn;

    @Column(name = "RTY_CNT", nullable = false)
    private Integer rtyCnt = 0;

    /**
     * 처리일시 — <b>저작도구가 이 행을 처리한 시각</b>. 두 의미를 겸한다.
     *
     * <ul>
     *   <li><b>종결 시각</b>({@code DONE}/{@code FAILED}) — {@link #markDone}/{@link #markFailed} 가 찍는다.</li>
     *   <li><b>대기 예산 앵커</b>(미처리 상태) — <b>최초 파일 미도착 관측 시각</b>. 미도착 대기 상한은
     *       관제가 준 {@code RCPTN_DT} 가 아니라 <b>이 값</b> 기준으로 잰다(설계 §6-0-1-a ㉠).
     *       {@code RCPTN_DT} 는 {@code DEFAULT CURRENT_TIMESTAMP} 일 뿐 강제가 없고 <b>INSERT 주체가
     *       관제</b>라, 과거 시각이 명시 INSERT 되면 <b>도착 즉시 상한 초과 → 첫 픽업에서 종결</b>된다.
     *       스탬프는 {@code LsDataIngestRepository#revertToPendingForRetry}(조건부 UPDATE, COALESCE 로
     *       최초 관측만 보존)가 찍고, 재큐는 이 값을 비워 <b>예산을 리셋</b>한다.</li>
     * </ul>
     *
     * <p>미처리이고 미도착 관측도 없으면 null.
     */
    @Column(name = "PRCS_DT")
    private LocalDateTime prcsDt;

    /**
     * 차기 재시도 예정 일시 (backoff) — 폴링 후보는 {@code PENDING} <b>AND</b>
     * ({@code NXTM_RTRY_DT IS NULL OR NXTM_RTRY_DT <= 현재})다.
     *
     * <p>상한만으로는 무한 정지가 <b>최대 상한(기본 24h) 정지</b>로 유계화될 뿐이다 — 미도착 행이 스캔
     * 상한만큼 FIFO 앞자리에 있으면 그동안 뒤의 정상 인입이 픽업되지 않는다. 미도착 관측 때마다 이 값을
     * 뒤로 밀면 그 행이 후보에서 빠져 <b>커서가 전진</b>한다(설계 §6-0-1-a ㉢).
     *
     * <p>재큐 시 {@code PRCS_DT} 와 함께 비운다 — 예산 앵커만 리셋하고 이 값을 남기면 되살린 행이
     * 예정 시각까지 다시 잠든다.
     */
    @Column(name = "NXTM_RTRY_DT")
    private LocalDateTime nxtmRtryDt;

    /** 적재 실패 사유(요약). 절대경로·시크릿·스택트레이스 미포함(CWE-359). */
    @Column(name = "ERR_MSG", length = ERR_MSG_MAX)
    private String errMsg;

    // ---------------------------------------------------------------------
    // 관제 수신 (29) — 조회 전용. setter 금지.
    // ---------------------------------------------------------------------

    @Column(name = "VMS_CLIP_ID", nullable = false, length = 128)
    private String vmsClipId;

    @Column(name = "VMS_CCTV_ID", nullable = false, length = 64)
    private String vmsCctvId;

    @Column(name = "VDO_FILE_NM", nullable = false, length = 300)
    private String vdoFileNm;

    /** 원시 파일 경로명 — 관제 NAS 실경로. 로그에 전문을 남기지 않는다(CWE-359). */
    @Column(name = "RAW_FILE_PATH_NM", nullable = false, length = 500)
    private String rawFilePathNm;

    /** 출처유형(RELAY/USER_ULD/GENERATED/ORIGINAL). 경계축은 "누가 만들었나"다. */
    @Column(name = "SRC_TYPE", nullable = false, length = 20)
    private String srcType;

    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;

    @Column(name = "FILE_FMT", length = 20)
    private String fileFmt;

    @Column(name = "VDO_CDC", length = 20)
    private String vdoCdc;

    /** 파일크기 — 관제 수신 바이트 수. */
    @Column(name = "FILE_SZ")
    private Long fileSz;

    /**
     * 지방자치단체명(관제 {@code video.location}) — 표준용어 {@code LCLGV_NM}, 도메인 명V100.
     *
     * <p>{@link #lclgvCd}(코드) · {@link #ogCd}(기관코드)와 <b>서로 다른 값</b>이다.
     */
    @Column(name = "LCLGV_NM", length = 100)
    private String lclgvNm;

    /** 영상길이(초). 밀리초 정밀도는 {@code LS_DATA_RAW.VDO_LEN_MS} 담당. */
    @Column(name = "VDO_LEN_SEC", precision = 10, scale = 0)
    private BigDecimal vdoLenSec;

    @Column(name = "FPS", length = 10)
    private String fps;

    /** 프레임수 — 표준용어 {@code FRME_CNT}(프레임=FRME). 수N10 = {@code NUMERIC(10)}. */
    @Column(name = "FRME_CNT", precision = 10, scale = 0)
    private BigDecimal frmeCnt;

    /** 종횡비 표기(예 16:9). */
    @Column(name = "ASPRT_RT", length = 20)
    private String asprtRt;

    @Column(name = "WDTH", precision = 10, scale = 0)
    private BigDecimal wdth;

    @Column(name = "VRTC", precision = 10, scale = 0)
    private BigDecimal vrtc;

    @Column(name = "RESL", length = 20)
    private String resl;

    /** 비트값 = 색심도 표기(예 24bit). 비트레이트가 아니다. */
    @Column(name = "BIT", length = 20)
    private String bit;

    /** 화소 표기(예 4K). */
    @Column(name = "PXL", length = 20)
    private String pxl;

    @Column(name = "WGS84_LAT", precision = 10, scale = 7)
    private BigDecimal wgs84Lat;

    @Column(name = "WGS84_LOT", precision = 10, scale = 7)
    private BigDecimal wgs84Lot;

    @Column(name = "OG_CD", length = 20)
    private String ogCd;

    /** CCTV명 — 촬영 시점 값 고정(카메라 교체 시 과거 영상 오염 방지 목적의 의도된 중복 저장). */
    @Column(name = "CCTV_NM", length = 300)
    private String cctvNm;

    /** CCTV 설치 높이(m). 촬영 시점 값 고정. */
    @Column(name = "CCTV_HGT", precision = 4, scale = 1)
    private BigDecimal cctvHgt;

    /** 주감시방향값(도). 촬영 시점 값 고정. */
    @Column(name = "MAIN_SURV_PAN_ANG")
    private Integer mainSurvPanAng;

    /** 이벤트 아이디(예 ABA_0001). 이벤트유형코드가 아니다. */
    @Column(name = "EVNT_ID", length = 50)
    private String evntId;

    /**
     * 이벤트유형코드 (V166 신설) — {@code LS_DATA_RAW.EVNT_TYPE_CD} 의 <b>1순위 원천</b>.
     *
     * <p>{@link #evntId}(식별자형, 예 {@code ABA_0001})와 <b>서로 다른 값</b>이다 — 대체·통합하지 않는다.
     *
     * <h3>왜 신설했나 (관제 데이터 참조 전면 제거의 대체 경로)</h3>
     * <p>이 값은 지금까지 관제 공유 이벤트리스트 테이블을 {@code EVNT_ID} 로 조인해
     * 해석하고 있었다. 그 공유 테이블을 제거하려면 <b>먼저</b> 관제가 유형코드를 직접 실어 보낼 통로가
     * 있어야 한다(대체 경로 없이 지우면 마킹 프리컨디션이 막혀 신규 영상 전량이 마킹 400 이 된다).
     *
     * <p><b>관제가 채우기 전까지 null 이다</b> — 그동안 적재 경로({@code TrainingVideoIngestTx})가
     * 기존 {@code EVNT_ID} 해석으로 폴백하므로 현행 동작이 유지된다(과도기).
     *
     * <p>길이 20 = 코드값 표준도메인(코드V20). 선존 {@code LS_DATA_RAW.EVNT_TYPE_CD} 와 동일하므로
     * 복사 시 절단이 구조적으로 발생하지 않는다.
     */
    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    /**
     * <b>원천 영상</b>(비식별 처리 <b>전</b>)의 익명정보 포함여부 (V166 신설, {@code Y}/{@code N}).
     *
     * <h3>★ 개인정보 3필드는 축이 두 개다 — 혼동 주의</h3>
     * <table>
     *   <tr><th>컬럼</th><th>대상</th><th>채우는 주체</th></tr>
     *   <tr><td>{@code LS_DATA_INGEST.ANONY_INCL_YN}(이 필드)</td>
     *       <td><b>원천 영상</b>(비식별 전)</td>
     *       <td>관제 인입 — <b>미수신 시 null 그대로</b>(수신 원장이므로 서버가 보정하지 않는다)</td></tr>
     *   <tr><td>{@code LS_DATA_RAW.ANONY_INCL_YN}(V163, 선존)</td>
     *       <td><b>비식별 영상</b></td>
     *       <td>사람이 화면에서 수동 입력({@code PUT /v1/videos/{rawSn}/privacy-meta})</td></tr>
     * </table>
     *
     * <p><b>이 값은 {@code LS_DATA_RAW} 로 복사되지 않는다</b> — 관제가 준 읽기 전용 사실을 작업 대상
     * 마스터에 이중 저장하지 않는다는 확정 설계다. 소비 측은 인입을 조인해서 읽는다:
     * {@code LEFT JOIN LS_DATA_INGEST i ON i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)}.
     * ⚠ <b>단 개인정보 3필드는 파생영상에서 제외</b>한다({@code r.ORGNL_RAW_SN IS NOT NULL} 이면 null) —
     * 파생은 부모의 <b>비식별본</b>으로 만들어져 원천 영상이 존재하지 않으며, 파생의 판정은
     * {@code LsDataRaw.copyPrivacyMetaFrom} 이 생성 시점에 계승한 <b>비식별 축</b>에서 온다.
     *
     * <p><b>fail-closed 기본값(익명 {@code N}·개인정보 {@code Y}·가명 {@code N})은 여기서 적용하지
     * 않는다</b> — 소비 시점(export 판정)에서 적용해야 "관제 미송신"과 "관제가 {@code N} 송신"이
     * 구분된다. 적재가 기본값을 채우면 그 구분이 영구히 사라진다.
     *
     * <p>두 축을 <b>같은 컬럼에 담지 않는다</b> — 선존 V163 컬럼의 null 은 "사람이 아직 입력하지
     * 않았다"를 뜻하고 {@code ExportPrivacyPolicy} 가 그 null 로 기본상수 프리필 여부를 가른다.
     * 관제 수신값을 거기에 쓰면 export 가 <b>관제 기본값을 사람의 판정으로 둔갑</b>시켜 내보낸다.
     *
     * <p>여부(YN) 도메인은 프로젝트 표준(V85·공공 여부C1) CHAR(1).
     */
    @Column(name = "ANONY_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String anonyInclYn;

    /** 원천 영상의 가명정보 포함여부 (V166, {@code Y}/{@code N}). 축 구분은 {@link #anonyInclYn} 참조. */
    @Column(name = "PSDO_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String psdoInclYn;

    /** 원천 영상의 개인정보 포함여부 (V166, {@code Y}/{@code N}). 축 구분은 {@link #anonyInclYn} 참조. */
    @Column(name = "PRVC_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcInclYn;

    @Column(name = "EVNT_NM", length = 200)
    private String evntNm;

    /**
     * 이벤트분류코드(대분류, 관제 수신 — V168 신설). {@code LS_EVNT_TYPE.EVNT_CLSF_CD} 의 원천이며
     * 이벤트유형 제외 필터({@code eventtype.excluded-class-codes})의 판정축이다.
     *
     * <p><b>코드에서 유도하지 않는다</b>(사용자 확정 2026-08-04) — 구 안이던
     * {@code SUBSTRING(EVNT_TYPE_CD, 3, 2)} 는 실측상 비규격 유형코드가 존재해 <b>존재하지 않는
     * 대분류</b>를 만든다. 관제가 안 보내면 null 이며 폴백하지 않는다(계획 확정 정책 R6).
     *
     * <p>길이 2 = 표준도메인 <b>코드C2</b>({@code CHAR(2)}, V172 정합 — @req R2). 실제 값도 2자
     * 고정이라 고정길이 패딩이 발생하지 않는다(카테고리 키 6자 = 분류 2 + 카테고리 4).
     */
    @Column(name = "EVNT_CLSF_CD", length = 2)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String evntClsfCd;

    /**
     * 이벤트카테고리코드(관제 수신 — V168 신설). 원래 이벤트 구조 3계층(대분류→카테고리→유형)의
     * 중간 레벨이며 {@code LS_EVNT_TYPE.EVNT_CTGRY_CD} 의 원천이다. 유형에 고유 이름이 없을 때
     * 표시명이 카테고리명으로 폴백하는 근거다. 관제가 안 보내면 null 이며 유도하지 않는다.
     *
     * <p>길이 4 = 표준도메인 <b>코드C4</b>({@code CHAR(4)}, V172 정합 — @req R2).
     */
    @Column(name = "EVNT_CTGRY_CD", length = 4)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String evntCtgryCd;

    /** 관제일지 내용. */
    @Column(name = "MNTR_CN", length = 4000)
    private String mntrCn;

    /**
     * 지방자치단체코드 — {@code LS_DATA_RAW.LCLGV_CD} 의 원천이자 관제 완료통지 페이로드
     * {@code lclgv_cd}(required)의 값 출처다.
     *
     * <p>{@link #lclgvNm}(지방자치단체명) · {@link #ogCd}(기관코드)와 <b>서로 다른 값</b>이다 —
     * 셋을 대체·통합하지 않는다.
     */
    @Column(name = "LCLGV_CD", length = 20)
    private String lclgvCd;

    /**
     * 검증이벤트유형코드 (V176 신설) — 외부 VLM 검증 API 요청의 {@code event_type} 조달처
     * (@req R5). 허용값은 {@link #VRFC_EVNT_TYPES} 6종이다.
     *
     * <h3>{@link #evntTypeCd}(이벤트유형코드)와 <b>다른 값</b>이다 — 대체·통합하지 않는다</h3>
     * <table>
     *   <tr><th>컬럼</th><th>값 예</th><th>쓰임</th></tr>
     *   <tr><td>{@code EVNT_TYPE_CD}</td><td>{@code EV01000101}</td>
     *       <td>관제 이벤트 코드 체계. 마킹 프리컨디션·오토라벨 프리셋 매칭 축</td></tr>
     *   <tr><td>{@code VRFC_EVNT_TYPE_CD}(이 필드)</td><td>{@code car_accident}</td>
     *       <td>외부 VLM 이 요구하는 <b>분석 대상 지정</b>값</td></tr>
     * </table>
     *
     * <h3>왜 저작도구가 매핑표를 만들지 않는가 (사용자 확정)</h3>
     * <p>관제 코드 → 벤더 enum 번역표를 우리가 들면 관제 코드 체계가 바뀔 때마다 그 표가 조용히
     * 낡고, 잘못 번역된 값으로 <b>외부 위탁</b>이 나간다. 그래서 값 자체를 관제 인입으로 받는다.
     *
     * <p><b>관제가 채우기 전까지 null 이다</b> — 기존 행 백필도 하지 않는다(유추해 채우면 그것이
     * 곧 자체 매핑표다). 소비 시점의 null 처리는 위탁 단계 책임이다.
     *
     * <p>길이 20 = 코드값 표준도메인(코드V20). 벤더 enum 최장값 {@code car_accident}(12자)를 수용한다.
     */
    @Column(name = "VRFC_EVNT_TYPE_CD", length = 20)
    private String vrfcEvntTypeCd;

    // ---------------------------------------------------------------------
    // 상태 전이 — 저작도구 운영 컬럼 전용
    // ---------------------------------------------------------------------

    /*
     * ★ 처리 착수(PENDING → PROCESSING) 전이 메서드를 두지 않는다 — 비원자 통로 금지.
     *
     * 구 구현은 이 자리에 markProcessing() 이 있었고 필드만 바꿨다. 그 전이는 "읽고-쓰기"라
     * 원자적이지 않다: 두 실행이 같은 PENDING 행을 읽으면 <b>둘 다</b> PROCESSING 으로 쓰고
     * 각자 "내가 잡았다"고 착각해 같은 클립을 중복 적재한다(CWE-362).
     *
     * @DynamicUpdate 도 @Version 도 이를 막지 못한다 — 전자는 SET 절을 dirty 컬럼으로 좁힐 뿐
     * UPDATE-UPDATE 충돌을 감지하지 않고, 후자는 이 엔티티에 없다. Quartz 클러스터링 역시
     * <b>트리거 중복 발화만</b> 막으며 잡 내부 레이스는 각 잡의 원자 클레임이 별도로 막는다
     * (CLAUDE.md '배치 성능' 명문 규칙 — 서로 대체하지 않는다).
     *
     * 착수 전이는 조건부 UPDATE 한 곳으로만 한다:
     *   LsDataIngestRepository#claimForProcessing (영향 행 수 1 = 클레임 성공 / 0 = 실패)
     */

    /**
     * 종결(성공) — 적재 완료 또는 <b>기적재 확인</b>(중복 클립).
     *
     * <p>이전 시도의 실패 사유는 지운다(종결 상태와 어긋난 사유가 남아 오판을 부른다).
     * 시도 이력은 {@code RTY_CNT} 에 남는다.
     *
     * <p><b>전제: 호출자는 이미 {@code claimForProcessing} 으로 이 행을 클레임했다.</b> 종결 전이는
     * 그래서 조건부 UPDATE 가 아니어도 안전하다 — 클레임에 성공한 실행은 단 하나이므로 이 행을
     * 종결시키는 주체도 하나뿐이다. 클레임 없이 부르면 그 전제가 깨진다.
     *
     * @param rawSn 적재 결과 영상 식별자 — 역추적 근거
     */
    public void markDone(Long rawSn) {
        this.prcsSttsCd = PRCS_STTS_DONE;
        this.rawSn = rawSn;
        this.errMsg = null;
        this.prcsDt = LocalDateTime.now();
        // 종결된 행에는 "차기 재시도 예정"이 없다 — 남겨두면 재큐 없이 되살아난 것처럼 보인다.
        this.nxtmRtryDt = null;
    }

    /**
     * 종결(실패) — 사유를 남긴다(조용한 유실 금지).
     *
     * <p>사유는 {@link LogSanitizer} 로 개행·제어문자·유니코드 라인 구분자를 제거해 저장한다
     * (CWE-117 — 이 값은 이후 로그·감사 화면으로 흘러간다). 절대경로·스택트레이스·PII 를 넣지
     * 않는 것은 <b>호출 측 책임</b>이다(CWE-359).
     *
     * <p>{@link #markDone} 과 동일하게 <b>클레임 성공을 전제</b>한다.
     *
     * <p>재시도 횟수는 종결까지의 시도 이력으로 함께 누적한다. {@code FAILED} 는 폴링 술어
     * ({@code PENDING})에서 빠지므로 <b>자동으로는</b> 재개되지 않는다. 다만 이 종결은 <b>가역</b>이다 —
     * {@code LsDataIngestRepository#requeueFailedForRetry}(조건부 UPDATE)로 재큐하면 다음 스캔이 다시
     * 집는다(설계 §6-0-1 ② — 종결 사유가 설정·환경 오류일 수 있으므로 되돌릴 수 없는 차단을 두지 않는다).
     *
     * <p>다음 주기 재시도가 맞는 상황(파일 미도착 — 대기 상한 이내)은 애초에 이 메서드를 부르지 않고
     * {@code revertToPendingForRetry} 로 미처리 복귀한다. 대기가 상한을 넘기면 그때 이 메서드로 종결해
     * 큐를 비운다(설계 §6-0-1 ① — 끝나지 않는 보류는 인입 전체를 정지시킨다).
     */
    public void markFailed(String errMsg) {
        this.prcsSttsCd = PRCS_STTS_FAILED;
        this.rtyCnt = (this.rtyCnt == null ? 0 : this.rtyCnt) + 1;
        this.errMsg = sanitizeErrorMessage(errMsg);
        this.prcsDt = LocalDateTime.now();
        // 종결된 행에는 "차기 재시도 예정"이 없다(재개는 재큐가 결정한다).
        this.nxtmRtryDt = null;
    }

    /**
     * 저장용 사유 정제 — 제어문자 제거 후 컬럼 길이로 절단한다.
     *
     * <p>{@link LogSanitizer#sanitize(String, int)} 는 상한 도달 시 접미사를 덧붙여 상한을 초과할 수
     * 있고 null 을 {@code "(null)"} 로 바꾼다. 둘 다 <b>로그 표기 규약</b>이라 DB 컬럼에 그대로
     * 흘리지 않는다(길이 초과 UPDATE 실패 · 사유 없음을 문자열로 위장).
     */
    private static String sanitizeErrorMessage(String errMsg) {
        if (errMsg == null) {
            return null;
        }
        String sanitized = LogSanitizer.sanitize(errMsg, ERR_MSG_MAX);
        return sanitized.length() <= ERR_MSG_MAX ? sanitized : sanitized.substring(0, ERR_MSG_MAX);
    }
}
