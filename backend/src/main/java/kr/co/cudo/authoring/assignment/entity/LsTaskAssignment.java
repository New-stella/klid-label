package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_TASK_ALTMNT", uniqueConstraints = {
        @UniqueConstraint(name = "UK_LS_TASK_ALTMNT",
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

    /**
     * 낙관적 잠금 (CWE-362 — D-ISSUE-02 동시 재배정 직렬화).
     *
     * <p>재배정은 기존 row 를 UPDATE 하므로 UK(RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 위반이 발생하지 않아
     * {@code DataIntegrityViolationException} 방어가 발화하지 않았고, 동일작업자 가드도 동시 요청이 모두
     * 커밋 전 값을 읽어 통과했다(4병렬 → 4건 전부 성공, 이력·이벤트 로그 4행 중복).
     * 2노드 Active-Active 배포라 JVM 락은 방어가 되지 않으므로 DB 낙관적 잠금으로 직렬화한다.
     */
    @Version
    @Column(name = "VER", nullable = false)
    private Long version;

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
