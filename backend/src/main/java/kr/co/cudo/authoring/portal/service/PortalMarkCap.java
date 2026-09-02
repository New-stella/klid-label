package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.service.MarkPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 포털 마킹 지점의 <b>추출 장수 상한 반영</b> — 순수 로직.
 *
 * <h3>넘으면 거부하지 않고 자른다</h3>
 * <p>긴 영상에 촘촘한 간격을 고르면 상한을 넘기 쉬운데, 통째로 거부하면 사용자는 몇으로 줄여야
 * 하는지 모른 채 시행착오를 해야 한다. 그래서 잘라서 받아들이고, 잘린 사실과 잘리기 전 수를 응답에
 * 함께 실어 <b>조용히 잘리지 않게</b> 한다.
 *
 * <h3>남기는 쪽이 방식마다 다르다 — 하나로 합치지 않는다</h3>
 * <ul>
 *   <li><b>자동</b>은 전 구간을 <b>고르게 다시 뽑는다</b>. 간격을 넓히는 것과 같으며, 자동의 목적이
 *       전 구간을 고른 간격으로 덮는 것이라 앞쪽만 남기면 그 목적이 깨진다.</li>
 *   <li><b>수동</b>은 <b>앞에서부터</b> 상한까지 남기고 뒤를 버린다. 사람이 고른 지점이라 어느 것이
 *       살아남는지 예측할 수 있어야 하는데, 고르게 솎으면 의도해서 찍은 지점이 임의로 빠진다.</li>
 * </ul>
 *
 * <p>두 방식 모두 <b>프레임 번호 오름차순</b>으로 정렬해 둔다 — 추출이 그 순서로 뽑고, 수동의
 * 「앞에서부터」가 요청 배열의 우연한 순서가 아니라 <b>영상 시간 순</b>이 되어 예측 가능해진다.
 *
 * <p>이 상한은 요청 본문의 지점 배열 원소 상한과 <b>다른 축</b>이다 — 그쪽은 과대 요청을 막는
 * 방어선이라 넘으면 거부하고, 이쪽은 실제로 뽑히는 장수의 상한이라 넘으면 자른다.
 *
 * @design API-240
 */
public final class PortalMarkCap {

    private PortalMarkCap() {
    }

    /** 자동 마킹 식별자 — 남기는 규칙을 가른다. */
    public static final String MODE_AUTO = "AUTO";

    /**
     * 상한을 반영한 지점 목록을 만든다.
     *
     * @param mode      마킹 방식(자동/수동)
     * @param marks     산출·요청된 지점
     * @param maxFrames 추출 장수 상한 — 0 이하면 1 로 본다(상한 없음으로 열지 않는다)
     */
    public static MarkPlan apply(String mode, List<MarkItem> marks, int maxFrames) {
        List<MarkItem> sorted = new ArrayList<>(marks);
        sorted.sort(Comparator.comparing(MarkItem::frameIndex,
                Comparator.nullsLast(Comparator.naturalOrder())));
        int cap = Math.max(1, maxFrames);
        int requested = sorted.size();
        if (requested <= cap) {
            return new MarkPlan(List.copyOf(sorted), requested);
        }
        List<MarkItem> kept;
        if (MODE_AUTO.equals(mode)) {
            // 전 구간 균등 재샘플링 — 첫 지점을 반드시 포함하고 끝까지 고르게 퍼진다.
            kept = new ArrayList<>(cap);
            for (int i = 0; i < cap; i++) {
                kept.add(sorted.get((int) ((long) i * requested / cap)));
            }
        } else {
            kept = new ArrayList<>(sorted.subList(0, cap));
        }
        return new MarkPlan(List.copyOf(kept), requested);
    }
}
