package kr.co.cudo.authoring.batch.vlm;

import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * VLM 미결 위탁 회수의 <b>DB 접근 전담</b> 트랜잭션 빈 (Phase C-1).
 *
 * <h3>왜 스위퍼와 분리했나 (자기호출 트랜잭션 유실)</h3>
 * <p>스위퍼({@link VlmSubmitPendingSweeper})는 "후보 조회 → 건별 원자 클레임 → 재개 트리거" 를
 * <b>건별 독립 트랜잭션</b>으로 수행해야 한다 — 한 건의 실패가 다른 건의 클레임을 롤백하면 안 되고,
 * 외부 재개 트리거를 트랜잭션 안에 넣어도 안 된다. 그런데 스위퍼가 자신의 {@code @Transactional}
 * 메서드를 직접 부르면 프록시를 거치지 않아 <b>경계가 통째로 사라진다</b>(이 레포의 실사고 패턴 —
 * 테스트는 ambient tx 때문에 GREEN 이었다). 그래서 트랜잭션 경계를 별도 빈에 두고 스위퍼는
 * 프록시 경유로만 호출한다.
 */
@Service
@RequiredArgsConstructor
public class VlmSubmitReclaimTxService {

    private final LsWebhookIdempotencyRepository repository;

    /** 회수 후보(미결 ISSUED = ACK 조차 못 받음, ACK 창 경과) 앵커 조회. */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public List<Candidate> findCandidates(LocalDateTime cutoff, int limit) {
        return repository
                .findStaleIssued(LsWebhookIdempotency.CHANNEL_VLM, cutoff, PageRequest.of(0, limit))
                .stream()
                .map(e -> new Candidate(e.getIdmpKey(), e.getRawSn()))
                .toList();
    }

    /**
     * H1 — 회수 후보(ACCEPTED = ACK 는 받았으나 결과 콜백 미수신, <b>콜백 창</b> 경과) 앵커 조회.
     *
     * <p>ACK 수신을 원장에 남기면 ACK 창 회수가 정상 위탁을 뺏지 않게 되는 대신, "수락됐는데 결과가
     * 영영 안 오는" 건이 무한 대기로 남는다. 그 회수의 유일한 주체가 이 패스다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public List<Candidate> findAcceptedCandidates(LocalDateTime cutoff, int limit) {
        return repository
                .findStaleAccepted(LsWebhookIdempotency.CHANNEL_VLM, cutoff, PageRequest.of(0, limit))
                .stream()
                .map(e -> new Candidate(e.getIdmpKey(), e.getRawSn()))
                .toList();
    }

    /**
     * 원자 클레임 — 정확히 1개 노드만 true 를 받는다(2노드 Active-Active 이중 회수 차단).
     *
     * @return true = 이 노드가 소유권을 획득
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String idmpKey, LocalDateTime cutoff) {
        if (idmpKey == null || idmpKey.isBlank()) {
            return false;
        }
        return repository.claimStaleIssued(idmpKey, cutoff, LocalDateTime.now()) == 1;
    }

    /**
     * H1 — 콜백 창 만료 건(ACCEPTED)의 원자 클레임. 표식은 ACK 창 회수와 동일한 {@code FAILED} 라
     * 회수 예산({@link #reclaimBudgetExceeded})이 두 창을 합산해 무한 재위탁을 막는다.
     *
     * <p>{@code sttsCd='ACCEPTED'} 를 UPDATE 조건에 실어, 그 사이 콜백이 도착해 PROCESSED 가 됐으면
     * 0행으로 no-op 이 되게 한다(도착한 결과를 회수가 뒤엎지 않는다).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean claimAccepted(String idmpKey, LocalDateTime cutoff) {
        if (idmpKey == null || idmpKey.isBlank()) {
            return false;
        }
        return repository.claimStaleAccepted(idmpKey, cutoff, LocalDateTime.now()) == 1;
    }

    /**
     * 이 영상의 회수 이력이 예산을 초과했는가 — 회수↔재위탁 무한 반복 차단(CWE-770).
     *
     * <p>회수 표식({@code FAILED}) 행 수로 판정한다. 방금 클레임한 건도 포함되므로 예산 {@code n} 은
     * "재위탁을 최대 {@code n-1} 회까지 허용" 을 뜻한다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public boolean reclaimBudgetExceeded(Long rawSn, int maxReclaims) {
        if (rawSn == null) {
            return true;
        }
        long reclaimed = repository.countByRawSnAndChnlCdAndSttsCd(
                rawSn, LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_FAILED);
        return reclaimed >= maxReclaims;
    }

    /** 회수 후보 앵커 — 원장 키 + 대상 영상. */
    public record Candidate(String idmpKey, Long rawSn) {}
}
