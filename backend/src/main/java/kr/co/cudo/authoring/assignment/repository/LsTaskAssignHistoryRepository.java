package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignHistory;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsTaskAssignHistoryRepository extends JpaRepository<LsTaskAssignHistory, Long> {

    List<LsTaskAssignHistory> findByAuthrtSeqOrderByChgDtAsc(Long authrtSeq);
}
