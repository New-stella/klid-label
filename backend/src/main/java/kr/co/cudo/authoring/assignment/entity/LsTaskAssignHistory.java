package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 작업자 재배정 이력. {@code AUTHRT_SEQ} 컬럼명은 V36 마이그레이션에서 그대로 유지되며,
 * 기존 운영 데이터의 매핑 키를 보존하기 위해 Entity 필드도 {@code authrtSeq} 그대로 사용한다.
 * 신규 LS_TASK_ASSIGNMENT.ASSIGNMENT_ID 와의 연결 재매핑은 운영 데이터 마이그레이션 시점에 별도 backfill 로 결정.
 */
@Entity
@Table(name = "LS_TASK_ASSIGN_HISTORY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsTaskAssignHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HSTRY_SEQ")
    private Long hstrySeq;

    @Column(name = "AUTHRT_SEQ", nullable = false)
    private Long authrtSeq;

    @Column(name = "RAW_DATA_ID", nullable = false)
    private Long rawDataId;

    @Column(name = "PREV_USER_NO", nullable = false)
    private Long prevUserNo;

    @Column(name = "NEW_USER_NO", nullable = false)
    private Long newUserNo;

    @Column(name = "TASK_TYPE_CD", nullable = false, length = 20)
    private String taskTypeCd;

    @Column(name = "CHG_USER_NO", nullable = false)
    private Long chgUserNo;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;

    @Builder
    private LsTaskAssignHistory(Long authrtSeq, Long rawDataId, Long prevUserNo, Long newUserNo,
                                String taskTypeCd, Long chgUserNo, LocalDateTime chgDt) {
        this.authrtSeq = authrtSeq;
        this.rawDataId = rawDataId;
        this.prevUserNo = prevUserNo;
        this.newUserNo = newUserNo;
        this.taskTypeCd = taskTypeCd;
        this.chgUserNo = chgUserNo;
        this.chgDt = chgDt;
    }

    /**
     * 재배정 이력 생성 — AUTHRT_SEQ 컬럼은 신규 ASSIGNMENT_ID 값을 그대로 매핑한다.
     * (운영 환경에서는 backfill 정책에 따라 별도 재매핑 가능)
     */
    public static LsTaskAssignHistory record(LsTaskAssignment prev, Long newUserNo, Long chgUserNo) {
        return LsTaskAssignHistory.builder()
                .authrtSeq(prev.getAssignmentId())
                .rawDataId(prev.getRawDataId())
                .prevUserNo(prev.getUserNo())
                .newUserNo(newUserNo)
                .taskTypeCd(prev.getTaskTypeCd())
                .chgUserNo(chgUserNo)
                .chgDt(LocalDateTime.now())
                .build();
    }
}
