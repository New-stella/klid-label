package kr.co.cudo.authoring.evntanno.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@ControlRepo
public interface LsEvntAnnoRepository extends JpaRepository<LsEvntAnno, Long> {

    /** 영상(RAW_SN) 단위 event_annotation 조회. UK(RAW_SN) 로 최대 1건. */
    Optional<LsEvntAnno> findByRawSn(Long rawSn);
}
