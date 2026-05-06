package kr.co.cudo.authoring.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "MNG_ACCT_AUTHRT")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngAcctAuthrt {

    @Id
    @Column(name = "AUTHRT_CD", length = 32)
    private String authrtCd;

    @Column(name = "AUTHRT_NM", nullable = false, length = 128)
    private String authrtNm;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;
}
