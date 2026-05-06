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
@Table(name = "MNG_EX_EVNT_TYPE")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngExEvntType {

    @Id
    @Column(name = "EVNT_TYPE_CD", length = 32)
    private String evntTypeCd;

    @Column(name = "CLCT_EVNT_NM", nullable = false, length = 255)
    private String clctEvntNm;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;
}
