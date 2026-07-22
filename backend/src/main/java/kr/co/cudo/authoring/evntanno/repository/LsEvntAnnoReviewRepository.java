package kr.co.cudo.authoring.evntanno.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsEvntAnnoReviewRepository extends JpaRepository<LsEvntAnnoReview, Long> {

    /** event_annotation ID 단위 검토 row 조회. */
    List<LsEvntAnnoReview> findByEvntAnnoSn(Long evntAnnoSn);
}
