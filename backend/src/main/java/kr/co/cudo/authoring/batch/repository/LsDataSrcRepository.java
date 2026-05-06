package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataSrcRepository extends JpaRepository<LsDataSrc, Long> {

    List<LsDataSrc> findByRawSnOrderByFrameNoAsc(Long rawSn);

    Optional<LsDataSrc> findByRawSnAndFrameNo(Long rawSn, Integer frameNo);

    long countByRawSn(Long rawSn);
}
