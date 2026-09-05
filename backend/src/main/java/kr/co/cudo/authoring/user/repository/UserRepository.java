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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface UserRepository extends JpaRepository<LsAcntUser, Long> {

    Optional<LsAcntUser> findByUserNo(Long userNo);

    /**
     * <b>관제 인계 토큰의 {@code userId} 클레임으로 사용자번호를 찾는다 — 단건 매칭일 때만</b>
     * (@design ADR-063).
     *
     * <p>{@code LS_ACNT_USER.USER_ID} 에는 <b>유일 제약이 없다</b>(PK 는 {@code USER_NO} 뿐).
     * 그래서 조회가 다중 매칭될 수 있고, 어느 행으로 잇는지가 추측이 되면 남의 계정으로 인가될 수
     * 있다(CWE-639). 따라서 <b>정확히 1건일 때만</b> 그 {@code USER_NO} 를 돌려주고, 0건이거나
     * 2건 이상이면 {@code empty} 를 돌려 fail-closed(무권한)로 흐르게 한다.
     *
     * <p>유일 인덱스로 입구를 좁히는 대신 <b>조회에서 닫는다</b> — 관제 ID 유일성이 보장되지 않은
     * 상태에서 인덱스를 걸면 기존 중복 때문에 기동이 실패하고, fail-closed 는 되돌릴 것이 없다.
     *
     * <p>{@code null}/공백 {@code userId} 는 방어적으로 여기서도 {@code empty} 를 돌린다(빈 조회로
     * 전 행을 훑지 않는다). {@code userId} 는 파라미터 바인딩만 쓴다(CWE-89).
     */
    default Optional<Long> findUserNoByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        List<Long> matches = findUserNosByUserId(userId);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * {@code USER_ID} 로 매칭되는 {@code USER_NO} 전부를 반환한다(다중/0건 판정용 — 단건 축약은
     * {@link #findUserNoByUserId(String)} 가 한다). 유일 제약이 없어 2건 이상일 수 있다.
     */
    @Query("SELECT u.userNo FROM LsAcntUser u WHERE u.userId = :userId")
    List<Long> findUserNosByUserId(@Param("userId") String userId);

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
     * <b>최종로그인일시 기록 — throttle 이 내장된 조건부 UPDATE</b> (V12).
     *
     * <p>저작도구에는 독립 로그인 UI 가 없어 "로그인" 이라는 단일 이벤트가 없다. 기록 지점은 JWT
     * 검증을 통과한 INTERNAL 요청이며 그 경로는 <b>모든 요청에서 돈다</b>. 매 요청 UPDATE 를 하면
     * 요청 수만큼 행 잠금·WAL 이 생기므로 {@code threshold} 조건절로 최소 간격을 강제한다.
     *
     * <h3>★ 왜 조건절이 정확성의 근거인가 (CWE-362)</h3>
     * <p>throttle 을 애플리케이션 로컬 캐시로만 구현하면 2노드 Active-Active 에서 노드별로 판정이
     * 갈려 두 노드가 같은 창 안에 각각 UPDATE 한다. 조건절은 DB 한 곳에서 판정하므로 노드 수와
     * 무관하게 창이 지켜지고, 두 노드가 동시에 들어와도 한쪽만 1행을 갱신한다(read-then-write 창이
     * 아예 없다 — 조회 없이 단일 문장이다).
     *
     * <h3>건드리지 않는 것</h3>
     * <ul>
     *   <li>{@code MDFCN_DT} — 관제 인계 표시정보(USER_ID·USER_NM)의 변경 시각이다. 접속마다
     *       갱신하면 "프로필이 바뀐 시각" 이라는 뜻이 오염된다.</li>
     *   <li>존재하지 않는 사용자 — {@code WHERE USER_NO} 가 매칭되지 않아 0행이다(행을 만들지
     *       않는다). 사용자 등록 주체는 역할 클레임 시점의 {@link #upsertUser} 하나로 유지한다.</li>
     * </ul>
     *
     * <p>보안: 세 값 모두 파라미터 바인딩이다(CWE-89).
     *
     * <p>{@code clearAutomatically}: native 문장이 영속성 컨텍스트를 우회하므로, 같은 컨텍스트가
     * 뒤이어 이 엔티티를 읽을 때 옛 스냅샷을 돌려주지 않도록 비운다.
     *
     * @param userNo    JWT subject 에서 파싱한 사용자번호
     * @param now       기록할 시각
     * @param threshold 이 시각보다 오래된(또는 값이 없는) 경우에만 기록한다 = {@code now - throttle}
     * @return 기록했으면 1, throttle 창 안이거나 대상 사용자가 없으면 0
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE LS_ACNT_USER
               SET LAST_LGN_DT = :now
             WHERE USER_NO = :userNo
               AND (LAST_LGN_DT IS NULL OR LAST_LGN_DT < :threshold)
            """, nativeQuery = true)
    int touchLastLogin(@Param("userNo") Long userNo,
                       @Param("now") LocalDateTime now,
                       @Param("threshold") LocalDateTime threshold);

    /**
     * WORKER 역할을 가진 활성 사용자 목록과 활성 라벨러 태스크 개수를 단일 쿼리로 조회한다.
     *
     * <p>WORKER 집계는 저작도구 소유 {@code LS_USER_ROLE}(ROLE_CD='WORKER') INNER JOIN 으로 산정하고,
     * {@code USE_YN='Y'} 로 비활성 사용자를 제외한다.
     * N+1 방지: 사용자별 LS_TASK_ALTMNT 활성 카운트를 상관 서브쿼리로 산정.
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
     * 사용자 마스터 페이징 검색 (관리자의 /admin/users 화면용).
     * keyword 가 null/빈 문자열이면 전체 검색, 그렇지 않으면 USER_ID/USER_NM/USER_EML_ADDR LIKE.
     * 활성/비활성 모두 포함.
     *
     * <p>자동등록 사용자는 {@code userId}/{@code userEmlAddr} 가 null 일 수 있다 — LIKE 는 null 에
     * 대해 참이 되지 않으므로 그 사용자는 이름으로만 검색된다(오류 아님).
     *
     * <h3>★ 역할 필터는 여기서 걸린다 — 서비스가 페이지 안에서 거르지 않는다 (@design AC-1018)</h3>
     * <p>구 구현은 한 페이지를 먼저 가져온 뒤 그 안에서 역할을 걸렀다. 그러면 결함이 둘이다 —
     * <b>1페이지 밖의 해당 역할 사용자에게 도달할 수 없고</b>(그 20건에 없으면 결과가 빈다),
     * <b>총건수가 필터 이전 합계</b>라 "3건 표시 / 총 87건" 같은 상태가 된다. 필터를 이 문장으로
     * 내리면 페이징·총건수가 모두 <b>서버 전체 기준</b>으로 정확해진다.
     *
     * <p>{@code LS_ACNT_USER} 와 {@code LS_USER_ROLE} 은 <b>ID 참조</b>라 연관 매핑이 없다(Aggregate
     * 간 ID 참조 원칙). 그래서 JOIN 이 아니라 상관 {@code EXISTS} 서브쿼리로 건다 — JOIN 은 역할
     * 행이 여러 개일 때 사용자를 중복시키지만({@code USER_NO} 단일 PK 라 지금은 1개여도, 그 전제가
     * 조회 정확성의 근거가 되면 안 된다) {@code EXISTS} 는 구조적으로 중복이 없다.
     *
     * <p><b>미배정(역할 행 없음) 사용자는 어떤 역할 필터에도 걸리지 않는다</b> — {@code EXISTS} 가
     * 거짓이 되어 자연히 빠진다. 기존 성질 그대로다(기본 역할을 부여하지 않으므로 인가와 정합).
     *
     * <p><b>count 쿼리를 따로 선언하지 않는다</b> — Spring Data 가 이 술어에서 유도한다. 손으로
     * 적으면 술어를 한쪽만 고치는 순간 총건수가 조용히 어긋난다(이 저장소에서 실제로 겪은 결함).
     *
     * <p>보안: {@code role} 은 Controller 의 {@code @Pattern} 화이트리스트로 사전 검증되고 여기서도
     * 파라미터 바인딩만 쓴다(CWE-89).
     *
     * @param role 역할 코드 필터. null/빈 문자열이면 필터하지 않는다(기존 동선 그대로)
     */
    @Query("""
            SELECT u FROM LsAcntUser u
             WHERE (:keyword IS NULL OR :keyword = ''
                    OR LOWER(u.userId)      LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.userNm)      LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.userEmlAddr) LIKE LOWER(CONCAT('%', :keyword, '%')))
               AND (:role IS NULL OR :role = ''
                    OR EXISTS (SELECT 1 FROM LsUserRole r
                                WHERE r.userNo = u.userNo AND r.roleCd = :role))
            """)
    Page<LsAcntUser> searchByKeywordAndRole(@Param("keyword") String keyword,
                                            @Param("role") String role,
                                            Pageable pageable);
}
