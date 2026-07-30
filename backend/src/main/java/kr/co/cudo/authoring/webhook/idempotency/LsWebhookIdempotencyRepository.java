package kr.co.cudo.authoring.webhook.idempotency;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsWebhookIdempotencyRepository extends JpaRepository<LsWebhookIdempotency, String> {

    /**
     * 비관적 쓰기 락(SELECT ... FOR UPDATE) 으로 원장 행을 조회한다.
     * 동일 idempotencyKey 의 동시 콜백을 직렬화하여 검수큐 중복 적재/META race(CWE-362)를 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LsWebhookIdempotency e where e.idmpKey = :key")
    Optional<LsWebhookIdempotency> findByIdmpKeyForUpdate(@Param("key") String key);

    /**
     * Phase C-1 — <b>미결(ISSUED) 위탁 회수 후보</b>(앵커) 목록.
     *
     * <p>논블로킹 제출은 상관키를 제출 <b>전에</b> ISSUED 로 선커밋한다. 그 뒤 ACK 도 콜백도 오지 않는
     * 경우(노드 사망·재기동으로 in-flight subscription 유실, 벤더 무응답)에는 어떤 실패 신호도 남지
     * 않아 재시도 큐·실패 회수기 어느 쪽도 집지 못한다 — 회수가 없으면 이 설계는 fire-and-forget 으로
     * 퇴화한다. 그래서 "선기록만 되고 마지막 갱신이 임계를 넘긴" 행을 후보로 삼는다.
     *
     * <p>정상 진행 중인 위탁을 뺏지 않도록 {@code MDFCN_DT <= cutoff} 로만 좁히고, {@code Pageable} 로
     * 한 tick 의 처리량을 제한한다(무제한 조회 금지, OWASP API4). rawSn 매핑이 없는 행은 재개 대상이
     * 아니므로 제외한다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query("select e from LsWebhookIdempotency e "
            + "where e.chnlCd = :channel and e.sttsCd = :status and e.rawSn is not null "
            + "and e.mdfcnDt <= :cutoff order by e.mdfcnDt asc")
    List<LsWebhookIdempotency> findStaleByStatus(@Param("channel") String channel,
                                                 @Param("status") String status,
                                                 @Param("cutoff") LocalDateTime cutoff,
                                                 Pageable pageable);

    /** 미결(ISSUED = ACK 조차 못 받은) 회수 후보. */
    default List<LsWebhookIdempotency> findStaleIssued(String channel, LocalDateTime cutoff, Pageable pageable) {
        return findStaleByStatus(channel, LsWebhookIdempotency.STATE_ISSUED, cutoff, pageable);
    }

    /**
     * H1 — <b>ACK 는 받았는데 콜백이 오지 않는</b> 회수 후보(ACCEPTED · 콜백 창 경과).
     *
     * <p>ACK 수신을 원장에 남기면 {@link #findStaleIssued} 가 진행 중 위탁을 뺏지 않게 되지만, 그 대신
     * "수락됐으나 결과가 영영 안 오는" 건이 무한 대기로 남는다. 그 회수의 유일한 주체가 이 후보 조회다
     * (임계는 ACK 창보다 훨씬 긴 콜백 창을 쓴다).
     */
    default List<LsWebhookIdempotency> findStaleAccepted(String channel, LocalDateTime cutoff, Pageable pageable) {
        return findStaleByStatus(channel, LsWebhookIdempotency.STATE_ACCEPTED, cutoff, pageable);
    }

    /**
     * H1 — 위탁 <b>수락(ACK) 수신</b> 기록. {@code ISSUED} 행에만 적용되는 조건부 원자 UPDATE 다.
     *
     * <p>{@code ISSUED} 로 좁히는 이유: ①콜백이 ACK 보다 먼저 도착해 이미 {@code PROCESSED} 인 행을
     * 되돌리면 안 된다(상태 강등 금지) ②미결 스위퍼가 이미 회수({@code FAILED})한 행을 되살리면
     * 회수 예산 판정이 무너진다. 0행은 "이미 진행/종결됨"(정상)이다.
     *
     * <p>{@code MDFCN_DT} 를 지금으로 갱신해 <b>콜백 창의 기산점</b>을 ACK 시각으로 옮긴다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LsWebhookIdempotency e set e.sttsCd = '" + LsWebhookIdempotency.STATE_ACCEPTED
            + "', e.mdfcnDt = :now where e.idmpKey = :key and e.sttsCd = '"
            + LsWebhookIdempotency.STATE_ISSUED + "'")
    int claimAckReceived(@Param("key") String key, @Param("now") LocalDateTime now);

    /**
     * Phase C-1 — 미결 행을 <b>원자 클레임</b>한다(2노드 Active-Active 이중 회수 차단, CWE-362).
     *
     * <p>{@code STTS_CD='ISSUED' AND MDFCN_DT <= cutoff} 를 UPDATE 조건에 그대로 실어 fail-safe 로
     * 재판정한다 — 후보 조회와 클레임 사이에 콜백이 도착해 PROCESSED 가 됐거나 다른 노드가 이미
     * 클레임했으면 0 행이 되어 덮어쓰지 않는다({@code LsBatRtyWtngRepository#claimAtomically} 동형).
     * Quartz 클러스터링은 트리거 중복만 막고 잡 내부 레이스는 막지 못하므로 이 CAS 가 필수다.
     *
     * <p>클레임 표식으로 {@code FAILED} 를 쓴다 — 뒤늦게 콜백이 도착해도
     * {@code PersistentWebhookIdempotencyLedger.toEntry} 가 비-PROCESSED 를 {@code ISSUED} 로 매핑하므로
     * 그 콜백은 여전히 정상 처리된다(회수가 늦은 콜백의 복구 가능성을 파괴하지 않는다).
     *
     * @return 영향 행수 (1 = 클레임 성공, 0 = 타 노드가 이미 처리했거나 상태가 바뀜)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LsWebhookIdempotency e set e.sttsCd = 'FAILED', e.mdfcnDt = :now "
            + "where e.idmpKey = :key and e.sttsCd = :from and e.mdfcnDt <= :cutoff")
    int claimStale(@Param("key") String key,
                   @Param("from") String from,
                   @Param("cutoff") LocalDateTime cutoff,
                   @Param("now") LocalDateTime now);

    /** 미결(ISSUED) 원자 클레임. */
    default int claimStaleIssued(String key, LocalDateTime cutoff, LocalDateTime now) {
        return claimStale(key, LsWebhookIdempotency.STATE_ISSUED, cutoff, now);
    }

    /** H1 — ACK 는 받았으나 콜백이 오지 않은(ACCEPTED) 건의 원자 클레임. 표식은 동일하게 {@code FAILED} 라
     *  회수 예산 판정({@link #countByRawSnAndChnlCdAndSttsCd})이 두 창을 함께 덮는다. */
    default int claimStaleAccepted(String key, LocalDateTime cutoff, LocalDateTime now) {
        return claimStale(key, LsWebhookIdempotency.STATE_ACCEPTED, cutoff, now);
    }

    /**
     * 해당 영상에 대해 이미 회수(클레임)된 위탁 건수 — <b>무한 재위탁 차단</b>용 예산 판정 (Phase C-1).
     *
     * <p>회수는 재위탁을 유발하고 재위탁은 새 ISSUED 행을 만든다. 벤더가 계속 무응답이면 회수↔재위탁이
     * 스윕 주기마다 영원히 반복되므로(CWE-770), 회수 이력이 예산을 넘으면 재개하지 않고 기록만 남긴다.
     */
    long countByRawSnAndChnlCdAndSttsCd(Long rawSn, String chnlCd, String sttsCd);
}
