package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 상태점검·부하 관측 결과를 원장에 <b>노드 하나씩</b> 반영한다. [@design ADR-057]
 *
 * <h3>왜 폴러와 클래스를 갈랐나</h3>
 * <p>한 노드의 갱신 실패가 다른 노드를 롤백하면 안 된다. 순회와 트랜잭션이 <b>같은 클래스</b>에 있으면
 * 자기 호출이라 프록시를 타지 않아 트랜잭션이 아예 열리지 않거나, 하나로 묶여 함께 롤백된다.
 * 그래서 트랜잭션 경계를 <b>별도 빈</b>으로 빼고 폴러가 노드마다 호출한다.
 *
 * <h3>상태 전이 규칙을 여기서 다시 쓰지 않는다</h3>
 * <p>무엇에서 무엇으로 갈 수 있는지는 {@link AiSrvrStatus} 가 소유한다. 여기서는 <b>언제</b>
 * 전이를 시도할지(연속 임계)만 정하고, 실제 전이는 저장소의 조건부 UPDATE 가 원자적으로 수행한다.
 */
@Slf4j
@Component
public class AiSrvrHealthTxService {

    private final LsAiSrvrRepository repository;
    private final LsAiSrvrUsgRepository usgRepository;
    private final int failThreshold;
    private final int recoverThreshold;

    public AiSrvrHealthTxService(
            LsAiSrvrRepository repository,
            LsAiSrvrUsgRepository usgRepository,
            @Value("${authoring.integration.ai-server.fail-threshold:3}") int failThreshold,
            @Value("${authoring.integration.ai-server.recover-threshold:3}") int recoverThreshold) {
        this.repository = repository;
        this.usgRepository = usgRepository;
        // 0 이하면 <점검 한 번 만에> 노드를 내리거나 올린다 — 한 번의 네트워크 흔들림으로 장비를
        // 잃지 않으려고 연속을 세는 것이므로, 오설정은 하한 1로 눌러 흡수한다.
        this.failThreshold = Math.max(1, failThreshold);
        this.recoverThreshold = Math.max(1, recoverThreshold);
    }

    /**
     * 상태점검 결과 한 건을 반영한다.
     *
     * <p>노드가 원장에서 사라졌으면 조용히 버린다 — 폴러가 목록을 읽은 뒤 관리자가 지웠을 뿐이고,
     * 다음 틱에는 목록에서도 빠진다.
     */
    @Transactional("controlTransactionManager")
    public void applyHealth(String srvrId, boolean healthy, LocalDateTime checkedAt) {
        Optional<LsAiSrvr> found = repository.findById(srvrId);
        if (found.isEmpty()) {
            log.debug("[AiSrvr] 원장에 없는 노드의 상태점검 결과를 버립니다. srvrId={}", srvrId);
            return;
        }
        LsAiSrvr server = found.get();
        AiSrvrStatus status = server.getSrvrSttsCd();

        if (healthy) {
            int successStreak = server.recordCheckSuccess(checkedAt);
            promoteIfRecovered(srvrId, status, successStreak);
            return;
        }
        int failStreak = server.recordCheckFailure(checkedAt);
        demoteIfExhausted(srvrId, status, failStreak);
    }

    private void promoteIfRecovered(String srvrId, AiSrvrStatus status, int successStreak) {
        // ★출발 상태를 UNAVAILABLE 로 <명시>한다. 전이표상 정비중->가용도 허용이지만 그것은
        //   <사람이> 정비를 끝냈다는 선언이지 배치가 대신 내릴 판단이 아니다. 배치가 올리면 관리자가
        //   세운 정비 상태가 기계에 의해 조용히 사라진다.
        if (status != AiSrvrStatus.UNAVAILABLE
                || !status.canTransitionTo(AiSrvrStatus.AVAILABLE)
                || successStreak < recoverThreshold) {
            return;
        }
        if (repository.promoteIfUnavailable(srvrId) > 0) {
            log.info("[AiSrvr] 연속 {}회 성공으로 노드를 복귀시켰습니다. srvrId={}", successStreak, srvrId);
        }
    }

    private void demoteIfExhausted(String srvrId, AiSrvrStatus status, int failStreak) {
        // 정비중->이용불가는 전이표가 막는다(정비 중 점검 실패는 기록만 한다). 규칙을 여기서
        // 다시 쓰지 않고 그 판정을 그대로 부른다.
        if (!status.canTransitionTo(AiSrvrStatus.UNAVAILABLE) || failStreak < failThreshold) {
            return;
        }
        // ★상태를 직접 UPDATE 하지 않는다. 마지막 가용 노드 보호(잠금 CTE + 조건부 UPDATE)는
        //   그 쿼리 하나가 소유하며, 우회하면 가용 노드가 0이 되어 AI 기능 전체가 멈춘다.
        int demoted = repository.demoteIfNotLastAvailable(srvrId, AiSrvrStatus.UNAVAILABLE.name());
        if (demoted > 0) {
            log.warn("[AiSrvr] 연속 {}회 실패로 노드를 이용불가로 내렸습니다. srvrId={}", failStreak, srvrId);
            return;
        }
        // 마지막 가용 노드였다. 내리지 않는 편이 낫다 — 내려도 갈 곳이 없고, 실제로 살아 있는데
        // 관측이 틀렸을 가능성이 남아 있다. 대신 시끄럽게 남긴다.
        log.warn("[AiSrvr] 연속 {}회 실패했지만 마지막 가용 노드라 내리지 않았습니다."
                + " AI 기능이 멈추기 직전입니다. srvrId={}", failStreak, srvrId);
    }

    /**
     * 용도별 부하 관측값을 반영한다 — <b>아는 용도만</b>.
     *
     * <p>비어 있으면 아무것도 하지 않는다. 「알 수 없음」과 「부하 0」은 다르며, 모른다고 0을 쓰면
     * 그 노드가 가장 한가한 노드가 되어 요청을 빨아들인다. 직전 값을 그대로 둔다.
     */
    @Transactional("controlTransactionManager")
    public void applyLoad(String srvrId, Map<AiSrvrUsageType, AiSrvrSlotLoad> slots,
                          LocalDateTime observedAt) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        slots.forEach((usage, load) -> {
            LsAiSrvrUsg row = usgRepository.findById(new LsAiSrvrUsg.Key(srvrId, usage))
                    .orElseGet(() -> LsAiSrvrUsg.of(srvrId, usage, observedAt));
            row.observe(load.running(), load.queued(), observedAt);
            // 이미 영속 상태면 save 는 사실상 no-op 이지만, 신규 행과 경로를 나누지 않는 편이
            // 읽기 쉽다(용도가 둘뿐이라 비용도 무시할 만하다).
            usgRepository.save(row);
        });
    }
}
