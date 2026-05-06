package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRawHstry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDataRawHstryRepository extends JpaRepository<LsDataRawHstry, Long> {

    List<LsDataRawHstry> findByRawSnOrderByChgDtAsc(Long rawSn);
}
