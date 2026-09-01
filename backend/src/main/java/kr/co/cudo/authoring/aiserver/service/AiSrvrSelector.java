package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.common.client.PinnedTarget;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 요청 하나를 <b>어느 장비로 보낼지</b> 고른다 — 축 A(추론)·축 B(시계열) 공용. [@design ADR-057]
 *
 * <h3>고르는 규칙 하나 — 그 용도의 실효 부하가 가장 낮은 가용 장비</h3>
 * <p>보는 값은 <b>그 요청 자신의 용도에 해당하는 부하뿐</b>이다. 두 용도는 장비 안에서 실행이
 * 격리돼 있어, 부하를 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이 되어
 * 일괄 처리가 밀린 장비를 화면 요청이 <b>피할 이유가 없는데도 피하게</b> 된다.
 *
 * <h3>★ 부하를 읽는 원천은 축마다 다르다 (같은 곳에서 읽을 수 없다)</h3>
 * <ul>
 *   <li><b>추론(INFERENCE)</b> — 관측값이다. 상태점검 폴러가 장비에 물어 원장
 *       ({@code LS_AI_SRVR_USG})에 남기고 평활 창을 채운다.</li>
 *   <li><b>시계열(TIMESERIES)</b> — <b>우리 원장의 미결 위탁 수</b>다. 폴러가 시계열 노드를 훑지
 *       않기 때문이다({@code AiSrvrHealthPoller} 가 추론 노드만 대상으로 삼는다 — 논블로킹 제출 +
 *       콜백이라 그 경로의 부하·상태 개념이 성립하지 않는다). 즉 이 축에서 장비가 얼마나 물려
 *       있는지를 아는 자리는 <b>우리가 보낸 것을 세는 것</b>뿐이다.</li>
 * </ul>
 * <p>★ 시계열 부하로 세는 것은 <b>수락된(ACCEPTED)</b> 위탁뿐이다 — 발급(ISSUED)은 벤더가 아직
 * 받지 않은 상태라 그 장비의 부하가 0이고, 세면 방금 제출이 몰린 장비를 과대평가해 다음 요청이
 * 반대편으로 쏠린다(값이 실제 부하가 아니라 <b>직전 배분의 메아리</b>가 된다).
 *
 * <h3>실효 부하 산술은 여기서 다시 쓰지 않는다</h3>
 * <p>공식({@code 처리중 + 대기})과 음수 정규화의 소유자는 {@link AiSrvrSlotLoad} 다. 여기서 자기
 * 식을 쓰면 그것이 <b>두 번째 진실원</b>이 되어 가중치를 바꿀 때 한쪽만 바뀐다.
 *
 * <h3>모두 같은 부하면 식별자 순으로 고른다</h3>
 * <p>동률 처리를 임의(예: 조회 순서)로 두면 <b>같은 상황에서 매번 다른 답</b>이 나와 원인 추적이
 * 불가능해지고 시험이 흔들린다. 부하가 전부 0(아무것도 물려 있지 않음)이면 어디로 보내도 대기가
 * 생기지 않으므로 한쪽으로 몰리는 것이 손해가 아니다 — 분산이 필요한 순간은 <b>물려 있는 것이
 * 생긴 뒤</b>이고 그때는 부하가 갈린다.
 *
 * <h3>★ 가용 장비가 0건이면 예외가 아니라 「고르지 못했다」다</h3>
 * <p>{@link Optional#empty()} 를 돌려주고 <b>호출자가 배포 기본 주소로 그대로 나간다</b>. 예외로
 * 만들면 원장에 그 축의 장비가 한 건도 없는 <b>현재 형상</b>(부트스트랩은 추론 노드 1건만 세운다 —
 * 시계열 노드는 운영자가 등록하기 전까지 0건이다)에서 시계열 위탁이 <b>전량 실패</b>한다. 즉
 * 「분산을 못 한다」를 「연동이 끊긴다」로 격상시키는 셈이라, 이 기능이 없던 때보다 나빠진다.
 * 분산하지 못했다는 사실은 호출자가 남긴다(장비 미상으로 원장에 기록된다).
 *
 * <h3>★ 보낼 수 없는 장비는 고르지 않는다</h3>
 * <p>후보 판정에 <b>「그 주소로 절대 목적지를 만들 수 있는가」</b>({@link PinnedTarget#canPin})를 건다.
 * 이 판정이 <b>선택</b>에 있어야 하는 이유는, 없으면 「고를 수는 있는데 보낼 수는 없는 장비」가 생기고
 * 그때 호출자는 그 장비를 원장에 적은 뒤 <b>배포 기본 주소로</b> 요청을 보내기 때문이다 — 오류가 아니라
 * 조용한 어긋남이라 아무 데도 드러나지 않고, 그 원장은 다음 배분의 입력이라 어긋남이 누적된다.
 * 여기서 걸러 두면 결과는 항상 「보낼 수 있는 장비」이거나 「없음」이라 그 여지가 <b>구조적으로</b> 사라진다.
 *
 * <h3>★ 고른 장비의 주소도 <b>연동 주소 정책</b>을 거친다</h3>
 * <p>배포 기본 주소는 빈 생성 시점에 정책({@link VlmUrlPolicy})을 거치는데, <b>원장에서 고른 장비의
 * 주소는 아무 판정도 거치지 않았다</b>. 그 축에는 운영자 입력을 받는 등록 입구조차 없어(주소가 원장
 * 행으로 직접 들어온다) 어디에서도 걸러지지 않는다. 게다가 그 목적지로 나가는 요청에는 표식이 붙어
 * <b>자격증명 가드의 호스트 비교도 면제</b>된다 — 즉 배포 기본값보다 <b>느슨한</b> 경로가 된다.
 *
 * <p>그래서 <b>같은 정책 객체를 여기서 부른다</b>. 판정을 복제하지 않는 것이 핵심이다 — 복제하면
 * 그것이 두 번째 진실원이 되어 정책을 바꿀 때 한쪽만 바뀐다. 통과하지 못한 장비는 후보에서 빠지고,
 * 그 사실은 <b>식별자만</b> 남기는 경고로 알린다(주소는 내부 토폴로지다 — CWE-497).
 *
 * <p>⚠ <b>순서가 중요하다</b> — 이 판정은 연동 주소 정책이 <b>평문 http 를 통과시킨 뒤에야</b> 안전하다.
 * 그 전 형상(HTTPS 강제)에서 걸면 평문으로 등록된 <b>정상 장비가 전부 후보에서 빠져</b> 분산이 조용히
 * 멈춘다. 정책을 다시 좁히려는 변경은 이 지점까지 함께 검토해야 한다.
 *
 * <h3>★ 조회가 실패하면 분산만 포기한다 — 위탁은 나간다</h3>
 * <p>원장·부하 조회는 DB 를 탄다. DB 순단·커넥션 풀 고갈에 예외가 그대로 올라가면 <b>배치 전체가
 * FAILED</b> 로 마감되는데, 그것은 <b>노드 분산을 도입하기 전에는 없던 실패 모드</b>다(그전에는 DB 가
 * 흔들려도 위탁은 나갔다). 편의 기능이 본 기능을 끌어내리면 안 되므로 「고르지 못했다」로 낮춘다 —
 * 같은 축의 상태 관측이 이미 그 원칙을 명시 보장하고 있다.
 * <p>이 낮춤은 안전하다 — 호출자에게는 <b>선커밋 이전</b>에 돌려주므로 고아 원장 행이 생기지 않는다.
 *
 * <h3>이 클래스는 배정 기록을 만들지 않는다</h3>
 * <p>영상 고정(같은 영상은 늘 같은 장비로)은 <b>축 A 의 성질</b>이다 — 추론은 한 영상의 프레임을
 * 여러 번 나눠 부르므로 장비가 바뀌면 추적이 끊긴다. 시계열은 영상 하나에 위탁 한 번이라 고정할
 * 대상이 없고, 고정하면 <b>죽은 장비에 묶인 영상이 영영 다른 장비로 가지 못한다</b>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSrvrSelector {

    private final AiSrvrRegistry registry;
    private final AiSrvrLoadSmoother smoother;
    private final LsAiSrvrUsgRepository usgRepository;
    /** 시계열 축의 부하 원천 — 우리가 보낸 위탁 중 결과를 기다리는 건수. */
    private final LsWebhookIdempotencyRepository submitLedger;
    /** 시계열 축 목적지의 주소 정책 — 배포 기본 주소가 거치는 것과 <b>같은 객체</b>다(복제 금지). */
    private final VlmUrlPolicy vlmUrlPolicy;

    /** 「보낼 수 없어 제외했다」를 장비당 1회만 알리기 위한 표식 — 매 호출 경고는 로그를 덮는다. */
    private final Set<String> unpinnableWarned = ConcurrentHashMap.newKeySet();

    /** 「연동 주소 정책을 통과하지 못해 제외했다」를 장비당 1회만 알리기 위한 표식. */
    private final Set<String> policyRejectedWarned = ConcurrentHashMap.newKeySet();

    /** 경고 표식의 상한 — 원장 장비 수만큼만 늘지만 무한 증식 경로를 남기지 않는다(CWE-770). */
    private static final int UNPINNABLE_WARN_CAP = 200;

    /**
     * 그 축의 가용 장비 중 <b>해당 용도의 실효 부하가 가장 낮은</b> 하나.
     *
     * @param srvrType 장비 축(추론 / 시계열). 두 축은 부하의 성질이 달라 같은 목록에서 섞어 고르지 않는다
     * @param usage    부하를 볼 용도. 시계열 축에서는 부하 원천이 우리 원장이라 이 값이 쓰이지 않는다
     * @return 고른 장비. 가용 장비가 없으면 {@code empty}(예외 아님 — 위 §가용 장비 0건 참조)
     */
    public Optional<LsAiSrvr> select(LsAiSrvr.SrvrType srvrType, AiSrvrUsageType usage) {
        if (srvrType == null) {
            return Optional.empty();
        }
        try {
            List<LsAiSrvr> candidates = registry.findAvailable().stream()
                    .filter(node -> node.getSrvrTypeCd() == srvrType)
                    .filter(this::pinnable)
                    .filter(node -> acceptedByEndpointPolicy(srvrType, node))
                    .toList();
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Integer> loads = loadsOf(srvrType, usage, candidates);
            return candidates.stream()
                    // 동률은 식별자 순 — 같은 상황에서 같은 답이 나와야 원인을 되짚을 수 있다.
                    .min(Comparator.<LsAiSrvr>comparingInt(node -> loads.getOrDefault(node.getSrvrId(), 0))
                            .thenComparing(LsAiSrvr::getSrvrId));
        } catch (RuntimeException e) {
            // 조회 실패로 <b>정상 위탁을 막지 않는다</b> — 분산만 포기한다(위 §조회가 실패하면).
            log.warn("[AiSrvr] 장비 선택 조회 실패 — 분산 없이 진행합니다 type={} cause={}",
                    srvrType, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 이 장비로 <b>실제로 보낼 수 있는가</b> — 보낼 수 없는 장비는 고르지 않는다.
     *
     * <p>판정은 {@link PinnedTarget#canPin(String)} 하나에 위임한다(전송 지점과 같은 술어). 여기서
     * 자기 기준을 쓰면 「고를 수는 있는데 보낼 수는 없는 장비」가 생기고, 그러면 원장에는 그 장비가
     * 남는데 요청은 배포 기본 주소로 나간다 — 조용한 어긋남이라 드러나지 않는다.
     *
     * <p>경고에는 <b>식별자만</b> 남긴다(CWE-497 — 주소는 내부 토폴로지다). 매 호출 시끄럽지 않도록
     * 장비당 1회만 출력한다.
     */
    private boolean pinnable(LsAiSrvr node) {
        if (PinnedTarget.canPin(node.getSrvrAddr())) {
            return true;
        }
        if (unpinnableWarned.size() < UNPINNABLE_WARN_CAP && unpinnableWarned.add(node.getSrvrId())) {
            log.warn("[AiSrvr] 주소로 목적지를 만들 수 없어 후보에서 제외합니다 srvrId={}",
                    VlmClient.safeForLog(node.getSrvrId()));
        }
        return false;
    }

    /**
     * 이 장비의 주소가 <b>연동 주소 정책</b>을 통과하는가 — 배포 기본 주소와 같은 기준.
     *
     * <p>판정은 정책 객체({@link VlmUrlPolicy#validate})에 <b>그대로 위임</b>한다. 그 정책이 보는 것은
     * 스킴({@code http}/{@code https})·형식·placeholder·예약 대역이며, 평문 http 와 사설 대역은
     * <b>통과시킨다</b>(2026-08-10 확정) — 그래서 정상 내부망 장비가 여기서 빠지지 않는다.
     *
     * <p><b>정책이 예외를 던지는 것은 「그 장비 제외」이지 「위탁 실패」가 아니다</b>. 예외를 그대로
     * 올리면 장비 하나의 주소 오타가 <b>배치 전체를 FAILED 로</b> 마감시키는데, 그것은 노드 분산을
     * 도입하기 전에는 없던 실패 모드다(같은 이유로 조회 실패도 「고르지 못했다」로 낮춘다).
     *
     * <p>경고에는 <b>식별자와 예외 종류만</b> 남긴다 — 정책 예외 메시지에는 호스트가 실려 있어
     * 그대로 찍으면 내부 토폴로지가 로그로 샌다(CWE-497). 장비당 1회만 출력한다.
     *
     * <p>⚠ <b>추론 축에는 대응 정책 객체가 아직 없다</b>({@code authoring.integration.ai-server.base-url}
     * 은 어떤 정책도 거치지 않는다). 그 축에 정책이 생기면 <b>여기에 그 객체를 잇는다</b> — 이 자리에서
     * 자체 판정을 새로 쓰지 말 것(두 번째 진실원 금지).
     *
     * <p>정책은 호스트 해석을 동반할 수 있다(예약 대역 판정). 선택은 위탁 1건당 한 번이고 후보 수는
     * 원장 장비 수라 비용이 문제되는 자리가 아니며, 해석 실패는 정책이 <b>통과</b>로 다룬다.
     */
    private boolean acceptedByEndpointPolicy(LsAiSrvr.SrvrType srvrType, LsAiSrvr node) {
        if (srvrType != LsAiSrvr.SrvrType.TIMESERIES) {
            return true;
        }
        try {
            vlmUrlPolicy.validate(node.getSrvrAddr());
            return true;
        } catch (RuntimeException e) {
            if (policyRejectedWarned.size() < UNPINNABLE_WARN_CAP
                    && policyRejectedWarned.add(node.getSrvrId())) {
                log.warn("[AiSrvr] 연동 주소 정책을 통과하지 못해 후보에서 제외합니다 srvrId={} cause={}",
                        VlmClient.safeForLog(node.getSrvrId()), e.getClass().getSimpleName());
            }
            return false;
        }
    }

    /** 후보 장비의 부하 표 — 축마다 원천이 다르다(클래스 주석 §부하를 읽는 원천). */
    private Map<String, Integer> loadsOf(LsAiSrvr.SrvrType srvrType, AiSrvrUsageType usage,
                                         List<LsAiSrvr> candidates) {
        Set<String> srvrIds = candidates.stream().map(LsAiSrvr::getSrvrId).collect(Collectors.toSet());
        return srvrType == LsAiSrvr.SrvrType.TIMESERIES
                ? outstandingSubmitLoads(srvrIds)
                : observedLoads(srvrIds, usage);
    }

    /**
     * 시계열 축 — 그 장비가 결과를 기다리고 있는 위탁 건수.
     *
     * <p>집계에 없는 장비는 0건이다({@code GROUP BY} 는 행이 없는 장비를 돌려주지 않는다). 값을 누르는
     * 것은 {@link AiSrvrSlotLoad#clampToZero(int)} 가 소유한 정책을 그대로 쓴다.
     *
     * <p>건수가 {@code int} 범위를 넘는 일은 현실적으로 없지만, 넘치면 <b>음수가 되어 가장 한가한
     * 장비로 뒤집히므로</b> 상한을 씌워 잘라 담는다.
     */
    private Map<String, Integer> outstandingSubmitLoads(Set<String> srvrIds) {
        Map<String, Integer> loads = new HashMap<>();
        submitLedger.countAcceptedBySrvrId(srvrIds).forEach(row ->
                loads.put(row.getSrvrId(), AiSrvrSlotLoad.clampToZero(
                        (int) Math.min(row.getLoadCount(), Integer.MAX_VALUE))));
        return loads;
    }

    /**
     * 추론 축 — 평활 창의 값, 없으면 <b>원장의 마지막 관측값</b>.
     *
     * <p>★ 폴백이 필요한 이유: 평활 창은 WAS 프로세스의 메모리에 있고 폴링 틱이 2노드로 나뉘므로 각
     * 노드의 창은 <b>부분 표본</b>이며 방금 기동한 노드는 비어 있다. 그때 창이 비었다고 0으로 읽으면
     * <b>바쁜 장비가 가장 한가한 장비로 보여</b> 요청을 통째로 빨아들인다 — 재기동 직후마다 그렇게
     * 된다. 원장 값은 평활 전이라 더 튀지만, 없는 값을 0으로 지어내는 것보다 낫다.
     *
     * <p>둘 다 없으면 0으로 본다 — 아직 한 번도 관측되지 않은 장비이며, 이 프로젝트는 그 상태를
     * 이미 0으로 표현한다({@code LsAiSrvrUsg.of} — 첫 폴링이 곧 채우고, 그 사이 잘못 골라도 실제
     * 호출 실패는 서킷브레이커가 잡는다).
     */
    private Map<String, Integer> observedLoads(Set<String> srvrIds, AiSrvrUsageType usage) {
        Map<String, Integer> loads = new HashMap<>();
        if (usage == null) {
            return loads;
        }
        for (String srvrId : srvrIds) {
            OptionalInt smoothed = smoother.peek(srvrId, usage);
            if (smoothed.isPresent()) {
                loads.put(srvrId, smoothed.getAsInt());
            }
        }
        Set<String> withoutSample = srvrIds.stream()
                .filter(id -> !loads.containsKey(id))
                .collect(Collectors.toSet());
        if (withoutSample.isEmpty()) {
            return loads;
        }
        // 공식은 여기서 다시 쓰지 않는다 — 저장된 행이 AiSrvrSlotLoad 의 식을 부른다.
        usgRepository.findBySrvrIdInAndUsgTypeCd(withoutSample, usage)
                .forEach(row -> loads.put(row.getSrvrId(), row.effectiveLoad()));
        return loads;
    }
}
