package kr.co.cudo.authoring.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "MNG_ACCT_USER_AUTHRT")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngAcctUserAuthrt {

    @EmbeddedId
    private Pk id;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Pk implements Serializable {
        @Column(name = "USER_NO")
        private Long userNo;

        @Column(name = "AUTHRT_CD", length = 32)
        private String authrtCd;
    }
}
