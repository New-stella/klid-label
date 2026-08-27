package kr.co.cudo.authoring.augment.entity;

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
 * Phase 9 — 데이터 증강 결과 (LS_DATA_AUG).
 *
 * <p>외부 SFR-07 시스템이 생성한 3종 증강 결과(WINTER/NIGHT/RAIN)를 적재한다.
 * 해상도 변경(SFR-06-03)도 저작도구 내부 수행 파생영상으로서 이 테이블에 통합 적재한다 —
 * {@link #AUG_RESL_1080P}/{@link #AUG_RESL_720P}/{@link #AUG_RESL_480P}({@link #RESL_PREFIX} 접두)
 * 판별자로 구분하며, 원본↔파생 라벨 배율 매핑은 {@code LS_DATA_AUG_LBL_MAP}
 * (COORD_RECALC_YN/SCALE_X/SCALE_Y)에 함께 적재한다. 구 전용 테이블
 * (LS_RESOLUTION_EXPORT/LS_RESOLUTION_LBL_MAP)은 폐기됐다(V126 백필 후 DROP).
 * {@link #AUG_RESOLUTION} 상수는 통합 이전 레거시 단일 코드 데이터 호환용으로만 유지한다.
 * 검수 상태/반려 사유/정합률은 LS_DATA_AUG_RVW 에 분리 저장한다.
 */
@Entity
@Table(name = "LS_DATA_AUG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAug {

    public static final String STTS_PENDING  = "PENDING";
    public static final String STTS_ACCEPTED = "ACCEPTED";
    public static final String STTS_REJECTED = "REJECTED";

    /**
     * <b>사용자 취소로 종결</b> — 2026-07-31 신설(FE 취소 API {@code POST /v1/augments/{id}/cancel}).
     *
     * <h3>왜 새 상태가 필요한가 (S1)</h3>
     * <p>「생성형 AI API 연동명세서 v1.1」 §4.6 은 취소에 <b>웹훅을 발사하지 않는다</b> — 동기 취소
     * 응답이 유일한 통보다. 그 시점에 상태를 확정하지 않으면 다시 알 방법이 없고, 그 증강은 영원히
     * {@link #STTS_PENDING} 에 남는다. 고아 회수기({@code findOrphanPendingAugSns})는 "job 0건" 만
     * 집으므로(취소된 증강은 job 이 1건 이상 존재한다) <b>만료 스윕도 건지지 못한다</b>.
     *
     * <h3>왜 {@link #STTS_REJECTED} 를 재사용하지 않는가</h3>
     * <p>{@code REJECTED} 에는 이미 두 의미가 겹쳐 있다 — REVIEWER 의 정상 반려와 외부 처리 실패
     * 롤업. 세 번째 의미를 얹으면 "취소된 증강" 을 어느 축으로도 구분할 수 없다(E-06 과 같은 형태의
     * 오집계). 취소는 실패가 아니므로 {@code DEAD_LETTER_AT}(처리 실패 전용 마커)도 <b>찍지 않는다</b>.
     *
     * <h3>집계 영향 — 새 집계 로직을 만들지 않는다</h3>
     * <p>{@code AugmentJobStatus}(영상 그룹 집계 enum)에 값을 <b>추가하지 않는다</b>. 대신
     * {@link #isTerminalStatus(String)} 이 CANCELED 를 종결로 인정해 기존 규칙("전부 종결 →
     * COMPLETED")이 그대로 성립하게 한다. 이걸 빠뜨리면 취소된 증강이 든 영상 그룹이 영원히
     * REQUESTED/IN_PROGRESS 로 표시된다.
     */
    public static final String STTS_CANCELED = "CANCELED";

    // ────────────────────────────────────────────────────────────────────────
    // 폐기 이력 — 구 ACTIVE_STATUSES(PENDING·ACCEPTED) 상수는 제거됐다 (2026-07-31).
    //
    // 그 상수는 "같은 (원본 대표프레임 × 증강 종류) 활성 증강은 1건" 이라는 부분 유니크 인덱스
    // UK_LS_DATA_AUG_ACTVTN(V143)의 술어를 코드에서 미러하는 <단일 원천> 이었다. 그 정책("요청 1회 =
    // 파생영상 1건")이 사용자 확정으로 폐기되고(증강 결과는 요청마다 다르게 생성되므로 원하는 결과가
    // 나올 때까지 같은 영상·종류로 재요청하는 것이 정상 동선이다) 인덱스도 V153 에서 DROP 됐다.
    // 인덱스가 없어진 뒤에도 상수만 남겨두면 "여기에 맞춰 DB 제약이 있다" 는 사라진 계약을 계속
    // 주장하게 되므로, 유일한 소비자(AugmentRequestService 중복 가드)와 함께 제거했다.
    // 되살리려면 V153 주석의 롤백 절차(활성 중복 선정리 → 인덱스 재생성)를 먼저 수행할 것.
    // ────────────────────────────────────────────────────────────────────────

    public static final String AUG_WINTER     = "WINTER";
    public static final String AUG_NIGHT      = "NIGHT";
    public static final String AUG_RAIN       = "RAIN";

    /**
     * 레거시 단일 해상도 증강 코드 — R1 v1.8 이전 외부 위탁 방식의 잔존 데이터 호환용.
     * 신규 해상도 파생은 이 단일값이 아니라 {@link #AUG_RESL_1080P}/{@link #AUG_RESL_720P}/{@link #AUG_RESL_480P}
     * ({@link #RESL_PREFIX} 접두) 3종으로 적재된다. 신규 콜백/요청에서 이 단일값은 사용하지 않는다.
     */
    public static final String AUG_RESOLUTION = "RESOLUTION";

    /** 해상도 파생 코드 접두 — 이 접두로 시작하면 저작도구 내부 해상도 파생(검수 대상 아님)이다. */
    public static final String RESL_PREFIX    = "RESL_";
    /** 해상도 파생 3종(신규) — {@code ResolutionPreset.name()} 과 1:1 대응. */
    public static final String AUG_RESL_1080P = "RESL_1080P";
    public static final String AUG_RESL_720P  = "RESL_720P";
    public static final String AUG_RESL_480P  = "RESL_480P";

    /**
     * 화면(FE)에 노출하는 증강종류 계약값 6종 — 목록 응답의 {@code augType} 이 가질 수 있는 값 전부다.
     *
     * <p>{@code LS_DATA_RAW.AUG_TYPE_CD} 는 자유 문자열 컬럼이라 계약 밖 값이 들어올 수 있다 —
     * 레거시 단일 코드 {@link #AUG_RESOLUTION}(통합 이전 데이터), 수기 정정분, 미지의 신규 코드 등.
     * 구 판별 소스였던 {@code VMS_CLIP_ID} 역파서는 이 6종만 낼 수 있었으므로, 컬럼으로 판별 원천을
     * 옮기면서 <b>계약 밖 값이 그대로 화면으로 새어 나가지 않게</b> 이 화이트리스트로 거른다
     * (CWE-20 — 미지의 값이 FE 분기축·표시 라벨로 유입되는 것을 막는 fail-safe).
     */
    public static final java.util.Set<String> CONTRACT_AUG_TYPES = java.util.Set.of(
            AUG_WINTER, AUG_NIGHT, AUG_RAIN, AUG_RESL_1080P, AUG_RESL_720P, AUG_RESL_480P);

    /** {@code augTypeCd} 가 FE 계약값 6종({@link #CONTRACT_AUG_TYPES}) 중 하나인가. null 은 false. */
    public static boolean isContractAugType(String augTypeCd) {
        return augTypeCd != null && CONTRACT_AUG_TYPES.contains(augTypeCd);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_SN")
    private Long dataAugSn;

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "AUG_TYPE_CD", nullable = false, length = 20)
    private String augTypeCd;

    @Column(name = "AUG_PROC_STTS_CD", nullable = false, length = 20)
    private String augProcSttsCd;

    @Transient
    private BigDecimal lblIntgrtPct;

    @Transient
    private String rejectRsn;

    @Transient
    private String dcsnUserNo;

    @Transient
    private LocalDateTime dcsnDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 토큰 sub(문자열) 저장 — 비숫자 sub 허용(JwtAuthenticationFilter.parseUserNo fail-closed)이라
     * 의도적으로 VARCHAR. 숫자 BIGINT 아님.
     */
    @Column(name = "REG_USER_NO", length = 50)
    private String regUserNo;

    /**
     * Phase 4 비동기 표준 컬럼 — webhook 인계 원래 위탁 요청 식별자.
     * UNIQUE 제약 (uk_aug_idempotency_key) — 동시 인계 race 차단.
     */
    @Column(name = "IDMP_KEY", length = 128)
    private String idempotencyKey;

    /**
     * Phase 4 비동기 표준 컬럼 — 외부 시스템 작업 ID.
     * UNIQUE 제약 (uk_aug_external_job_id).
     */
    @Column(name = "OTSD_JOB_ID", length = 200)
    private String externalJobId;

    /**
     * 외부 위탁 시 전송한 <b>생성 조건 원문</b> JSON — V153 신설.
     *
     * <p>구 구현은 증강 유형별 고정 문구를 서버가 만들어 보냈지만, 지금은 REVIEWER 입력값이
     * 그대로 나간다. 같은 (영상 × 종류) 반복 요청이 허용되므로(V153 【2】) "이 파생본은 어떤
     * 조건으로 만든 것인가" 를 이 컬럼 없이는 되짚을 수 없다 — 그래서 <b>보낸 원문 그대로</b> 남긴다.
     *
     * <h3>담기는 모양은 두 가지이며 둘 다 정상이다 (2026-08-27 v1.3)</h3>
     * <ul>
     *   <li><b>현행</b> — 나간 바디와 같은 <b>분리 형태</b> {@code {"mtdt":{...},"prompt":"..."}}.
     *       자유 지시문이 없으면 그 키 자체가 없다.</li>
     *   <li><b>구 형태</b> — 조건 5필드가 최상위에 평평하게 놓인 {@code {"time":...,"season":...}}.
     *       v1.3 이전 요청분이며 <b>마이그레이션하지 않는다</b>.</li>
     * </ul>
     * <p>조회 경로({@code AugmentResultItemResponse.prompt}·{@code AugmentSummaryResponse})는 이 값을
     * <b>파싱하지 않고 문자열 그대로</b> 내려주므로 두 형태가 공존해도 깨지지 않는다. 파싱하는 소비자를
     * 새로 만들면 그때 두 형태를 모두 읽어야 한다.
     *
     * <p>표준용어 등록 복합용어 <b>프롬프트내용 = PROMPT_CN</b>, 사업도메인 <b>내용V4000</b>
     * (=VARCHAR(4000)) 을 물리명·크기 모두 등록값 그대로 채택했다. 적재량 상한은 조건 5항목(허용 코드)
     * + 자유 지시문 1,000자 + JSON 오버헤드이며, 컬럼 폭 초과는 적재 시점 500 이 되지 않도록
     * {@code AugmentRequestService} 가 <b>입구에서</b> 400 으로 끊는다.
     *
     * <p>해상도 파생(RESL_*)과 V153 이전 요청은 {@code null} 이다.
     */
    @Column(name = "PROMPT_CN", length = 4000)
    private String promptCn;

    /**
     * 이 요청이 만들어 낸 <b>파생 영상</b>({@code LS_DATA_RAW.RAW_SN}) — V155 신설.
     *
     * <p>표준용어 등록 복합용어 <b>신규원시일련번호 = NEW_RAW_SN</b>(데이터타입 N, 길이 19)을 물리명·
     * 타입 모두 등록값 그대로 채택했다.
     *
     * <h3>왜 필요한가</h3>
     * <p>증강 행 ↔ 파생 영상을 잇는 유일한 단서가 {@code VMS_CLIP_ID} 의 마커 문자열뿐이었고, 그 접미는
     * 증강 행 PK 가 아니라 <b>생성 시각</b>이다. 같은 (영상 × 종류) 반복 요청이 허용된 뒤로는(2026-07-31)
     * 유형만으로 짝지으면 <b>다른 요청의 파생본</b>을 가리킨다. 이 컬럼은 그 추정을 없앤다 — 결과 조회의
     * {@code derivativeRawSn} 과 <b>파생영상 등재 게이트</b>(파생은 검수 승인 뒤에만 작업 대상)가 모두
     * 이 값을 근거로 삼는다.
     *
     * <h3>NULL 의 의미는 둘이다 (게이트 판정에 직결)</h3>
     * <ul>
     *   <li><b>아직/영영 파생이 없다</b> — 생성 전(외부 콜백 대기)이거나 실패로 끝난 요청.</li>
     *   <li><b>V155 이전에 만들어진 파생</b> — 백필하지 않았다(시각 기반 역추정이 엉뚱한 행을 가리킨다).
     *       등재 게이트는 이 경우를 <b>그랜드퍼더링</b>으로 통과시킨다.</li>
     * </ul>
     * 두 의미가 겹쳐도 게이트는 안전하다 — 신규 파생을 만드는 경로는 <b>정확히 두 곳</b>
     * ({@code AugmentResultService.createAugmentedVideo} · {@code ResolutionReservationPersister.reserveAndCreate})
     * 이고 둘 다 파생 RAW 를 INSERT 한 <b>같은 트랜잭션</b>에서 이 값을 채우기 때문에, "신규 파생인데
     * NEW_RAW_SN 이 NULL" 인 상태는 커밋될 수 없다.
     */
    @Column(name = "NEW_RAW_SN")
    private Long newRawSn;

    /** Phase 4 비동기 표준 컬럼 — 재시도 횟수. */
    @Column(name = "RTRY_NMTM", nullable = false)
    private int retryCount;

    /** Phase 4 비동기 표준 컬럼 — 영구 실패(dead-letter) 마킹 시점. */
    @Column(name = "DEAD_LETTER_AT")
    private LocalDateTime deadLetterAt;

    @Builder
    private LsDataAug(Long srcSn, String augTypeCd, String augProcSttsCd,
                      BigDecimal lblIntgrtPct, String rejectRsn,
                      String dcsnUserNo, LocalDateTime dcsnDt,
                      LocalDateTime regDt, String regUserNo,
                      String idempotencyKey, String externalJobId, String promptCn) {
        this.srcSn = srcSn;
        this.augTypeCd = augTypeCd;
        this.augProcSttsCd = augProcSttsCd;
        this.lblIntgrtPct = lblIntgrtPct;
        this.rejectRsn = rejectRsn;
        this.dcsnUserNo = dcsnUserNo;
        this.dcsnDt = dcsnDt;
        this.regDt = regDt;
        this.regUserNo = regUserNo;
        this.idempotencyKey = idempotencyKey;
        this.externalJobId = externalJobId;
        this.promptCn = promptCn;
        this.retryCount = 0;
    }

    /**
     * 외부 시스템에서 생성된 PENDING 상태의 증강 결과를 신규 등록한다.
     */
    public static LsDataAug createPending(Long srcSn, String augTypeCd,
                                          BigDecimal lblIntgrtPct,
                                          String regUserNo) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augTypeCd)
                .augProcSttsCd(STTS_PENDING)
                .lblIntgrtPct(lblIntgrtPct)
                .regDt(LocalDateTime.now())
                .regUserNo(regUserNo)
                .build();
    }

    /**
     * 콜백 충실 플로우 요청용 — PENDING 행을 멱등 키/외부 작업 ID 와 함께 단일 INSERT 로 적재한다
     * (DEV_FIX MEDIUM-2: 이중 save 로 인한 IDMP_KEY=null orphan aug 경계 제거).
     *
     * <p>idempotencyKey 는 UUID 기반(dataAugSn 비의존)이므로 save 이전에 미리 발급해 행에 실어
     * 한 번의 save 로 커밋한다.
     *
     * <p>프롬프트를 남기지 않는 호출 전용 오버로드다(테스트 시드 등). 실제 요청 경로는
     * {@link #createRequested(Long, String, String, String, String, String)} 를 쓴다 — 그쪽이
     * 전송한 prompt 원문을 함께 적재한다.
     */
    public static LsDataAug createRequested(Long srcSn, String augTypeCd, String regUserNo,
                                            String idempotencyKey, String externalJobId) {
        return createRequested(srcSn, augTypeCd, regUserNo, idempotencyKey, externalJobId, null);
    }

    /**
     * 콜백 충실 플로우 요청용 — 위와 동일하되 <b>외부로 전송한 prompt JSON 원문</b>을 함께 적재한다(V153).
     *
     * <p>같은 (영상 × 종류) 반복 요청이 허용되므로(2026-07-31 정책) 파생본마다 "어떤 조건으로
     * 만들었는가" 를 남겨야 사후 역추적이 가능하다. 전송본과 저장본이 어긋나지 않도록
     * <b>같은 dict 에서 만든 문자열</b>을 넘긴다(호출부 {@code AugmentRequestService} 참조).
     */
    public static LsDataAug createRequested(Long srcSn, String augTypeCd, String regUserNo,
                                            String idempotencyKey, String externalJobId,
                                            String promptCn) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augTypeCd)
                .augProcSttsCd(STTS_PENDING)
                .regDt(LocalDateTime.now())
                .regUserNo(regUserNo)
                .idempotencyKey(idempotencyKey)
                .externalJobId(externalJobId)
                .promptCn(promptCn)
                .build();
    }

    /**
     * 저작도구 내부 해상도 파생 <b>예약</b>행을 {@link #STTS_PENDING} 상태로 신규 등록한다 — RQ-SFR-06-03 파생영상.
     *
     * <p>해상도 파생 aug 상태는 파생영상 <b>생성 라이프사이클</b>과 일치한다: 예약 시점에는 파생 RAW 가 아직
     * PENDING(생성 중)이므로 aug 도 {@link #STTS_PENDING}(=생성 중, non-terminal)으로 커밋한다. finalize 성공
     * 확정 시에만 {@link #markResolutionGenerated()} 로 {@link #STTS_ACCEPTED}(=생성 완료, terminal)로 전이한다.
     * 이로써 예약~확정 사이의 in-flight 창에서 증강 이력 집계가 조기 COMPLETED 로 오표기되지 않는다.
     *
     * <p>해상도 파생은 외부 콜백/라벨 검수 대상이 아니라 내부 생성물이므로, 외부 증강(WINTER/NIGHT/RAIN)의
     * accept/reject 검수 플로우를 타지 않는다. 파생영상 본체(RAW)의 라벨링·검수 워크플로우는
     * 별도(LS_RAW_DATA_STATUS)로 진행되며 이 증강 행의 상태와 무관하다.
     *
     * <p>{@code IDMP_KEY}/{@code OTSD_JOB_ID} 는 콜백형 증강(webhook 인계) 전용 컬럼이므로 해상도 경로는
     * NULL 로 고정한다. {@code RTRY_NMTM=0}.
     *
     * @param srcSn        대표프레임 SRC_SN (measureFirstFrame 이 확정한 원본 첫 프레임 — 단일 기준)
     * @param augResTypeCd 해상도 파생 코드({@link #RESL_PREFIX} 접두 필수, 예: RESL_720P)
     * @param regUserNo    등록자(REVIEWER) 토큰 sub
     */
    public static LsDataAug createResolutionPending(Long srcSn, String augResTypeCd, String regUserNo) {
        return buildResolution(srcSn, augResTypeCd, regUserNo, STTS_PENDING);
    }

    /**
     * 이미 생성 완료된 해상도 파생 aug 행({@link #STTS_ACCEPTED})을 직접 구성한다 — 테스트/레거시 데이터
     * 시딩 전용(post-finalize 상태 재현). 정상 생성 경로는 {@link #createResolutionPending}(예약) +
     * {@link #markResolutionGenerated()}(확정 전이)를 사용한다.
     */
    public static LsDataAug createResolutionAccepted(Long srcSn, String augResTypeCd, String regUserNo) {
        return buildResolution(srcSn, augResTypeCd, regUserNo, STTS_ACCEPTED);
    }

    private static LsDataAug buildResolution(Long srcSn, String augResTypeCd, String regUserNo, String status) {
        if (augResTypeCd == null || !augResTypeCd.startsWith(RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "해상도 파생 코드는 'RESL_' 접두여야 합니다.");
        }
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augResTypeCd)
                .augProcSttsCd(status)
                .regDt(LocalDateTime.now())
                .regUserNo(regUserNo)
                .build();
    }

    /**
     * 해상도 파생 예약행을 생성 완료(PENDING→ACCEPTED)로 전이한다 — RQ-SFR-06-03 파생영상.
     *
     * <p>finalize 성공 확정 경로에서만 호출된다(REQUIRES_NEW 원자성 내, markMarkingReady/markDeidentified 와
     * 동일 트랜잭션). RESL_ 접두 + PENDING 가드로 오배송/이중 전이를 차단한다. 전이 후 집계는 terminal 로
     * 관측되어 COMPLETED(파생 생성 완료)로 정합된다.
     */
    public void markResolutionGenerated() {
        if (augTypeCd == null || !augTypeCd.startsWith(RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "해상도 파생 행만 생성 완료로 전이할 수 있습니다.");
        }
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 해상도 파생 행입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = STTS_ACCEPTED;
    }

    /**
     * 검수 결과를 LsDataAug.augProcSttsCd 에도 동기 반영 (DB 설계서 라인 162-169 호환).
     * 상세 audit 컬럼(LBL_INTGRT_PCT/RJCT_RSN/DCSN_USER_NO/DCSN_DT)은 LS_DATA_AUG_RVW 에서 관리.
     */
    /**
     * 종결 상태 판정 <b>단일 원천</b> — 검수 결과 축({@code AUG_PROC_STTS_CD})에서 "더 바뀌지 않는" 값.
     *
     * <p>이 판정을 호출처마다 문자열 비교로 복제하면 상태가 늘 때 한쪽만 고쳐져 조용히 어긋난다
     * (실제로 CANCELED 신설 시 {@code AugmentReviewService.isTerminal} 만 고치고 다른 곳을 빠뜨리면
     * 그 그룹이 영원히 진행중으로 보인다). 판정은 여기 한 곳에서만 한다.
     */
    public static boolean isTerminalStatus(String status) {
        return STTS_ACCEPTED.equals(status)
                || STTS_REJECTED.equals(status)
                || STTS_CANCELED.equals(status);
    }

    /** 이 증강이 종결됐는가 — {@link #isTerminalStatus(String)} 위임. */
    public boolean isTerminal() {
        return isTerminalStatus(this.augProcSttsCd);
    }

    /**
     * 사용자 취소 확정 — {@link #STTS_PENDING} → {@link #STTS_CANCELED}.
     *
     * <p><b>취소 요청 트랜잭션의 "클레임"</b>이다(S4). 호출자는 반드시
     * {@code findByDataAugSnForUpdate}(FOR UPDATE)로 이 행을 잠근 뒤 호출해야 하며, 그래야
     * ①동시 취소 2건이 직렬화되고 ②외부 취소 호출이 <b>정확히 한 번만</b> 나간다. 잠금 없이 부르면
     * 두 요청이 모두 PENDING 을 관측해 둘 다 외부로 나가고, 두 번째가 벤더 409 를 받는다.
     *
     * <p><b>웹훅과의 경합도 같은 잠금으로 정리된다</b> — 콜백 경로({@code GenAiCallbackService} →
     * {@code AugmentResultService})도 같은 행을 FOR UPDATE 로 잠그므로, 취소가 먼저 커밋되면 늦게 온
     * 성공 결과는 non-PENDING 앵커에 흡수되어 폐기된다(WARN 만 남는다 — 의도된 동작).
     *
     * <p>비-PENDING 재취소는 {@link ErrorCode#CONFLICT} 다. 다만 <b>API 는 이 예외를 사용자에게
     * 노출하지 않는다</b> — 호출자가 잠금 안에서 상태를 먼저 보고 멱등 200 으로 회신한다.
     */
    public void markCanceled() {
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 증강 결과입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = STTS_CANCELED;
    }

    /**
     * <b>생성 결과</b> 확정 — {@link #STTS_PENDING} → {@link #STTS_ACCEPTED}(생성 성공) /
     * {@link #STTS_REJECTED}(생성 실패).
     *
     * <h3>★ 이 축은 사람의 사용/폐기 결정이 아니다 (2026-07-31 확정 — 다시 합치지 말 것)</h3>
     * <p>{@code AUG_PROC_STTS_CD} 는 <b>외부/내부 생성기가 소유</b>한다 — 증강은 웹훅
     * ({@code AugmentResultService}), 해상도는 finalize({@link #markResolutionGenerated()})가 쓴다.
     * REVIEWER 의 채택/반려는 <b>{@code LS_DATA_AUG_RVW.RVW_STTS_CD} 가 단독으로 소유</b>한다.
     *
     * <p>구 구현은 검수 서비스({@code AugmentReviewService.accept/reject})도 이 메서드를 호출해 한
     * 컬럼에 두 주체가 썼다. 외부 증강은 <b>웹훅이 항상 먼저</b> 도착해 PENDING 을 소진하므로, 그
     * 뒤에 오는 REVIEWER 의 승인·반려는 아래 non-PENDING 가드에 걸려 <b>영구히 409</b> 였다 —
     * 사용/폐기 워크플로 자체가 도달 불가능했다. 그래서 호출자를 생성기 하나로 좁히고 이름도
     * 축(생성 결과)에 맞췄다(구 {@code applyReviewStatus}).
     */
    public void applyGenerationResult(String newStatus) {
        if (newStatus == null
                || (!STTS_ACCEPTED.equals(newStatus) && !STTS_REJECTED.equals(newStatus))) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "유효하지 않은 생성 결과 상태입니다. status=" + newStatus);
        }
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 증강 결과입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = newStatus;
    }

    /**
     * <b>결정할 결과물이 실재하는가</b> — 사람의 사용/폐기 결정(accept/reject) 가능 여부의
     * <b>판정 단일 원천</b>.
     *
     * <h3>왜 이 가드가 필요한가 (2026-07-31 DEV_FIX HIGH — 되돌리지 말 것)</h3>
     * <p>축 분리 이전에는 {@code applyReviewStatus} 의 "PENDING 에서만 전이" 가드가 <b>부수적으로</b>
     * "생성이 끝나기 전에는 결정할 수 없다" 를 강제하고 있었다. 축을 가르며 그 호출을 걷어내자
     * 대체 가드 없이 사라져, <b>요청 직후(결과물 0건) 상태의 증강을 승인</b>할 수 있게 됐다 —
     * 그러면 뒤늦게 도착한 콜백이 만든 파생영상이 <b>사람이 한 번도 보지 않은 채</b> 등재 게이트를
     * 통과한다(게이트는 {@code ACCEPTED} 검수 행 존재만 본다). 상위 요구가 "이미지를 비교해 보고
     * 사용 여부를 선택" 이므로 결정은 결과물 실재를 전제로만 성립한다.
     *
     * <h3>판정 기준 = {@code AUG_PROC_STTS_CD == ACCEPTED} <b>이고</b> dead-letter 가 아님</h3>
     * <p>{@code NEW_RAW_SN != null} 도 후보였으나 채택하지 않았다. 두 값은 외부 증강 경로에서
     * <b>같은 트랜잭션</b>에 확정되므로({@code AugmentResultService.handle} — 성공 ⟺ 상태 전이 +
     * {@code createAugmentedVideo} 가 한 커밋) 신규 데이터에서는 등가지만, {@code NEW_RAW_SN} 은
     * V155 신설이라 <b>그 이전 파생은 매핑이 없다</b>. 매핑을 기준으로 삼으면 실제 결과물이 있는
     * 레거시 증강까지 결정 불가가 되어 그랜드퍼더링 정책과 어긋난다.
     *
     * <h3>★ dead-letter 축을 함께 본다 (2026-07-31 DEV_FIX MEDIUM — 되돌리지 말 것)</h3>
     * <p>구 판정은 상태 컬럼만 봤다. 그런데 <b>비동기 확정(Phase A/B/C) 실패</b>는 상태를 건드리지
     * 못한다 — {@code applyGenerationResult} 가 PENDING 에서만 전이를 허용하기 때문에
     * {@code AugmentExtractPersist.markAugProcessingFailed} 는 <b>{@code ACCEPTED} 를 그대로 둔 채
     * {@code DEAD_LETTER_AT} 만 찍는다</b>(그 파일 javadoc 이 이 마커를 "이 도메인의 실패 판정 축"
     * 으로 선언한다). 그래서 상태만 보는 판정은 <b>프레임 0건으로 영구 실패한 파생</b>을 "결정 가능"
     * 으로 통과시켰고, 그 결과 ①결과물 없는 채택이 성립해 등재 게이트를 통과했으며 ②같은 화면의
     * 헤더는 {@code aggregateStatus}({@link #isProcessingFailed()})로 FAILED 를 표시해 <b>한 화면에서
     * 두 축이 상반</b>됐다.
     *
     * <p>따라서 이 판정과 {@link #isProcessingFailed()} 는 <b>같은 사실</b>을 말한다 —
     * 실패로 못박힌 증강은 어느 축에서도 "성공/결정 가능" 으로 보이지 않는다.
     *
     * <p><b>자기 반증</b>: {@code ACCEPTED} 인데 결과물이 없는 경로가 있는가? 이 값을
     * {@code ACCEPTED} 로 쓰는 곳은 정확히 셋이다 — ①{@link #applyGenerationResult}(웹훅; 성공은
     * 부모 게이트 PASS 를 전제로 하고 같은 트랜잭션에서 파생 RAW 를 INSERT 한다. 영상 생성이 실패로
     * 튀면 트랜잭션째 롤백되어 {@code ACCEPTED} 도 커밋되지 않는다) ②{@link #markResolutionGenerated()}
     * ③{@link #createResolutionAccepted} — ②③은 {@code RESL_} 접두 전용이고 그 행은 검수 진입
     * 자체가 앞단에서 차단된다({@code AugmentReviewService.loadOrThrow}). 따라서 이 판정으로
     * 통과하는 외부 증강에는 파생 영상 행이 반드시 존재한다.
     *
     * <p><b>보장하지 않는 것</b>: 파생의 <b>프레임·라벨</b>은 커밋 후 비동기로 채워지므로
     * (Phase 11 {@code AsyncAugmentFrameRunner}) 결정 시점에 비교 이미지가 아직 0장일 수 있다.
     * 이 가드가 세우는 불변식은 "결정 대상 파생영상이 실재하고 그 확정이 실패로 끝나지 않았다" 이지
     * "프레임까지 완비됐다" 가 아니다(반입 진행 중과 영구 실패의 구분은
     * {@code AugmentResultViewService} 의 {@code resultState} 가 화면에 전달한다).
     */
    public boolean isGenerationSucceeded() {
        return STTS_ACCEPTED.equals(this.augProcSttsCd) && !isProcessingFailed();
    }

    /**
     * 생성이 아직 진행 중인가 — 결정 불가 사유를 "아직 없음" 과 "영영 없음" 으로 가르는 축.
     * (거부 응답 문구를 나누기 위한 것으로, 판정 자체는 {@link #isGenerationSucceeded()} 가 한다.)
     */
    public boolean isGenerationInProgress() {
        return STTS_PENDING.equals(this.augProcSttsCd);
    }

    /**
     * 이 요청이 만든 파생 영상({@code LS_DATA_RAW.RAW_SN})을 연결한다 — {@link #newRawSn} 참조.
     *
     * <p><b>반드시 파생 RAW 를 INSERT 한 그 트랜잭션 안에서</b> 호출한다(커밋 후 비동기로 미루면
     * 그 사이 등재 게이트가 "매핑 없는 파생" 으로 보고 그랜드퍼더링 통과시켜 미검수 파생이 목록에
     * 뜬다). 두 호출부 모두 같은 트랜잭션에 두 객체를 이미 들고 있으므로 재조회가 필요 없다 —
     * <b>{@code findById} 로 다시 로드한 별도 인스턴스에 쓰지 말 것</b>(그 쓰기는 원 인스턴스의
     * dirty checking 에 덮여 사라질 수 있다).
     *
     * <p>1회 배정만 허용한다. 이미 다른 파생을 가리키는 행을 덮어쓰면 앞선 파생이 매핑을 잃고
     * 그랜드퍼더링으로 새어 나간다.
     */
    public void assignDerivativeRawSn(Long newRawSn) {
        if (newRawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "파생 영상 식별자가 없습니다.");
        }
        if (this.newRawSn != null && !this.newRawSn.equals(newRawSn)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 다른 파생 영상이 연결된 증강 행입니다. dataAugSn=" + this.dataAugSn);
        }
        this.newRawSn = newRawSn;
    }

    // ============================================================
    // Phase 4 — 비동기 표준 컬럼 비즈니스 메서드 (setter 금지 패턴)
    // ============================================================

    /** 멱등 키 할당. 신규 webhook 인계 시점에 1회 호출. */
    public void assignIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /** 외부 작업 ID 할당. */
    public void assignExternalJobId(String externalJobId) {
        this.externalJobId = externalJobId;
    }

    /**
     * 재시도 횟수 1 증가.
     *
     * <p>호출 지점(Phase 8-B 배선): 처리 실패 확정({@code AugmentResultService} 실패 인계). 즉 이 값은
     * "이 증강 1건에 대해 몇 번 재차 시도했는가" 를 누적한다.
     *
     * <p>구 서술의 "비식별 누락 신고 해소 후 보류분 재개" 경로는 2026-07-29 로 폐기됐다(보류 개념
     * 자체가 없어졌고 재개 리스너도 삭제됐다).
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }

    /**
     * 영구 실패 마킹 — dead-letter 큐 진입.
     *
     * <p>증강 채널에는 실패 이후 자동 재시도 구동기가 없다(보류·재개 개념은 2026-07-29 폐기 —
     * 재요청은 운영자가 명시적으로 다시 요청하는 것뿐이다).
     * 따라서 처리 실패가 확정되는 순간이 곧 영구 실패이며, {@code AugmentResultService} 의 실패
     * 인계 경로가 이 메서드를 호출한다. 이 마커가 있어야 집계가 실패를 실패로 보인다
     * ({@link #isProcessingFailed()}).
     */
    public void markDeadLetter() {
        this.deadLetterAt = LocalDateTime.now();
    }

    /**
     * <b>외부 처리 축</b>의 실패로 영구 종결됐는가 (E-ISSUE-06/E-06 파생).
     *
     * <h3>왜 {@code REJECTED} 자체를 실패 판정 축으로 쓸 수 없는가</h3>
     * <p>{@link #augProcSttsCd}(PENDING/ACCEPTED/REJECTED)는 <b>검수 결과 축</b>이다. REVIEWER 의
     * 정상 반려도 {@code REJECTED} 이고, 외부 처리 실패 롤업도 {@code REJECTED} 로 종결된다. 두
     * 경우를 {@code REJECTED} 문자열만으로 구분하면 검수 반려가 장애로 보이거나(오탐) 반대로 처리
     * 실패가 "검수 완료" 로 보인다(미탐). 그래서 실패 판정은 <b>처리 실패 전용 마커</b>인
     * {@code DEAD_LETTER_AT} 로만 한다 — 이 마커는 실패 인계 경로에서만 찍힌다.
     *
     * <p>job 상태 축(RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED, {@code LS_DATA_AUG_JOB})과도
     * 별개 코드 공간이다. 세 축을 섞지 않는다.
     */
    public boolean isProcessingFailed() {
        return deadLetterAt != null;
    }
}
