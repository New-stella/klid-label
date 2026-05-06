package kr.co.cudo.authoring.project.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "LS_PJT_DDLN")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjtDdln {

    @EmbeddedId
    private Pk id;

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

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Pk implements Serializable {
        @Column(name = "PJT_ID")
        private Long pjtId;

        @Column(name = "DDLN_SEQ")
        private Long ddlnSeq;
    }
}
