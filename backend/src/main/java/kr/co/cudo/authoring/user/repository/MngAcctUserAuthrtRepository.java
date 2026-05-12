package kr.co.cudo.authoring.user.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.user.entity.MngAcctUserAuthrt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 사용자 권한 매핑 Repository — {@code MNG_ACCT_USER_AUTHRT}.
 *
 * <p>{@link MngAcctUserAuthrt} 가 {@code @Immutable} 이므로 save() 불가 → 변경은 본 Repository 의
 * {@code @Modifying} 쿼리(delete/insert)로만 수행한다.
 *
 * <p>보안: 모든 파라미터는 {@code @Param} 바인딩만 사용 (SQL Injection 차단).
 */
@ControlRepo
public interface MngAcctUserAuthrtRepository extends JpaRepository<MngAcctUserAuthrt, MngAcctUserAuthrt.Pk> {

    /**
     * 특정 사용자의 모든 권한 매핑 삭제. 역할 변경 시 insert 직전에 호출.
     */
    @Modifying
    @Transactional("controlTransactionManager")
    @Query("DELETE FROM MngAcctUserAuthrt a WHERE a.id.userNo = :userNo")
    void deleteByUserNo(@Param("userNo") Long userNo);

    /**
     * 새 권한 매핑 추가. AUTHRT_CD 는 Controller/DTO 단계 {@code @Pattern} 화이트리스트로
     * 사전 검증된 값만 도달한다.
     */
    @Modifying
    @Transactional("controlTransactionManager")
    @Query(value = "INSERT INTO MNG_ACCT_USER_AUTHRT (USER_NO, AUTHRT_CD, REG_DT) VALUES (:userNo, :authrtCd, :regDt)",
            nativeQuery = true)
    void insertAuthrt(@Param("userNo") Long userNo,
                      @Param("authrtCd") String authrtCd,
                      @Param("regDt") LocalDateTime regDt);
}
