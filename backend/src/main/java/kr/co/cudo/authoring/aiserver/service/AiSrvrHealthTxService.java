package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.common.client.VlmClient;
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
        demoteIfExhausted(server, status, failStreak);
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

    /**
     * 연속 실패가 임계에 닿았으면 <b>이용불가로 내린다</b> — 그 유형에 하나만 남았어도 내린다.
     * [@design ADR-057] [@design AC-1093]
     *
     * <h3>★ 마지막 가용 노드 보호를 타지 않는다 (2026-09-07 정정)</h3>
     * <p>구 코드는 {@code demoteIfNotLastAvailable} 을 불렀다. 그 쿼리는 가용이 하나뿐이면 강등을
     * 거부하므로, 두 장비가 <b>같은 시각에 둘 다 죽으면</b> 먼저 내려간 쪽만 이용불가가 되고 나중 쪽은
     * 「가용」으로 남는다. 화면은 「한 대는 살아 있다」고 말하는데 실제로는 AI 기능이 이미 멈춰 있다 —
     * 관측이 거짓말을 하는 상태다(현장 실측: gpu01 연속 실패 3회인데 가용 / gpu02 28회 이용불가).
     *
     * <p>그 보호는 <b>사람이 관리 화면에서 내리는 조작</b>에 거는 것이고({@link
     * kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository#demoteIfNotLastAvailableOfType}),
     * 상태점검이 내리는 이용불가는 조작이 아니라 <b>관측</b>이다. 관측을 막으면 죽은 장비로 계속 보낸다.
     * 관리 창구 축은 <b>그대로 막는다</b> — 두 경로가 다른 것이 의도다. [@design AC-1091] [@design API-229]
     *
     * <p>⚠ 구 주석의 「내려도 갈 곳이 없다」는 <b>폴백이 실재하던 시절의 근거</b>였고 어느 축에서도
     * 강등을 막을 이유가 되지 못한다 — 그 이유는 축마다 다르며 {@code warnIfTypeExhausted} javadoc 의
     * 표가 소유한다. 되살리지 말 것.
     *
     * <h3>전이표를 여기서 다시 쓰지 않는다</h3>
     * <p>정비중→이용불가가 막히는 것은 {@link AiSrvrStatus#canTransitionTo} 가 소유한 규칙이다.
     * 상태를 직접 UPDATE 하지도 않는다 — 동시 폴링·관리자 조작과 겹쳐도 한쪽만 갱신되도록 저장소의
     * <b>조건부 UPDATE</b>가 원자적으로 수행한다.
     */
    private void demoteIfExhausted(LsAiSrvr server, AiSrvrStatus status, int failStreak) {
        String srvrId = server.getSrvrId();
        // 정비중->이용불가는 전이표가 막는다(정비 중 점검 실패는 기록만 한다). 규칙을 여기서
        // 다시 쓰지 않고 그 판정을 그대로 부른다.
        if (!status.canTransitionTo(AiSrvrStatus.UNAVAILABLE) || failStreak < failThreshold) {
            return;
        }
        int demoted = repository.demoteByHealthCheck(srvrId, AiSrvrStatus.UNAVAILABLE.name());
        if (demoted == 0) {
            // 그 사이 누가 상태를 바꿨다(관리자 조작·다른 노드의 같은 판정). 정상이며 조용히 넘어간다.
            log.debug("[AiSrvr] 강등 시점에 상태가 이미 바뀌어 있었습니다. srvrId={}", safe(srvrId));
            return;
        }
        log.warn("[AiSrvr] 연속 {}회 실패로 노드를 이용불가로 내렸습니다. srvrId={}",
                failStreak, safe(srvrId));
        warnIfTypeExhausted(server.getSrvrTypeCd());
    }

    /**
     * 그 유형의 가용 장비가 <b>0이 되었는지</b> 알린다 — 운영자가 알아야 할 것은 이쪽이다.
     *
     * <p>「한 대가 내려갔다」보다 「이 유형이 어떤 상태가 되었는가」가 조치를 부르는 사실이다. 어느
     * 축이든 장비가 복구되어 연속 성공이 복귀 임계에 닿으면 <b>저절로</b> 풀린다(원장을 고칠 일이
     * 아니다). [@design AC-1094]
     *
     * <h3>★ 문구를 축에 따라 가른다 — 두 축이 정확히 엇갈려 있다</h3>
     * <table>
     *   <caption>상태점검이 보는 축 ↔ 위탁이 원장에서 고르는 축</caption>
     *   <tr><th>축</th><th>상태점검이 관측하는가</th><th>위탁 시 원장에서 고르는가</th></tr>
     *   <tr><td>추론(INFERENCE)</td><td><b>그렇다</b> — 폴러가 이 유형만 훑는다</td>
     *       <td><b>아니다</b> — 요청이 배포 기본 주소로 나간다</td></tr>
     *   <tr><td>시계열(TIMESERIES)</td><td>아니다 — 폴러가 명시적으로 제외한다</td>
     *       <td><b>그렇다</b> — 선택기가 원장에서 고른다</td></tr>
     * </table>
     *
     * <p>그래서 <b>「이 유형의 위탁은 폴백 없이 거부됩니다」를 두 축에 함께 쓰면 추론 쪽이 거짓말</b>이
     * 된다 — 마지막 추론 장비를 내려 이 경고가 뜨는 바로 그 순간에도 추론 위탁은 <b>계속 나간다</b>.
     * 이 CO 의 동기가 「정확한 상태를 보여 달라」이므로 운영자 대면 문구가 부정확하면 방향이 정반대다.
     *
     * <p>이 비대칭의 판정은 여기서 새로 쓰지 않고 {@code AiServerHealthIndicator} 가 이미 못 박아 둔
     * 서술을 그대로 따른다(<i>「원장으로 장비를 고를 수 없다」는 뜻이지 위탁 전건 거부가 아니다 …
     * 시계열 축은 다르다 — 두 축을 같은 뜻으로 읽지 말 것</i>).
     *
     * <p>⚠ 추론 축의 「배포 기본 주소로 계속 나간다」는 <b>지금 형상의 사실이지 지향점이 아니다</b>.
     * 그 축이 원장에서 고르도록 배선되면 이 분기의 문구도 함께 옮겨야 한다.
     *
     * <p>세는 일이 실패해도 강등 자체를 되돌리지 않는다 — 알림은 부수 효과이고, 여기서 예외가 올라가면
     * 이미 옳게 내린 상태 전이가 함께 롤백된다.
     */
    private void warnIfTypeExhausted(LsAiSrvr.SrvrType srvrTypeCd) {
        if (srvrTypeCd == null) {
            return;
        }
        try {
            if (repository.countBySrvrTypeCdAndSrvrSttsCd(srvrTypeCd, AiSrvrStatus.AVAILABLE) > 0) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("[AiSrvr] 가용 장비 수 확인에 실패했습니다(강등은 그대로 유지됩니다). type={} cause={}",
                    srvrTypeCd, e.getClass().getSimpleName());
            return;
        }
        if (srvrTypeCd == LsAiSrvr.SrvrType.TIMESERIES) {
            log.warn("[AiSrvr] ★{} 유형의 가용 장비가 0이 되었습니다 — 이 유형의 위탁은 폴백 없이"
                            + " 거부됩니다. 장비를 살리면 연속 성공 {}회로 자동 복귀합니다.",
                    srvrTypeCd, recoverThreshold);
            return;
        }
        // 추론 축은 아직 원장으로 장비를 고르지 않는다(요청은 배포 기본 주소로 나간다). 여기서
        // "거부됩니다"라고 적으면 실제로는 도는 기능을 멈춘 것처럼 알리게 된다.
        log.warn("[AiSrvr] ★{} 유형의 가용 장비가 0이 되었습니다 — 이 유형은 아직 원장으로 장비를"
                        + " 고르지 않아 요청은 배포 기본 주소로 계속 나갑니다(위탁이 막히지는 않습니다)."
                        + " 다만 이중화·분산이 성립하지 않고, 관측상 그 유형의 장비가 하나도 응답하지"
                        + " 않고 있습니다. 장비를 살리면 연속 성공 {}회로 자동 복귀합니다.",
                srvrTypeCd, recoverThreshold);
    }

    /** 원장 유래 문자열을 그대로 찍지 않는다(CWE-117) — 판정은 공용 유틸이 소유한다. */
    private static String safe(String value) {
        return VlmClient.safeForLog(value);
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
