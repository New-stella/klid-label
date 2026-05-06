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

@Entity
@Table(name = "LS_PJT_META")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPjtMeta {

    @EmbeddedId
    private Pk id;

    @Column(name = "META_VAL", length = 2000)
    private String metaVal;

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Pk implements Serializable {
        @Column(name = "PJT_ID")
        private Long pjtId;

        @Column(name = "META_KEY", length = 64)
        private String metaKey;
    }
}
