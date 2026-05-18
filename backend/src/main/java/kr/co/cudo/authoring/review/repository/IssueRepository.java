package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@ControlRepo
public interface IssueRepository extends JpaRepository<LsDataIssue, Long> {

    List<LsDataIssue> findByVideoIdOrderByRegisteredAtDesc(Long videoId);

    /**
     * QUR-03 작업자별 반려 건수 집계.
     * VIDEO_ID(=RAW_SN) 가 작업자에게 배정(LS_TASK_ASSIGNMENT.LABELER) 된 영상의 반려 건수를 합산.
     */
    @Query(value = "SELECT COUNT(i) FROM LsDataIssue i " +
                   "WHERE i.videoId IN (" +
                   "  SELECT a.rawDataId FROM LsTaskAssignment a " +
                   "  WHERE a.userNo = :workerNo AND a.taskTypeCd = 'LABELER'" +
                   ")")
    long countRejectionsByWorker(@Param("workerNo") Long workerNo);
}
