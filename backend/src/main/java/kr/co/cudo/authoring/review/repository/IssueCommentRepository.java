package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface IssueCommentRepository extends JpaRepository<LsIssueComment, Long> {

    /** 한 이슈의 댓글을 등록 시각 오름차순으로 조회 (스레드 시간순 노출). */
    List<LsIssueComment> findByDataIssueSnOrderByRegDtAsc(Long dataIssueSn);

    /** 여러 이슈의 댓글을 한 번에 조회 (N+1 회피용 IN 쿼리). 등록 시각 오름차순. */
    List<LsIssueComment> findByDataIssueSnInOrderByRegDtAsc(Collection<Long> dataIssueSns);
}
