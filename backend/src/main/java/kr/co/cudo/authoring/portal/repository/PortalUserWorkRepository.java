package kr.co.cudo.authoring.portal.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 데이터마트 채널에서 포털 사용자가 남긴 <b>세 저작물을 한 벌로</b> 다루는 단일 지점.
 * @design DFEAT-055, API-225, AC-1068, AC-1069
 *
 * <p>저작물은 셋이다 — 저장 라벨({@code LS_PORTAL_USER_LABEL}) · 메타 오버레이
 * ({@code LS_PORTAL_USER_META}) · 이벤트 어노테이션 오버레이({@code LS_PORTAL_USER_EVNT_ANNO}).
 * 셋 다 <b>같은 사용자가 같은 영상에 남긴 것</b>이라 보존기간 축에서 한 벌로 다룬다.
 *
 * <h3>★ 라벨만 보면 두 군데가 동시에 틀린다</h3>
 * <ul>
 *   <li><b>삭제</b> — 라벨만 지우면 그 사용자가 고친 메타·어노테이션이 <b>영원히 남는다</b>.</li>
 *   <li><b>후보 탐색</b> — 라벨을 만들지 않고 메타만 고친 (사용자, 영상)은 <b>후보에 잡히지도
 *       않아</b> 삭제 대상 확대만으로는 그 구멍이 닫히지 않는다.</li>
 *   <li><b>기산점</b> — 저작의 시작은 라벨을 처음 만든 때가 아니라 <b>그 영상에 무엇이든 처음
 *       저장한 때</b>다. 라벨만 보면 메타를 먼저 고친 사용자의 기산점이 실제보다 늦어진다.</li>
 * </ul>
 *
 * <h3>★★ 왜 세 문장이 아니라 한 문장으로 지우는가 — 순차 삭제는 증거를 스스로 지운다</h3>
 * <p>만료 판정은 「세 저작물 중 <b>어느 하나라도</b> 커트라인 이전에 저장됐다」는 OR 이다. 그런데
 * 표마다 따로 DELETE 를 날리면 <b>앞 문장이 그 OR 의 항을 없애 버린다</b> — 라벨이 30일 전,
 * 메타가 1일 전인 그룹에서 라벨을 먼저 지우면 그 다음 메타 삭제문의 판정이 거짓이 되어
 * <b>메타만 살아남는다</b>. 오류가 없어 어떤 시험에도 걸리지 않는 종류의 결함이다.
 * <p>PostgreSQL 의 데이터 변경 CTE 는 <b>모두 같은 스냅샷</b>을 보고 서로의 효과를 보지 못한다.
 * 그래서 판정 한 번 · 삭제 셋을 <b>단일 실행문</b>으로 묶으면 그 함정이 구조적으로 사라진다.
 *
 * <h3>★ 조건은 후보 조회가 아니라 실행문이 진다</h3>
 * <p>고른 시점과 지우는 시점 사이의 창을 실행문 안에서 닫는다(DFEAT-055). 그래서
 * {@link #deleteExpiredWorkGroup} 는 <b>소유자·영상·커트라인 셋을 함께 받고 그 인자가 곧 조건</b>
 * 이다. <b>식별자만 받아 지우는 창구를 두지 않는다</b> — 그런 창구가 있으면 부르는 쪽이 조건을
 * 한 번만 잊어도 되돌릴 수 없다.
 *
 * <h3>후보 조회와 삭제 조건은 동치다 — 갈리면 후보로만 잡히고 영영 안 지워진다</h3>
 * <p>{@link #findExpiredWorkGroups} 는 표마다 「커트라인 이전 저장이 있는 (사용자, 영상)」을
 * 뽑아 합집합을 만들고, 삭제문은 「세 표 중 어느 하나에 커트라인 이전 저장이 있다」를 본다.
 * 「그룹의 최초 저장이 커트라인보다 이르다」와 <b>같은 사실의 두 표현</b>이다
 * ({@code MIN(REG_DT) < cutoff} ⟺ {@code ∃ 행. REG_DT < cutoff}). 한쪽만 고치면 매 회차 후보로
 * 잡히기만 하고 실제로는 0행이 된다 — 그 동치성을 시험이 고정한다.
 *
 * <p>⚠ 커트라인 <b>산술</b>은 여기서 하지 않는다 — {@code PortalRetentionPolicy} 가 소유한다.
 * 이 클래스는 받은 커트라인을 조건으로 쓰기만 한다.
 */
@Repository
// 리포지토리가 스스로 트랜잭션 경계를 갖는다(PortalUploadAssetRepository 와 같은 규약) — 여기
//   삭제문은 네이티브라 활성 트랜잭션 없이는 실행 자체가 거부된다. 호출부 트랜잭션이 있으면 합류한다.
@Transactional("controlTransactionManager")
public class PortalUserWorkRepository {

    /**
     * 만료 판정 — 세 표 중 <b>어느 하나라도</b> 커트라인 이전 저장을 가진 (사용자, 영상)인가.
     *
     * <p>삭제문 안에서 <b>한 번만</b> 평가되도록 CTE 로 뽑아 둔다. 세 DELETE 에 복붙하면 셋이
     * 갈릴 수 있고, 무엇보다 복붙해도 CTE 스냅샷 성질이 없으면 위 클래스 주석의 함정이 그대로다.
     */
    private static final String DELETE_EXPIRED_WORK_GROUP = """
            WITH expired AS (
                SELECT 1 AS ok
                 WHERE EXISTS (SELECT 1 FROM ls_portal_user_label t
                                WHERE t.portal_user_no = :portalUserNo
                                  AND t.src_raw_sn = :srcRawSn
                                  AND t.reg_dt < :cutoff)
                    OR EXISTS (SELECT 1 FROM ls_portal_user_meta t
                                WHERE t.portal_user_no = :portalUserNo
                                  AND t.src_raw_sn = :srcRawSn
                                  AND t.reg_dt < :cutoff)
                    OR EXISTS (SELECT 1 FROM ls_portal_user_evnt_anno t
                                WHERE t.portal_user_no = :portalUserNo
                                  AND t.src_raw_sn = :srcRawSn
                                  AND t.reg_dt < :cutoff)
            ),
            del_label AS (
                DELETE FROM ls_portal_user_label t
                 WHERE t.portal_user_no = :portalUserNo
                   AND t.src_raw_sn = :srcRawSn
                   AND EXISTS (SELECT 1 FROM expired)
                RETURNING 1
            ),
            del_meta AS (
                DELETE FROM ls_portal_user_meta t
                 WHERE t.portal_user_no = :portalUserNo
                   AND t.src_raw_sn = :srcRawSn
                   AND EXISTS (SELECT 1 FROM expired)
                RETURNING 1
            ),
            del_anno AS (
                DELETE FROM ls_portal_user_evnt_anno t
                 WHERE t.portal_user_no = :portalUserNo
                   AND t.src_raw_sn = :srcRawSn
                   AND EXISTS (SELECT 1 FROM expired)
                RETURNING 1
            )
            SELECT (SELECT count(*) FROM del_label)
                 + (SELECT count(*) FROM del_meta)
                 + (SELECT count(*) FROM del_anno)
            """;

    /** 표별 후보 — 「커트라인 이전 저장이 하나라도 있는 (사용자, 영상)」. 셋의 합집합이 후보 전체다. */
    private static final List<String> EXPIRED_GROUP_QUERIES = List.of(
            "select distinct w.portalUserNo, w.srcRawSn from LsPortalUserLabel w where w.regDt < :cutoff",
            "select distinct w.portalUserNo, w.srcRawSn from LsPortalUserMeta w where w.regDt < :cutoff",
            "select distinct w.portalUserNo, w.srcRawSn from LsPortalUserEvntAnno w where w.regDt < :cutoff");

    /** 표별 영상 단위 최초 저장 시각 — 셋 중 가장 이른 것이 그 사용자의 저작 최초 저장 시각이다. */
    private static final List<String> EARLIEST_QUERIES = List.of(
            "select w.srcRawSn, min(w.regDt) from LsPortalUserLabel w"
                    + " where w.portalUserNo = :portalUserNo and w.srcRawSn in :rawSns group by w.srcRawSn",
            "select w.srcRawSn, min(w.regDt) from LsPortalUserMeta w"
                    + " where w.portalUserNo = :portalUserNo and w.srcRawSn in :rawSns group by w.srcRawSn",
            "select w.srcRawSn, min(w.regDt) from LsPortalUserEvntAnno w"
                    + " where w.portalUserNo = :portalUserNo and w.srcRawSn in :rawSns group by w.srcRawSn");

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    /**
     * 보존기간이 만료된 (사용자, 영상) 후보 전량. @design DFEAT-055, AC-1068
     *
     * <p>★ 저장 라벨만 훑지 않는다 — 라벨 없이 메타나 어노테이션만 고친 사용자가 여기서 빠지면
     * 그 저작물은 <b>어느 회차에도 잡히지 않아 영구히 남는다</b>.
     *
     * <p>표마다 「커트라인 이전 저장이 있는가」를 물어 합집합을 만든다. 표를 가로지르는 집계
     * ({@code MIN})를 쓰지 않는 것은 그것이 같은 사실의 더 비싼 표현이기 때문이다 — 그룹의 최초
     * 저장이 커트라인보다 이르다는 것은 곧 <b>어느 표엔가 커트라인 이전 행이 있다</b>는 뜻이다.
     *
     * @return 중복 없는 (사용자, 영상) 목록. 발견 순서를 보존해 회차마다 처리 순서가 흔들리지 않는다
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<WorkGroup> findExpiredWorkGroups(LocalDateTime cutoff) {
        Set<WorkGroup> groups = new LinkedHashSet<>();
        for (String jpql : EXPIRED_GROUP_QUERIES) {
            for (Object[] row : em.createQuery(jpql, Object[].class)
                    .setParameter("cutoff", cutoff)
                    .getResultList()) {
                if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                    continue;
                }
                groups.add(new WorkGroup((String) row[0], ((Number) row[1]).longValue()));
            }
        }
        return List.copyOf(groups);
    }

    /**
     * 만료 그룹의 <b>세 저작물을 한 문장으로</b> 지운다. @design DFEAT-055, AC-1068
     *
     * <p>판정을 실행문 안에 담아 「고른 시점」과 「지우는 시점」 사이의 창을 닫는다. 소유자와 영상은
     * 세 DELETE 각각에도 다시 걸리므로 <b>하나만 빠져도 남의 저작물을 지운다</b> — 그 하나하나가
     * 회귀 시험의 대상이다.
     *
     * <p>2노드 Active-Active 멱등성도 이 조건이 담당한다 — 한 노드가 먼저 지우면 커트라인 이전
     * 저장이 남지 않아 판정이 거짓이 되고, 다른 노드는 0행이다.
     *
     * @return 지워진 행 수 합계(세 표 합산). 0 이면 타 노드 선점 또는 아직 만료 전
     */
    public int deleteExpiredWorkGroup(String portalUserNo, Long srcRawSn, LocalDateTime cutoff) {
        Object removed = em.createNativeQuery(DELETE_EXPIRED_WORK_GROUP)
                .setParameter("portalUserNo", portalUserNo)
                .setParameter("srcRawSn", srcRawSn)
                .setParameter("cutoff", cutoff)
                .getSingleResult();
        // 손으로 쓴 삭제는 1차 캐시를 갱신하지 않는다 — 같은 트랜잭션에서 이어 읽으면 지워진
        //   엔티티가 그대로 살아 나온다(오류가 없어 조용하다).
        em.clear();
        return removed == null ? 0 : ((Number) removed).intValue();
    }

    /**
     * 영상별 <b>그 사용자의 저작 최초 저장 시각</b> — 만료 예정 시각의 기산점. @design DFEAT-055, AC-1068
     *
     * <p>세 저작물을 통틀어 <b>가장 이른</b> 저장 시각이다. 저작의 시작은 라벨을 처음 만든 때가
     * 아니라 그 영상에 무엇이든 처음 저장한 때다.
     *
     * <p>★ <b>마지막 저장이 아니다.</b> 마지막 저장에서 다시 계산하면 저장할 때마다 만료가 뒤로
     * 밀려 작업을 이어 가는 한 만료가 영영 오지 않는다(2026-09-04 에 고친 결함이다 — 되돌리지 말 것).
     *
     * <p>소유자 스코프를 WHERE 에 강제해 타 사용자의 저작 이력이 섞이지 않게 한다(IDOR).
     * 목록 한 페이지의 영상 집합을 표당 1회씩 총 3회로 모은다(영상마다 조회하면 N+1).
     *
     * @return 영상 → 최초 저장 시각. 세 저작물을 하나도 갖지 않은 영상은 <b>키 자체가 없다</b>
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Map<Long, LocalDateTime> findEarliestAuthoredAtByVideo(String portalUserNo,
                                                                  Collection<Long> rawSns) {
        Map<Long, LocalDateTime> earliest = new HashMap<>();
        if (portalUserNo == null || rawSns == null || rawSns.isEmpty()) {
            return earliest;
        }
        for (String jpql : EARLIEST_QUERIES) {
            for (Object[] row : em.createQuery(jpql, Object[].class)
                    .setParameter("portalUserNo", portalUserNo)
                    .setParameter("rawSns", rawSns)
                    .getResultList()) {
                if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                    continue;
                }
                Long rawSn = ((Number) row[0]).longValue();
                LocalDateTime at = (LocalDateTime) row[1];
                earliest.merge(rawSn, at, (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        return earliest;
    }

    /**
     * 보존기간 축이 다루는 <b>한 벌</b> — 같은 사용자가 같은 영상에 남긴 저작물 전체.
     *
     * @param portalUserNo 포털 사용자 식별자(격리 키)
     * @param srcRawSn     대상 데이터마트 영상
     */
    public record WorkGroup(String portalUserNo, Long srcRawSn) {
    }
}
