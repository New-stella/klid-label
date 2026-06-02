package kr.co.cudo.authoring.batch.entity;

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
@Table(name = "LS_DATA_META_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMetaHstry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HSTRY_SEQ")
    private Long hstrySeq;

    @Column(name = "META_SN", nullable = false)
    private Long metaSn;

    @Column(name = "PREV_VL", length = 2000)
    private String prevVl;

    @Column(name = "NEW_VL", length = 2000)
    private String newVl;

    @Column(name = "CHG_USER_NO")
    private Long chgUserNo;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;

    @Builder
    private LsDataMetaHstry(Long metaSn, String prevVl, String newVl, Long chgUserNo) {
        this.metaSn = metaSn;
        this.prevVl = prevVl;
        this.newVl = newVl;
        this.chgUserNo = chgUserNo;
        this.chgDt = LocalDateTime.now();
    }

    public static LsDataMetaHstry record(Long metaSn, String prevVl, String newVl, Long chgUserNo) {
        return LsDataMetaHstry.builder()
                .metaSn(metaSn).prevVl(prevVl).newVl(newVl).chgUserNo(chgUserNo)
                .build();
    }
}
