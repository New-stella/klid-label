package kr.co.cudo.authoring.marking.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsMarkingRepository extends JpaRepository<LsMarking, Long> {

    List<LsMarking> findByRawSnOrderByCreatedAtDesc(Long rawSn);
}
