package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
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

@Entity
@Table(name = "LS_TASK_ASSIGNMENT", uniqueConstraints = {
        @UniqueConstraint(name = "UK_LS_TASK_ASSIGNMENT",
                columnNames = {"RAW_DATA_ID", "USER_NO", "TASK_TYPE_CD"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsTaskAssignment {

    public static final String TASK_LABELER = "LABELER";
    public static final String TASK_REVIEWER = "REVIEWER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ASSIGNMENT_ID")
    private Long assignmentId;

    @Column(name = "USER_NO", nullable = false)
    private Long userNo;

    @Column(name = "RAW_DATA_ID", nullable = false)
    private Long rawDataId;

    @Column(name = "TASK_TYPE_CD", nullable = false, length = 20)
    private String taskTypeCd;

    @Column(name = "REG_USER_NO", nullable = false)
    private Long regUserNo;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Builder
    private LsTaskAssignment(Long userNo, Long rawDataId, String taskTypeCd,
                             Long regUserNo, LocalDateTime regDt) {
        this.userNo = userNo;
        this.rawDataId = rawDataId;
        this.taskTypeCd = taskTypeCd;
        this.regUserNo = regUserNo;
        this.regDt = regDt;
    }

    public static LsTaskAssignment createLabeler(Long rawDataId, Long workerNo, Long actorNo) {
        return LsTaskAssignment.builder()
                .userNo(workerNo)
                .rawDataId(rawDataId)
                .taskTypeCd(TASK_LABELER)
                .regUserNo(actorNo)
                .regDt(LocalDateTime.now())
                .build();
    }

    public static LsTaskAssignment createReviewer(Long rawDataId, Long reviewerNo, Long actorNo) {
        return LsTaskAssignment.builder()
                .userNo(reviewerNo)
                .rawDataId(rawDataId)
                .taskTypeCd(TASK_REVIEWER)
                .regUserNo(actorNo)
                .regDt(LocalDateTime.now())
                .build();
    }

    public void reassignTo(Long newWorkerNo) {
        this.userNo = newWorkerNo;
    }
}
