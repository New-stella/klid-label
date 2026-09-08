package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 최근 N회 관측의 <b>최대값</b>으로 부하를 평활한다. [@design ADR-057]
 *
 * <h3>왜 평균이 아니라 최대인가</h3>
 * <p>폴링은 몇 초에 한 번이라 표본이 성기다. 평균을 쓰면 <b>방금 밀리기 시작한 노드</b>가 한동안
 * 한가해 보여 그쪽으로 요청이 더 간다. 최대는 반대로 <b>이미 풀린 혼잡</b>을 잠깐 더 기억한다 —
 * 둘 중 틀렸을 때 손해가 작은 쪽을 고른 것이다. 과대평가는 요청을 옆 노드로 보낼 뿐이지만,
 * 과소평가는 <b>이미 밀린 노드에 요청을 더 얹는다</b>.
 *
 * <h3>평활 대상은 실효 부하(처리중 + 대기)다</h3>
 * <p>대기만 세면 이미 하나를 잡고 있는 노드를 한가한 노드와 구분하지 못한다.
 *
 * <h3>노드 x 용도 조합마다 창이 따로 있다</h3>
 * <p>두 용도는 장비 안에서 실행이 격리돼 있어, 표본을 합치면 일괄 처리가 밀린 장비를 화면 요청이
 * 피할 이유가 없는데도 피하게 된다.
 *
 * <p>⚠ <b>이 창은 WAS 프로세스의 메모리에 있다.</b> 원장 표에는 마지막 관측값(평활 전)이 담기고,
 * 평활값은 여기에만 있다. 2노드 Active-Active 에서는 폴링 틱이 두 노드로 나뉘므로 각 노드의 창은
 * <b>부분 표본</b>이며, 방금 기동한 노드는 창이 비어 있다. 그 경우 판정은 원장의 마지막 관측값으로
 * 떨어진다(선택기가 그 폴백을 갖는다 — Phase 3). 창을 공유하려면 표본 이력을 DB 에 쌓아야 하는데,
 * 보존 주기·정리 배치를 함께 정해야 하는 별개 결정이라 지금 하지 않는다.
 *
 * <p>⚠ <b>지금은 이 값을 읽는 곳이 없다.</b> 노드 선택은 Phase 3 이고, 이 단계에서는 창을 쌓아 두기만
 * 한다. 읽는 창구(표본이 없을 때 원장의 마지막 관측값으로 떨어지는 폴백 포함)는 그 선택기와 함께
 * 만든다 — 쓰는 쪽이 정해지기 전에 읽기 API 를 먼저 만들면 실제로 필요한 모양과 어긋난다.
 */
@Component
public class AiSrvrLoadSmoother {

    /** 창 하한 — 1 이면 평활이 꺼진 것과 같다(마지막 표본 그대로). */
    static final int MIN_WINDOW = 1;

    private final int window;

    /** 노드 x 용도 -> 최근 표본. 값 자체가 잠금 단위다(창이 짧아 경합 비용이 무시할 만하다). */
    private final Map<String, Deque<Integer>> samples = new ConcurrentHashMap<>();

    public AiSrvrLoadSmoother(
            @Value("${authoring.integration.ai-server.smoothing-window:3}") int window) {
        // ★0 이하를 그대로 쓰면 표본이 하나도 남지 않아 <부하가 항상 0으로 보인다> — 가장 위험한
        //   오설정이므로 기동을 막는 대신 하한으로 눌러 흡수한다(평활이 꺼질 뿐 판정은 살아 있다).
        this.window = Math.max(MIN_WINDOW, window);
    }

    /**
     * 새 표본을 넣고 창 안의 최대값을 돌려준다.
     *
     * @param effectiveLoad 실효 부하(처리중 + 대기). 대기 단독을 넣지 말 것
     */
    public int smooth(String srvrId, AiSrvrUsageType usage, int effectiveLoad) {
        Deque<Integer> window0 = samples.computeIfAbsent(key(srvrId, usage),
                ignored -> new ArrayDeque<>(window + 1));
        synchronized (window0) {
            // 음수 정규화는 AiSrvrSlotLoad 가 소유한다 — 여기서 Math.max 를 다시 적지 않는다.
            window0.addLast(AiSrvrSlotLoad.clampToZero(effectiveLoad));
            while (window0.size() > window) {
                window0.removeFirst();
            }
            return window0.stream().mapToInt(Integer::intValue).max().orElse(0);
        }
    }


    /**
     * 창 안의 최대값을 <b>표본을 넣지 않고</b> 읽는다 — 노드 선택기의 조회 창구.
     *
     * <p>{@link #smooth} 를 읽기에 재사용하면 관측하지도 않은 값이 표본으로 들어가 창이 오염된다
     * (선택할 때마다 직전 값이 한 번 더 쌓여 혼잡 기억이 실제보다 오래 남는다).
     *
     * <p>★ <b>표본이 없으면 {@code empty} 다 — 0 이 아니다.</b> 이 창은 WAS 프로세스의 메모리에 있고
     * 폴링 틱이 2노드로 나뉘므로 각 노드의 창은 <b>부분 표본</b>이며, 방금 기동한 노드는 비어 있다.
     * 그때 0을 돌려주면 <b>모르는 노드가 가장 한가한 노드가 되어</b> 요청을 전부 빨아들인다. 폴백
     * (원장의 마지막 관측값)은 호출자가 정한다 — 여기서는 「모른다」를 그대로 말한다.
     */
    public OptionalInt peek(String srvrId, AiSrvrUsageType usage) {
        if (srvrId == null || usage == null) {
            return OptionalInt.empty();
        }
        Deque<Integer> window0 = samples.get(key(srvrId, usage));
        if (window0 == null) {
            return OptionalInt.empty();
        }
        synchronized (window0) {
            return window0.stream().mapToInt(Integer::intValue).max();
        }
    }

    /**
     * 원장에 없는 노드의 표본을 버린다.
     *
     * <p>두 가지를 함께 막는다 — (1)지운 노드를 <b>같은 식별자로 다시 세웠을 때</b> 죽은 장비의 혼잡
     * 기억이 새 장비로 전이되는 것, (2)노드가 늘고 주는 운영에서 표본 맵이 무한히 자라는 것.
     */
    public void retainOnly(Set<String> liveSrvrIds) {
        samples.keySet().removeIf(key -> !liveSrvrIds.contains(srvrIdOf(key)));
    }

    private static String key(String srvrId, AiSrvrUsageType usage) {
        // ★구분자는 「/」 이고, 그 문자는 식별자 허용 집합(AiSrvrIdPolicy — 소문자·숫자·하이픈·밑줄)
        //   <밖>이라 충돌하지 않는다. 용도 이름(BATCH·INTERACTIVE)에도 없다. 되짚기는 뒤에서부터
        //   자르므로(srvrIdOf 의 lastIndexOf) 식별자에 하이픈·밑줄이 있어도 그대로 복원된다.
        //
        // ⚠구 서술 폐기(2026-09-08) — 여기 「식별자는 <소문자·숫자만> 허용되므로 구분자와 충돌하지
        //   않는다」고 적혀 있었다. 하이픈·밑줄이 허용되면서 <그 전제가 거짓>이 됐다. 결론(충돌하지
        //   않는다)은 실측으로 그대로 참이며 근거만 바뀌었다 — 판정 기준은 「어떤 문자만 되는가」가
        //   아니라 「구분자 문자가 허용 집합 밖인가」다. 지우지 않고 남기는 이유는 다음 사람이 낡은
        //   전제를 근거로 식별자 형식을 되좁히지 않게 하기 위해서다.
        return srvrId + "/" + usage.name();
    }

    private static String srvrIdOf(String key) {
        return key.substring(0, key.lastIndexOf('/'));
    }
}
