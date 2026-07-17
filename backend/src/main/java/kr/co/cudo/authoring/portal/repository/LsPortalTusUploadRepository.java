package kr.co.cudo.authoring.portal.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
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
 * 포털 TUS 업로드 세션 리포지토리 (control DB).
 *
 * <p>소유자 스코프·파라미터 바인딩 파생 메서드만 노출한다(IDOR 차단, 문자열 연결 없음).
 */
@ControlRepo
public interface LsPortalTusUploadRepository extends JpaRepository<LsPortalTusUpload, UUID> {

    /** 사용자별 진행 중 세션 수 — 동시 IN_PROGRESS 상한 검증용(429). */
    long countByPortalUserNoAndSttsCd(String portalUserNo, String sttsCd);

    /**
     * 세션 행을 PESSIMISTIC_WRITE 로 잠금 조회.
     *
     * <p>PATCH/DELETE 진입 시 행을 잠가 동일 세션의 동시 PATCH·cancel 을 직렬화한다. 잠금 보유
     * 구간에서 offset 검사 + 파일 write + offset 갱신을 원자적으로 수행해 파일 오염을 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM LsPortalTusUpload u WHERE u.uldId = :uldId")
    Optional<LsPortalTusUpload> findByUldIdForUpdate(@Param("uldId") UUID uldId);

    /**
     * 완료 전이를 DB 조건부 UPDATE 로 강제(멱등).
     *
     * <p>STTS_CD 가 IN_PROGRESS 인 행만 COMPLETED + ULD_SN 으로 전이한다. affectedRows==1 인
     * 호출만 LS_PORTAL_ULD INSERT 책임을 갖고, 0 이면 이미 완료된 것이므로 멱등 응답한다.
     *
     * @return 전이된 행 수(1 이면 본 호출이 완료 책임, 0 이면 이미 완료됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsPortalTusUpload u SET u.sttsCd = 'COMPLETED', u.uldSn = :uldSn, u.mdfcnDt = :now "
            + "WHERE u.uldId = :uldId AND u.sttsCd = 'IN_PROGRESS'")
    int markCompletedIfInProgress(@Param("uldId") UUID uldId,
                                  @Param("uldSn") Long uldSn,
                                  @Param("now") LocalDateTime now);

    /** TTL 만료 + 진행 중 세션 — 스윕 잡 스캔용. */
    @Query("SELECT u FROM LsPortalTusUpload u WHERE u.sttsCd = 'IN_PROGRESS' AND u.expiresAt < :now")
    List<LsPortalTusUpload> findExpired(@Param("now") LocalDateTime now);

    /**
     * 만료된 진행 중 세션의 조건부 벌크 삭제(스윕 2노드 중복 실행 안전화 — database 🔴1 / security M-4).
     *
     * <p>{@code IN_PROGRESS} 인 행만 삭제한다. 두 노드가 같은 세션을 동시에 스윕해도 한쪽만 1행을
     * 삭제하고 다른 쪽은 0행이 되어 예외 없이 멱등하다(엔티티 로드 후 {@code delete} 의
     * "존재 재확인 후 remove" 경합 예외 제거).
     *
     * @return 삭제된 행 수(1 이면 본 노드가 정리 책임, 0 이면 이미 정리됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM LsPortalTusUpload u WHERE u.uldId = :uldId AND u.sttsCd = 'IN_PROGRESS'")
    int deleteExpiredInProgress(@Param("uldId") UUID uldId);
}
