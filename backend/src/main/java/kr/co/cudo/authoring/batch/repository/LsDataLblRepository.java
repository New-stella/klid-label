package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDataLblRepository extends JpaRepository<LsDataLbl, Long> {

    List<LsDataLbl> findBySrcSn(Long srcSn);

    List<LsDataLbl> findBySrcSnAndAutoLblYn(Long srcSn, String autoLblYn);

    long countBySrcSnAndAutoLblYn(Long srcSn, String autoLblYn);
}
