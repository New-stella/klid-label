package kr.co.cudo.authoring.portal.upload;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 포털 재개 업로드 세션 — <b>공용 세션 원장</b>({@code LS_TUS_UPLOAD}) 위의 포털 스코프 (ADR-058 흡수).
 *
 * <h3>★ 채널 판별은 클립 식별자의 <b>부재</b>다</h3>
 * <p>관제 세션은 클립 식별자가 구조적으로 항상 채워지고(요청 검증이 강제한다) 그 값이 인입 행의
 * 역참조 키다. 포털에는 그 개념이 없어 비어 있으며, 그 부재가 곧 채널 판별자다
 * ({@link LsTusUpload#createPortalSession} javadoc 이 근거를 갖는다).
 *
 * <p>이 판별이 <b>정리 잡 둘</b>을 가른다. 관제 정리 잡은 만료 세션의 인입 행을 함께 종결시키는데
 * 포털 세션에는 종결할 인입 행이 없고 임시 파일도 다른 저장 루트에 있어 그 잡의 경로 가드에 막힌다 —
 * 즉 관제 잡이 포털 세션을 집으면 <b>행만 사라지고 파일이 고아로 남는다</b>. 서로의 세션을 집지
 * 않도록 양쪽이 같은 축으로 거른다.
 *
 * @design ADR-058
 * @design ERD-028
 */
@ControlRepo
public interface PortalTusSessionRepository extends JpaRepository<LsTusUpload, UUID> {

    /** 포털 세션 판별 — 클립 식별자가 비어 있는 것이 곧 포털 채널이다. */
    String PORTAL_SCOPE = " and (u.vmsClipId is null or u.vmsClipId = '')";

    /** 사용자별 진행 중 세션 수 — 동시 진행 상한 검증용. */
    @Query("select count(u) from LsTusUpload u"
            + " where u.userNo = :owner and u.status = 'IN_PROGRESS'" + PORTAL_SCOPE)
    long countInProgressByOwner(@Param("owner") String portalUserNo);

    /** 포털 세션 단건 — 관제 세션은 여기서 보이지 않는다(채널 격리). */
    @Query("select u from LsTusUpload u where u.uploadId = :uploadId" + PORTAL_SCOPE)
    Optional<LsTusUpload> findPortalSession(@Param("uploadId") UUID uploadId);

    /**
     * 세션 행을 비관적 쓰기 락으로 잠금 조회 — 같은 세션의 동시 이어쓰기·취소를 직렬화한다.
     * 잠금 구간에서 오프셋 검사 + 파일 기록 + 오프셋 전진을 원자적으로 수행해 파일 오염을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from LsTusUpload u where u.uploadId = :uploadId" + PORTAL_SCOPE)
    Optional<LsTusUpload> findPortalSessionForUpdate(@Param("uploadId") UUID uploadId);

    /**
     * 완료 전이를 DB 조건부 UPDATE 로 강제(멱등). 진행 중인 행만 완료로 전이하며,
     * <b>1행을 전이한 호출만</b> 자산 적재 책임을 갖는다. 0 이면 이미 완료된 것이라 멱등 응답한다.
     *
     * @return 전이한 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("update LsTusUpload u set u.status = 'COMPLETED', u.rawSn = :rawSn, u.mdfcnDt = :now"
            + " where u.uploadId = :uploadId and u.status = 'IN_PROGRESS'"
            + " and (u.vmsClipId is null or u.vmsClipId = '')")
    int markCompletedIfInProgress(@Param("uploadId") UUID uploadId,
                                  @Param("rawSn") Long rawSn,
                                  @Param("now") LocalDateTime now);

    /** 만료 + 진행 중 포털 세션 — 스윕 스캔용 후보. */
    @Query("select u from LsTusUpload u where u.status = 'IN_PROGRESS' and u.expiresAt < :now"
            + PORTAL_SCOPE + " order by u.expiresAt asc")
    List<LsTusUpload> findExpired(@Param("now") LocalDateTime now);

    /**
     * 만료된 진행 중 포털 세션의 <b>원자 클레임</b> — 삭제 자체가 클레임이다.
     *
     * <p>2노드 동시 실행에서 한쪽만 1행을 지우고 다른 쪽은 0행이 되어 예외 없이 멱등하다. 1행을 지운
     * 노드만 임시 파일을 정리한다(중복 삭제 시도 방지).
     *
     * @return 삭제한 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsTusUpload u where u.uploadId = :uploadId and u.status = 'IN_PROGRESS'"
            + " and (u.vmsClipId is null or u.vmsClipId = '')")
    int deleteExpiredInProgress(@Param("uploadId") UUID uploadId);
}
