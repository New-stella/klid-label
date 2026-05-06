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

@Entity
@Table(name = "LS_PJT_USER_AUTHRT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjtUserAuthrt {

    public static final String TASK_LABELER = "LABELER";
    public static final String TASK_REVIEWER = "REVIEWER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUTHRT_SEQ")
    private Long authrtSeq;

    @Column(name = "PJT_ID", nullable = false)
    private Long pjtId;

    @Column(name = "USER_NO", nullable = false)
    private Long userNo;

    @Column(name = "RAW_DATA_ID", nullable = false)
    private Long rawDataId;

    @Column(name = "TASK_TYPE_CD", nullable = false, length = 32)
    private String taskTypeCd;

    @Column(name = "REG_USER_NO", nullable = false)
    private Long regUserNo;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Builder
    private LsPjtUserAuthrt(Long pjtId, Long userNo, Long rawDataId, String taskTypeCd,
                            Long regUserNo, LocalDateTime regDt) {
        this.pjtId = pjtId;
        this.userNo = userNo;
        this.rawDataId = rawDataId;
        this.taskTypeCd = taskTypeCd;
        this.regUserNo = regUserNo;
        this.regDt = regDt;
    }

    public static LsPjtUserAuthrt createLabeler(Long pjtId, Long rawDataId, Long workerNo, Long actorNo) {
        return LsPjtUserAuthrt.builder()
                .pjtId(pjtId)
                .userNo(workerNo)
                .rawDataId(rawDataId)
                .taskTypeCd(TASK_LABELER)
                .regUserNo(actorNo)
                .regDt(LocalDateTime.now())
                .build();
    }

    public void reassignTo(Long newWorkerNo) {
        this.userNo = newWorkerNo;
    }
}
