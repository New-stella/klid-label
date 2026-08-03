package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 트리거가 skip 된 마킹의 <b>종결 처리</b> 전용 트랜잭션 서비스 (B-ISSUE-41).
 *
 * <h3>왜 별도 빈의 {@code REQUIRES_NEW} 인가</h3>
 * <p>호출자 {@code MarkingBatchBridge} 는 {@code @TransactionalEventListener(AFTER_COMMIT)} 라
 * <b>활성 트랜잭션이 없는</b> 컨텍스트에서 실행된다. 엔티티를 그냥 조회·변경해도 dirty checking 이
 * 작동하지 않아 영속되지 않으며(브리지의 다른 전이들이 이미 같은 이유로 이 패턴을 쓴다), 같은 빈
 * 내부 {@code @Transactional} 자기호출은 프록시를 우회한다. 따라서 별도 빈의 {@code REQUIRES_NEW}
 * public 메서드로 분리해 즉시 커밋시킨다.
 *
 * <h3>왜 종결시켜야 하는가</h3>
 * <p>마킹 저장(커밋)과 배치 트리거 판단이 분리돼 있어, 브리지가 skip 을 결정해도 방금 커밋된
 * {@code PENDING} 마킹은 남는다. {@code PENDING → VLM_REQUESTED/VLM_FAILED} 전이는 <b>오직 VLM 단계</b>
 * 에서만 일어나고 그 단계는 배치가 돌아야 도달하므로, skip 된 마킹은 아무도 전이시키지 않는 <b>영구
 * 고아</b>가 된다. 활성 마킹은 후속 마킹을 409 로 막으므로 그 영상은 <b>다시는 마킹할 수 없고</b>
 * {@code batch/retry} 도 stage 가 FAILED 가 아니라 409 다(복구 API 부재 — DB 직접 수정 외 수단 없음).
 * V142 부분 유니크 인덱스가 방지하려던 "고아 활성 마킹"을 skip 경로가 제도적으로 재생산하던 결함이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingSkipTxService {

    private final LsMarkingRepository markingRepository;

    /**
     * skip 된 마킹을 {@link LsMarking#STATUS_SKIPPED} 로 종결한다 — <b>{@code PENDING} 일 때만</b>.
     *
     * <p>멱등·fail-safe: 마킹 식별자가 없거나(구 이벤트) 행이 사라졌거나 이미 다른 상태면 조용히
     * no-op 이다. 종결 자체가 실패해도 마킹 API 응답(201 + skip 사유)에는 영향을 주지 않아야 하므로
     * 예외를 만들지 않는다.
     *
     * @param markingSn 방금 생성돼 커밋된 마킹 PK (nullable)
     * @param rawSn     로그용 영상 PK
     * @return 실제로 종결 전이가 일어났으면 {@code true}
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean terminateSkipped(Long markingSn, Long rawSn) {
        if (markingSn == null) {
            return false;
        }
        return markingRepository.findById(markingSn)
                .map(marking -> {
                    if (!marking.markSkipped()) {
                        return false;
                    }
                    markingRepository.save(marking);
                    log.warn("[MarkingSkip] marking terminated as SKIPPED rawSn={} markingSn={}",
                            rawSn, markingSn);
                    return true;
                })
                .orElse(false);
    }
}
