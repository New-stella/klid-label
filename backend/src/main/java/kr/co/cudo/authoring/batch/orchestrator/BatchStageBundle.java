package kr.co.cudo.authoring.batch.orchestrator;

import java.util.List;
import java.util.Optional;

/**
 * 건너뛰기·되돌리기·재수행의 <b>단위</b> — 작업 묶음. [@design API-198] [@design API-200] [@design API-201]
 *
 * <h3>왜 개별 단계가 아니라 묶음인가 (Critical)</h3>
 * <p>오토라벨은 <b>AI 탐지 → AI 분할 → 트랙 보간</b>이 한 벌이다. 뒤 작업이 앞 결과를 입력으로 받고
 * 보간이 그 산출물을 재계산하므로, 쪼개서 일부만 수행하면 <b>산출물끼리 어긋난다</b>(탐지만 다시 만든
 * 프레임에 옛 분할·옛 보간이 남는다).
 *
 * <p>더 중요한 것은 <b>보간이 단위 밖에 있으면 안 된다</b>는 점이다. 보간은 건너뛰는 조건이 없어 어떤
 * 재수행에서도 무조건 돌고, {@code lblSrcCd='INTERPOLATE'} 라벨을 사람이 고쳤는지 보지 않고 전량 삭제한
 * 뒤 재생성한다 — 삭제 이력도 승인 스냅샷도 없어 복구 지점이 0 이다. 보간을 묶음 <b>안</b>으로 들여
 * 함께 켜지고 꺼지게 하면 그 파괴가 <b>구조적으로 소멸</b>한다. 오토라벨을 재수행할 때 보간이 다시
 * 계산되는 것은 「오토라벨을 통째로 다시 만든다」를 사용자가 고른 <b>예상되는 결과</b>다.
 *
 * <h3>여기가 묶음 정의의 단일 지점이다</h3>
 * <p>어느 단계가 어느 묶음에 속하는지는 <b>이 enum 하나</b>가 정한다. 오케스트레이터의 스킵 게이트,
 * 재수행의 실행 범위 산출, 화면에 내리는 스킵 목록이 모두 여기를 읽는다 — 두 벌이 되면 "건너뛰었다고
 * 표시되는데 실제로는 도는" 상태가 생긴다.
 *
 * <p>단계 <b>순서</b>는 여기에 적지 않는다({@code BatchPipelineConfig} 가 진실원이다). 이 enum 이 정하는
 * 것은 <b>구성원</b>뿐이며, 실행 범위(토글 맵) 산출은 실제 파이프라인을 읽는
 * {@code BatchBundleTogglePolicy} 가 담당한다.
 *
 * <h3>비식별·프레임 추출은 묶음이 아니다</h3>
 * <p>그 산출물은 뒤 작업과 데이터마트 산출의 <b>전제</b>라, 건너뛴 채 파이프라인이 완료되면 산출물이
 * 조용히 비어 버린다. 여기 두 묶음은 건너뛰어도 "그 부가 정보가 없는 학습데이터"로 완결된다 —
 * 시계열을 건너뛰면 영상 서술이 사람이 직접 쓴 전문으로 폴백하고, 오토라벨을 건너뛰면 프레임은 남고
 * 작업자가 처음부터 라벨링한다.
 */
public enum BatchStageBundle {

    /** 시계열(외부 VLM 위탁) — 혼자 완결되는 작업이라 구성원이 하나다. */
    VLM(BatchStage.VLM),

    /** 오토라벨 — AI 탐지 · AI 분할 · 트랙 보간. 쪼개서 부분 수행하지 않는다(위 Javadoc). */
    AUTOLABEL(BatchStage.YOLO, BatchStage.SAM2, BatchStage.INTERPOLATE);

    private final List<BatchStage> stages;

    BatchStageBundle(BatchStage... stages) {
        this.stages = List.of(stages);
    }

    /** 이 묶음의 구성 단계. 파이프라인 순서와 <b>같은 순서로 적혀 있으나 순서의 진실원은 아니다</b>. */
    public List<BatchStage> stages() {
        return stages;
    }

    public boolean contains(BatchStage stage) {
        return stages.contains(stage);
    }

    /**
     * 이 단계가 속한 묶음 — 오케스트레이터 스킵 게이트의 해석기.
     *
     * <p>어느 묶음에도 속하지 않는 단계({@code MARKING}·{@code FRAME_EXTRACT}·{@code DEIDENTIFY})는
     * {@link Optional#empty()} 다 = <b>건너뛸 수 없다</b>. 게이트는 이 빈 값을 "스킵 아님"으로 읽으므로,
     * 묶음에 넣지 않은 단계가 실수로 건너뛰어지는 경로가 생기지 않는다(fail-closed).
     */
    public static Optional<BatchStageBundle> containing(BatchStage stage) {
        if (stage == null) {
            return Optional.empty();
        }
        for (BatchStageBundle bundle : values()) {
            if (bundle.contains(stage)) {
                return Optional.of(bundle);
            }
        }
        return Optional.empty();
    }

    /**
     * 요청 경로 변수의 묶음 문자열을 해석한다.
     *
     * <p>Jackson·Spring 의 enum 변환에 맡기지 않는 이유: 변환 실패가 <b>다른 사유</b>의 400
     * ("요청 값이 올바르지 않습니다")으로 새면 호출자가 사유를 구분할 수 없고, 프레임워크 메시지가
     * 요청 값을 그대로 되비출 수 있다(CWE-79/117).
     *
     * @return 허용 묶음이면 해당 값, 미지·null 이면 {@code null}(호출자가 400 처리)
     */
    public static BatchStageBundle parse(String bundle) {
        if (bundle == null || bundle.isBlank()) {
            return null;
        }
        String normalized = bundle.trim();
        for (BatchStageBundle candidate : values()) {
            if (candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
