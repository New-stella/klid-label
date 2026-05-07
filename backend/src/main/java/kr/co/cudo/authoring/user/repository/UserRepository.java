package kr.co.cudo.authoring.user.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface UserRepository extends JpaRepository<MngAcctUser, Long> {

    Optional<MngAcctUser> findByUserNo(Long userNo);

    /**
     * WORKER 권한을 가진 활성 사용자 목록과 활성 라벨러 태스크 개수를 단일 쿼리로 조회한다.
     * N+1 방지: 사용자별 LS_PJT_USER_AUTHRT GROUP BY COUNT 를 LEFT JOIN.
     */
    @Query("""
            SELECT new kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount(
                u.userNo, u.userId, u.userNm, u.userEmail,
                (SELECT COUNT(a) FROM kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt a
                  WHERE a.userNo = u.userNo AND a.taskTypeCd = 'LABELER')
            )
            FROM MngAcctUser u, MngAcctUserAuthrt ua
            WHERE u.userNo = ua.id.userNo
              AND ua.id.authrtCd = 'WORKER'
              AND u.useYn = 'Y'
            ORDER BY u.userNo ASC
            """)
    List<WorkerWithTaskCount> findAllWorkersWithTaskCount();

    /**
     * 사용자 마스터 페이징 검색 (REVIEWER 의 /manage/users 화면용).
     * keyword 가 null/빈 문자열이면 전체 검색, 그렇지 않으면 USER_ID/USER_NM/USER_EMAIL LIKE.
     * 활성/비활성 모두 포함.
     */
    @Query("""
            SELECT u FROM MngAcctUser u
             WHERE (:keyword IS NULL OR :keyword = ''
                    OR LOWER(u.userId)    LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.userNm)    LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.userEmail) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<MngAcctUser> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
