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
@Table(name = "LS_PJT_USER_AUTHRT_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjtUserAuthrtHstry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HSTRY_SEQ")
    private Long hstrySeq;

    @Column(name = "AUTHRT_SEQ", nullable = false)
    private Long authrtSeq;

    @Column(name = "PJT_ID", nullable = false)
    private Long pjtId;

    @Column(name = "RAW_DATA_ID", nullable = false)
    private Long rawDataId;

    @Column(name = "PREV_USER_NO", nullable = false)
    private Long prevUserNo;

    @Column(name = "NEW_USER_NO", nullable = false)
    private Long newUserNo;

    @Column(name = "TASK_TYPE_CD", nullable = false, length = 32)
    private String taskTypeCd;

    @Column(name = "CHG_USER_NO", nullable = false)
    private Long chgUserNo;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;

    @Builder
    private LsPjtUserAuthrtHstry(Long authrtSeq, Long pjtId, Long rawDataId, Long prevUserNo, Long newUserNo,
                                 String taskTypeCd, Long chgUserNo, LocalDateTime chgDt) {
        this.authrtSeq = authrtSeq;
        this.pjtId = pjtId;
        this.rawDataId = rawDataId;
        this.prevUserNo = prevUserNo;
        this.newUserNo = newUserNo;
        this.taskTypeCd = taskTypeCd;
        this.chgUserNo = chgUserNo;
        this.chgDt = chgDt;
    }

    public static LsPjtUserAuthrtHstry record(LsPjtUserAuthrt prev, Long newUserNo, Long chgUserNo) {
        return LsPjtUserAuthrtHstry.builder()
                .authrtSeq(prev.getAuthrtSeq())
                .pjtId(prev.getPjtId())
                .rawDataId(prev.getRawDataId())
                .prevUserNo(prev.getUserNo())
                .newUserNo(newUserNo)
                .taskTypeCd(prev.getTaskTypeCd())
                .chgUserNo(chgUserNo)
                .chgDt(LocalDateTime.now())
                .build();
    }
}
