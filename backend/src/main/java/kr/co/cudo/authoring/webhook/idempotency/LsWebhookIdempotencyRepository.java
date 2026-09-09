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
import java.util.Collection;
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

    /**
     * {@link #findStaleByStatus} 의 다채널 변형 — 한 외부 시스템이 <b>여러 창구</b>로 나뉘는 경우에 쓴다.
     *
     * <p>창구가 늘었는데 한 채널만 훑으면 나머지 채널의 미결 행이 회수되지 않고 영구히 남아,
     * 그 영상은 미결 판정에 걸려 <b>재위탁 자체가 막힌다</b>.
     */
    @Query("select e from LsWebhookIdempotency e "
            + "where e.chnlCd in :channels and e.sttsCd = :status and e.rawSn is not null "
            + "and e.mdfcnDt <= :cutoff order by e.mdfcnDt asc")
    List<LsWebhookIdempotency> findStaleByStatusIn(@Param("channels") Collection<String> channels,
                                                   @Param("status") String status,
                                                   @Param("cutoff") LocalDateTime cutoff,
                                                   Pageable pageable);

    /** 미결(ISSUED = ACK 조차 못 받은) 회수 후보. */
    default List<LsWebhookIdempotency> findStaleIssued(String channel, LocalDateTime cutoff, Pageable pageable) {
        return findStaleByStatus(channel, LsWebhookIdempotency.STATE_ISSUED, cutoff, pageable);
    }

    /** 미결(ISSUED) 회수 후보 — 여러 창구를 한 번에. */
    default List<LsWebhookIdempotency> findStaleIssued(Collection<String> channels, LocalDateTime cutoff,
                                                       Pageable pageable) {
        return findStaleByStatusIn(channels, LsWebhookIdempotency.STATE_ISSUED, cutoff, pageable);
    }

    /** ACK 는 받았으나 콜백 미수신 회수 후보 — 여러 창구를 한 번에. */
    default List<LsWebhookIdempotency> findStaleAccepted(Collection<String> channels, LocalDateTime cutoff,
                                                         Pageable pageable) {
        return findStaleByStatusIn(channels, LsWebhookIdempotency.STATE_ACCEPTED, cutoff, pageable);
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
     * 해당 영상에 <b>미결 위탁</b>(결과를 기다리는 중)이 남아 있는가 — 배치 재실행의 중복 위탁 차단 (@req R1).
     *
     * <p>자동 재시도 큐는 파이프라인을 선두부터 전부 다시 돌린다. 그때 위탁 스텝이 원장을 보지 않으면
     * <b>같은 영상에 상관키가 둘 생긴다</b>(새 {@code request_id} 발급 + 외부 재호출). 미결은 곧 "콜백을
     * 기다리는 중"이며 그 회수 책임은 미결 스위퍼({@code VlmSubmitPendingSweeper})에 있다.
     *
     * <p>미결의 정의는 {@code ISSUED}(ACK 미수신) ∪ {@code ACCEPTED}(ACK 수신·콜백 대기)다 —
     * {@code PROCESSED}(콜백 처리 완료)·{@code FAILED}(스위퍼가 회수한 표식)는 미결이 아니므로 재위탁을
     * 막지 않는다. {@code EXISTS} 로 첫 행에서 종료한다. 파라미터 바인딩만 사용(CWE-89).
     */
    boolean existsByRawSnAndChnlCdAndSttsCdIn(Long rawSn, String chnlCd, java.util.Collection<String> sttsCds);

    /**
     * 해당 영상에 대해 이미 회수(클레임)된 위탁 건수 — <b>무한 재위탁 차단</b>용 예산 판정 (Phase C-1).
     *
     * <p>회수는 재위탁을 유발하고 재위탁은 새 ISSUED 행을 만든다. 벤더가 계속 무응답이면 회수↔재위탁이
     * 스윕 주기마다 영원히 반복되므로(CWE-770), 회수 이력이 예산을 넘으면 재개하지 않고 기록만 남긴다.
     */
    long countByRawSnAndChnlCdAndSttsCd(Long rawSn, String chnlCd, String sttsCd);

    /**
     * <b>장비별 부하 집계</b> — 그 장비가 결과를 기다리고 있는 위탁 건수. [@design ERD-021] [@design ADR-057]
     *
     * <p>노드 선택기가 「어느 장비가 한가한가」를 판정하는 값이다. 시계열 축은 논블로킹 제출 + 콜백이라
     * <b>「처리 대기」라는 개념이 성립하지 않아</b> 물어볼 대상이 없다 — 그래서 그 장비가 얼마나 물려
     * 있는지를 아는 자리가 <b>우리가 보낸 것을 세는 이 원장뿐</b>이다.
     *
     * <p>⚠ <b>구 서술 정정(2026-09-08)</b> — 근거가 <i>「상태점검 폴러가 관측하지 않으므로
     * ({@code AiSrvrHealthPoller} 가 추론 노드만 훑는다)」</i>로 적혀 있었다. <b>폴러는 이제 시계열도
     * 훑는다</b> — 다만 <b>상태만</b> 재고 부하는 재지 않는다(살아 있는가 ↔ 여유가 있는가는 다른 축이며,
     * 시계열에서 잴 수 있는 것은 앞의 것뿐이다). 즉 <b>결론(부하 원천이 이 원장이다)은 그대로</b>이고
     * 근거만 「훑지 않아서」에서 「부하 개념이 없어서」로 좁혀졌다. 두 축을 한 문장에 묶은 것이 그 서술의
     * 오류였고, 같은 정정이 {@code AiSrvrSelector} 클래스 주석에도 있다.
     *
     * <p>★ <b>{@code ACCEPTED} 만 센다 — 상태를 <u>인자로 받지 않는다</b></u>. 인터페이스 메서드는
     * 암묵적으로 public 이라 상태 파라미터를 남겨 두면 어느 호출부든 {@code ISSUED} 를 넘길 수 있고,
     * 그러면 이 불변식이 <b>구조가 아니라 관례</b>에 머문다("통로를 하나로 제한했다"는 것은 사실이
     * 아니게 된다). 그래서 상태를 쿼리 문자열에 상수로 박아 <b>넘길 자리 자체를 없앤다</b>
     * ({@link #claimAckReceived} 가 같은 이유로 쓰는 기법이다). {@code ISSUED} 는 우리가 상관키를 선커밋한 것일 뿐 벤더가 아직
     * 받지 않은 상태라 그 장비의 부하가 0이다 — 세면 방금 제출이 몰린 장비를 과대평가해 다음 요청이
     * 반대편으로 쏠리고, 그 반대편도 곧 같은 이유로 과대평가돼 값이 실제 부하가 아니라 <b>직전 배분의
     * 메아리</b>가 된다. {@code PROCESSED}(끝난 것)·{@code FAILED}(회수 표식)도 부하가 아니다.
     *
     * <p><b>결과에 없는 장비는 0건</b>이다 — {@code GROUP BY} 는 행이 없는 장비를 돌려주지 않으므로
     * 호출측이 0으로 채운다(없는 것을 「모름」으로 다루면 한가한 새 장비가 후보에서 빠진다).
     *
     * <p>파라미터 바인딩만 사용(CWE-89). 채널로 좁히지 않는 이유는 이 컬럼을 채우는 경로가 시계열 위탁
     * 뿐이라 다른 채널 행은 장비 미상({@code null})으로 남아 {@code IN} 조건에서 자연히 빠지기 때문이다.
     *
     * @param srvrIds 셀 대상 장비 — <b>현재 가용한 장비 목록</b>. 원장에서 사라진 장비의 식별자가 이 표에
     *                남아 있을 수 있으나(외래키를 걸지 않는다) 이 조건에서 자연히 빠진다.
     *                <b>비어 있으면 호출하지 말 것</b> — {@code IN ()} 은 DB 방언에 따라 문법 오류다
     */
    @Query("select e.srvrId as srvrId, count(e) as loadCount from LsWebhookIdempotency e "
            + "where e.srvrId in :srvrIds and e.sttsCd = '" + LsWebhookIdempotency.STATE_ACCEPTED
            + "' group by e.srvrId")
    List<ServerLoadCount> countAcceptedBySrvrIdIn(@Param("srvrIds") Collection<String> srvrIds);

    /**
     * 장비별 <b>수락된(ACCEPTED)</b> 위탁 건수 — 부하 판정의 유일한 통로.
     *
     * <p>빈 목록을 먼저 걸러 낸다 — {@code IN ()} 은 DB 방언에 따라 문법 오류다.
     */
    default List<ServerLoadCount> countAcceptedBySrvrId(Collection<String> srvrIds) {
        if (srvrIds == null || srvrIds.isEmpty()) {
            return List.of();
        }
        return countAcceptedBySrvrIdIn(srvrIds);
    }

    /** 장비별 집계 한 줄 — 엔티티를 통째로 싣지 않기 위한 투영. */
    interface ServerLoadCount {
        String getSrvrId();

        long getLoadCount();
    }
}
