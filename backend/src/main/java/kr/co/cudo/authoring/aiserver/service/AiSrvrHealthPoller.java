package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 원장의 노드를 <b>하나씩</b> 돌며 상태와 부하를 관측한다. [@design ADR-057]
 *
 * <p>⚠ 아래 「꺼짐」 서술의 범위는 <b>이 관측 배치</b>다. 헬스 인디케이터는 같은 설정을 보지 않고
 * 매 호출 노드를 직접 핑하므로, 「꺼져 있으면 ai-server 로 아무 요청도 나가지 않는다」로 읽으면
 * 사실과 어긋난다.
 *
 * <h3>★ 기본값은 꺼짐이다 — 부하 축만이 아니라 상태 점검 축까지</h3>
 * <p>상태 점검과 부하 조회는 둘 다 <b>추론 서버가 요청을 받아 응답할 여유가 있어야</b> 성립한다.
 * 실행을 용도별로 나누기 전에는 추론이 서버의 처리 흐름을 통째로 붙잡아 상태 점검조차 늦어지므로,
 * 그 시기에 관측을 켜면 <b>바쁜 장비를 죽은 장비로 오판</b>한다. 장비가 하나뿐이면 마지막 하나를
 * 지키는 장치가 막아 주지만, <b>둘 이상이면 먼저 판정된 하나는 그 장치에 걸리지 않아 멀쩡한 장비를
 * 하나 잃는다</b>. 그래서 관측 전체를 꺼둔 상태로 들어가고, 실행이 용도별로 나뉜 뒤에 켠다.
 *
 * <p>게이팅은 두 겹이다 — (1)잡 자체를 등록하지 않는 것이 1차이고(그 설정이 진실원),
 * (2)여기 방어는 프로그래밍 호출·수동 트리거로 들어오는 경로를 막는다. 어느 겹도 다른 겹을
 * 대체하지 않는다.
 *
 * <h3>노드마다 트랜잭션을 연다</h3>
 * <p>한 노드의 갱신 실패가 다른 노드를 롤백하면, 장비 하나의 문제로 <b>원장 전체가 낡는다</b>.
 * 트랜잭션 경계는 {@link AiSrvrHealthTxService} 가 갖고 여기서는 실패를 노드 단위로 가둔다.
 *
 * <h3>실패한 노드에는 부하를 묻지 않는다</h3>
 * <p>응답조차 못 하는 노드에 한 번 더 물어봐야 얻을 것이 없고, 틱마다 상한만큼 더 기다린다.
 */
@Slf4j
@Component
public class AiSrvrHealthPoller {

    private final LsAiSrvrRepository repository;
    private final AiSrvrHealthProbe healthProbe;
    private final AiSrvrLoadProbe loadProbe;
    private final AiSrvrLoadSmoother smoother;
    private final AiSrvrHealthTxService txService;
    private final boolean pollEnabled;
    private final Clock clock;

    @Autowired
    public AiSrvrHealthPoller(LsAiSrvrRepository repository,
                              AiSrvrHealthProbe healthProbe,
                              AiSrvrLoadProbe loadProbe,
                              AiSrvrLoadSmoother smoother,
                              AiSrvrHealthTxService txService,
                              @Value("${authoring.integration.ai-server.poll-enabled:false}")
                              String pollEnabled) {
        this(repository, healthProbe, loadProbe, smoother, txService, isEnabled(pollEnabled),
                Clock.systemDefaultZone());
    }

    /**
     * 설정값을 <b>잡 등록 조건과 똑같이</b> 읽는다. [P1-3]
     *
     * <p>{@code @ConditionalOnProperty(havingValue = "true")} 는 {@code equalsIgnoreCase("true")}
     * 하나만 참으로 보는데, 이 값을 {@code boolean} 으로 주입받으면 Spring 변환기가
     * {@code on}·{@code yes}·{@code 1} 까지 참으로 읽는다. 그러면 <b>잡은 등록되지 않았는데 폴러와
     * 헬스 상세는 「켜짐」이라고 말한다</b> — 운영자가 켰다고 믿는 채 원장이 영원히 정지한다.
     * 그래서 <b>문자열로 받아 여기서 같은 규칙으로</b> 판정한다.
     *
     * <p>진실원은 잡 등록 조건이다({@code AiSrvrHealthPollTriggerConfig}). 그쪽은 애너테이션이라
     * 이 메서드를 부를 수 없으므로, 규칙이 갈라지지 않는지는 회귀 시험이 지킨다.
     */
    public static boolean isEnabled(String rawValue) {
        return rawValue != null && "true".equalsIgnoreCase(rawValue.trim());
    }

    /** 시험용 — 관측 시각을 고정해 대기 없이 검증한다. */
    AiSrvrHealthPoller(LsAiSrvrRepository repository,
                       AiSrvrHealthProbe healthProbe,
                       AiSrvrLoadProbe loadProbe,
                       AiSrvrLoadSmoother smoother,
                       AiSrvrHealthTxService txService,
                       boolean pollEnabled,
                       Clock clock) {
        this.repository = repository;
        this.healthProbe = healthProbe;
        this.loadProbe = loadProbe;
        this.smoother = smoother;
        this.txService = txService;
        this.pollEnabled = pollEnabled;
        this.clock = clock;
    }

    /** 한 틱 — 원장의 추론 노드를 전부 관측한다. */
    public void pollAll() {
        if (!pollEnabled) {
            // 원장 조회조차 하지 않는다. 「꺼짐」은 <이 관측 배치가> 외부 호출을 한 건도 내지
            // 않는다는 뜻이다. ⚠ 시스템 전체로 확대해 읽지 말 것 — 헬스 인디케이터
            // (AiServerHealthIndicator)는 이 설정과 무관하게 사람이 볼 때마다 직접 핑한다
            // (배치가 꺼져 있어 원장 값이 갱신되지 않으므로 그것이 의도다).
            return;
        }
        List<LsAiSrvr> nodes = repository.findAll().stream()
                // ★시계열 축은 논블로킹 제출 + 콜백이라 이 경로의 부하·상태 개념이 성립하지 않는다.
                //   같은 목록에서 섞어 고르면 안 되는 것과 같은 이유다.
                .filter(node -> node.getSrvrTypeCd() == LsAiSrvr.SrvrType.INFERENCE)
                .toList();
        // ★노드가 0건이어도 정리는 <먼저> 한다. 여기서 되돌아가면 원장을 비운 뒤 같은 식별자로
        //   다시 세웠을 때 죽은 장비의 혼잡 기억이 새 장비로 전이된다 — 정리가 막겠다고 선언한
        //   바로 그것이다. 정리를 건너뛸 이유(외부 호출 절약)는 이 호출에 해당하지 않는다.
        smoother.retainOnly(nodes.stream().map(LsAiSrvr::getSrvrId).collect(Collectors.toSet()));
        if (nodes.isEmpty()) {
            return;
        }

        LocalDateTime observedAt = LocalDateTime.now(clock);
        for (LsAiSrvr node : nodes) {
            try {
                pollOne(node, observedAt);
            } catch (RuntimeException failure) {
                // ★여기서 잡지 않으면 노드 하나의 문제로 뒤 노드가 통째로 관측에서 빠진다.
                //   주소·응답 본문을 싣지 않는다(내부 토폴로지 — CWE-497).
                log.warn("[AiSrvr] 노드 관측에 실패했습니다(다른 노드는 계속 진행). srvrId={} · 원인={}",
                        node.getSrvrId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void pollOne(LsAiSrvr node, LocalDateTime observedAt) {
        String srvrId = node.getSrvrId();
        boolean healthy = healthProbe.ping(node);
        txService.applyHealth(srvrId, healthy, observedAt);
        if (!healthy) {
            return;
        }

        AiSrvrLoadReport report = loadProbe.probe(node);
        if (!report.hasValues()) {
            // 「아직 부하를 알리지 않음」도 「알 수 없음」도 저장된 직전 값을 그대로 둔다.
            // ★둘 다 상태점검 실패로 세지 않는다 — 그 판정은 위에서 이미 끝났다.
            return;
        }
        // 평활에는 <실효 부하>가 들어간다. 대기만 넣으면 이미 하나를 잡고 있는 노드를 한가한
        // 노드와 구분하지 못한다.
        report.slots().forEach((usage, load) ->
                smoother.smooth(srvrId, usage, load.effectiveLoad()));
        txService.applyLoad(srvrId, report.slots(), observedAt);
    }

    /** 현재 게이팅 상태 — 헬스 인디케이터가 「관측이 꺼져 있다」를 표시하는 데 쓴다. */
    public boolean isPollEnabled() {
        return pollEnabled;
    }
}
