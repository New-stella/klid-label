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
     * 영상 단위 — 아직 해소되지 않은 문의가 달린 프레임 식별자(SRC_SN) 집합. 중복은 제거되고
     * 정렬은 보장하지 않는다(호출부가 집합으로 쓴다). 결과가 비는 것은 정상이다.
     *
     * <p><b>판정 축은 「해소 여부」 하나이지 문의의 종류가 아니다.</b> 반려 사유 성격의 항목
     * ({@code ISSUE_TYPE_CD='REJECTION'})은 등록 시점부터 {@code RESOLVED} 로 고정되므로 타입
     * 조건을 따로 걸지 않아도 자연히 빠진다 — 두 축을 섞으면 나중에 상태 머신이 바뀔 때 한쪽만
     * 갱신된다.
     *
     * <p>{@code SRC_SN} 이 비어 있는 행은 <b>영상 단위 문의</b>라 어느 프레임도 표시하게 만들어선
     * 안 되므로 제외한다.
     *
     * <p>★ 프레임마다 부르지 말 것(N+1) — 영상 1건당 한 번 불러 집합으로 쓰는 조회다.
     *
     * @design API-043
     */
    default List<Long> findUnresolvedSrcSnsByDataRawSn(Long dataRawSn) {
        return findSrcSnsWithIssueSttsCdNot(dataRawSn, LsDataIssue.STTS_RESOLVED);
    }

    /**
     * {@link #findUnresolvedSrcSnsByDataRawSn(Long)} 의 실쿼리. 상태 상수를 파라미터로 받아
     * JPQL 안에 상태 문자열 리터럴이 다시 생기지 않게 한다(상수 사본 = 두 번째 진실원).
     */
    @Query("SELECT DISTINCT i.srcSn FROM LsDataIssue i " +
           "WHERE i.dataRawSn = :dataRawSn " +
           "  AND i.srcSn IS NOT NULL " +
           "  AND i.issueSttsCd <> :excludedSttsCd")
    List<Long> findSrcSnsWithIssueSttsCdNot(@Param("dataRawSn") Long dataRawSn,
                                            @Param("excludedSttsCd") String excludedSttsCd);

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
