package kr.co.cudo.authoring.portal.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 조달 진행 상태를 들고 있는 자리 — <b>이 배포본(노드) 안에서만</b> 유효하다.
 *
 * <h3>★ 원장 표를 만들지 않는다 (이번 범위의 확정)</h3>
 * <p>「준비 완료」의 진실원은 <b>파일 시스템</b>({@code PortalMaterialsWorkspace#isReady})이고, 이
 * 클래스가 들고 있는 것은 그것으로 표현할 수 없는 두 가지뿐이다 — <b>지금 하고 있는 중</b>과
 * <b>직전에 왜 실패했는가</b>. 표를 두면 마이그레이션이 따라오고 그러면 이번 범위를 넘는다.
 *
 * <h3>⚠ 그래서 생기는 한계 (인지·수용)</h3>
 * <ul>
 *   <li><b>재기동하면 진행 중 표시가 사라진다.</b> 그때 준비 완료가 아니면 조달은 다시 시작할 수
 *       있는 상태로 돌아간다 — 고아 잠금으로 <b>영영 막히는</b> 것보다 낫다(그것이 파일 잠금을
 *       두지 않은 이유다).</li>
 *   <li><b>노드마다 따로 센다.</b> 두 노드가 같은 데이터셋을 동시에 조달할 수 있으나, 각자 자기
 *       작업 중 자리에서 풀고 먼저 끝낸 쪽만 공개하므로(뒤진 쪽은 자기 것을 버린다) 결과는
 *       한 벌이다.</li>
 *   <li><b>재기동하면 「왜 실패했는지」도 사라진다.</b> 그 자리는 다시 조달을 시도하면 채워진다.</li>
 * </ul>
 *
 * <h3>실패 기록은 상한을 둔다</h3>
 * <p>없는 데이터셋 식별자를 계속 물으면 실패 기록이 무한히 쌓인다(CWE-770). 오래된 것부터
 * 버리는 상한을 둔다 — <b>버려진 기록은 「기록 없음」으로 보이고 다시 조달을 시도할 수 있다</b>.
 *
 * @design INT-014
 */
@Component
public class PortalMaterialsProvisionState {

    /**
     * 실패 기록 보존 상한 — <b>메모리 방어 상한이지 사양 값이 아니다</b>.
     *
     * <p>넘으면 오래된 것부터 버린다. 버려도 기능이 깨지지 않는다(그 데이터셋은 「기록 없음」으로
     * 보이고 재시도가 가능하다).
     */
    static final int MAX_FAILURE_RECORDS = 500;

    /** 진행 중인 데이터셋 — 중복 착수를 막는 유일한 수단이다. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 직전 실패 기록 — 삽입 순서 LRU. */
    private final Map<Long, Failure> failures = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Failure> eldest) {
                    return size() > MAX_FAILURE_RECORDS;
                }
            });

    /**
     * 착수를 선점한다.
     *
     * @return 이번 호출이 선점했으면 {@code true}. 이미 진행 중이면 {@code false}
     */
    public boolean claim(long datasetId) {
        boolean claimed = inFlight.add(datasetId);
        if (claimed) {
            // 새로 착수하면 직전 실패는 더 이상 현재 상태가 아니다.
            failures.remove(datasetId);
        }
        return claimed;
    }

    /** 착수를 놓는다 — 성공·실패 어느 쪽으로 끝나도 반드시 부른다. */
    public void release(long datasetId) {
        inFlight.remove(datasetId);
    }

    /** 진행 중인가. */
    public boolean inProgress(long datasetId) {
        return inFlight.contains(datasetId);
    }

    /** 실패를 기록한다 — <b>사유는 값(enum)으로만</b> 남긴다(경로·본문 원문 금지). */
    public void recordFailure(long datasetId, PortalMaterialsFailureReason reason) {
        failures.put(datasetId, new Failure(reason, Instant.now()));
    }

    /** 직전 실패 기록 — 없으면 {@code null}. */
    public Failure lastFailure(long datasetId) {
        return failures.get(datasetId);
    }

    /** 준비 완료로 마감한다 — 실패 기록을 지운다. */
    public void clearFailure(long datasetId) {
        failures.remove(datasetId);
    }

    /** 실패 기록 1건. */
    public record Failure(PortalMaterialsFailureReason reason, Instant at) {
    }
}
