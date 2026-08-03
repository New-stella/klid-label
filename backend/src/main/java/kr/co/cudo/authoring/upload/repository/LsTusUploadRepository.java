package kr.co.cudo.authoring.upload.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ControlRepo
public interface LsTusUploadRepository extends JpaRepository<LsTusUpload, UUID> {

    /** 사용자별 진행 중 세션 수 — 동시 IN_PROGRESS 상한(3) 검증용 (HIGH-9, 429). */
    long countByUserNoAndStatus(String userNo, String status);

    /**
     * 클립 ID 별 <b>살아 있는</b> 세션 수 — 인입 행 되살리기 가드 (DEV_FIX 2차 [A]).
     *
     * <p>미도착 대기 상한 종결은 인입 행만 {@code FAILED} 로 내리고 <b>세션은 살려 둔다</b>(다른 종결
     * 경로는 둘을 함께 종결한다). 그 행을 되살리면 같은 클립 ID 의 세션이 <b>둘</b> 살아 있게 되어
     * 완료 순서에 따라 메타와 파일이 뒤섞인다. 되살리기 전에 이 카운트로 막는다.
     *
     * <p>소유자를 가리지 않는다 — 다른 REVIEWER 의 진행 중 업로드도 같은 파일명을 노리므로 동일하게
     * 막아야 한다.
     */
    long countByVmsClipIdAndStatus(String vmsClipId, String status);

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
     * <p>STATUS 가 IN_PROGRESS 인 행만 COMPLETED 로 전이한다. <b>affectedRows==1 인 호출만
     * {@code LS_DATA_INGEST} 인입 행 INSERT 책임</b>을 갖고, 0 이면 다른 트랜잭션이 이미 완료시킨
     * 것이므로 아무것도 만들지 않고 멱등 응답한다. 마지막 청크 재전송·동시 완료에서 인입 행이 두 번
     * 생성되는 것을 DB 레벨에서 차단한다.
     *
     * <p><b>Phase 1 — {@code RAW_SN} 은 더 이상 여기서 정해지지 않는다.</b> 업로드는 인입 행만 남기고
     * 적재는 인입 폴링({@code TrainingVideoIngestTx})이 수행하므로 호출부는 null 을 넘긴다. 적재 결과
     * 영상 식별자는 {@code LS_DATA_INGEST.RAW_SN} 이 보유한다(구 값이 남은 과거 행 호환을 위해
     * 컬럼·파라미터는 유지한다).
     *
     * @return 전이된 행 수 (1 이면 본 호출이 완료 책임, 0 이면 이미 완료됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsTusUpload u SET u.status = 'COMPLETED', u.rawSn = :rawSn, u.mdfcnDt = :now "
            + "WHERE u.uploadId = :uploadId AND u.status = 'IN_PROGRESS'")
    int markCompletedIfInProgress(@Param("uploadId") UUID uploadId,
                                  @Param("rawSn") Long rawSn,
                                  @Param("now") LocalDateTime now);

    /**
     * 실패 경로의 <b>세션 명시 종결</b> — 임시 파일이 사라진 세션이 재개 가능한 채로 남지 않게 한다.
     *
     * <p><b>왜 별도 트랜잭션인가</b>: 이 전이는 <b>호출자 트랜잭션이 롤백되는 경로</b>에서 필요하다
     * (인입 INSERT 실패·매직바이트 실패 — 파일은 이미 옮겨졌거나 지워졌는데 DB 는 되돌아간다).
     * 같은 트랜잭션에서 바꾸면 함께 롤백돼 세션이 {@code IN_PROGRESS} 로 부활하고, 클라이언트 재시도가
     * <b>존재하지 않는 임시 파일</b>에 이어쓰기를 시도한다.
     *
     * <p><b>호출 규약 (Critical)</b>: 반드시 <b>호출자 트랜잭션이 끝난 뒤</b>
     * ({@code afterCompletion}) 호출해야 한다. {@code appendChunk} 는 같은 행을
     * {@code PESSIMISTIC_WRITE} 로 잠그고 있으므로, 트랜잭션 보유 중에 별도 커넥션으로 이 UPDATE 를
     * 실행하면 <b>자기 자신과 교착</b>한다.
     *
     * <p>{@code EXPRY_DT} 도 함께 앞당긴다 — {@code LsTusUpload#isExpired} 가 상태가 아니라 만료시각을
     * 보므로, 상태만 바꾸면 HEAD/PATCH 가 410 을 내지 않고 정리 잡({@code findExpired})도 집지 않는다.
     * {@code COMPLETED} 세션은 건드리지 않는다(정상 완료분 보호).
     *
     * @return 종결된 행 수(0 또는 1)
     */
    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    @Query("UPDATE LsTusUpload u SET u.status = 'EXPIRED', u.expiresAt = :now, u.mdfcnDt = :now "
            + "WHERE u.uploadId = :uploadId AND u.status <> 'COMPLETED'")
    int terminateSession(@Param("uploadId") UUID uploadId, @Param("now") LocalDateTime now);

    /**
     * TTL 만료 + 미완료 세션 — 정리 잡 스캔용 <b>후보</b> 조회.
     *
     * <p>상한({@code Pageable}) 필수 — 만료 세션이 대량으로 쌓여도 한 tick 이 무한정 길어지지 않게 한다
     * (무제한 조회 금지). 실제 삭제는 {@link #deleteExpiredById} 로 <b>원자 클레임에 성공한 건만</b>
     * 수행해야 한다(2노드 중복 삭제 방지).
     */
    @Query("SELECT u FROM LsTusUpload u WHERE u.status <> 'COMPLETED' AND u.expiresAt < :now "
            + "ORDER BY u.expiresAt ASC")
    List<LsTusUpload> findExpired(@Param("now") LocalDateTime now,
                                  org.springframework.data.domain.Pageable pageable);

    /**
     * 만료 세션 정리의 <b>원자 클레임</b> — 삭제 자체가 클레임이다 (Phase 9-B).
     *
     * <p>구 구현은 {@code findExpired} 후 {@code delete(entity)} 라, 2노드 Active-Active 에서 같은 행을
     * 두 노드가 지우려다 한쪽이 낙관적 잠금 예외로 터지고 임시 파일 삭제도 중복 시도됐다. Quartz
     * 클러스터링({@code isClustered})은 기본 꺼져 있고 이 잡은 {@code @Scheduled} 라 잡 단위 배타성이
     * 아예 없다. 조건부 DELETE 로 바꾸면 <b>1행을 지운 노드만</b> 파일 삭제 책임을 갖는다.
     *
     * <p>{@code status <> 'COMPLETED'} + {@code expiresAt < :now} 는 fail-safe 가드다 — 그 사이 업로드가
     * 완료됐거나 만료가 갱신됐으면 삭제하지 않는다(정상 세션·완료 파일 보호). 파라미터 바인딩만
     * 사용(CWE-89 표면 없음).
     *
     * @return 삭제한 행 수(0 또는 1). 1 인 호출만 임시 파일을 지운다.
     */
    @Modifying
    @Query("DELETE FROM LsTusUpload u WHERE u.uploadId = :uploadId "
            + "AND u.status <> 'COMPLETED' AND u.expiresAt < :now")
    int deleteExpiredById(@Param("uploadId") UUID uploadId, @Param("now") LocalDateTime now);
}
