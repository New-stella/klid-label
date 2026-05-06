package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsDataSrcHstryRepository extends JpaRepository<LsDataSrcHstry, Long> {
}
