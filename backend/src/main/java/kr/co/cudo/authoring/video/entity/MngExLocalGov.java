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
@Table(name = "MNG_EX_LOCAL_GOV")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngExLocalGov {

    @Id
    @Column(name = "LCLGV_CD", length = 32)
    private String lclgvCd;

    @Column(name = "SIDO_NM", length = 64)
    private String sidoNm;

    @Column(name = "SGG_NM", length = 64)
    private String sggNm;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;
}
