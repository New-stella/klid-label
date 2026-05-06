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
@Table(name = "LS_PJT_DATA_MPNG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjtDataMpng {

    @EmbeddedId
    private Pk id;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Pk implements Serializable {
        @Column(name = "PJT_ID")
        private Long pjtId;

        @Column(name = "RAW_DATA_ID")
        private Long rawDataId;
    }
}
