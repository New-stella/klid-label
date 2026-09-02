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
     * <p>★<b>원장에 없는 장비로는 배정하지 않는다</b> — {@code SELECT ... WHERE EXISTS} 형태인 이유다.
     * 유령 장비에 묶인 영상은 <b>영원히 호출되지 않는다</b>(영상당 한 건이라 다른 장비로 갈아탈 수도
     * 없다). 종전에는 외래키가 이것을 막았는데 그 외래키를 없앴으므로(V26) 그 보호를 여기로 옮긴다.
     *
     * <p>⚠ 외래키를 없앤 것과 이 검사는 <b>서로 다른 축</b>이다 — 막아야 할 것은 <b>유령 장비로의
     * 신규 배정</b>이고, 막지 말아야 할 것은 <b>이미 끝난 이력 때문에 장비를 못 지우는 것</b>이다.
     * 외래키는 둘을 한 장치로 묶어 후자까지 막고 있었다.
     *
     * @return 실제로 넣었으면 1, 이미 있었거나 <b>그 장비가 원장에 없으면</b> 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO LS_AI_SRVR_ALTMNT (ALTMNT_SN, RAW_SN, SRVR_ID, ALTMNT_DT)
            SELECT nextval('LS_AI_SRVR_ALTMNT_SEQ'), :rawSn, :srvrId, :now
             WHERE EXISTS (SELECT 1 FROM LS_AI_SRVR WHERE SRVR_ID = :srvrId)
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
                "배정 직후 배정 행을 찾지 못했습니다 — 원장에 없는 장비였을 수 있습니다."
                        + " rawSn=" + rawSn + " srvrId=" + srvrId));
    }

    /**
     * 이 노드에 묶인 영상 배정이 하나라도 있는가.
     *
     * <p>⚠ <b>삭제를 막는 데 쓰지 말 것</b>(2026-09-01 확정 · V26 에서 외래키 제거). 배정 표는
     * 처리가 도는 동안 프레임을 한 노드에 묶어 두는 자리이고, 처리가 끝나면 그 묶음은 의미가 없다
     * (추적기 상태가 그 노드 프로세스의 메모리에 있었고 그때 이미 사라졌다). 끝난 배정까지 삭제를
     * 막으면 <b>한 번이라도 영상을 처리한 장비는 영구히 교체 불가</b>가 된다.
     *
     * <p>구 서술 폐기 — <i>"배정 행의 외래키가 ON DELETE RESTRICT 라 … 입구에서 걸러 충돌로
     * 답한다"</i>. 그 외래키가 없어졌고 삭제 가드도 이 판정을 부르지 않는다.
     */
    boolean existsBySrvrId(String srvrId);
}
