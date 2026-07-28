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
                      String idempotencyKey, String externalJobId) {
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
     */
    public static LsDataAug createRequested(Long srcSn, String augTypeCd, String regUserNo,
                                            String idempotencyKey, String externalJobId) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augTypeCd)
                .augProcSttsCd(STTS_PENDING)
                .regDt(LocalDateTime.now())
                .regUserNo(regUserNo)
                .idempotencyKey(idempotencyKey)
                .externalJobId(externalJobId)
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
    public void applyReviewStatus(String newStatus) {
        if (newStatus == null
                || (!STTS_ACCEPTED.equals(newStatus) && !STTS_REJECTED.equals(newStatus))) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "유효하지 않은 검수 상태입니다. status=" + newStatus);
        }
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 증강 결과입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = newStatus;
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
     * <p>호출 지점(Phase 8-B 배선): ①처리 실패 확정({@code AugmentResultService} 실패 인계)
     * ②비식별 누락 신고 해소 후 <b>보류분 재개</b>({@code AugmentRequestBridge}). 즉 이 값은
     * "이 증강 1건에 대해 몇 번 재차 시도했는가" 를 누적한다.
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }

    /**
     * 영구 실패 마킹 — dead-letter 큐 진입.
     *
     * <p>증강 채널에는 실패 이후 자동 재시도 구동기가 없다(재개는 <b>보류</b> 해제 트리거뿐이다).
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
