package kr.co.cudo.authoring.export.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.export.entity.LsDataSet;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsDataSetRepository extends JpaRepository<LsDataSet, Long> {
}
