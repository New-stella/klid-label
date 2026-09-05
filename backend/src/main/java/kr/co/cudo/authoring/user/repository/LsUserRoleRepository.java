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
     *
     * <h3>★ 주체({@code mdfrId})를 함께 쓴다 (V22, @design AC-1018)</h3>
     * <p>권한 상승은 감사에서 가장 중요한 사건인데 이 표에는 <b>대상만 있고 주체가 없었다</b>.
     * 그래서 이 문장이 역할과 주체를 <b>같은 문장에서</b> 쓴다 — 역할을 쓰고 주체를 따로 UPDATE
     * 하면 두 쓰기 사이에서 실패했을 때 "주체 없는 역할 변경" 이 남는다.
     *
     * <p>{@code mdfrId} 는 인계 토큰 subject 에서만 온다(요청 바디 금지 — 위조 가능). 값이
     * {@code null} 이면 그대로 null 을 쓴다 — 인증 주체를 알 수 없는 경로(운영 배치·시험 하네스)의
     * 사실 그대로이며 지어내지 않는다.
     *
     * <p>{@code CAST(... AS varchar)} 는 파라미터 타입 추론 실패 방어다 — null 바인딩에서 PostgreSQL
     * 이 타입을 정하지 못해 실패하는 자리를 만들지 않는다.
     *
     * @param mdfrId 역할을 바꾼 주체(인계 토큰 subject). 알 수 없으면 null
     */
    @Modifying(clearAutomatically = true)
    @Transactional("controlTransactionManager")
    @Query(value = """
            INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT, MDFR_ID)
            VALUES (:userNo, :roleCd, now(), CAST(:mdfrId AS varchar))
            ON CONFLICT (USER_NO) DO UPDATE
              SET ROLE_CD = :roleCd, UPD_DT = now(), MDFR_ID = CAST(:mdfrId AS varchar)
            """, nativeQuery = true)
    int upsertRole(@Param("userNo") Long userNo,
                   @Param("roleCd") String roleCd,
                   @Param("mdfrId") String mdfrId);

    /**
     * 주체를 모르는 호출부용 진입점 — {@link #upsertRole(Long, String, String)} 에 위임한다.
     *
     * <p>SQL 을 복제하지 않고 <b>위임</b>하는 것이 핵심이다. 같은 upsert 를 두 문장으로 두면
     * 한쪽만 고쳐지는 순간 두 번째 진실원이 된다.
     *
     * <p>운영 창구(사용자 관리 화면)는 이 진입점을 쓰지 않는다 — 주체를 반드시 실어야 한다.
     */
    default int upsertRole(Long userNo, String roleCd) {
        return upsertRole(userNo, roleCd, null);
    }

    /** 해당 역할 코드를 가진 사용자 수. 관리자 부트스트랩 창구의 개폐 판정에 쓴다. */
    long countByRoleCd(String roleCd);

    /**
     * <b>역할이 없을 때만 부여한다 — 기존 역할은 절대 덮어쓰지 않는다.</b>
     *
     * <p>진입 시 작업자 자동 등록(ADR-055)의 쓰기 지점이다. {@code DO NOTHING} 이 핵심이며
     * {@code DO UPDATE} 로 바꾸면 <b>관리자가 지정한 역할이 그 사람의 다음 요청에 되돌려진다</b>
     * — 자동 등록이 역할 관리를 무력화한다.
     *
     * <p>조회 후 INSERT 가 아닌 이유는 {@code upsertRole} 과 같다(2노드 동시 진입 → PK 위반 →
     * 트랜잭션 abort). 여기서는 abort 가 <b>인증 요청 경로</b>에서 나므로 더 나쁘다.
     *
     * @return 실제로 부여했으면 1, 이미 역할이 있으면 0
     * @design AC-1016
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT)
            VALUES (:userNo, :roleCd, now())
            ON CONFLICT (USER_NO) DO NOTHING
            """, nativeQuery = true)
    int insertRoleIfAbsent(@Param("userNo") Long userNo, @Param("roleCd") String roleCd);

    /**
     * <b>관리자 부트스트랩 — 관리자가 한 명도 없을 때만 부여하며, 요청자의 기존 역할은 덮어쓴다.</b>
     *
     * <p>서비스가 진입부에서 이미 개수를 확인하지만, 그 확인과 쓰기 사이에는 창이 있다. 조건을
     * 문장 안에 다시 넣어 <b>이미 커밋된 관리자</b>가 있으면 아무 일도 일어나지 않게 한다.
     *
     * <h3>★ 왜 {@code DO UPDATE} 인가 (되돌리면 부트스트랩이 도달 불가능해진다)</h3>
     * <p>이 창구는 <b>역할 보유자에게도</b> 열려 있다(창이 열려 있는 동안 한정). 이미 운영 중인
     * 시스템에는 역할 없는 사용자가 없고, 신규 설치에서도 진입 시 자동 등록이 <b>같은 요청의
     * 앞단에서</b> 작업자 역할을 부여하기 때문이다. {@code DO NOTHING} 이면 그 사용자들에게 관리자가
     * 붙지 않아 <b>최초 관리자를 만들 경로가 0개</b>가 된다. 실제로 그 교착이 났다.
     *
     * <p>덮어쓰기가 안전한 근거는 <b>조건이 그대로 남아 있다</b>는 것이다 — 관리자가 한 명이라도
     * 있으면 {@code SELECT} 가 0행을 내어 {@code ON CONFLICT} 절에 닿지도 않는다. 즉 이 문장이
     * 남의 역할을 바꾸는 것은 "아직 아무도 관리자가 아닌" 구간뿐이고, 그 대상도 <b>요청자 자신</b>
     * 하나다(userNo 는 JWT subject 에서만 온다).
     *
     * @param userNo 요청자 사용자번호 (JWT subject 에서만 취한 값)
     * @param roleCd 부여할 역할 코드 — 부트스트랩이므로 관리자 고정이다
     * @return 부여했으면 1, 이미 관리자가 있으면 0
     * @design ADR-055
     * @design API-007
     * @design AC-127
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT)
            SELECT CAST(:userNo AS bigint), CAST(:roleCd AS varchar), now()
             WHERE NOT EXISTS (SELECT 1 FROM LS_USER_ROLE WHERE ROLE_CD = :roleCd)
            ON CONFLICT (USER_NO) DO UPDATE
              SET ROLE_CD = EXCLUDED.ROLE_CD, UPD_DT = now()
            """, nativeQuery = true)
    int upsertRoleIfNoneHasRole(@Param("userNo") Long userNo, @Param("roleCd") String roleCd);

    /**
     * <b>관리자 행 전체를 잠그고 목록을 돌려준다 — 마지막 관리자 보호의 원자성 근거.</b>
     *
     * <h3>왜 조회 후 UPDATE 로는 안 되는가 (CWE-362 write skew)</h3>
     * <p>두 관리자가 서로를 동시에 내리면, 각자 조회 시점에는 <b>둘 다 관리자가 둘</b>이라고 보아
     * 통과하고 결과적으로 관리자가 0명이 된다. 조건을 UPDATE 문의 {@code EXISTS} 로 옮겨도 같다 —
     * READ COMMITTED 에서 {@code EXISTS} 는 잠금을 걸지 않아 상대의 미커밋 변경을 보지 못한다.
     *
     * <p>그래서 <b>관리자 행 집합 자체</b>를 {@code FOR UPDATE} 로 잠근다. 뒤늦은 트랜잭션은 앞선
     * 트랜잭션의 커밋을 기다렸다가 잠금 획득 시점에 조건을 다시 평가하므로(PostgreSQL 의 잠금 후
     * 재검사), 이미 강등된 행은 결과에서 빠지고 "이제 한 명뿐" 을 정확히 관측한다.
     *
     * <p>{@code ORDER BY USER_NO} 는 교착 방지다 — 두 트랜잭션이 같은 순서로 잠근다.
     *
     * @return 잠긴 관리자들의 사용자번호(오름차순)
     * @design AC-124
     */
    @Query(value = """
            SELECT USER_NO FROM LS_USER_ROLE
             WHERE ROLE_CD = :roleCd
             ORDER BY USER_NO
               FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockUserNosByRoleCd(@Param("roleCd") String roleCd);
}
