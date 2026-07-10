package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDataLblHstryRepository extends JpaRepository<LsDataLblHstry, Long> {

    /** 프레임 단위 버전 목록 — 최신순. */
    List<LsDataLblHstry> findBySrcSnOrderByRegDtDesc(Long srcSn);

}
