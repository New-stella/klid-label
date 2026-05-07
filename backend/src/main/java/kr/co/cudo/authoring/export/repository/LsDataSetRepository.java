package kr.co.cudo.authoring.export.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.export.entity.LsDataSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsDataSetRepository extends JpaRepository<LsDataSet, Long> {

    /** 내보내기 작업 목록 (REVIEWER 의 데이터셋 화면용) — 최신순 페이징. */
    Page<LsDataSet> findAllByOrderByRegisteredAtDesc(Pageable pageable);
}
