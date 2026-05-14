package kr.co.cudo.authoring.meta.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDataMetaReviewRepository extends JpaRepository<LsDataMetaReview, Long> {

    boolean existsByDataMetaSn(Long dataMetaSn);

    List<LsDataMetaReview> findAllByDataRawSnAndRvwSttsCd(Long dataRawSn, String rvwSttsCd);

    List<LsDataMetaReview> findAllByDataRawSn(Long dataRawSn);
}
