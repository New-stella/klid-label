package kr.co.cudo.authoring.user.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 저작도구 소유 사용자 역할 매핑 Repository (klid_system 공유 DB — Control 데이터소스).
 *
 * <p>비즈니스 로직 금지 — 조회/저장 메서드만 정의한다.
 * <p>역할 분리 리팩토링 Phase 2 — 역할 부여/변경 쓰기 경로를 본 리포의 원자 upsert 로 전환했다.
 */
@ControlRepo
public interface LsUserRoleRepository extends JpaRepository<LsUserRole, Long> {

    /** 단건 조회 — USER_NO 로 역할 매핑 조회. */
    Optional<LsUserRole> findByUserNo(Long userNo);

    /**
     * 주어진 USER_NO 목록의 역할 매핑을 한 번에 조회 (N+1 방지).
     * 빈 컬렉션 호출 시 빈 리스트 반환.
     */
    List<LsUserRole> findByUserNoIn(Collection<Long> userNos);

    /**
     * 역할 부여/변경 — USER_NO 단일 PK 기준 원자적 upsert (CWE-362 PK race 방어).
     *
     * <p>비원자 {@code findByUserNo → save} 패턴은 동시 PATCH 시 PK 중복 예외를 유발하므로
     * {@code INSERT ... ON CONFLICT (USER_NO) DO UPDATE} 단일 문으로 처리한다. 동일 역할
     * 재적용은 멱등(idempotent)하다.
     *
     * <p>보안: 모든 값은 {@code @Param} 바인딩만 사용 (SQL Injection 차단). ROLE_CD 화이트리스트
     * 검증은 Controller/DTO {@code @Pattern} 단계에서 선행한다.
     *
     * <p>{@code clearAutomatically=true} — upsert 후 동일 트랜잭션 내 재조회 시 stale 1차 캐시
     * 대신 DB 최신 값을 반환하도록 영속성 컨텍스트를 비운다.
     */
    @Modifying(clearAutomatically = true)
    @Transactional("controlTransactionManager")
    @Query(value = """
            INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT)
            VALUES (:userNo, :roleCd, now())
            ON CONFLICT (USER_NO) DO UPDATE
              SET ROLE_CD = :roleCd, UPD_DT = now()
            """, nativeQuery = true)
    int upsertRole(@Param("userNo") Long userNo, @Param("roleCd") String roleCd);
}
