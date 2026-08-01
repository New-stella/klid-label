package kr.co.cudo.authoring.augment.service;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태조회 창 회전기 단위 검증 (DEV_FIX 2차 MED-1).
 *
 * <p>핵심 계약은 하나다 — <b>모든 원소가 유한한 호출 횟수 안에 창에 들어온다</b>. 구 구현
 * ({@code subList(0, 10)} 고정)은 11번째 이후 원소를 <b>영원히</b> 내보내지 않았다.
 */
class AugmentStatusWindowRotatorTest {

    private static final int WINDOW = 10;

    private static List<Integer> items(int size) {
        List<Integer> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(i);
        }
        return items;
    }

    @Test
    @DisplayName("창이_상한_이하면_전량을_조회하고_커서를_남기지_않는다")
    void doesNotTrackCursorWhenEverythingFitsInOneWindow() {
        AugmentStatusWindowRotator rotator = new AugmentStatusWindowRotator();

        assertThat(rotator.nextWindow(1L, items(WINDOW), WINDOW)).hasSize(WINDOW);

        // 정상 형상(청크 한 자릿수)에서는 추적 엔트리가 아예 생기지 않는다(메모리 최소화).
        assertThat(rotator.trackedCount()).isZero();
    }

    @Test
    @DisplayName("앞_창이_고정되지_않고_폴링마다_회전한다")
    void windowRotatesEveryCall() {
        AugmentStatusWindowRotator rotator = new AugmentStatusWindowRotator();
        List<Integer> pending = items(12);

        assertThat(rotator.nextWindow(1L, pending, WINDOW)).containsExactlyElementsOf(items(10));
        assertThat(rotator.nextWindow(1L, pending, WINDOW)).containsExactly(10, 11);
        // 한 바퀴 돌면 다시 앞 창 — 순회는 계속된다
        assertThat(rotator.nextWindow(1L, pending, WINDOW)).containsExactlyElementsOf(items(10));
    }

    /**
     * 보장 검증 — 비종결 {@code n} 개는 <b>최대 {@code ceil(n/w)} 회</b> 안에 전부 조회된다.
     * 구 구현에서는 index ≥ 10 이 어떤 횟수를 반복해도 조회되지 않았다.
     */
    @ParameterizedTest(name = "n={0}")
    @CsvSource({"11", "12", "20", "21", "37", "100"})
    @DisplayName("모든_원소가_유한한_폴링_안에_창에_포함된다")
    void everyElementIsCoveredWithinFiniteCalls(int size) {
        AugmentStatusWindowRotator rotator = new AugmentStatusWindowRotator();
        List<Integer> pending = items(size);
        int expectedWindows = (size + WINDOW - 1) / WINDOW;

        Set<Integer> seen = new HashSet<>();
        for (int poll = 0; poll < expectedWindows; poll++) {
            seen.addAll(rotator.nextWindow(7L, pending, WINDOW));
        }

        assertThat(seen).as("ceil(n/w) 회 안에 전부 조회돼야 한다").hasSize(size);
    }

    @Test
    @DisplayName("증강마다_커서가_독립이라_다른_증강의_폴링이_회전을_밀지_않는다")
    void cursorsAreIsolatedPerAugment() {
        AugmentStatusWindowRotator rotator = new AugmentStatusWindowRotator();
        List<Integer> pending = items(12);

        // 증강 A/B 폴링이 교대로 들어와도 각자의 회전이 유지된다(전역 카운터면 에일리어싱으로 굶는다).
        assertThat(rotator.nextWindow(1L, pending, WINDOW)).containsExactlyElementsOf(items(10));
        assertThat(rotator.nextWindow(2L, pending, WINDOW)).containsExactlyElementsOf(items(10));
        assertThat(rotator.nextWindow(1L, pending, WINDOW)).containsExactly(10, 11);
        assertThat(rotator.nextWindow(2L, pending, WINDOW)).containsExactly(10, 11);
    }

    @Test
    @DisplayName("추적_커서_수는_상한을_넘지_않는다")
    void trackedCursorsAreBounded() {
        AugmentStatusWindowRotator rotator = new AugmentStatusWindowRotator();
        List<Integer> pending = items(11);

        for (long augSn = 0; augSn < AugmentStatusWindowRotator.MAX_TRACKED_AUGMENTS * 2; augSn++) {
            rotator.nextWindow(augSn, pending, WINDOW);
        }

        // CWE-770 — 폴링이 만드는 맵이 무한히 자라면 안 된다.
        assertThat(rotator.trackedCount())
                .isLessThanOrEqualTo(AugmentStatusWindowRotator.MAX_TRACKED_AUGMENTS);
    }

    @Test
    @DisplayName("커서는_보존기간이_지나면_회수된다")
    void cursorsExpireAfterRetention() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        AugmentStatusWindowRotator rotator = new AugmentStatusWindowRotator(ticker);
        rotator.nextWindow(1L, items(11), WINDOW);
        assertThat(rotator.trackedCount()).isEqualTo(1);

        nanos.addAndGet(AugmentStatusWindowRotator.RETENTION.plus(Duration.ofMinutes(1)).toNanos());

        assertThat(rotator.trackedCount()).as("폴링이 끊긴 증강의 커서는 회수된다").isZero();
    }
}
