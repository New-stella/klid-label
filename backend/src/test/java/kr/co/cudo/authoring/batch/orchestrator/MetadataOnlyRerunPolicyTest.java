package kr.co.cudo.authoring.batch.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「메타만 더하는 묶음」 allowlist 의 회귀 가드. [@design API-201]
 *
 * <h3>무엇을 막는가</h3>
 * <p>이 판정은 <b>승인 완료 영상에 배치를 돌려도 되는가</b>를 정한다. 잘못 넓히면 확정된 학습데이터의
 * 라벨이 재검수 없이 재생성되고, 잘못 좁히면 「벤더 연동이 늦어져 건너뛴 영상이 그대로 승인되더라도
 * 시계열을 나중에 받는다」가 성립하지 않는다.
 *
 * <p>특히 <b>fail-open 회귀</b>를 겨눈다 — 구 구현은 {@code bundle == AUTOLABEL} denylist 라, 묶음이
 * 하나 추가되면 아무도 손대지 않았는데 그 묶음이 자동으로 면제됐다.
 */
class MetadataOnlyRerunPolicyTest {

    @Test
    @DisplayName("★시계열만_면제_대상이고_오토라벨은_아니다")
    void onlyVlmIsMetadataOnly() {
        assertThat(MetadataOnlyRerunPolicy.isMetadataOnly(BatchStageBundle.VLM)).isTrue();
        assertThat(MetadataOnlyRerunPolicy.isMetadataOnly(BatchStageBundle.AUTOLABEL))
                .as("오토라벨은 라벨을 다시 만든다 — 승인 시점 스냅샷과 어긋난다")
                .isFalse();
    }

    @Test
    @DisplayName("null_묶음은_면제되지_않는다_failclosed")
    void nullBundleIsNotExempt() {
        assertThat(MetadataOnlyRerunPolicy.isMetadataOnly(null)).isFalse();
    }

    /**
     * ★★<b>fail-open 회귀 차단</b> — allowlist 로 유지돼야 새 묶음이 자동으로 면제되지 않는다.
     *
     * <p>묶음이 추가됐는데 이 테스트가 <b>깨지지 않는다면</b> 그 묶음은 이미 면제된 것이다(=denylist 로
     * 되돌아갔다는 신호). 명시 등록 없이 통과하는 묶음이 하나라도 있으면 실패한다.
     */
    @Test
    @DisplayName("★★명시_등록하지_않은_묶음은_어느_것도_면제되지_않는다_denylist_회귀차단")
    void unregisteredBundlesAreNeverExempt() {
        Set<BatchStageBundle> registered = Set.of(BatchStageBundle.VLM);
        for (BatchStageBundle bundle : BatchStageBundle.values()) {
            if (registered.contains(bundle)) {
                continue;
            }
            assertThat(MetadataOnlyRerunPolicy.isMetadataOnly(bundle))
                    .as("%s 가 명시 등록 없이 면제됐다 — allowlist 가 denylist 로 되돌아갔다", bundle)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("허용_단계_집합은_면제_묶음의_구성원_합집합이다")
    void allowedStagesIsUnionOfMemberStages() {
        assertThat(MetadataOnlyRerunPolicy.allowedStages())
                .containsExactlyInAnyOrderElementsOf(BatchStageBundle.VLM.stages());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 실행 범위 fail-closed
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시계열_단계만_도는_실행은_면제_범위다")
    void vlmOnlyScopeIsMetadataOnly() {
        assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(List.of(BatchStage.VLM))).isTrue();
    }

    @Test
    @DisplayName("★마킹_적재기는_함께_돌아도_면제_범위를_깨지_않는다")
    void markingLoaderDoesNotBreakScope() {
        // MARKING 은 컨텍스트 적재기(쓰기 없음)이고 어떤 재수행에서도 꺼지지 않는다 — 이걸 거부하면
        //   정상 시계열 재수행이 통째로 막힌다.
        assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(
                List.of(BatchStage.MARKING, BatchStage.VLM))).isTrue();
    }

    @Test
    @DisplayName("★★오토라벨_단계가_하나라도_섞이면_면제_범위가_아니다")
    void anyAutolabelStageBreaksScope() {
        for (BatchStage stage : BatchStageBundle.AUTOLABEL.stages()) {
            assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(List.of(BatchStage.VLM, stage)))
                    .as("%s 가 켜진 실행이 면제로 통과하면 승인 영상의 라벨이 재생성된다", stage)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("★프레임_추출처럼_묶음에_속하지_않는_단계도_면제_범위가_아니다")
    void nonBundleStageBreaksScope() {
        assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(
                List.of(BatchStage.VLM, BatchStage.FRAME_EXTRACT))).isFalse();
    }

    @Test
    @DisplayName("빈_목록과_null_은_면제_범위가_아니다_failclosed")
    void emptyOrNullScopeIsRejected() {
        assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(null)).isFalse();
        assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(List.of())).isFalse();
    }

    /**
     * ★판정 입력이 <b>토글 맵이 아니라 「돌 단계 목록」</b>이어야 하는 이유를 고정한다.
     *
     * <p>{@code BatchContext.isStageEnabled} 는 <b>키가 없으면 켜진 것으로</b> 읽는다. 즉 토글 맵에서
     * {@code true} 인 항목만 세는 구현으로 되돌리면, 「맵에 없어서 도는」 오토라벨 단계를 통째로 놓쳐
     * 면제가 그대로 통과한다. 이 테스트는 그 함정을 문서화한 <b>실증</b>이다.
     */
    @Test
    @DisplayName("★토글맵의_true_항목만_세는_구현으로_되돌리면_뚫린다_함정_실증")
    void toggleMapOnlyCheckWouldBeBypassed() {
        // 오토라벨 단계 키가 아예 없는 토글 맵 — 이 맵만 보면 "켜진 것은 VLM 뿐" 으로 보인다.
        Map<String, Boolean> deceptiveToggles = Map.of(BatchStage.VLM.name(), true);
        assertThat(deceptiveToggles.containsKey(BatchStage.YOLO.name()))
                .as("전제: 오토라벨 키가 맵에 없다")
                .isFalse();

        // 그러나 실제로 도는 단계에는 YOLO 가 포함된다(키 부재 = enabled).
        assertThat(MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(
                List.of(BatchStage.VLM, BatchStage.YOLO)))
                .as("실제 실행 목록으로 판정하면 걸러진다")
                .isFalse();
    }
}
