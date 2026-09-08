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

    /**
     * 계통별 임계 — <b>계통마다 자기 자리와 자기 기본값</b>을 갖는다. [@design AC-1099]
     *
     * <h3>★ 공통값을 두고 물려받게 하지 않는다</h3>
     * <p>「값을 안 주면 공통값을 쓴다」로 만들면 <b>그 공통값을 바꾸는 순간 두 계통이 함께 움직여</b>
     * 계통을 가른 이 조항이 이름만 남는다. 그래서 각 계통이 자기 설정 키와 자기 기본값을 갖고,
     * 한쪽에만 값을 줘도 다른 쪽은 자기 기본값으로 판정한다.
     *
     * <p>두 계통의 <b>기본값이 같은 값이어도 무방하다</b> — 요구되는 것은 값의 다름이 아니라
     * 자리가 갈려 서로를 움직이지 않는다는 것이다. 초기 기본값 3은 추론이 쓰던 값을 그대로 옮긴
     * 것이며, 시계열에 맞는 수는 운영에서 정한다(새 숫자를 지어내지 않았다).
     *
     * <p>왜 계통마다 다른 값이 필요한가: 추론은 <b>우리가 배포한 서버</b>이고 시계열은 <b>외부
     * 벤더의 서비스</b>라 응답 특성도, 우리가 손댈 수 있는 범위도 같지 않다. 하나로 공유하면
     * 한쪽을 조이려다 다른 쪽까지 움직여 고치려던 쪽만 고칠 수가 없다.
     */
    private final Map<LsAiSrvr.SrvrType, Thresholds> thresholds;

    /** 한 계통의 임계 쌍. */
    private record Thresholds(int fail, int recover) {
    }

    public AiSrvrHealthTxService(
            LsAiSrvrRepository repository,
            LsAiSrvrUsgRepository usgRepository,
            @Value("${authoring.integration.ai-server.fail-threshold:3}") int inferenceFailThreshold,
            @Value("${authoring.integration.ai-server.recover-threshold:3}") int inferenceRecoverThreshold,
            // ★시계열은 <외부 벤더 축의 설정 이름공간>에 자기 자리를 갖는다(주소 씨앗 키와 같은 곳).
            //   추론 키 아래에 두면 이름만으로 「ai-server 의 값」으로 읽혀 두 계통이 다시 얽힌다.
            @Value("${vlm.client.fail-threshold:3}") int timeseriesFailThreshold,
            @Value("${vlm.client.recover-threshold:3}") int timeseriesRecoverThreshold) {
        this.repository = repository;
        this.usgRepository = usgRepository;
        this.thresholds = Map.of(
                LsAiSrvr.SrvrType.INFERENCE,
                        clamp(inferenceFailThreshold, inferenceRecoverThreshold),
                LsAiSrvr.SrvrType.TIMESERIES,
                        clamp(timeseriesFailThreshold, timeseriesRecoverThreshold));
    }

    /**
     * 오설정 흡수 — <b>두 자리 모두</b>에 건다.
     *
     * <p>0 이하면 <b>점검 한 번 만에</b> 노드를 내리거나 올린다. 한 번의 네트워크 흔들림으로 장비를
     * 잃지 않으려고 연속을 세는 것이므로, 하한 1로 눌러 흡수한다. 계통을 가르면서 <b>한쪽만
     * 방어하는 일이 없도록</b> 이 함수를 두 자리가 함께 쓴다.
     */
    private static Thresholds clamp(int fail, int recover) {
        return new Thresholds(Math.max(1, fail), Math.max(1, recover));
    }

    /**
     * 그 계통의 <b>확정 실패 임계</b> — 오설정 흡수({@link #clamp})가 끝난 값이다.
     *
     * <h3>★ 시험 관측용으로 열어 둔 자리다 — 지우면 하한 방어가 다시 무방비가 된다</h3>
     * <p>하한 흡수는 <b>행위로 관측되지 않는다</b>. 연속 횟수는 1부터 시작하므로 임계가 0이든 1이든
     * {@code streak < threshold} 가 똑같이 거짓이고, 그래서 {@code Math.max(1, …)} 를 통째로 걷어내는
     * 변이를 심어도 이 클래스의 시험이 <b>전건 통과</b>했다(2026-09-08 실측). 값을 직접 읽어 단언할 수
     * 있어야 그 변이가 붉어진다.
     *
     * <p>공개 범위를 <b>패키지 안</b>으로 좁혀 둔 이유는 판정 자체는 여전히 이 클래스가 소유하기
     * 때문이다 — 바깥에서 이 값을 읽어 자기 판정을 쓰면 그 사본이 두 번째 진실원이 된다.
     */
    int failThresholdOf(LsAiSrvr.SrvrType srvrTypeCd) {
        return thresholdsOf(srvrTypeCd).fail();
    }

    /** 그 계통의 <b>확정 복귀 임계</b> — 위 {@link #failThresholdOf} 와 같은 이유로 열어 둔다. */
    int recoverThresholdOf(LsAiSrvr.SrvrType srvrTypeCd) {
        return thresholdsOf(srvrTypeCd).recover();
    }

    /**
     * 그 계통의 임계 — 유형을 모르는 행은 추론 기준으로 본다(원장의 기본 형상).
     *
     * <p>여기서 「없으면 다른 계통 값」으로 떨어뜨리지 <b>않는다</b> — 그러면 물려받기가 되살아난다.
     */
    private Thresholds thresholdsOf(LsAiSrvr.SrvrType srvrTypeCd) {
        Thresholds found = srvrTypeCd == null ? null : thresholds.get(srvrTypeCd);
        return found != null ? found : thresholds.get(LsAiSrvr.SrvrType.INFERENCE);
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

        Thresholds limits = thresholdsOf(server.getSrvrTypeCd());
        if (healthy) {
            int successStreak = server.recordCheckSuccess(checkedAt);
            promoteIfRecovered(srvrId, status, successStreak, limits.recover());
            return;
        }
        int failStreak = server.recordCheckFailure(checkedAt);
        demoteIfExhausted(server, status, failStreak, limits);
    }

    private void promoteIfRecovered(String srvrId, AiSrvrStatus status, int successStreak,
                                    int recoverThreshold) {
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
    private void demoteIfExhausted(LsAiSrvr server, AiSrvrStatus status, int failStreak,
                                   Thresholds limits) {
        int failThreshold = limits.fail();
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
        warnIfTypeExhausted(server.getSrvrTypeCd(), limits.recover());
    }

    /**
     * 그 유형의 가용 장비가 <b>0이 되었는지</b> 알린다 — 운영자가 알아야 할 것은 이쪽이다.
     *
     * <p>「한 대가 내려갔다」보다 「이 유형이 어떤 상태가 되었는가」가 조치를 부르는 사실이다. 어느
     * 축이든 장비가 복구되어 연속 성공이 복귀 임계에 닿으면 <b>저절로</b> 풀린다(원장을 고칠 일이
     * 아니다). [@design AC-1094]
     *
     * <h3>★ 이제 두 축이 같다 — 문구를 가르지 않는다 (2026-09-08)</h3>
     * <table>
     *   <caption>상태점검이 보는 축 ↔ 위탁이 원장에서 고르는 축</caption>
     *   <tr><th>축</th><th>상태점검이 관측하는가</th><th>위탁 시 원장에서 고르는가</th></tr>
     *   <tr><td>추론(INFERENCE)</td><td><b>그렇다</b></td><td><b>그렇다</b> — 클라이언트가 목적지를
     *       원장에서 고른 장비로 고정한다</td></tr>
     *   <tr><td>시계열(TIMESERIES)</td><td><b>그렇다</b> — 폴러가 계통을 가르지 않는다</td>
     *       <td><b>그렇다</b> — 선택기가 원장에서 고른다</td></tr>
     * </table>
     *
     * <p>두 축 모두 후보가 0이 되면 위탁이 <b>폴백 없이 거부</b>되므로 같은 문구를 쓴다.
     * [@design AC-1093]
     *
     * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — 여기 두 축이 <i>정확히 엇갈려 있다</i>고 적고
     * (<i>추론은 관측하지만 원장으로 고르지 않고, 시계열은 그 반대</i>) 그래서 <i>「폴백 없이
     * 거부됩니다」를 두 축에 함께 쓰면 추론 쪽이 거짓말이 된다</i>고 못 박았다. <b>그 엇갈림이
     * 이번에 양쪽 다 메워졌다</b> — 폴러가 계통을 가르지 않게 됐고, 추론 호출도 원장에서 골라
     * 목적지를 고정한다. 지우지 않고 남기는 이유는, 그 표가 <b>왜 한때 문구를 갈랐는지</b>를 설명하기
     * 때문이다(그 시절엔 가르는 것이 옳았다).
     *
     * <p>⚠ 같은 비대칭을 서술한 자리가 <b>이 클래스 밖에도 있다</b> — {@code AiServerHealthIndicator}
     * 와 {@code LsAiSrvrRepository#demoteByHealthCheck} 가 그것이다. 한 자리만 고치면 나머지가
     * 남으므로, 이 축을 다시 손댈 때는 셋을 함께 볼 것.
     *
     * <p>세는 일이 실패해도 강등 자체를 되돌리지 않는다 — 알림은 부수 효과이고, 여기서 예외가 올라가면
     * 이미 옳게 내린 상태 전이가 함께 롤백된다.
     */
    private void warnIfTypeExhausted(LsAiSrvr.SrvrType srvrTypeCd, int recoverThreshold) {
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
        // ★두 축이 같아졌으므로 문구를 가르지 않는다(위 javadoc §이제 두 축이 같다).
        log.warn("[AiSrvr] ★{} 유형의 가용 장비가 0이 되었습니다 — 이 유형의 요청은 폴백 없이"
                        + " 거부됩니다. 장비를 살리면 연속 성공 {}회로 자동 복귀합니다.",
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
