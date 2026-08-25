package kr.co.cudo.authoring.webhook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 묘사 축 결과의 <b>어노테이션 초안 반영을 별도 트랜잭션으로 격리</b>하는 경계. [design: CDIAG-014] [design: SEQ-023]
 *
 * <h3>왜 이 경계가 필요한가 — 삼키기만으로는 격리가 안 된다</h3>
 * <p>설계가 요구하는 것은 <b>"초안 채움이 실패해도 시계열 서술 전문의 적재를 되돌리지 않는다"</b>이다
 * (초안 하나 때문에 주 축을 잃는 것이 더 나쁘다). 그런데 협력자
 * ({@link TimeseriesResultApplier} 구현체)는 자기 메서드에 트랜잭션 경계를 갖고 있어 <b>기본 전파로는
 * 콜백 트랜잭션에 합류</b>한다. 합류한 안쪽에서 예외가 나면 스프링이 <b>바깥 트랜잭션을
 * rollback-only 로 표시</b>하므로, 수신부가 예외를 잡아 조용히 진행해도 <b>커밋 시점에 통째로
 * 터진다</b>(그 경우 서술 적재도 멱등 마킹도 함께 사라져 벤더 재전송에 의존하게 된다).
 *
 * <p>그래서 <b>새 트랜잭션</b>으로 호출한다. 실패는 이 안쪽 트랜잭션에서 끝나고 바깥 콜백
 * 트랜잭션은 아무 표시도 받지 않으므로, 수신부가 예외를 잡으면 그 자리에서 흐름이 이어진다.
 *
 * <h3>대가(알고 택한다)</h3>
 * <ul>
 *   <li>초안은 <b>바깥 콜백보다 먼저 독립 커밋</b>된다 — 이후 콜백 처리가 실패해 바깥이 롤백되면
 *       "초안만 남고 서술은 없는" 상태가 잠시 생긴다. 벤더 재전송으로 서술이 다시 적재되고 초안
 *       채움은 멱등이라 두 번째부터 아무 일도 하지 않으므로 수렴한다. 반대 방향(주 축을 잃는 것)이
 *       훨씬 나쁘다.</li>
 *   <li>안쪽은 바깥의 <b>미커밋 변경을 보지 못한다</b>. 현재 협력자가 읽는 것(어노테이션·인입·승인
 *       이력·질문 목록·<b>마킹</b>)은 이 콜백이 쓰는 것과 겹치지 않으므로 문제가 없다.
 *       ★마킹은 협력자가 「고른 질문」을 조달하려고 읽는데, 그 선택값·상태는 <b>위탁 제출 시점에 이미
 *       커밋</b>돼 있고 이 콜백은 초안 채움 <b>이후</b>에야 마킹을 쓴다 — 그래서 못 보는 미커밋 변경이 없다. 협력자가 이 콜백이 방금 쓴
 *       값을 읽어야 하게 되면 이 격리를 다시 검토해야 한다.</li>
 *   <li>커넥션을 <b>하나 더</b> 잡는다(바깥을 붙든 채 새 커넥션). 콜백은 동시성이 낮아 수용한다.</li>
 * </ul>
 *
 * <p><b>추가 질문 축은 이 경계를 쓰지 않는다</b> — 그 축은 콜백 트랜잭션에 합류한 채로 두는 종전
 * 동작을 유지한다(그 축은 시계열 서술을 적재하지 않아 "주 축을 잃는" 상황 자체가 없다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsolatedTimeseriesDraftApplier {

    private final TimeseriesResultApplier applier;

    /**
     * 묘사 전문을 어노테이션 초안으로 반영한다 — <b>새 트랜잭션</b>에서.
     *
     * <p>예외를 여기서 잡지 않는 것은 의도다: 같은 트랜잭션 메서드 안에서 잡으면 이 트랜잭션이 이미
     * rollback-only 로 표시된 뒤라 커밋 시점에 다시 터진다. 잡는 자리는 <b>이 경계 바깥</b>
     * ({@code VlmResultService})이어야 한다.
     *
     * @return 실제로 초안이 채워졌으면 true.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean applyDescription(Long rawSn, String description) {
        return applier.applyDescription(rawSn, description);
    }
}
