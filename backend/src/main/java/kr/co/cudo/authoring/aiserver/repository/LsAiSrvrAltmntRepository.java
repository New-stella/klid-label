package kr.co.cudo.authoring.aiserver.repository;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrAltmnt;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 영상별 노드 배정 저장소 (Control 데이터소스). [@design ADR-057] [@req R3]
 */
@ControlRepo
public interface LsAiSrvrAltmntRepository extends JpaRepository<LsAiSrvrAltmnt, Long> {

    Optional<LsAiSrvrAltmnt> findByRawSn(Long rawSn);

    /**
     * 배정 행을 <b>없을 때만</b> 넣는다 — 이미 있으면 조용히 아무것도 하지 않는다.
     *
     * <p>{@code RAW_SN} 유니크 제약과 짝이다. 두 노드가 같은 영상을 동시에 배정하면 진 쪽은
     * 이 문장에서 기다렸다가 <b>충돌을 확인하고 그냥 통과</b>한다(예외가 아니다).
     *
     * @return 실제로 넣었으면 1, 이미 있었으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO LS_AI_SRVR_ALTMNT (ALTMNT_SN, RAW_SN, SRVR_ID, ALTMNT_DT)
            VALUES (nextval('LS_AI_SRVR_ALTMNT_SEQ'), :rawSn, :srvrId, :now)
            ON CONFLICT (RAW_SN) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("rawSn") Long rawSn,
                       @Param("srvrId") String srvrId,
                       @Param("now") LocalDateTime now);

    /**
     * <b>멱등 배정</b> — 넣어 보고, 이미 있으면 그 값을 읽어 돌려준다.
     *
     * <p>진 쪽이 예외를 받으면 그 배치가 실패로 종결되지만 <b>실제로는 아무 문제가 없다</b> —
     * 하려던 일을 이미 누군가 해 두었을 뿐이다. 그래서 예외 대신 <b>이긴 쪽의 배정</b>을 그대로
     * 쓴다. 이것이 성립하려면 넣기와 읽기가 <b>같은 트랜잭션</b>이어야 하므로, 호출측이 트랜잭션
     * 경계를 연 뒤 부른다.
     *
     * <p>⚠ 이미 배정된 영상에 <b>다른 노드</b>를 넘겨도 기존 배정이 이긴다. 진행 중인 영상의
     * 노드를 갈아타면 추적이 끊기기 때문이다. 재배정은 이 메서드가 아니라 사유를 남기는 별도
     * 경로로 한다.
     */
    default LsAiSrvrAltmnt assignIfAbsent(Long rawSn, String srvrId, LocalDateTime now) {
        insertIfAbsent(rawSn, srvrId, now);
        return findByRawSn(rawSn).orElseThrow(() -> new IllegalStateException(
                "배정 직후 배정 행을 찾지 못했습니다. rawSn=" + rawSn));
    }
}
