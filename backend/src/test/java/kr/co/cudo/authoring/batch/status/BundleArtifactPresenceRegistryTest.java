package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 묶음 → 산출물 판정기 매핑({@link BundleArtifactPresenceRegistry})의 계약 검증.
 * [@design SCREEN-009] [@design AC-051]
 *
 * <p>여기서 지키는 것은 셋이다.
 * <ol>
 *   <li><b>매핑이 한 곳이다</b> — 호출부가 묶음별로 분기하지 않으므로, 묶음이 늘면 여기서만 드러난다.</li>
 *   <li><b>미배선은 「산출물 없음」이다</b> — 판정 불가를 "있음"으로 읽으면 조치가 필요한 영상이
 *       화면에서 조용히 사라진다. 모르면 감추지 않는다.</li>
 *   <li><b>한 묶음에 판정기가 둘이면 기동을 실패시킨다</b> — 조용한 승자 결정은 실행마다 답이
 *       흔들리고 그 흔들림이 화면 깜빡임으로만 드러난다.</li>
 * </ol>
 */
class BundleArtifactPresenceRegistryTest {

    private static final long RAW_SN = 7L;

    /** 테스트용 판정기 — 담당 묶음과 답을 고정한다. */
    private record FixedPresence(BatchStageBundle bundle, boolean answer) implements BundleArtifactPresence {
        @Override public boolean exists(Long rawSn) { return answer; }
    }

    @Test
    @DisplayName("등록된_판정기의_답을_그대로_돌려준다")
    void delegatesToRegisteredPresence() {
        BundleArtifactPresenceRegistry registry = new BundleArtifactPresenceRegistry(List.of(
                new FixedPresence(BatchStageBundle.VLM, true),
                new FixedPresence(BatchStageBundle.AUTOLABEL, false)));

        assertThat(registry.hasArtifact(RAW_SN, BatchStageBundle.VLM)).isTrue();
        assertThat(registry.hasArtifact(RAW_SN, BatchStageBundle.AUTOLABEL)).isFalse();
    }

    /**
     * ★fail-safe 방향 — 판정기가 없으면 "없음"이다. 반대로 두면(모르니 있다고 치기) 조치가 필요한
     * 영상이 배너에서 사라져 재수행 창구가 없어진다. 이 방향이 뒤집히면 손실이 비가역이다.
     */
    @Test
    @DisplayName("★판정기가_없는_묶음은_산출물_없음으로_본다_감추지_않는다")
    void unwiredBundleIsTreatedAsMissingArtifact() {
        BundleArtifactPresenceRegistry registry = new BundleArtifactPresenceRegistry(List.of(
                new FixedPresence(BatchStageBundle.VLM, true)));

        assertThat(registry.hasArtifact(RAW_SN, BatchStageBundle.AUTOLABEL)).isFalse();
        assertThat(registry.unwiredBundles()).containsExactly(BatchStageBundle.AUTOLABEL);
    }

    @Test
    @DisplayName("★한_묶음에_판정기가_둘이면_기동을_실패시킨다")
    void duplicateJudgeFailsFast() {
        assertThatThrownBy(() -> new BundleArtifactPresenceRegistry(List.of(
                new FixedPresence(BatchStageBundle.VLM, true),
                new FixedPresence(BatchStageBundle.VLM, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VLM");
    }

    @Test
    @DisplayName("rawSn이나_묶음이_null이면_산출물_없음이다_예외_아님")
    void nullInputsAreMissingArtifact() {
        BundleArtifactPresenceRegistry registry = new BundleArtifactPresenceRegistry(List.of(
                new FixedPresence(BatchStageBundle.VLM, true)));

        assertThat(registry.hasArtifact(null, BatchStageBundle.VLM)).isFalse();
        assertThat(registry.hasArtifact(RAW_SN, null)).isFalse();
    }

    /**
     * ⚠ <b>실제 스프링 배선은 여기서 확인하지 않는다</b> — 이 시험은 판정기를 손으로 넣어 주므로
     * {@code @Component} 누락을 구조적으로 잡지 못한다. "모든 묶음에 판정기가 붙어 있는가" 는
     * 실제 컨텍스트를 쓰는 {@code ClearedBundleActionIT} 가 본다. 여기서 흉내 내면 통과하는 시험이
     * 하나 늘 뿐 배선 누락은 그대로 통과한다.
     */
    @Test
    @DisplayName("등록된_묶음은_미배선_목록에_들어가지_않는다")
    void registeredBundlesAreNotReportedUnwired() {
        BundleArtifactPresenceRegistry registry = new BundleArtifactPresenceRegistry(List.of(
                new FixedPresence(BatchStageBundle.VLM, true),
                new FixedPresence(BatchStageBundle.AUTOLABEL, true)));

        assertThat(registry.unwiredBundles()).isEmpty();
    }
}
