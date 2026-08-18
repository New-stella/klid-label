package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsPortalUserLabelRepository extends JpaRepository<LsPortalUserLabel, Long> {

    List<LsPortalUserLabel> findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(
            String portalUserNo, Long srcRawSn);

    List<LsPortalUserLabel> findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(
            String portalUserNo, Long srcDataSrcSn);

    /**
     * 영상별 본인 저장 라벨의 마지막 저장일({@code MAX(REG_DT)}) — 보존기간 만료 예정 시각의 기준점.
     * @design DFEAT-055
     *
     * <p>목록 한 페이지의 영상 집합을 <b>단일 집계 쿼리</b>로 모은다(영상마다 조회하면 N+1). 소유자
     * 스코프({@code PORTAL_USER_NO})를 WHERE 에 강제해 타 사용자의 저장 이력이 섞이지 않게 한다(IDOR).
     *
     * @return {@code [srcRawSn, MAX(regDt)]} 행 목록 — 저장 라벨이 없는 영상은 <b>행 자체가 없다</b>
     */
    @Query("select l.srcRawSn, max(l.regDt) from LsPortalUserLabel l "
            + "where l.portalUserNo = :portalUserNo and l.srcRawSn in :rawSns "
            + "group by l.srcRawSn")
    List<Object[]> findMaxRegDtGroupedBySrcRawSn(@Param("portalUserNo") String portalUserNo,
                                                 @Param("rawSns") Collection<Long> rawSns);

    // ===== 보존기간 만료 자동 삭제 배치 전용 — 사용자 요청 진입점에서 직접 사용 금지 =====

    /**
     * 보존기간이 만료된 (사용자, 영상) 그룹 후보. @design DFEAT-055, AC-032
     *
     * <p>기준점은 그 그룹 저장 라벨의 {@code MAX(REG_DT)} 다({@code PortalRetentionPolicy} 와 같은 축).
     * 재작업으로 라벨을 다시 저장하면 {@code MAX} 가 밀려 그룹이 후보에서 자동으로 빠진다
     * (AC-032 and_examples[1]).
     *
     * @return {@code [portalUserNo, srcRawSn]} 행 목록
     */
    @Query("select l.portalUserNo, l.srcRawSn from LsPortalUserLabel l "
            + "group by l.portalUserNo, l.srcRawSn having max(l.regDt) < :cutoff")
    List<Object[]> findExpiredLabelGroups(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 만료 그룹의 저장 라벨을 <b>조건부</b> 벌크 삭제한다. @design DFEAT-055, AC-032
     *
     * <p>★ 후보 조회에서 이미 판정했더라도 <b>삭제문 자체에 만료 조건을 다시 건다</b> — 조회~삭제
     * 사이에 사용자가 재작업으로 라벨을 저장하면 그 그룹은 더 이상 만료가 아니기 때문이다. 조건을
     * 빼면 그 창에서 <b>방금 저장한 작업물이 비가역으로 사라진다</b>.
     *
     * <p>2노드 Active-Active 멱등성도 이 조건이 담당한다 — 한 노드가 먼저 지우면 다른 노드는 0행이다.
     *
     * @return 삭제된 라벨 행 수(0 이면 타 노드 선점 또는 재작업으로 만료 해제)
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsPortalUserLabel l "
            + "where l.portalUserNo = :portalUserNo and l.srcRawSn = :srcRawSn "
            + "and not exists (select k.userLblSn from LsPortalUserLabel k "
            + "                where k.portalUserNo = :portalUserNo and k.srcRawSn = :srcRawSn "
            + "                and k.regDt >= :cutoff)")
    int deleteExpiredLabelGroup(@Param("portalUserNo") String portalUserNo,
                                @Param("srcRawSn") Long srcRawSn,
                                @Param("cutoff") LocalDateTime cutoff);
}
