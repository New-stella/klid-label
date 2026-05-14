package kr.co.cudo.authoring.meta.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsDataMetaReviewRepository extends JpaRepository<LsDataMetaReview, Long> {
}
