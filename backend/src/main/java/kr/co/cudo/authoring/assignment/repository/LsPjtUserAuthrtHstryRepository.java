package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrtHstry;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsPjtUserAuthrtHstryRepository extends JpaRepository<LsPjtUserAuthrtHstry, Long> {

    List<LsPjtUserAuthrtHstry> findByAuthrtSeqOrderByChgDtAsc(Long authrtSeq);
}
