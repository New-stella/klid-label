package kr.co.cudo.authoring.project.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "LS_META")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMeta {

    @Id
    @Column(name = "META_KEY", length = 64)
    private String metaKey;

    @Column(name = "META_VL", length = 2000)
    private String metaVal;
}
