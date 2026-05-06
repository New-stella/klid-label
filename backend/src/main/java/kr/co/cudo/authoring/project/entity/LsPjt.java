package kr.co.cudo.authoring.project.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_PJT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjt {

    @Id
    @Column(name = "PJT_ID")
    private Long pjtId;

    @Column(name = "PJT_NM", nullable = false, length = 255)
    private String pjtNm;

    @Column(name = "PJT_DESC", length = 2000)
    private String pjtDesc;

    @Column(name = "PJT_STTS_CD", nullable = false, length = 32)
    private String pjtSttsCd;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;

    @Column(name = "REG_USER_NO")
    private Long regUserNo;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;
}
