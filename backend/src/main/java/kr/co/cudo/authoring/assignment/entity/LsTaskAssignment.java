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
    /**
     * <b>(구) 검수자 배정 — 새로 쓰지 않는다. 이미 적재된 행을 판독하기 위해서만 존치한다.</b>
     *
     * <p>검수는 배정 없이 전체 대기열에서 집어가므로 배정을 인가 축으로 쓰지 않는다. 그래서 이 값으로
     * 행을 만드는 경로를 없앴다(구 {@code createReviewer} 팩토리 제거). 상수를 <b>지우지 말 것</b> —
     * 지우면 남아 있는 옛 행의 의미를 코드에서 읽을 수 없게 된다. 같은 저장소의
     * {@code LsTaskEventLog.EVENT_PRIVACY_META_RESET}(신규 발생 없음·과거 행 판독용 존치)과 같은 관례다.
     *
     * <p>옛 행은 <b>지우지 않는다</b>. 유니크 제약에 작업 유형이 들어 있어 그 행이 남아도 작업자 배정과
     * 충돌하지 않으므로 제약도 바꾸지 않는다.
     *
     * @design ADR-067
     * @design ERD-014
     */
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

    // 구 createReviewer 팩토리는 제거됐다 — 검수자 배정을 새로 만드는 경로를 두지 않는다(ADR-067).
    // 되살리지 말 것: 검수는 배정 없이 전체 대기열에서 집어가며 배정의 대상은 작업자뿐이다.

    public void reassignTo(Long newWorkerNo) {
        this.userNo = newWorkerNo;
    }
}
