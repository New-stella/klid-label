package kr.co.cudo.authoring.aiserver.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 용도별 부하 행의 <b>실효 부하</b> 정의 검증. [@design ADR-057]
 *
 * <p>대기 건수만으로는 여유를 과소평가한다 — 용도마다 동시에 처리하는 건수가 하나여서, 대기가
 * 없더라도 이미 하나를 잡고 있으면 그 용도는 바쁘다.
 */
class LsAiSrvrUsgTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 4, 12, 33);

    @Test
    @DisplayName("실효부하는_처리중건수와_대기건수의_합이다")
    void 실효부하는_처리중건수와_대기건수의_합이다() {
        LsAiSrvrUsg usg = LsAiSrvrUsg.of("gpu01", AiSrvrUsageType.BATCH, NOW);

        usg.observe(1, 12, NOW);

        assertThat(usg.effectiveLoad()).isEqualTo(13);
    }

    @Test
    @DisplayName("대기가_0이어도_처리중이면_한가한_노드보다_뒤로_밀린다")
    void 대기가_0이어도_처리중이면_한가한_노드보다_뒤로_밀린다() {
        // given — 대기만 보면 둘 다 "없음"이라 요청의 절반이 바쁜 쪽으로 간다
        LsAiSrvrUsg busy = LsAiSrvrUsg.of("gpu01", AiSrvrUsageType.INTERACTIVE, NOW);
        busy.observe(1, 0, NOW);
        LsAiSrvrUsg idle = LsAiSrvrUsg.of("gpu02", AiSrvrUsageType.INTERACTIVE, NOW);
        idle.observe(0, 0, NOW);

        // when — 실효 부하 오름차순
        List<LsAiSrvrUsg> ordered = java.util.stream.Stream.of(busy, idle)
                .sorted(Comparator.comparingInt(LsAiSrvrUsg::effectiveLoad))
                .toList();

        // then — 한가한 노드가 앞에 온다
        assertThat(ordered).extracting(LsAiSrvrUsg::getSrvrId).containsExactly("gpu02", "gpu01");
    }

    @Test
    @DisplayName("새로_세운_행은_부하가_0이다")
    void 새로_세운_행은_부하가_0이다() {
        // 관측 전에는 아는 것이 없다 — 추측해 채우지 않는다
        assertThat(LsAiSrvrUsg.of("gpu01", AiSrvrUsageType.BATCH, NOW).effectiveLoad()).isZero();
    }

    @Test
    @DisplayName("음수_관측값은_0으로_눌러_담는다")
    void 음수_관측값은_0으로_눌러_담는다() {
        // 상대가 잠그지 않고 세는 근사값이라 순간 음수가 나올 수 있다. 음수 부하는 「가장 한가한 노드」가
        // 되어 요청을 빨아들이고, 컬럼 제약(0 이상)에도 걸려 갱신이 통째로 실패한다.
        LsAiSrvrUsg usg = LsAiSrvrUsg.of("gpu01", AiSrvrUsageType.BATCH, NOW);

        usg.observe(-1, -5, NOW);

        assertThat(usg.getPrcsNocs()).isZero();
        assertThat(usg.getWtngNocs()).isZero();
    }

    @Test
    @DisplayName("슬롯_이름은_대소문자와_공백을_가리지_않고_읽는다")
    void 슬롯_이름은_대소문자와_공백을_가리지_않고_읽는다() {
        assertThat(AiSrvrUsageType.fromSlotKey("batch")).contains(AiSrvrUsageType.BATCH);
        assertThat(AiSrvrUsageType.fromSlotKey(" INTERACTIVE ")).contains(AiSrvrUsageType.INTERACTIVE);
    }

    @Test
    @DisplayName("모르는_슬롯_이름은_비어있음으로_돌려준다")
    void 모르는_슬롯_이름은_비어있음으로_돌려준다() {
        // 예외로 만들면 상대가 슬롯을 하나 늘렸을 때 조회 전체가 실패한다
        assertThat(AiSrvrUsageType.fromSlotKey("training")).isEmpty();
        assertThat(AiSrvrUsageType.fromSlotKey(null)).isEmpty();
    }
}
