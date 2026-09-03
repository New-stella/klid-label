package kr.co.cudo.authoring.portal.marking;

import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.service.MarkPlan;
import kr.co.cudo.authoring.portal.service.PortalMarkCap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 마킹 지점의 추출 장수 상한 반영 — 순수 로직.
 *
 * <p>구 동작(업로드 직후 고정 간격 추출)이 갖고 있던 「상한 초과 시 균등 재샘플링」 규칙이 이제
 * <b>자동 마킹 저장 시점</b>으로 옮겨 왔다. 수동은 규칙이 다르다(앞에서부터 남긴다) — 두 방식을
 * 하나로 합치지 않는다.
 */
class PortalMarkCapTest {

    private static List<MarkItem> marks(int... frames) {
        List<MarkItem> list = new ArrayList<>();
        for (int f : frames) {
            list.add(new MarkItem(f, null));
        }
        return list;
    }

    private static List<MarkItem> series(int count, int step) {
        List<MarkItem> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MarkItem(i * step, null));
        }
        return list;
    }

    @Test
    @DisplayName("상한_이내면_자르지_않고_요청_수와_저장_수가_같다")
    void keepsAllWhenUnderCap() {
        MarkPlan plan = PortalMarkCap.apply("AUTO", series(10, 300), 2000);

        assertThat(plan.marks()).hasSize(10);
        assertThat(plan.requestedCount()).isEqualTo(10);
        assertThat(plan.truncated()).isFalse();
    }

    @Test
    @DisplayName("★자동은_전_구간을_고르게_다시_뽑는다 — 앞쪽만_남기지_않는다")
    void autoResamplesAcrossWholeRange() {
        MarkPlan plan = PortalMarkCap.apply("AUTO", series(1000, 30), 10);

        assertThat(plan.marks()).hasSize(10);
        assertThat(plan.requestedCount()).isEqualTo(1000);
        assertThat(plan.truncated()).isTrue();
        // 첫 지점을 포함하고, 마지막 지점이 앞쪽 구간에 머물지 않는다(= 고르게 퍼졌다).
        assertThat(plan.marks().get(0).frameIndex()).isZero();
        assertThat(plan.marks().get(9).frameIndex()).isGreaterThan(30 * 800);
    }

    @Test
    @DisplayName("★수동은_앞에서부터_남긴다 — 고르게_솎으면_의도한_지점이_임의로_빠진다")
    void manualKeepsHead() {
        MarkPlan plan = PortalMarkCap.apply("MANUAL", series(10, 100), 3);

        assertThat(plan.marks()).extracting(MarkItem::frameIndex).containsExactly(0, 100, 200);
        assertThat(plan.requestedCount()).isEqualTo(10);
        assertThat(plan.truncated()).isTrue();
    }

    @Test
    @DisplayName("★프레임_번호_오름차순으로_정규화한다 — 수동의_앞에서부터가_영상_시간_순이_된다")
    void sortsByFrameIndex() {
        MarkPlan plan = PortalMarkCap.apply("MANUAL", marks(900, 300, 0), 2);

        assertThat(plan.marks()).extracting(MarkItem::frameIndex).containsExactly(0, 300);
    }

    @Test
    @DisplayName("상한이_0_이하여도_상한_없음으로_열리지_않는다")
    void capNeverOpensUp() {
        MarkPlan plan = PortalMarkCap.apply("AUTO", series(5, 10), 0);

        assertThat(plan.marks()).hasSize(1);
        assertThat(plan.truncated()).isTrue();
    }
}
