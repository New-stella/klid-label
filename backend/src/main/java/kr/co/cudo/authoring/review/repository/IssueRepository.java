package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@ControlRepo
public interface IssueRepository extends JpaRepository<LsDataIssue, Long> {

    List<LsDataIssue> findByDataRawSnOrderByRegDtDesc(Long dataRawSn);

    /** 영상 단위 이슈 스레드 — 등록 시각 오름차순(스레드 시간순 노출용). */
    List<LsDataIssue> findByDataRawSnOrderByRegDtAsc(Long dataRawSn);

    /**
     * 영상의 미해소 문의(INQUIRY) 건수 — RESOLVED 가 아닌 OPEN/ANSWERED 합산.
     * REJECTION 이력은 제외.
     */
    @Query("SELECT COUNT(i) FROM LsDataIssue i " +
           "WHERE i.dataRawSn = :rawSn " +
           "  AND i.issueTypeCd = 'INQUIRY' " +
           "  AND i.issueSttsCd <> 'RESOLVED'")
    long countUnresolvedInquiries(@Param("rawSn") Long rawSn);

    /**
     * QUR-03 작업자별 반려 건수 집계.
     * DATA_RAW_SN(=RAW_SN) 이 작업자에게 배정(LS_TASK_ALTMNT.LABELER) 된 영상의 반려 건수를 합산.
     * V57 이후 INQUIRY 행도 동일 테이블(LS_DATA_ISSUE)에 저장되므로 ISSUE_TYPE_CD='REJECTION' 으로 한정한다
     * (한정하지 않으면 문의 건이 반려 건수에 합산되어 QUR-03 집계가 부풀려진다).
     */
    @Query(value = "SELECT COUNT(i) FROM LsDataIssue i " +
                   "WHERE i.issueTypeCd = 'REJECTION' " +
                   "  AND i.dataRawSn IN (" +
                   "  SELECT a.rawDataId FROM LsTaskAssignment a " +
                   "  WHERE a.userNo = :workerNo AND a.taskTypeCd = 'LABELER'" +
                   ")")
    long countRejectionsByWorker(@Param("workerNo") Long workerNo);
}
