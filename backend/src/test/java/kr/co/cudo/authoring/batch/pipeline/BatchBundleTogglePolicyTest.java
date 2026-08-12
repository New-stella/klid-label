package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 묶음 → stage 토글 환산 단위 테스트. [@design API-201]
 *
 * <p>이 변환이 「어느 단계를 돌릴지」의 유일한 해석 지점이다. 여기서 어긋나면 오케스트레이터는 아무것도
 * 눈치채지 못하고 <b>묶음 밖 단계를 실행</b>한다 — 그중 트랙 보간은 사람이 손댄 라벨을 복구 지점 없이
 * 지운다.
 */
class BatchBundleTogglePolicyTest {

    /** 프로덕션과 같은 순서의 파이프라인(스텝 본체는 필요 없다 — 이 정책은 stage() 만 읽는다). */
    private static BatchBundleTogglePolicy policy() {
        return new BatchBundleTogglePolicy(new BatchPipeline(List.of(
                stubStep(BatchStage.MARKING),
                stubStep(BatchStage.VLM),
                stubStep(BatchStage.FRAME_EXTRACT),
                stubStep(BatchStage.YOLO),
                stubStep(BatchStage.SAM2),
                stubStep(BatchStage.INTERPOLATE))));
    }

    private static BatchStep stubStep(BatchStage stage) {
        return new BatchStep() {
            @Override
            public BatchStage stage() {
                return stage;
            }

            @Override
            public void execute(BatchContext ctx) {
                // 이 테스트는 토글 산출만 본다 — 실행 본체는 필요 없다.
            }
        };
    }

    @Test
    @DisplayName("★시계열_묶음은_VLM만_켜고_나머지는_전부_끈다_보간_포함")
    void vlmBundleEnablesOnlyVlm() {
        // 시계열 재수행이 보간을 돌리면 사람이 손댄 보간 라벨이 전량 삭제·재생성된다.
        Map<String, Boolean> toggles = policy().togglesFor(BatchStageBundle.VLM);

        assertThat(toggles.get(BatchStage.VLM.name())).isTrue();
        assertThat(toggles.get(BatchStage.FRAME_EXTRACT.name())).isFalse();
        assertThat(toggles.get(BatchStage.YOLO.name())).isFalse();
        assertThat(toggles.get(BatchStage.SAM2.name())).isFalse();
        assertThat(toggles.get(BatchStage.INTERPOLATE.name())).isFalse();
    }

    @Test
    @DisplayName("★★오토라벨_묶음은_AI탐지_AI분할_보간을_한꺼번에_켠다_쪼개지_않는다")
    void autolabelBundleEnablesAllThreeMembers() {
        // 이 단정이 무너지면 「오토라벨 재수행」이 일부만 돌아 산출물끼리 어긋난다(옛 분할·옛 보간 잔존).
        Map<String, Boolean> toggles = policy().togglesFor(BatchStageBundle.AUTOLABEL);

        assertThat(toggles.get(BatchStage.YOLO.name())).isTrue();
        assertThat(toggles.get(BatchStage.SAM2.name())).isTrue();
        assertThat(toggles.get(BatchStage.INTERPOLATE.name())).isTrue();
        // 다른 묶음·전제 단계는 건드리지 않는다.
        assertThat(toggles.get(BatchStage.VLM.name())).isFalse();
        assertThat(toggles.get(BatchStage.FRAME_EXTRACT.name())).isFalse();
    }

    @Test
    @DisplayName("★MARKING_은_어떤_재수행에서도_토글에_담기지_않는다_컨텍스트_적재기")
    void markingIsNeverToggled() {
        // 마킹 로드는 작업 단계가 아니라 컨텍스트 적재기다. 끄면 VLM 은 마킹 없는 위탁을 보내고
        //   FRAME_EXTRACT 는 "마킹 데이터가 없습니다"로 죽는다.
        assertThat(policy().togglesFor(BatchStageBundle.VLM))
                .doesNotContainKey(BatchStage.MARKING.name());
        assertThat(policy().togglesFor(BatchStageBundle.AUTOLABEL))
                .doesNotContainKey(BatchStage.MARKING.name());
    }

    @Test
    @DisplayName("★묶음이_null_이면_fail_closed_전_단계가_disabled_다")
    void nullBundleDisablesEverything() {
        // 정상 경로에서는 입구 검증이 걸러 도달하지 않는다. 도달하더라도 파이프라인이 통째로 도는
        //   것보다 아무것도 돌지 않는 편이 안전하다.
        assertThat(policy().togglesFor(null).values()).containsOnly(false);
    }

    @Test
    @DisplayName("★파이프라인에_단계가_추가되면_자동으로_off_로_들어온다_재수행에_딸려_돌지_않는다")
    void newPipelineStageDefaultsToDisabled() {
        // 토글 대상 목록의 진실원이 파이프라인이라, 어느 묶음에도 넣지 않은 신규 단계는 off 다.
        //   목록을 정책에 상수로 적어 뒀다면 새 단계가 토글 맵에서 빠져 "토글 없음 = enabled" 로
        //   해석돼 재수행에 조용히 딸려 돌았을 것이다.
        BatchBundleTogglePolicy withNewStage = new BatchBundleTogglePolicy(new BatchPipeline(List.of(
                stubStep(BatchStage.MARKING),
                stubStep(BatchStage.VLM),
                stubStep(BatchStage.YOLO),
                stubStep(BatchStage.SAM2),
                stubStep(BatchStage.INTERPOLATE),
                stubStep(BatchStage.DEIDENTIFY)))); // 어느 묶음에도 속하지 않는 신규 단계 대역

        Map<String, Boolean> toggles = withNewStage.togglesFor(BatchStageBundle.AUTOLABEL);

        assertThat(toggles).containsKey(BatchStage.DEIDENTIFY.name());
        assertThat(toggles.get(BatchStage.DEIDENTIFY.name())).isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 묶음 정의 자체 — 여기가 단일 지점이다
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★오토라벨_묶음의_구성원은_AI탐지_AI분할_보간_셋이다")
    void autolabelMembership() {
        assertThat(BatchStageBundle.AUTOLABEL.stages())
                .containsExactly(BatchStage.YOLO, BatchStage.SAM2, BatchStage.INTERPOLATE);
        assertThat(BatchStageBundle.VLM.stages()).containsExactly(BatchStage.VLM);
    }

    @Test
    @DisplayName("★★보간은_반드시_어떤_묶음_안에_있어야_한다_밖에_있으면_무조건_돈다")
    void interpolationMustBelongToABundle() {
        // 이 저장소의 반복 사고의 뿌리 — 보간이 단위 밖이면 어떤 재수행에서도 무조건 돌아
        //   사람이 손댄 보간 라벨을 복구 지점 없이 지운다.
        assertThat(BatchStageBundle.containing(BatchStage.INTERPOLATE))
                .contains(BatchStageBundle.AUTOLABEL);
    }

    @Test
    @DisplayName("★전제_단계는_어느_묶음에도_속하지_않는다_건너뛸_수_없다")
    void prerequisiteStagesBelongToNoBundle() {
        // 비식별·프레임 추출·마킹 산출물은 뒤 작업과 데이터마트 산출의 전제라, 건너뛴 채 완료되면
        //   산출물이 조용히 빈다.
        assertThat(BatchStageBundle.containing(BatchStage.MARKING)).isEmpty();
        assertThat(BatchStageBundle.containing(BatchStage.FRAME_EXTRACT)).isEmpty();
        assertThat(BatchStageBundle.containing(BatchStage.DEIDENTIFY)).isEmpty();
        assertThat(BatchStageBundle.containing(null)).isEmpty();
    }

    @Test
    @DisplayName("묶음_문자열_해석은_대소문자를_무시하고_미지값은_null_이다")
    void bundleParsing() {
        assertThat(BatchStageBundle.parse("vlm")).isEqualTo(BatchStageBundle.VLM);
        assertThat(BatchStageBundle.parse(" AUTOLABEL ")).isEqualTo(BatchStageBundle.AUTOLABEL);
        // 구 단위(개별 단계)로 부르던 값은 더 이상 수락하지 않는다 — 묶음 축으로 반전됐다.
        assertThat(BatchStageBundle.parse("YOLO")).isNull();
        assertThat(BatchStageBundle.parse("SAM2")).isNull();
        assertThat(BatchStageBundle.parse("INTERPOLATE")).isNull();
        assertThat(BatchStageBundle.parse("")).isNull();
        assertThat(BatchStageBundle.parse(null)).isNull();
    }
}
