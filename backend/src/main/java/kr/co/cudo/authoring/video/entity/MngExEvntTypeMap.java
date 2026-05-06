package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "MNG_EX_EVNT_TYPE_MAP")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngExEvntTypeMap {

    @Id
    @Column(name = "EVNT_TYPE_CD", length = 32)
    private String evntTypeCd;

    @Column(name = "EVNT_NM", nullable = false, length = 255)
    private String evntNm;

    @Column(name = "UP_EVNT_TYPE_CD", length = 32)
    private String upEvntTypeCd;

    @Column(name = "EVNT_LVL", nullable = false)
    private Integer evntLvl;
}
