package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 포털 사용자 저장 라벨 오버레이 저장소 — 모든 진입이 <b>소유자 스코프</b>다 (IDOR / CWE-639).
 *
 * <h3>★ 보존기간 축의 조회·삭제 창구는 이 파일에 없다 — 저작물이 셋이라 표를 가로지른다</h3>
 * <p>기산점(만료 예정 시각)·후보 탐색·삭제는 전부 {@link PortalUserWorkRepository} 가 소유한다.
 * 저장 라벨만 보는 창구를 여기에 되살리면 라벨 없이 메타·이벤트 어노테이션만 고친 (사용자, 영상)이
 * <b>고지에서도 삭제에서도 빠져</b> 그 저작물이 영구히 남는다(DFEAT-055 · AC-1068). 되돌리지 말 것.
 * <p>구 창구 {@code findMinRegDtGroupedBySrcRawSn} · {@code findExpiredLabelGroups} ·
 * {@code deleteExpiredLabelGroup} 은 그 이유로 이관됐다.
 *
 * @design ERD-018
 */
@ControlRepo
public interface LsPortalUserLabelRepository extends JpaRepository<LsPortalUserLabel, Long> {

    List<LsPortalUserLabel> findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(
            String portalUserNo, Long srcRawSn);

    List<LsPortalUserLabel> findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(
            String portalUserNo, Long srcDataSrcSn);
}
