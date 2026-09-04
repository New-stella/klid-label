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
     * 영상별 본인 저장 라벨의 <b>최초 저장일</b>({@code MIN(REG_DT)}) — 보존기간 만료 예정 시각의 기준점.
     * @design DFEAT-055, AC-1068
     *
     * <h3>★ 마지막 저장({@code MAX})이 아니다 — 되돌리지 말 것</h3>
     * <p>포털 확정 회신(2026-09-03)이 데이터마트 채널의 기산점을 <b>그 사용자의 저작 최초 저장 시점</b>
     * 으로 못박았다. 마지막 저장에서 다시 계산하면 <b>사용자가 저장할 때마다 만료가 뒤로 밀려</b>
     * 작업을 이어 가는 한 만료가 영영 오지 않고, 그러면 이 채널의 자동 삭제가 사실상 실행되지 않으며
     * 화면이 고지한 만료 예정일도 계속 어긋난다. 편의를 이유로 {@code MAX} 로 되돌리지 말 것.
     *
     * <p>⚠ 업로드 축은 <b>여전히 마지막 저장</b>이 기준점 한쪽이다
     * ({@code PortalUploadAssetRepository.findLastLabelSavedAt} 의 {@code max(l.reg_dt)}). 두 채널의
     * 기산점이 같은지는 확정 회신에 언급이 없으므로 <b>이 축의 확정을 그쪽으로 옮기지 않는다</b> —
     * 이 파일의 {@code min} 을 근거로 그쪽을 「일관성」으로 고치지 말 것.
     *
     * <p>목록 한 페이지의 영상 집합을 <b>단일 집계 쿼리</b>로 모은다(영상마다 조회하면 N+1). 소유자
     * 스코프({@code PORTAL_USER_NO})를 WHERE 에 강제해 타 사용자의 저장 이력이 섞이지 않게 한다(IDOR).
     *
     * @return {@code [srcRawSn, MIN(regDt)]} 행 목록 — 저장 라벨이 없는 영상은 <b>행 자체가 없다</b>
     */
    @Query("select l.srcRawSn, min(l.regDt) from LsPortalUserLabel l "
            + "where l.portalUserNo = :portalUserNo and l.srcRawSn in :rawSns "
            + "group by l.srcRawSn")
    List<Object[]> findMinRegDtGroupedBySrcRawSn(@Param("portalUserNo") String portalUserNo,
                                                 @Param("rawSns") Collection<Long> rawSns);

    // ===== 보존기간 만료 자동 삭제 배치 전용 — 사용자 요청 진입점에서 직접 사용 금지 =====

    /**
     * 보존기간이 만료된 (사용자, 영상) 그룹 후보. @design DFEAT-055, AC-1068, AC-032
     *
     * <p>기준점은 그 그룹 저장 라벨의 <b>{@code MIN(REG_DT)}(최초 저장)</b> 이며
     * {@link #findMinRegDtGroupedBySrcRawSn} · {@code PortalRetentionPolicy} 와 <b>같은 축</b>이다.
     * 두 곳의 집계 함수가 갈리면 <b>화면이 고지한 만료일과 실제 삭제일이 어긋난다</b> — 한쪽만 고치지 말 것.
     *
     * <p>★ 재작업으로 라벨을 다시 저장해도 그룹은 후보에서 <b>빠지지 않는다</b>. {@code MIN} 은 뒤로
     * 밀리지 않으므로, 최초 저장이 보존기간을 넘긴 그룹은 <b>지금 작업 중이라도</b> 삭제 대상이다
     * (DFEAT-055 — 포털 확정 회신 2026-09-03). 구 {@code MAX} 축에서는 저장할 때마다 만료가 밀려
     * 이 배치가 사실상 아무것도 지우지 못했다.
     *
     * @return {@code [portalUserNo, srcRawSn]} 행 목록
     */
    @Query("select l.portalUserNo, l.srcRawSn from LsPortalUserLabel l "
            + "group by l.portalUserNo, l.srcRawSn having min(l.regDt) < :cutoff")
    List<Object[]> findExpiredLabelGroups(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 만료 그룹의 저장 라벨을 <b>조건부</b> 벌크 삭제한다. @design DFEAT-055, AC-1068, AC-032
     *
     * <p>★ 후보 조회에서 이미 판정했더라도 <b>삭제문 자체에 만료 조건을 다시 건다</b> — 삭제는
     * 비가역이므로 「고른 시점」과 「지우는 시점」 사이의 창을 실행문 안에서 닫는다(DFEAT-055).
     * 식별자만 받아 지우는 창구를 따로 두지 않는 것도 같은 이유다 — 그런 창구가 있으면 부르는 쪽이
     * 조건을 한 번만 잊어도 되돌릴 수 없다.
     *
     * <p>조건은 후보 쿼리와 <b>같은 축</b>이다 — 「그 그룹의 <b>최초</b> 저장이 커트라인보다 이르다」,
     * 즉 커트라인 이전에 저장된 라벨이 하나라도 있다({@code MIN(REG_DT) < cutoff} 와 동치).
     * ⚠ 구 조건 {@code not exists (regDt >= cutoff)}(= 커트라인 이후 저장이 하나도 없다)는
     * {@code MAX} 축의 조건이라 <b>되살리면 재작업 중인 그룹이 영영 지워지지 않는다</b>.
     *
     * <p>2노드 Active-Active 멱등성도 이 조건이 담당한다 — 한 노드가 먼저 지우면 남은 행이 없어
     * 조건이 거짓이 되므로 다른 노드는 0행이다.
     *
     * @return 삭제된 라벨 행 수(0 이면 타 노드 선점 또는 아직 만료 전)
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsPortalUserLabel l "
            + "where l.portalUserNo = :portalUserNo and l.srcRawSn = :srcRawSn "
            + "and exists (select k.userLblSn from LsPortalUserLabel k "
            + "            where k.portalUserNo = :portalUserNo and k.srcRawSn = :srcRawSn "
            + "            and k.regDt < :cutoff)")
    int deleteExpiredLabelGroup(@Param("portalUserNo") String portalUserNo,
                                @Param("srcRawSn") Long srcRawSn,
                                @Param("cutoff") LocalDateTime cutoff);
}
