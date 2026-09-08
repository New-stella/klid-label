package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.client.VlmClient;
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
 * <h3>★ 상태는 두 계통 다 재고, 부하는 추론만 잰다 (2026-09-08)</h3>
 * <p>「살아 있는가」와 「여유가 있는가」는 <b>다른 축</b>이며, 그 구분이 관측 대상을 정할 때에도
 * 그대로 적용된다는 것이 근거 결정의 사양이다.
 * <ul>
 *   <li><b>상태</b> — 추론·시계열 <b>둘 다</b> 관측한다. 창구는 계통마다 다르며 그 판정은
 *       {@link HttpAiSrvrHealthProbe} 가 단독으로 갖는다.</li>
 *   <li><b>부하</b> — <b>추론만</b> 관측한다. 시계열은 위탁을 제출하고 결과를 콜백으로 받는 방식이라
 *       그 계통에 「처리 대기」라는 개념이 성립하지 않는다. 그 축의 부하 원천은 <b>우리 원장의 미결
 *       위탁 수</b>이며 {@code AiSrvrSelector} 가 소유한다.</li>
 * </ul>
 *
 * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — 시계열을 제외하던 자리에 <i>「시계열 축은 논블로킹 제출 +
 * 콜백이라 이 경로의 <b>부하·상태</b> 개념이 성립하지 않는다」</i>고 적혀 있었다. <b>그 근거가 두
 * 축을 섞었다</b> — 부하는 정말 못 재지만 <b>살아 있는지는 잴 수 있다</b>. 그대로 두면 시계열 장비는
 * 죽어도 「가용」으로 남아 위탁이 죽은 주소로 계속 나가고, 그 계통은 후보가 0이 되어도 아무도
 * 알아채지 못한다. 지우지 않고 남기는 이유는 왜 한때 그렇게 적혔는지가 사라지면 다음 사람이 같은
 * 역추정으로 필터를 되살리기 때문이다. <b>부하 관측을 시계열에 적용하지 않는 것은 그대로다.</b>
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
 *
 * <h3>★ 점검 <b>주기</b>도 계통마다 따로다 (2026-09-08 확정) [@design AC-1099]</h3>
 * <p>그래서 이 클래스에는 <b>계통을 받는 얼굴</b>({@link #poll(LsAiSrvr.SrvrType)})이 있고 트리거가
 * 계통마다 있다. 주기 값 자체는 {@link kr.co.cudo.authoring.aiserver.job.AiSrvrHealthPollTriggerConfig}
 * 이 소유하며 여기서 읽지 않는다.
 *
 * <p>⚠ <b>인지·수용한 대가</b> — 주기가 길어지면 <b>죽은 장비가 목록에 남는 시간이 길어진다</b>.
 * 배제까지 걸리는 시간은 「주기 × 연속 실패 임계」라 시계열 기본값(10초)에서는 추론(5초)의 대략
 * <b>두 배</b>가 된다. 그 구간의 위탁은 죽은 주소로 나갔다 실패한다. 벤더를 두드리는 횟수를 줄이는
 * 대가이며 근거 결정에 등재돼 있다.
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

    /**
     * 한 틱 — 원장의 노드를 <b>전부</b> 관측한다(상태는 전 계통, 부하는 추론만).
     *
     * <p>계통을 가리지 않는 얼굴이다. 정규 경로는 계통별 트리거가 {@link #poll(LsAiSrvr.SrvrType)} 을
     * 부르며, 이 얼굴은 <b>수동 트리거·프로그래밍 호출</b>과 계통 정보를 갖지 않은 <b>구 잡 등록 행</b>
     * (되돌림 배포 등)이 쓴다.
     */
    public void pollAll() {
        poll(null);
    }

    /**
     * 한 틱 — <b>그 계통만</b> 관측한다. [@design AC-1099] [@design ADR-057]
     *
     * <h3>★ 왜 계통마다 따로 도는가</h3>
     * <p>점검 주기를 계통마다 따로 두기로 확정됐고(2026-09-08), 그러려면 <b>트리거가 계통마다</b>
     * 있어야 한다. 트리거 하나가 전 계통을 훑으면서 「최근 점검 시각으로 거른다」는 방식은,
     * <b>시계열의 실효 주기가 추론 주기보다 짧아질 수 없어</b> 한쪽이 다른 쪽의 하한을 정하게 된다 —
     * 그것이 정확히 이 결정이 없애려던 결합이다.
     *
     * <h3>★★ 평활 표본 정리는 <b>전 계통</b>을 기준으로 한다 — 여기서 계통으로 좁히면 안 된다</h3>
     * <p>정리는 「원장에 없는 장비의 기억을 버린다」는 뜻이다. 이 틱이 보는 계통만 기준으로 삼으면
     * <b>다른 계통 장비가 전부 「원장에 없다」로 보여</b> 그쪽 표본이 통째로 지워진다. 부하 표본을
     * 갖는 것은 추론뿐이므로, 시계열 틱이 돌 때마다 추론의 평활 창이 비고 <b>바쁜 장비가 가장
     * 한가한 장비로 보여 요청을 통째로 빨아들인다</b>(재기동 직후와 같은 상태가 5초마다 재현된다).
     * 그래서 원장 조회는 <b>전체</b>로 하고 계통 필터는 <b>관측 대상에만</b> 건다.
     *
     * @param srvrType 관측할 계통. {@code null} 이면 전 계통(위 {@link #pollAll()})
     */
    public void poll(LsAiSrvr.SrvrType srvrType) {
        if (!pollEnabled) {
            // 원장 조회조차 하지 않는다. 「꺼짐」은 <이 관측 배치가> 외부 호출을 한 건도 내지
            // 않는다는 뜻이다. ⚠ 시스템 전체로 확대해 읽지 말 것 — 헬스 인디케이터
            // (AiServerHealthIndicator)는 이 설정과 무관하게 사람이 볼 때마다 직접 핑한다
            // (배치가 꺼져 있어 원장 값이 갱신되지 않으므로 그것이 의도다).
            return;
        }
        // ★계통을 가르지 않는다 — 상태는 두 계통 다 잰다(위 클래스 주석 §상태는 두 계통 다 재고).
        //   ⚠구 동작 폐기(2026-09-08): 여기 INFERENCE 만 남기는 필터가 있었다. 되살리지 말 것 —
        //     그러면 시계열 장비가 죽어도 「가용」으로 남아 위탁이 죽은 주소로 계속 나간다.
        //     부하를 시계열에서 재지 않는 것은 pollOne 이 따로 지킨다.
        List<LsAiSrvr> ledger = repository.findAll();
        // ★노드가 0건이어도 정리는 <먼저> 한다. 여기서 되돌아가면 원장을 비운 뒤 같은 식별자로
        //   다시 세웠을 때 죽은 장비의 혼잡 기억이 새 장비로 전이된다 — 정리가 막겠다고 선언한
        //   바로 그것이다. 정리를 건너뛸 이유(외부 호출 절약)는 이 호출에 해당하지 않는다.
        // ★★기준은 <전 계통>이다(위 javadoc §평활 표본 정리). 이 틱의 계통으로 좁히면 다른 계통
        //   장비가 「원장에 없다」로 보여 그쪽 표본이 매 틱 지워진다.
        smoother.retainOnly(ledger.stream().map(LsAiSrvr::getSrvrId).collect(Collectors.toSet()));
        List<LsAiSrvr> nodes = srvrType == null
                ? ledger
                : ledger.stream().filter(node -> node.getSrvrTypeCd() == srvrType).toList();
        if (nodes.isEmpty()) {
            return;
        }

        LocalDateTime observedAt = LocalDateTime.now(clock);
        for (LsAiSrvr node : nodes) {
            try {
                pollOne(node, observedAt);
            } catch (RuntimeException failure) {
                // ★여기서 잡지 않으면 노드 하나의 문제로 뒤 노드가 통째로 관측에서 빠진다.
                //   주소·응답 본문을 싣지 않는다(내부 토폴로지 — CWE-497). 식별자도 원장 유래라
                //   정제해 싣는다(CWE-117) — 원장 행은 체크 제약을 우회해 들어왔을 수 있다.
                log.warn("[AiSrvr] 노드 관측에 실패했습니다(다른 노드는 계속 진행). srvrId={} · 원인={}",
                        VlmClient.safeForLog(node.getSrvrId()), failure.getClass().getSimpleName());
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

        if (node.getSrvrTypeCd() != LsAiSrvr.SrvrType.INFERENCE) {
            // ★부하는 추론만 잰다. 시계열은 제출 후 콜백이라 「처리 대기」라는 개념이 없고, 그 축의
            //   부하 원천은 우리 원장의 미결 위탁 수다(AiSrvrSelector 소유). 여기서 물으면 벤더의
            //   상태 창구를 부하 창구로 착각해 두드리게 된다.
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
