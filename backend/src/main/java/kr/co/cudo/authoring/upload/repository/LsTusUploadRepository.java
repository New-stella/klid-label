package kr.co.cudo.authoring.upload.repository;

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

@ControlRepo
public interface LsTusUploadRepository extends JpaRepository<LsTusUpload, UUID> {

    /** 사용자별 진행 중 세션 수 — 동시 IN_PROGRESS 상한(3) 검증용 (HIGH-9, 429). */
    long countByUserNoAndStatus(String userNo, String status);

    /**
     * 세션 행을 PESSIMISTIC_WRITE 로 잠금 조회 (HIGH-1).
     *
     * <p>PATCH 진입 시 이 메서드로 행을 잠가 동일 세션의 동시 PATCH 를 직렬화한다.
     * 잠금 보유 구간에서 offset 검사 + 파일 write + offset 갱신을 원자적으로 수행해
     * 두 청크가 같은 위치에 교차 write 하거나 충돌 측 truncate 가 정상 바이트를 자르는
     * 파일 오염을 차단한다. {@code @Version} 은 이중 방어로 유지.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM LsTusUpload u WHERE u.uploadId = :uploadId")
    Optional<LsTusUpload> findByUploadIdForUpdate(@Param("uploadId") UUID uploadId);

    /**
     * 완료 전이를 DB 조건부 UPDATE 로 강제 (MED-1).
     *
     * <p>STATUS 가 IN_PROGRESS 인 행만 COMPLETED + RAW_SN 으로 전이한다. affectedRows==1 인
     * 호출만 LS_DATA_RAW INSERT 책임을 갖고, 0 이면 다른 트랜잭션이 이미 완료시킨 것이므로
     * 멱등 응답(기존 RAW_SN)을 반환한다. 마지막 청크 재전송·동시 완료에서 LS_DATA_RAW 가
     * 두 번 생성되는 것을 DB 레벨에서 차단한다.
     *
     * @return 전이된 행 수 (1 이면 본 호출이 완료 책임, 0 이면 이미 완료됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsTusUpload u SET u.status = 'COMPLETED', u.rawSn = :rawSn, u.mdfcnDt = :now "
            + "WHERE u.uploadId = :uploadId AND u.status = 'IN_PROGRESS'")
    int markCompletedIfInProgress(@Param("uploadId") UUID uploadId,
                                  @Param("rawSn") Long rawSn,
                                  @Param("now") LocalDateTime now);

    /** TTL 만료 + 미완료 세션 — Quartz 정리 잡 스캔용. */
    @Query("SELECT u FROM LsTusUpload u WHERE u.status <> 'COMPLETED' AND u.expiresAt < :now")
    List<LsTusUpload> findExpired(@Param("now") LocalDateTime now);
}
