package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지목 재수행의 <b>작업 묶음 → stage 토글</b> 변환 — 판정 단일 지점. [@design API-201]
 *
 * <h3>왜 새 축을 만들지 않았나</h3>
 * <p>오케스트레이터에 "이번 실행에서 어느 단계를 돌릴지"를 알리는 축은 <b>이미 있다</b> —
 * {@link BatchContext} 의 stage 토글이다. 묶음을 그 토글로 환산하면 오케스트레이터 루프도, 각 단계의
 * {@code isEnabled} 규약도 그대로 쓰인다. 두 번째 축을 만들면 "어느 것이 이겼나"를 판정하는 규칙이
 * 새로 필요해지고 그 규칙이 두 벌이 된다.
 *
 * <h3>★범위를 고르지 않는다 — 묶음이 곧 범위다 (구 {@code scope} 축 폐기)</h3>
 * <p>구 동작은 요청이 「그 단계만(ONLY) / 그 단계부터 끝까지(FROM)」를 골랐다. 그 축은 <b>단위가 개별
 * 단계였기 때문에</b> 필요했던 것이다 — AI 탐지만 다시 돌리면 뒤의 분할·보간이 옛 산출물로 남아
 * 어긋나므로 사용자가 "끝까지"를 골라야 했고, 반대로 "끝까지"를 고르면 관계없는 보간까지 돌아 사람이
 * 손댄 라벨이 지워졌다. 단위를 묶음으로 바꾸면 <b>그 선택 자체가 사라진다</b>: 건너뛰기를 해제한 묶음의 구성원만
 * 켜고 나머지는 전부 끈다. 오토라벨을 재수행하면 보간이 함께 다시 만들어지는데, 그것은 「오토라벨을
 * 통째로 다시 만든다」의 <b>예상되는 결과</b>다.
 *
 * <h3>토글 대상 목록의 진실원은 파이프라인 정의 하나다</h3>
 * <p>어느 단계들이 존재하는지를 여기에 다시 적으면 {@link BatchPipelineConfig} 와 두 벌이 되어 단계를
 * 추가·재배치할 때 조용히 갈린다. 그래서 주입받은 <b>실제 파이프라인</b>({@code postMarkingPipeline})을
 * 순회한다 — 파이프라인에 단계가 추가되면 그 단계는 <b>자동으로 off</b> 로 들어온다(어느 묶음에도 속하지
 * 않으므로). 새 단계가 재수행에 조용히 딸려 도는 일이 구조적으로 없다(fail-closed).
 *
 * <p>묶음의 <b>구성원</b>은 {@link BatchStageBundle} 이 단독으로 정한다. 이 클래스는 그 소속 판정을
 * 재유도하지 않고 그대로 묻기만 한다.
 *
 * <h3>★MARKING 은 어떤 재수행에서도 끄지 않는다</h3>
 * <p>{@link MarkingLoadStep} 은 작업을 수행하는 단계가 아니라 <b>컨텍스트 적재기</b>다(마킹 로드 +
 * marks 파싱, 쓰기 없음). VLM 은 마킹 엔티티를, FRAME_EXTRACT 는 파싱된 marks 를 이 단계에서 받으므로,
 * 끄면 그 두 단계가 "마킹 데이터가 없습니다"로 죽거나 마킹 없는 위탁을 보낸다. 즉 이 단계를 켜 두는
 * 것은 범위를 넓히는 것이 아니라 <b>범위가 성립하기 위한 전제</b>다.
 *
 * <h3>안전 전제 (Critical)</h3>
 * <p>이 변환기는 <b>이미 검증된 대상 묶음</b>만 받는다는 전제 위에 있다. "그 영상에서 실제로 건너뛰기를 해제한
 * 묶음인가" 판정은 <b>입구</b>({@code BatchStageRerunService})가 끝낸다. 여기서는 묶음을 토글로 옮기기만
 * 한다.
 */
@Component
public class BatchBundleTogglePolicy {

    private final BatchPipeline pipeline;

    public BatchBundleTogglePolicy(@Qualifier("postMarkingPipeline") BatchPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 작업 묶음을 stage 토글 맵으로 환산한다.
     *
     * @param bundle 재수행할 작업 묶음(입구에서 이미 "건너뛰기를 해제한 묶음"으로 검증된 값)
     * @return {@link BatchStage#name()} → enabled. 오케스트레이터가 {@link BatchContext} 로 실어 나른다
     */
    public Map<String, Boolean> togglesFor(BatchStageBundle bundle) {
        List<BatchStep> steps = pipeline.steps();
        Map<String, Boolean> toggles = new LinkedHashMap<>();
        for (BatchStep step : steps) {
            BatchStage stage = step.stage();
            if (stage == BatchStage.MARKING) {
                continue; // 컨텍스트 적재기 — 어떤 재수행에서도 끄지 않는다(위 Javadoc).
            }
            // 묶음이 null 이면 전 단계 disabled — fail-closed. 알 수 없는 대상 때문에 파이프라인이
            //   통째로 도는 것보다 아무것도 돌지 않는 편이 안전하다(정상 경로는 입구 검증이 거른다).
            toggles.put(stage.name(), bundle != null && bundle.contains(stage));
        }
        return Map.copyOf(toggles);
    }
}
