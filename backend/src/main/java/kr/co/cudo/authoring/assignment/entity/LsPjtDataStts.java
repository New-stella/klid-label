package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "LS_PJT_DATA_STTS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjtDataStts {

    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_ASSIGNED = "ASSIGNED";
    // Phase 7 검수 워크플로우 상태 (CM_CODE.GROUP_CODE='DATA_STTS_CD' V3 seed 와 일치)
    public static final String STTS_IN_REVIEW = "IN_REVIEW";
    public static final String STTS_APPROVED = "APPROVED";
    public static final String STTS_REJECTED = "REJECTED";

    @EmbeddedId
    private Pk id;

    @Column(name = "DATA_STTS_CD", nullable = false, length = 32)
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
    @Column(name = "VERSION", nullable = false)
    private Long version;

    @Builder
    private LsPjtDataStts(Pk id, String dataSttsCd, int stpCycl, int igiCycl, LocalDateTime updDt) {
        this.id = id;
        this.dataSttsCd = dataSttsCd;
        this.stpCycl = stpCycl;
        this.igiCycl = igiCycl;
        this.updDt = updDt;
    }

    public static LsPjtDataStts initial(Long pjtId, Long rawDataId) {
        return LsPjtDataStts.builder()
                .id(Pk.of(pjtId, rawDataId))
                .dataSttsCd(STTS_PENDING)
                .stpCycl(0)
                .igiCycl(0)
                .updDt(LocalDateTime.now())
                .build();
    }

    public void markAssigned() {
        this.dataSttsCd = STTS_ASSIGNED;
        this.updDt = LocalDateTime.now();
    }

    /**
     * 검수 워크플로우 상태 전이 (의미 있는 비즈니스 메서드 — Setter 금지 원칙 준수).
     * 전이 가능 여부 검증은 ReviewStateMachine 가 책임지며, 본 메서드는 단순 갱신만 수행.
     */
    public void transitionTo(String newStatus) {
        this.dataSttsCd = newStatus;
        this.updDt = LocalDateTime.now();
    }

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Pk implements Serializable {
        @Column(name = "PJT_ID")
        private Long pjtId;

        @Column(name = "RAW_DATA_ID")
        private Long rawDataId;

        private Pk(Long pjtId, Long rawDataId) {
            this.pjtId = pjtId;
            this.rawDataId = rawDataId;
        }

        public static Pk of(Long pjtId, Long rawDataId) {
            return new Pk(pjtId, rawDataId);
        }
    }
}
