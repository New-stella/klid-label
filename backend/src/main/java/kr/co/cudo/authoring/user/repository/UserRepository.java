package kr.co.cudo.authoring.user.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface UserRepository extends JpaRepository<LsAcntUser, Long> {

    Optional<LsAcntUser> findByUserNo(Long userNo);

    /**
     * 주어진 userNo 목록에 해당하는 사용자 마스터를 한 번에 조회 (N+1 방지).
     * 빈 컬렉션 호출 시 빈 리스트 반환.
     */
    List<LsAcntUser> findByUserNoIn(java.util.Collection<Long> userNos);

    /**
     * <b>사용자 자동등록 — 원자 upsert(등록 + 조건부 갱신)</b> (V169).
     *
     * <h3>왜 조회 후 INSERT 가 아닌가 (CWE-362)</h3>
     * <p>2노드 Active-Active 라 같은 사용자가 두 노드에서 동시에 역할 클레임을 할 수 있다. "없으면
     * 넣는다"를 조회 → INSERT 두 문장으로 쓰면 두 노드가 모두 "없음"을 관측한 뒤 각각 INSERT 해
     * <b>PK 위반</b>이 난다. PostgreSQL 은 제약 위반 시 <b>트랜잭션 전체를 abort</b> 하므로 그 예외를
     * 잡아도 진행 중이던 클레임 트랜잭션이 죽는다(=역할 부여 실패). {@code ON CONFLICT} 는 충돌을
     * <b>예외 없이</b> 흡수하므로 두 노드 모두 성공한다.
     *
     * <h3>★ userNo 는 호출자가 JWT subject 에서만 취한다</h3>
     * <p>이 upsert 는 넘어온 {@code userNo} 행을 만들거나 갱신한다. 요청 바디의 값을 그대로 넘기면
     * <b>남의 행 표시명을 바꿀 수 있다</b>(CWE-639). 호출부({@code RoleClaimService})가 {@code sub}
     * 파싱값만 넘기며 그 계약은 테스트로 고정돼 있다.
     *
     * <h3>갱신 대상</h3>
     * <ul>
     *   <li><b>갱신</b> — {@code USER_ID}·{@code USER_NM}(관제 인계 표시 정보). 관제가 이름을 바꾸면
     *       반영돼야 한다.</li>
     *   <li><b>미송신 보존</b> — 수신값이 null 이면 기존 값을 유지한다({@code COALESCE}). 관제가 안
     *       보낸 것이 기존 사실을 <b>지우면</b> 안 된다. 호출자가 공백 문자열을 null 로 정규화한다.</li>
     *   <li><b>미갱신</b> — {@code USE_YN}. 운영자가 비활성화한 사용자가 재클레임으로 <b>되살아나면
     *       안 된다</b>. {@code USER_EML_ADDR} 도 관제 인계 키에 없어 건드리지 않는다.</li>
     *   <li><b>no-op 방지</b> — 실제로 값이 바뀔 때만 UPDATE 한다({@code IS DISTINCT FROM}).
     *       매 클레임마다 행을 건드리면 불필요한 행 잠금·WAL 이 생기고 {@code MDFCN_DT} 가 오염된다.</li>
     * </ul>
     *
     * <p>보안: 세 값 모두 파라미터 바인딩이다(CWE-89). 길이 초과분은 호출자가 자르며 DB 컬럼 길이가
     * 최종 방어선이다.
     *
     * <p>{@code clearAutomatically}: 이 native 문장은 영속성 컨텍스트를 우회하므로, 직후의
     * {@code findByUserNo} 가 1차 캐시의 <b>옛 스냅샷</b>을 돌려주지 않도록 컨텍스트를 비운다.
     *
     * @return 신규 등록되거나 <b>실제로 갱신</b>됐으면 1, 아무 변화가 없으면 0
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN)
            VALUES (:userNo, :userId, COALESCE(:userNm, ''), 'Y')
            ON CONFLICT (USER_NO) DO UPDATE
               SET USER_ID  = COALESCE(EXCLUDED.USER_ID, LS_ACNT_USER.USER_ID),
                   USER_NM  = COALESCE(NULLIF(EXCLUDED.USER_NM, ''), LS_ACNT_USER.USER_NM),
                   MDFCN_DT = CURRENT_TIMESTAMP
             WHERE LS_ACNT_USER.USER_ID
                       IS DISTINCT FROM COALESCE(EXCLUDED.USER_ID, LS_ACNT_USER.USER_ID)
                OR LS_ACNT_USER.USER_NM
                       IS DISTINCT FROM COALESCE(NULLIF(EXCLUDED.USER_NM, ''), LS_ACNT_USER.USER_NM)
            """, nativeQuery = true)
    int upsertUser(@Param("userNo") Long userNo,
                   @Param("userId") String userId,
                   @Param("userNm") String userNm);

    /**
     * WORKER 역할을 가진 활성 사용자 목록과 활성 라벨러 태스크 개수를 단일 쿼리로 조회한다.
     *
     * <p>WORKER 집계는 저작도구 소유 {@code LS_USER_ROLE}(ROLE_CD='WORKER') INNER JOIN 으로 산정하고,
     * {@code USE_YN='Y'} 로 비활성 사용자를 제외한다.
     * N+1 방지: 사용자별 LS_TASK_ASSIGNMENT 활성 카운트를 상관 서브쿼리로 산정.
     */
    @Query("""
            SELECT new kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount(
                u.userNo, u.userId, u.userNm, u.userEmlAddr,
                (SELECT COUNT(a) FROM kr.co.cudo.authoring.assignment.entity.LsTaskAssignment a
                  WHERE a.userNo = u.userNo AND a.taskTypeCd = 'LABELER')
            )
            FROM LsAcntUser u, LsUserRole r
            WHERE u.userNo = r.userNo
              AND r.roleCd = 'WORKER'
              AND u.useYn = 'Y'
            ORDER BY u.userNo ASC
            """)
    List<WorkerWithTaskCount> findAllWorkersWithTaskCount();

    /**
     * 사용자 마스터 페이징 검색 (REVIEWER 의 /manage/users 화면용).
     * keyword 가 null/빈 문자열이면 전체 검색, 그렇지 않으면 USER_ID/USER_NM/USER_EML_ADDR LIKE.
     * 활성/비활성 모두 포함.
     *
     * <p>자동등록 사용자는 {@code userId}/{@code userEmlAddr} 가 null 일 수 있다 — LIKE 는 null 에
     * 대해 참이 되지 않으므로 그 사용자는 이름으로만 검색된다(오류 아님).
     */
    @Query("""
            SELECT u FROM LsAcntUser u
             WHERE (:keyword IS NULL OR :keyword = ''
                    OR LOWER(u.userId)      LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.userNm)      LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.userEmlAddr) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<LsAcntUser> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
