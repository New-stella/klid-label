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
}
