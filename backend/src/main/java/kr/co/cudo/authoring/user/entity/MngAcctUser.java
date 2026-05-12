package kr.co.cudo.authoring.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 사용자 마스터 엔티티 — 관제서버팀 공유 테이블 `MNG_ACCT_USER`.
 *
 * <p>{@code @Immutable} 로 JPA dirty checking 갱신은 비활성. 활성/비활성 상태 변경은
 * Repository 의 {@code @Modifying} UPDATE 쿼리({@code UserRepository.updateUseYn})로 수행한다.
 */
@Entity
@Table(name = "MNG_ACCT_USER")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngAcctUser {

    @Id
    @Column(name = "USER_NO")
    private Long userNo;

    @Column(name = "USER_ID", nullable = false, length = 64)
    private String userId;

    @Column(name = "USER_NM", nullable = false, length = 128)
    private String userNm;

    @Column(name = "USER_EMAIL", length = 255)
    private String userEmail;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;
}
