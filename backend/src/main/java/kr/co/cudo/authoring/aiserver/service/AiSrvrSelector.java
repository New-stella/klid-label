package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.PinnedTarget;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
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
 *   <li><b>시계열(TIMESERIES)</b> — <b>우리 원장의 미결 위탁 수</b>다. 그 계통은 위탁을 제출하고
 *       결과를 콜백으로 받는 방식이라 <b>「처리 대기」라는 개념이 성립하지 않아</b> 물어볼 대상이
 *       없다. 즉 이 축에서 장비가 얼마나 물려 있는지를 아는 자리는 <b>우리가 보낸 것을 세는 것</b>
 *       뿐이다.</li>
 * </ul>
 *
 * <p>⚠ <b>구 서술 정정(2026-09-08)</b> — 시계열의 근거가 <i>「폴러가 시계열 노드를 훑지 않기
 * 때문이다({@code AiSrvrHealthPoller} 가 추론 노드만 대상으로 삼는다 — 논블로킹 제출 + 콜백이라 그
 * 경로의 <b>부하·상태</b> 개념이 성립하지 않는다)」</i>로 적혀 있었다. <b>폴러는 이제 시계열도
 * 훑는다</b> — 다만 <b>상태만</b> 재고 부하는 재지 않는다. 즉 부하 원천이 우리 원장이라는
 * <b>결론은 그대로</b>이고 근거만 「훑지 않아서」에서 「부하 개념이 없어서」로 좁혀졌다. 두 축(살아
 * 있는가 ↔ 여유가 있는가)을 한 문장에 묶은 것이 그 서술의 오류였다.
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
 * <h3>★★ 쓸 수 있는 후보가 0이면 <b>사유를 가리지 않고 거부</b>한다 — 폴백은 없다</h3>
 * <p>판정 축은 하나다: <b>「쓸 수 있는 후보가 0인가」</b>. 그 축이 0이 된 사유가 원장 식별자 형식
 * 위반이든, 목적지를 만들 수 없는 주소든, 주소 정책 탈락이든, <b>상태점검 실패로 그 유형이 전부
 * 이용불가</b>가 된 것이든, 심지어 <b>그 유형의 행이 한 건도 없든</b> 같다.
 * {@link NonRetryableExternalException} 으로 <b>fail-closed</b> 한다. [@design AC-1093]
 *
 * <p>⚠ <b>구 동작 폐기(2026-09-07)</b> — 여기 「가용 장비가 0건이면 {@link Optional#empty()} 를 돌려주고
 * <b>호출자가 배포 기본 주소로 그대로 나간다</b>」고 적혀 있었고, 거부는 「형식 위반이 하나라도 섞였을
 * 때」로 좁혀져 있었다. 그 좁힘이 <b>실제 장애를 감췄다</b> — 두 장비가 함께 죽어 전부 이용불가가 되면
 * 형식 위반은 하나도 없으므로 위탁이 조용히 배포 기본 주소로 나갔다. <b>되살리지 말 것.</b>
 *
 * <p>⚠⚠ <b>「그 유형의 행이 0건이면 폴백」 예외를 만들지 말 것.</b> 배포 설정값은 유형별로 <b>최초 1회
 * 씨앗</b>일 뿐이며, 원장에 행이 없을 때 설정값으로 대신 호출하는 폴백을 두면 <b>진실원이 둘</b>이 되어
 * 이 결정이 없애려던 상태로 돌아간다. 씨앗값이 없어 아무것도 심기지 않은 상태는 <b>미연동이 정상인
 * 배포</b>로 이미 규정돼 있어 사용 시점 거부가 맞다. [@design AC-1092] [@design ADR-046]
 *
 * <p>거부된 일괄 처리의 대가는 {@code noUsableServer} javadoc §대가 참조 — 배치 재시도 큐에서 상한까지
 * 재실행된 뒤 소진된다. <b>해소 경로는 사유마다 다르다</b>: 형식 위반은 사람이 원장을 고쳐야 풀리고,
 * 상태점검 실패는 장비가 복구되어 연속 성공이 복귀 임계에 닿으면 <b>저절로</b> 풀린다. [@design AC-1094]
 *
 * <h3>★ 원장 식별자 형식 — <b>고를 때마다</b> 판정하고, 위반 행만 뺀다 [@design ADR-062]</h3>
 * <p>식별자가 형식({@link AiSrvrIdPolicy})을 어기면 서킷브레이커 이름과 메트릭 라벨이 조용히
 * 어긋난다. 그 판정은 원래 <b>기동 가드</b>에 있어 원장 한 행이 앱 전체를 못 뜨게 했다. 지금은
 * 기동이 통과하고(거기서는 ERROR 로 알리기만 한다) <b>보호가 실제로 필요한 이 자리</b>에서 걸린다 —
 * 고르는 자리에서 위반 행을 빼면 잘못된 식별자가 나갈 길이 구조적으로 없다.
 *
 * <p>★★ <b>판정 결과를 들고 있지 않는다.</b> 원장은 운영 화면에서 <b>런타임에</b> 바뀐다(장비 등록·
 * 수정). 기동 시 1회 판정을 캐시하면 <b>뒤에 추가한 정상 장비가 영영 제외</b>되거나 그 반대가 된다.
 * 그래서 이 클래스는 위반 집합도 판정 시각도 필드로 두지 않고, {@code select} 가 불릴 때마다
 * 원장 스냅샷을 다시 걸러 낸다.
 *
 * <p>★★ <b>위반 때문에 후보가 하나도 남지 않으면 위탁 거부다</b> — 위 §쓸 수 있는 후보가 0이면 과
 * 같은 판정에 그대로 흡수된다. 여기서 {@code empty} 를 돌려주면 호출자가 <b>배포 기본 주소로 폴백</b>
 * 하는데, 그러면 「쓰면 안 되는 장비만 있는 원장」을 만난 요청이 아무 일도 없었다는 듯 나간다 —
 * 이 판정이 막으려던 바로 그 일이다. {@link NonRetryableExternalException} 은 <b>원장을 고쳐야 풀리는
 * 결정적 실패라는 표식</b>이다 — ⚠ 그 타입이 자동 재시도를 막아 주지는 <b>않는다</b>. 정확한 대가는
 * {@code noUsableServer} javadoc §대가 둘 참조.
 *
 * <p>★ <b>거부 조건에 사유 한정이 없다.</b> 형식 위반이 하나라도 걸러졌는지를 <b>세지 않는다</b> —
 * 후보가 0이면 그것으로 끝이다. 좁히려면 「그 걸러짐이 없었다면 후보가 남았을까」라는 반사실 판정이
 * 필요한데, 그 답은 <b>필터 순서에 따라 달라져</b> 같은 원장이 경로마다 다르게 판정된다.
 *
 * <p>⚠ <b>이것은 회귀가 아니다.</b> 구 동작은 그런 원장 행이 하나만 있어도 <b>앱이 아예 뜨지 못했다</b> —
 * 지금은 뜨고, 그 축의 위탁 하나만 거부된다. 넓다고 결함으로 재보고하지 말 것.
 *
 * <p>거부 사유에는 <b>어느 연동인지와 사유 분류만</b> 싣는다 — 식별자 원문·주소는 서버 로그에만
 * 남긴다(CWE-209/497).
 *
 * <h3>★ 보낼 수 없는 장비는 고르지 않는다</h3>
 * <p>후보 판정에 <b>「그 주소로 절대 목적지를 만들 수 있는가」</b>({@link PinnedTarget#canPin})를 건다.
 * 이 판정이 <b>선택</b>에 있어야 하는 이유는, 없으면 「고를 수는 있는데 보낼 수는 없는 장비」가 생기고
 * 그때 호출자는 그 장비를 원장에 적은 뒤 <b>배포 기본 주소로</b> 요청을 보내기 때문이다 — 오류가 아니라
 * 조용한 어긋남이라 아무 데도 드러나지 않고, 그 원장은 다음 배분의 입력이라 어긋남이 누적된다.
 * 여기서 걸러 두면 결과는 항상 「보낼 수 있는 장비」이거나 「없음」이라 그 여지가 <b>구조적으로</b> 사라진다.
 *
 * <h3>★ 고른 장비의 주소도 <b>연동 주소 정책</b>을 거친다</h3>
 * <p>배포 기본 주소는 <b>전송 시점</b>에 정책({@link VlmUrlPolicy})을 거치는데, <b>원장에서 고른
 * 장비의 주소는 아무 판정도 거치지 않았다</b>. 그 축에는 운영자 입력을 받는 등록 입구조차 없어
 * (주소가 원장 행으로 직접 들어온다) 어디에서도 걸러지지 않는다. 게다가 그 목적지로 나가는 요청에는
 * 표식이 붙어 <b>자격증명 가드의 호스트 비교도 면제</b>된다 — 즉 배포 기본값보다 <b>느슨한</b>
 * 경로가 된다.
 *
 * <p>⚠ <b>구 서술 폐기</b> — 여기 「배포 기본 주소는 <b>빈 생성 시점</b>에 정책을 거친다」고 적혀
 * 있었다. 2026-09-03 확정으로 그 판정이 기동에서 전송 시점으로 옮겨져 <b>더는 사실이 아니다</b>
 * ([@design ADR-062]). 이 문장을 근거로 「기동이 이미 걸렀을 것」이라고 가정하지 말 것 —
 * 배포 기본 주소도 원장 장비 주소도 <b>쓰이는 순간에</b> 판정된다.
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

    /** 「식별자 형식을 어겨 제외했다」를 장비당 1회만 알리기 위한 표식. */
    private final Set<String> malformedIdWarned = ConcurrentHashMap.newKeySet();

    /**
     * 그 축의 가용 장비 중 <b>해당 용도의 실효 부하가 가장 낮은</b> 하나.
     *
     * @param srvrType 장비 축(추론 / 시계열). 두 축은 부하의 성질이 달라 같은 목록에서 섞어 고르지 않는다
     * @param usage    부하를 볼 용도. 시계열 축에서는 부하 원천이 우리 원장이라 이 값이 쓰이지 않는다
     * @return 고른 장비. {@code empty} 는 <b>「원장을 못 읽었다」</b>일 때뿐이다(조회 실패 — 분산만 포기).
     *         「후보가 0이다」는 {@code empty} 가 아니라 예외다. 두 가지를 섞으면 DB 순단이 전 위탁을
     *         죽이거나(전자를 후자로) fail-closed 가 조용히 열린다(후자를 전자로).
     * @throws NonRetryableExternalException <b>쓸 수 있는 후보가 0일 때</b> — 사유를 가리지 않는다
     *         (형식 위반 · 목적지 불가 · 주소 정책 탈락 · 상태점검 실패로 전부 이용불가 · 그 유형의 행
     *         0건). 폴백하면 이 판정이 막으려던 일이 그대로 일어난다(위 §쓸 수 있는 후보가 0이면).
     *         [@design ADR-062] [@design AC-1074] [@design AC-1093]
     */
    public Optional<LsAiSrvr> select(LsAiSrvr.SrvrType srvrType, AiSrvrUsageType usage) {
        if (srvrType == null) {
            return Optional.empty();
        }
        Outcome outcome;
        try {
            outcome = choose(srvrType, usage);
        } catch (RuntimeException e) {
            // 조회 실패로 <b>정상 위탁을 막지 않는다</b> — 분산만 포기한다(위 §조회가 실패하면).
            log.warn("[AiSrvr] 장비 선택 조회 실패 — 분산 없이 진행합니다 type={} cause={}",
                    srvrType, e.getClass().getSimpleName());
            return Optional.empty();
        }
        // ★ 거부는 반드시 <try 밖>에서 던진다. 안에서 던지면 바로 위 catch 가 그것을 「조회 실패」로
        //   삼켜 empty 로 낮추고, 그 순간 호출자가 배포 기본 주소로 폴백해 fail-closed 가 조용히 열린다.
        if (outcome.rejected()) {
            throw noUsableServer(srvrType);
        }
        return outcome.chosen();
    }

    /**
     * <b>이미 배정된 장비</b>를 지금도 고를 수 있는가 — 영상 고정 경로가 쓰는 얼굴. [@design ADR-057]
     *
     * <p>후보 판정을 호출부에 복제하지 않기 위한 자리다. 「가용인가 · 식별자 형식이 맞는가 · 목적지를
     * 만들 수 있는가 · 주소 정책을 통과하는가」를 여기서 다시 쓰면 그 사본이 두 번째 진실원이 되어,
     * 선택기는 거른 장비를 고정 경로는 그대로 쓰는 어긋남이 열린다. 그래서 <b>같은 후보 목록</b>에서
     * 식별자로 찾기만 한다.
     *
     * <p>비어 있으면 <b>그 장비를 지금 쓸 수 없다</b>는 뜻이다 — 「없는 장비」와 「이용불가 장비」를
     * 구분하지 않는다(호출자에게는 둘 다 「이 배정으로는 못 보낸다」로 같다).
     *
     * <h3>★★ 모집단이 {@link #select} 와 <b>다르다</b> — 정비중 장비는 여기서 살아남는다 [@design AC-1100]</h3>
     * <p>「신규 배정을 받을 수 있는가」와 「이미 붙어 있는 배정을 유지하는가」는 <b>다른 술어</b>이고,
     * 그 둘이 갈리는 자리가 정비중({@link kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus#DRAINING})
     * 하나다. 판정은 상태 열거형({@link
     * kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus#retainsPinnedAssignment})이 소유하며 여기서
     * 다시 쓰지 않는다.
     *
     * <p>⚠ <b>구 동작 폐기(2026-09-08)</b> — 여기서도 {@code select} 와 같은 후보 목록
     * ({@code registry.findAvailable()} 기반)을 썼다. 그래서 <b>정비중 장비에 고정된 영상이 곧바로
     * 재배정</b>됐고, 「신규 배정만 막고 진행 중인 작업은 끝까지」라는 정비중의 정의가 깨졌다(YOLO 가
     * gpu01 에 고정된 뒤 관리자가 gpu01 을 정비로 내리면, 이어지는 SAM2 가 gpu02 로 옮겨 가 앞 단계의
     * 추적 상태를 이어받지 못한다). <b>되살리지 말 것.</b>
     *
     * <p>⚠⚠ 그 대신 <b>{@code select} 쪽 모집단을 넓히지 말 것</b> — 넓히면 정비중 장비가 신규 배정을
     * 받아 정비가 끝나지 않는다(무중단 정비가 반대쪽에서 깨진다). 두 얼굴이 <b>모집단만 다르고 나머지
     * 판정은 공유</b>하는 것이 이 구조의 요점이다.
     *
     * <p>이용불가·비활성은 <b>종전대로 재배정 대상</b>이다 — 그 장비의 추적 상태는 이미 사라졌고,
     * 유지하면 그 영상이 영영 죽은 장비에 묶인다.
     *
     * <p>★ <b>여기서는 거부를 던지지 않는다.</b> 후보 0 판정은 {@link #select} 가 소유한다 —
     * 고정 경로는 「고정된 장비를 못 쓴다」와 「그 계통에 쓸 장비가 없다」를 <b>갈라서</b> 처리해야
     * 하고(앞은 재배정, 뒤는 거부), 여기서 던지면 그 구분이 사라진다.
     */
    public Optional<LsAiSrvr> selectPinned(LsAiSrvr.SrvrType srvrType, String srvrId) {
        if (srvrType == null || srvrId == null) {
            return Optional.empty();
        }
        return pinRetainable(srvrType).stream()
                .filter(node -> srvrId.equals(node.getSrvrId()))
                .findFirst();
    }

    /**
     * 그 유형의 <b>신규 배정을 받을 수 있는</b> 장비들 — {@link #select} 의 모집단.
     *
     * <p>상태 축은 {@link AiSrvrRegistry#findAvailable()} 이 소유한다. ⚠ <b>여기를 넓혀 정비중을 넣지
     * 말 것</b> — 정비 중인 장비가 새 영상을 계속 받아 정비가 끝나지 않는다. 고정 유지 축이 정비중을
     * 살려야 하는 것은 {@link #pinRetainable} 이 <b>별도 모집단</b>으로 해결한다.
     */
    private List<LsAiSrvr> candidates(LsAiSrvr.SrvrType srvrType) {
        return usableAmong(registry.findAvailable(), srvrType);
    }

    /**
     * 그 유형의 <b>이미 붙은 배정을 유지할 수 있는</b> 장비들 — {@link #selectPinned} 의 모집단.
     * [@design AC-1100]
     *
     * <p>모집단만 넓히고 <b>나머지 판정은 {@link #usableAmong} 으로 공유</b>한다. 여기서 형식·목적지·
     * 주소 정책을 다시 쓰면 그 사본이 두 번째 진실원이 되어, 선택기가 거른 장비를 고정 경로가 그대로
     * 쓰는 어긋남이 열린다(그것이 이 클래스에 얼굴을 하나 더 두는 이유다).
     */
    private List<LsAiSrvr> pinRetainable(LsAiSrvr.SrvrType srvrType) {
        return usableAmong(registry.findAll().stream()
                .filter(node -> node.getSrvrSttsCd() != null
                        && node.getSrvrSttsCd().retainsPinnedAssignment())
                .toList(), srvrType);
    }

    /** 모집단을 받아 <b>보낼 수 있는 장비</b>만 남긴다 — 상태 밖 판정의 단일 지점. */
    private List<LsAiSrvr> usableAmong(List<LsAiSrvr> pool, LsAiSrvr.SrvrType srvrType) {
        return pool.stream()
                .filter(node -> node.getSrvrTypeCd() == srvrType)
                .filter(this::wellFormedSrvrId)
                .filter(this::pinnable)
                .filter(node -> acceptedByEndpointPolicy(srvrType, node))
                .toList();
    }

    /**
     * 한 번의 선택 — <b>원장 스냅샷을 그때그때 다시 걸러 낸다</b>. [@design ADR-062]
     *
     * <p>★ 형식 판정을 <b>필드에 담지 않는 것</b>이 이 메서드의 핵심 제약이다. 원장은 운영 화면에서
     * 런타임에 바뀌므로, 한 번 낸 판정을 들고 있으면 뒤에 등록한 정상 장비가 영영 제외되거나 이미
     * 고친 장비가 계속 제외된다. 걸러 내는 순서는 <b>형식 → 목적지 가능 → 주소 정책</b>이며, 형식이
     * 맨 앞인 이유는 그 위반이 <b>가장 시끄럽게 알려야 할 것</b>(원장이 체크 제약을 우회해 들어왔다는
     * 뜻)이라 뒤 필터에 가려지지 않게 하기 위해서다.
     *
     * <p>⚠ <b>구 근거 폐기(2026-09-07)</b> — 여기 「형식이 맨 앞인 이유는 그 위반만이 「거부」로
     * 격상되는 축이라 몇 건이 그것 때문에 빠졌는지 세어야 하기 때문」이라고 적혀 있었다. 이제 거부는
     * <b>사유를 가리지 않으므로</b> 세지 않는다. 그 세는 코드를 되살리지 말 것 — 그것이 상태점검으로
     * 전부 이용불가가 된 원장을 폴백으로 흘려보내던 자리다. [@design AC-1093]
     */
    private Outcome choose(LsAiSrvr.SrvrType srvrType, AiSrvrUsageType usage) {
        List<LsAiSrvr> candidates = candidates(srvrType);
        if (candidates.isEmpty()) {
            // ★사유를 가리지 않는다 — 형식 위반이 걸러졌는지 세지 않고, 그 유형의 행이 0건이어도 같다.
            //  여기서 empty 를 돌려주면 호출자가 배포 기본 주소로 폴백해 실제 장애가 감춰진다.
            return Outcome.rejection();
        }
        Map<String, Integer> loads = loadsOf(srvrType, usage, candidates);
        return Outcome.of(candidates.stream()
                // 동률은 식별자 순 — 같은 상황에서 같은 답이 나와야 원인을 되짚을 수 있다.
                .min(Comparator.<LsAiSrvr>comparingInt(node -> loads.getOrDefault(node.getSrvrId(), 0))
                        .thenComparing(LsAiSrvr::getSrvrId)));
    }

    /**
     * 선택 결과 — 「골랐다」·「못 골랐다」·「거부한다」 <b>셋</b>을 구분한다.
     *
     * <p>「골랐다」와 「거부한다」를 {@link Optional} 하나로 뭉개면 호출자가 둘을 같은 폴백으로 다룬다.
     * <b>「못 골랐다」는 이 안에 없다</b> — 후보가 0이면 사유를 가리지 않고 거부이기 때문이다
     * ([@design AC-1093]). {@code empty} 가 남아 있는 유일한 자리는 {@code select} 의 <b>조회 실패
     * catch</b>(원장을 못 읽었다)와 축이 {@code null} 인 경우뿐이며, 그것은 이 레코드 밖의 축이다.
     *
     * <p>⚠ 「후보 0」을 위해 {@code none()} 을 되살리지 말 것 — 그것이 실제 장애를 감추던 경로다.
     */
    private record Outcome(Optional<LsAiSrvr> chosen, boolean rejected) {

        static Outcome of(Optional<LsAiSrvr> chosen) {
            return new Outcome(chosen, false);
        }

        /**
         * 쓸 수 있는 장비가 하나도 없다 — 폴백 없이 위탁을 거부한다.
         *
         * <p>⚠ 이름을 컴포넌트({@code rejected})와 <b>같게 두지 말 것</b> — static 팩토리가 레코드
         * 접근자를 가려 {@code outcome.rejected()} 가 {@code boolean} 이 아니라 {@code Outcome} 을
         * 돌려주고, 그 자리에서 컴파일이 깨진다(실제로 한 번 겪었다).
         */
        static Outcome rejection() {
            return new Outcome(Optional.empty(), true);
        }
    }

    /**
     * 이 장비의 식별자가 <b>형식 규약</b>을 지키는가 — 어긴 행은 후보에서 뺀다. [@design ADR-062]
     *
     * <p>판정은 {@link AiSrvrIdPolicy#isValid} <b>하나에 위임</b>한다(DB 체크 제약·입력 DTO·기동
     * 기록이 쓰는 것과 같은 원천). 여기서 정규식을 다시 쓰면 그 사본이 두 번째 진실원이 되어 한쪽만
     * 통과하는 값이 조용히 생긴다.
     *
     * <p>왜 여기서 빼는가: 이 값은 서킷브레이커 이름과 메트릭 라벨로 <b>조립</b>되므로, 형식을 어긴
     * 장비를 고르면 그 순간부터 라벨이 조용히 어긋나고 <b>한참 뒤 대시보드에서야</b> 드러난다.
     *
     * <p>기록은 ERROR 다 — 이 상태는 DB 체크 제약을 <b>우회해 들어온 행</b>(운영 SQL · 제약 도입 이전
     * 데이터)이라는 뜻이고, 기동 시점 기록과 같은 수준으로 남아야 한 사건으로 이어 읽힌다. 매 호출
     * 시끄럽지 않도록 장비당 1회만 출력하며, 식별자는 <b>서버 기록에만</b> 남는다(CWE-117 sanitize).
     */
    private boolean wellFormedSrvrId(LsAiSrvr node) {
        if (AiSrvrIdPolicy.isValid(node.getSrvrId())) {
            return true;
        }
        if (malformedIdWarned.size() < UNPINNABLE_WARN_CAP
                && malformedIdWarned.add(String.valueOf(node.getSrvrId()))) {
            log.error("[AiSrvr] 식별자가 형식({})을 위반해 후보에서 제외합니다 srvrId={}"
                            + " — 이 값은 서킷브레이커 이름·메트릭 라벨로 조립되므로 그대로 두면"
                            + " 라벨이 조용히 어긋납니다. 원장의 SRVR_ID 를 고치세요.",
                    AiSrvrIdPolicy.SRVR_ID_REGEX, VlmClient.safeForLog(node.getSrvrId()));
        }
        return false;
    }

    /**
     * 위탁 거부 — <b>쓸 수 있는 후보가 하나도 없다</b>. 사유를 가리지 않는다.
     * [@design ADR-062] [@design AC-1074] [@design AC-1093]
     *
     * <p>{@link NonRetryableExternalException} 을 쓰는 이유는 <b>「이 요청만으로는 풀리지 않는 결정적
     * 실패」를 뜻하는 표식</b>이기 때문이다. 새 실패 경로를 만들지 않으므로 기존 확정 실패 기록으로
     * 그대로 흐른다.
     *
     * <p>⚠ <b>사유는 메시지로 가르지 않는다</b> — 어느 연동인지와 사유 분류만 싣는다. 형식 위반인지
     * 상태점검 실패인지는 <b>서버 기록</b>에서 갈린다(형식 위반은 {@code wellFormedSrvrId} 가 ERROR 로,
     * 상태점검 전부 이용불가는 상태점검 쪽이 「그 유형의 가용이 0이 되었다」로 남긴다).
     *
     * <p>⚠ <b>그 타입이 재시도를 막아 주지는 않는다 — 아래 §대가를 반드시 함께 읽을 것.</b>
     *
     * <p>메시지에는 <b>어느 연동인지와 사유 분류만</b> 싣는다 — 식별자 원문·주소·형식 규칙은 서버
     * 기록에만 남긴다(CWE-209/497). 연동 이름은 {@link IntegrationEndpoint} 가 소유한 표기를 그대로
     * 쓴다(문구를 여기서 다시 쓰면 같은 연동이 자리마다 다른 이름으로 불린다).
     *
     * <h3>⚠ 인지하고 수용한 대가 — <b>보류가 아니라 실패</b>다</h3>
     * <p>시계열 위탁 스텝은 정책적 차단을 예외가 아니라 <b>보류</b>(건너뜀 + 사유 적재)로 다루는
     * 규약을 갖는다. 그런데 이 거부는 예외라, 그 회차의 배치가 <b>건너뜀이 아니라 실패로 마감</b>된다.
     * 그러면 「보류를 근거로 되짚어 다시 위탁하는 재개 러너」가 그 영상을 <b>집지 않는다</b> —
     * 원장을 고친 뒤 사람이 다시 돌려야 한다.
     *
     * <p><b>그 대가를 알고 골랐다.</b> 이유는 둘이다 — ①이 거부는 「지금은 안 되지만 곧 풀린다」가
     * 아니라 <b>운영 데이터를 고쳐야 풀리는 상태</b>라, 자동 재개 대상으로 두면 고쳐지지 않는 조건에
     * 재시도가 쌓인다 ②보류로 낮추려면 위탁 스텝 쪽 배선이 필요한데 그것은 이 결정의 범위 밖이다.
     * 같은 성질의 대가(확정 실패 뒤 자동 재개가 없어 사람이 다시 돌려야 하는 축)를 이 저장소가
     * 이미 인지·수용한 선례가 있다.
     *
     * <h3>★ 해소 경로는 사유마다 다르다 — 뭉뚱그리면 멀쩡한 원장을 들여다본다 [@design AC-1094]</h3>
     * <ul>
     *   <li><b>원장 식별자 형식 위반</b> — 사람이 원장을 고쳐야 풀린다. 저절로 풀리는 길이 없다.</li>
     *   <li><b>상태점검 실패로 그 유형이 전부 이용불가</b> — <b>원장은 그대로 두고 장비를 살린다.</b>
     *       연속 성공이 복귀 임계에 닿으면 그 장비가 가용으로 돌아오고 이 거부도 <b>저절로</b> 풀린다.</li>
     * </ul>
     * <p>운영자가 할 조치가 정반대이므로 합쳐 적지 않는다.
     *
     * <h3>⚠⚠ 대가 둘 — <b>자동 재시도를 상한까지 소진한다</b> (2026-09-03 실측 정정)</h3>
     * <p>★<b>구 서술 폐기</b>: 여기 「{@link NonRetryableExternalException} 은 {@code ignore-exceptions}
     * 에 등록돼 있어 재시도·서킷 집계를 오염시키지 않는다」고 적혀 있었다. <b>근거가 성립하지 않는다.</b>
     * 그 등록은 <b>연동 호출을 감싸는 데코레이터</b>에만 걸리는데, 이 거부는 <b>장비를 고르는 자리</b>에서
     * 나므로 그 데코레이터 <b>안이 아니다</b>(이 클래스에 재시도·서킷 애너테이션이 하나도 없다).
     * 즉 그 등록은 이 경로와 <b>무관</b>하다 — 결론이 우연히 참으로 보였을 뿐이다.
     *
     * <p>실제로 걸리는 재시도는 <b>배치 재시도 큐</b>다. 오케스트레이터가 이 예외를 잡아 실패로 마감하며
     * 큐에 등록하는데, <b>그 등록 판정은 예외 종류를 보지 않는다</b>(등록 메서드가 예외를 인자로 받지도
     * 않는다). 영구 실패로 빠지는 지름길도 특정 내부 예외형에만 열려 있어 이 예외는 해당하지 않는다.
     * ⇒ 이 거부는 <b>설정 상한(기본 3회)까지 자동 재실행된 뒤 소진</b>되어 끝난다 — 매번 같은 자리에서
     * 같은 이유로 실패하면서.
     *
     * <p><b>그럼에도 동작을 바꾸지 않는다.</b> 소진은 유한하고 시끄러우며, 여기서 폴백으로 낮추면
     * fail-closed 축이 무너진다(잘못된 식별자로 위탁이 나간다). 재시도를 실제로 끊으려면 배치 축의
     * 실패 분류에 손대야 하고 그것은 이 결정의 범위 밖이다.
     *
     * <p>결론은 그대로다 — <b>자동 재시도 자체는 어느 사유에서도 상황을 해소하지 못한다.</b> 큐가 다시
     * 부르는 것은 같은 요청이고 거부를 낳은 조건은 그대로이기 때문이다. 풀리는 길은 위 §해소 경로다.
     *
     * <p>던지는 지점이 <b>선커밋 이전</b>이라 상관키만 남은 고아 원장 행이 생기지 않는다는 것은
     * <b>그대로 유효</b>하다(이 사실은 실측으로 확인됐다).
     */
    private NonRetryableExternalException noUsableServer(LsAiSrvr.SrvrType srvrType) {
        log.error("[AiSrvr] 쓸 수 있는 후보가 하나도 없어 위탁을 거부합니다 — 사유는 형식 위반·목적지 불가"
                        + "·주소 정책 탈락·상태점검 실패로 전부 이용불가·그 유형의 행 0건 중 하나이며,"
                        + " 어느 쪽인지는 바로 앞의 제외 기록으로 갈립니다."
                        + " ⚠자동 재시도는 상한까지 같은 이유로 실패한 뒤 소진됩니다"
                        + "(형식 위반이면 사람이 원장을 고쳐야 하고, 상태점검 실패면 장비 복구로 저절로 풀립니다)."
                        + " 배포 기본 주소로 폴백하지 않습니다. type={} 식별자형식={}",
                srvrType, AiSrvrIdPolicy.SRVR_ID_REGEX);
        return new NonRetryableExternalException(
                endpointOf(srvrType).displayName() + " 위탁에 쓸 수 있는 장비가 없어 요청을 보내지 않았습니다.");
    }

    /** 장비 축 → 운영자에게 보여줄 연동 이름. 표기의 소유자는 {@link IntegrationEndpoint} 다. */
    private static IntegrationEndpoint endpointOf(LsAiSrvr.SrvrType srvrType) {
        return srvrType == LsAiSrvr.SrvrType.TIMESERIES
                ? IntegrationEndpoint.VLM
                : IntegrationEndpoint.AI_SERVER;
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
