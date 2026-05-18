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
@Table(name = "LS_DEADLINE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDeadline {

    @Id
    @Column(name = "DDLN_SEQ")
    private Long ddlnSeq;

    @Column(name = "DDLN_DT")
    private LocalDateTime ddlnDt;

    @Column(name = "ANONY_INCL_YN", nullable = false, length = 1)
    private String anonyInclYn;

    @Column(name = "PSDO_INCL_YN", nullable = false, length = 1)
    private String psdoInclYn;

    @Column(name = "PRVC_INCL_YN", nullable = false, length = 1)
    private String prvcInclYn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;
}
