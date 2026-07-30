package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VLM 위탁에 수반되는 <b>마킹 상태 전이만</b> 독립 트랜잭션으로 수행하는 전용 빈 (Phase C-1).
 *
 * <h3>왜 별도 빈인가 (자기호출 트랜잭션 유실 — 이 레포의 실사고 패턴)</h3>
 * <p>이 레포에는 {@code @Transactional} 메서드를 <b>같은 클래스 안에서</b> 호출해(self-invocation)
 * 프록시를 거치지 않아 트랜잭션 경계가 통째로 사라지고, 그런데도 테스트는 ambient tx 때문에 GREEN 이던
 * 사고 이력이 있다. 아래 두 전이는 각각
 * <ul>
 *   <li><b>제출 전 선커밋</b>({@link #persistVlmRequested}) — 호출자({@code VlmTimeseriesStep})의
 *       트랜잭션이 아직 커밋되지 않은 시점에 <b>이미 커밋돼 있어야</b> 한다. 콜백이 ACK 보다 먼저
 *       도착해도 수신부가 전이 대상을 찾을 수 있어야 하기 때문이다.</li>
 *   <li><b>제출 실패 보상</b>({@link #markVlmFailedIfRequested}) — 파이프라인 스레드가 아닌
 *       완료 핸들러 스레드에서 실행된다(ambient tx 없음).</li>
 * </ul>
 * 둘 다 "호출자 트랜잭션과 운명을 분리" 하는 것이 요구사항이므로 {@code REQUIRES_NEW} 로 선언하고,
 * 자기호출이 원천적으로 불가능하도록 <b>별도 빈</b>으로 분리한다.
 *
 * <p>커넥션 점유: 호출 시점 기준 동시 점유 최대치는 (호출자 tx + 본 tx) = 2 로,
 * 기존 {@code WebhookIdempotencyLedger.recordIssued}(REQUIRES_NEW) 와 동일하며 순차 호출이라
 * 점유 깊이가 늘지 않는다(커넥션 기아 교착 이력 대비).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VlmMarkingTxService {

    private final LsMarkingRepository markingRepository;

    /**
     * 제출 <b>전</b> 선커밋 — {@code PENDING → VLM_REQUESTED} 전이를 독립 커밋한다.
     *
     * <p>{@link LsMarking#markVlmRequested()} 는 {@code PENDING} 에서만 전이하고 그 외 상태
     * (VLM_REQUESTED/VLM_COMPLETED/VLM_FAILED)에서는 no-op 이다 — retry 재실행이 이미 완료된 마킹을
     * 되돌리는 durable 역행을 차단한다. 전달된 인스턴스는 대개 detached 이므로 {@code save}(=merge)로
     * 명시 영속한다. markingSn 미발급(미영속 마킹)이면 저장을 생략한다.
     *
     * @return 실제로 전이가 발생했으면 true
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean persistVlmRequested(LsMarking marking) {
        if (marking == null) {
            return false;
        }
        boolean transitioned = marking.markVlmRequested();
        if (transitioned && marking.getMarkingSn() != null) {
            markingRepository.save(marking);
        }
        return transitioned;
    }

    /**
     * 제출 <b>확정 실패</b> 보상 — {@code VLM_REQUESTED} 인 마킹만 {@code VLM_FAILED} 로 전이한다.
     *
     * <p>DB 최신 상태를 다시 읽고 판정한다 — 완료 핸들러는 파이프라인 스레드 밖에서 늦게 실행되므로,
     * 그 사이 콜백이 먼저 도착해 {@code VLM_COMPLETED} 가 됐을 수 있다. 그 경우 전이하면
     * <b>정상 완료를 실패로 강등</b>하게 되므로 no-op 으로 둔다(멱등·역행 금지).
     *
     * @return 실제로 전이가 발생했으면 true
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean markVlmFailedIfRequested(Long markingSn) {
        if (markingSn == null) {
            return false;
        }
        return markingRepository.findById(markingSn)
                .filter(m -> LsMarking.STATUS_VLM_REQUESTED.equals(m.getSttsCd()))
                .map(m -> {
                    m.markVlmFailed();
                    markingRepository.save(m);
                    log.warn("[Vlm][Submit] marking transitioned VLM_REQUESTED->VLM_FAILED markingSn={}",
                            markingSn);
                    return true;
                })
                .orElse(false);
    }
}
