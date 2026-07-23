package kr.co.cudo.authoring.meta.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsDataMetaReviewRepository extends JpaRepository<LsDataMetaReview, Long> {

    boolean existsByDataMetaSn(Long dataMetaSn);

    /** 메타 PK 집합으로 검토행 배치 조회 — 조회 응답의 검토상태 조인 시 N+1 방지. */
    List<LsDataMetaReview> findByDataMetaSnIn(Collection<Long> dataMetaSns);

    List<LsDataMetaReview> findAllByDataRawSnAndRvwSttsCd(Long dataRawSn, String rvwSttsCd);

    List<LsDataMetaReview> findAllByDataRawSn(Long dataRawSn);
}
