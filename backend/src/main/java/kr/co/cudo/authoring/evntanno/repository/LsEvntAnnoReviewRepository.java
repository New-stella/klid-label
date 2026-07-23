package kr.co.cudo.authoring.evntanno.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@ControlRepo
public interface LsEvntAnnoReviewRepository extends JpaRepository<LsEvntAnnoReview, Long> {

    /**
     * event_annotation ID 단위 검토 row 조회 — <b>최신 RVW_SN 우선 결정적 정렬</b>({@code ORDER BY RVW_SN DESC}).
     *
     * <p>{@code LS_EVNT_ANNO_REVIEW.EVNT_ANNO_SN} 에 UNIQUE 제약이 없어 이론상 중복 검토 row 가 존재할 수 있다.
     * 이때 정렬 없이 {@code stream().findFirst()} 로 임의 1건을 고르면(비결정) 오래된/잘못된 검토를 승인·판정할
     * 위험이 있다(MEDIUM). 모든 조회 경로(승인/반려/자동확정/재동결/동결 판정)가 이 메서드를 통해 <b>항상
     * 최신 검토</b>를 선택하도록 정렬을 리포지토리에 고정한다. 파라미터는 바인딩되어 SQL Injection 표면이 없다(CWE-89).
     */
    @Query("SELECT r FROM LsEvntAnnoReview r WHERE r.evntAnnoSn = :evntAnnoSn ORDER BY r.rvwSn DESC")
    List<LsEvntAnnoReview> findByEvntAnnoSn(@Param("evntAnnoSn") Long evntAnnoSn);
}
