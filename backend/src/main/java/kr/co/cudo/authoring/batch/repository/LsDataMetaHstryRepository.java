package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataMetaHstry;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsDataMetaHstryRepository extends JpaRepository<LsDataMetaHstry, Long> {
}
