package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_RAW_DATA_STATUS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsRawDataStatus {

    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_ASSIGNED = "ASSIGNED";
    // Phase 7 검수 워크플로우 상태 (CM_CODE.GROUP_CODE='DATA_STTS_CD' V3 seed 와 일치)
    public static final String STTS_BATCH_QUEUED = "BATCH_QUEUED";
    // Phase 7 검수 워크플로우 상태 (CM_CODE.GROUP_CODE='DATA_STTS_CD' V3 seed 와 일치)
    public static final String STTS_IN_REVIEW = "IN_REVIEW";
    public static final String STTS_PROCESSING = "PROCESSING";
    public static final String STTS_COMPLETED = "COMPLETED";
    public static final String STTS_FAILED = "FAILED";
    public static final String STTS_APPROVED = "APPROVED";
    public static final String STTS_REJECTED = "REJECTED";

    /** {@code REVLT_YN} 허용값 — 재검토 필요. */
    public static final String REVLT_YES = "Y";
    /** {@code REVLT_YN} 허용값(기본) — 재검토 불요. */
    public static final String REVLT_NO = "N";

    @Id
    @Column(name = "RAW_DATA_ID")
    private Long rawDataId;

    @Column(name = "DATA_STTS_CD", nullable = false, length = 20)
    private String dataSttsCd;

    @Column(name = "STP_CYCL", nullable = false)
    private int stpCycl;

    @Column(name = "IGI_CYCL", nullable = false)
    private int igiCycl;

    @Column(name = "UPD_DT", nullable = false)
    private LocalDateTime updDt;

    /**
     * 낙관적 잠금 (CWE-362 비즈니스 로직 Race Condition 방어).
     * 동시 두 REVIEWER 가 같은 영상을 승인 시도할 때 1건만 성공 → 다른 1건은 OptimisticLockException.
     */
    @Version
    @Column(name = "VER", nullable = false)
    private Long version;

    /**
     * 재검토여부(V177, REVLT_YN) — 검수 승인 이후 라벨/메타가 수정되어 재검토가 필요한가.
     *
     * <p><b>왜 생성자 파라미터가 아니라 필드 초기화인가</b>: 이 클래스의 {@code @Builder} 는 아래
     * 명시 생성자(explicit constructor)의 파라미터 목록만 빌더 필드로 노출한다. {@code revltYn} 을
     * 그 목록에 넣지 않고 필드 선언에서 바로 {@code REVLT_NO} 로 초기화하면, 빌더·{@link #initial}
     * 등 <b>모든 생성 경로</b>가 예외 없이 "N" 으로 시작한다(신규 행은 항상 재검토 불요) — 기존
     * {@code .builder()...build()} 호출부(테스트 다수)를 전혀 손대지 않고도 NOT NULL 제약을
     * 만족시키는 가장 안전한 방법이다. 이후 값은 {@link #markNeedsRecheck()}/{@link #clearNeedsRecheck()}
     * 로만 바뀐다(Setter 금지 원칙).
     */
    @Column(name = "REVLT_YN", nullable = false, length = 1)
    private String revltYn = REVLT_NO;

    @Builder
    private LsRawDataStatus(Long rawDataId, String dataSttsCd, int stpCycl, int igiCycl, LocalDateTime updDt) {
        this.rawDataId = rawDataId;
        this.dataSttsCd = dataSttsCd;
        this.stpCycl = stpCycl;
        this.igiCycl = igiCycl;
        this.updDt = updDt;
    }

    public static LsRawDataStatus initial(Long rawDataId) {
        return LsRawDataStatus.builder()
                .rawDataId(rawDataId)
                .dataSttsCd(STTS_PENDING)
                .stpCycl(0)
                .igiCycl(0)
                .updDt(LocalDateTime.now())
                .build();
    }

    /**
     * 배정 완료 전이 (검증 없음).
     *
     * <p><b>호출 전 상태 검증은 호출자 책임</b>이다. 본 메서드는 현재 상태를 보지 않으므로 종결 상태
     * (APPROVED)에도 적용된다 — {@code AssignmentService.assign} 은 호출 <b>전에</b>
     * APPROVED 대상을 409(ASSIGNMENT_ALREADY_COMPLETED)로 거부한다(D-ISSUE-01).
     */
    public void markAssigned() {
        this.dataSttsCd = STTS_ASSIGNED;
        this.updDt = LocalDateTime.now();
    }

    public void markBatchQueued() {
        this.dataSttsCd = STTS_BATCH_QUEUED;
        this.updDt = LocalDateTime.now();
    }

    /**
     * 검수 워크플로우 상태 전이 (의미 있는 비즈니스 메서드 — Setter 금지 원칙 준수).
     *
     * <p><b>본 메서드는 아무 검증도 하지 않는다. 검증은 호출자 책임이다</b> (B-ISSUE-03 정정 —
     * 과거 주석은 검증을 {@code ReviewStateMachine} 책임이라 했으나 배치 경로는 상태 머신을 호출하지
     * 않아 아무도 검증하지 않는 공백이 있었다). 현재 호출자는 두 곳뿐이며 각자 사전 검증을 갖는다:
     * <ul>
     *   <li>{@code ReviewService}(submit/cancelSubmit/startReview/approve/reject) — {@code ReviewStateMachine.verify}</li>
     *   <li>배치 경로 — {@code BatchTransitionService} 가 조건부 UPDATE 로 검수 소유 상태를 차단하므로
     *       본 메서드를 호출하지 않는다.</li>
     * </ul>
     */
    public void transitionTo(String newStatus) {
        this.dataSttsCd = newStatus;
        this.updDt = LocalDateTime.now();
    }

    /**
     * 재검토 필요로 표시(V177, Phase 7a-1) — <b>멱등</b>. 이미 {@code Y} 면 값 변경이 일어나지 않으므로
     * (같은 값 재대입) Hibernate dirty-checking 이 UPDATE 를 내지 않는다 — 반복 호출해도 부작용이
     * 쌓이지 않는다. {@code UPD_DT}(작업 상태 최종 수정 시각 — {@code transitionTo}/{@code markAssigned}
     * 등이 갱신하는 값)는 건드리지 않는다 — 재검토 표시는 <b>다른 축</b>(수정 발생 여부)이라 상태
     * 전이 시각 의미를 오염시키지 않는다.
     */
    public void markNeedsRecheck() {
        this.revltYn = REVLT_YES;
    }

    /**
     * 재검토 표시 해제(Phase 7a-2 재승인 경로에서 사용 예정) — <b>멱등</b>. 이미 {@code N} 이면 no-op.
     */
    public void clearNeedsRecheck() {
        this.revltYn = REVLT_NO;
    }

    /** 검수 승인 이후 수정되어 재검토가 필요한 상태인가. */
    public boolean needsRecheck() {
        return REVLT_YES.equals(this.revltYn);
    }
}
