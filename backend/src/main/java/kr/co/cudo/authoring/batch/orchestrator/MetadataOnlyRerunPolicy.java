package kr.co.cudo.authoring.batch.orchestrator;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「라벨을 다시 만들지 않고 <b>메타만 더하는</b> 묶음」의 판정 단일 지점. [@design API-201]
 *
 * <h3>무엇을 결정하나</h3>
 * <p>이 목록에 든 묶음은 재수행할 때 <b>검수 소유 게이트 2겹을 면제</b>받는다 —
 * ①{@code BatchStageRerunService} 의 승인 이력 거부(400) ②같은 서비스의 검수 소유 작업 상태
 * 거부(409)와 {@code BatchTransitionService} 진입 가드의 차단. 면제는 「차단하지 않는다」이지
 * 「작업 상태를 전이한다」가 <b>아니다</b> — 작업 상태({@code LS_RAW_DATA_STATUS})는 그대로 보존되며
 * {@code APPROVED} 영상은 {@code APPROVED} 로 남는다(데이터마트 뷰에서 이탈하지 않는다).
 *
 * <h3>왜 면제해도 되나 — 근거는 그 가드 자신의 서술이다</h3>
 * <p>진입 가드가 밝힌 차단 사유는 <b>"APPROVED 영상에 AUTO 라벨이 새로 적재되면서도 상태가
 * APPROVED 로 남아 탐지 불가능한 데이터 오염이 된다"</b> 하나다. 시계열 재수행은 라벨을 만들지
 * 않는다 — {@code BatchBundleTogglePolicy} 가 되돌린 묶음의 구성원만 켜고 나머지를 전부 끄며,
 * 시계열 묶음의 구성원은 {@link BatchStage#VLM} 하나뿐이다. 즉 그 사유가 이 묶음에는 성립하지 않는다.
 *
 * <h3>★allowlist 다 — denylist 로 되돌리지 말 것 (fail-closed)</h3>
 * <p>구 구현은 {@code bundle == AUTOLABEL} 이라는 <b>denylist</b> 였다. 그러면 새 묶음이 추가되는
 * 순간 <b>아무도 손대지 않았는데 자동으로 면제</b>된다(fail-open). allowlist 면 새 묶음은 여기에
 * 명시 등록하기 전까지 자동으로 엄격하다.
 *
 * <h3>여기를 넓히기 전에 답해야 하는 것</h3>
 * <p>「그 묶음이 <b>확정된 학습데이터의 라벨·프레임</b>을 다시 만드는가」. 예면 넣지 않는다 —
 * 승인 시점 스냅샷과 어긋난 산출물이 재검수 없이 관제로 나간다.
 */
public final class MetadataOnlyRerunPolicy {

    /**
     * 검수 소유 게이트를 면제하는 묶음 — 라벨을 다시 만들지 않고 메타만 더하는 것만 넣는다.
     *
     * <p>오토라벨은 <b>들어오지 않는다</b>: 산출물이 라벨이라 승인 시점 스냅샷과 어긋난다.
     */
    private static final Set<BatchStageBundle> METADATA_ONLY =
            Collections.unmodifiableSet(EnumSet.of(BatchStageBundle.VLM));

    private MetadataOnlyRerunPolicy() {
    }

    /** 그 묶음이 검수 소유 게이트 면제 대상인가. {@code null} 은 언제나 {@code false}(fail-closed). */
    public static boolean isMetadataOnly(BatchStageBundle bundle) {
        return bundle != null && METADATA_ONLY.contains(bundle);
    }

    /**
     * 면제 모드에서 <b>켜져 있어도 되는</b> 단계 집합 — 위 묶음들의 구성원 합집합.
     *
     * <p>구성원 판정은 {@link BatchStageBundle} 이 단독으로 소유한다. 여기서 재유도하지 않는다.
     */
    public static Set<BatchStage> allowedStages() {
        Set<BatchStage> stages = new LinkedHashSet<>();
        for (BatchStageBundle bundle : METADATA_ONLY) {
            stages.addAll(bundle.stages());
        }
        return Collections.unmodifiableSet(stages);
    }

    /**
     * ★<b>런타임 fail-closed</b> — 이번 실행에서 <b>실제로 돌 단계</b>가 면제 대상 안에 들어오는가.
     *
     * <p>면제 모드는 「라벨을 만들지 않는다」를 전제로 승인 영상의 진입을 연다. 그 전제를 배선
     * 실수로 깨뜨리면(토글 산출이 바뀌거나 진입점이 잘못 연결되면) <b>승인 영상에 오토라벨이 도는</b>
     * 최악이 된다. 오케스트레이터가 단계를 돌리기 <b>전에</b> 이 검사를 통과해야 한다.
     *
     * <h3>★인자는 토글 맵이 아니라 「돌 단계 목록」이다 (Critical)</h3>
     * <p>토글 맵을 그대로 검사하면 <b>뚫린다</b> — {@code BatchContext.isStageEnabled} 는 <b>키가 없으면
     * 켜진 것으로 본다</b>. 즉 맵에 {@code true} 로 적힌 것만 세면 「맵에 없어서 도는」 단계를 통째로
     * 놓친다. 그래서 판정 입력은 오케스트레이터가 실제 파이프라인 × 컨텍스트로 산출한 결과여야 한다.
     * 이 클래스는 그 사실을 받아 <b>허용 여부만</b> 답한다.
     *
     * <p>빈 목록은 거부한다 — 아무것도 돌지 않을 실행에 승인 게이트를 열 이유가 없고, 「산출을 못
     * 했는데 열렸다」는 조합은 판정 입력이 잘못됐다는 신호다(fail-closed).
     *
     * @param stagesThatWillRun 이번 실행에서 실제로 실행될 단계들
     */
    public static boolean runScopeIsMetadataOnly(Collection<BatchStage> stagesThatWillRun) {
        if (stagesThatWillRun == null || stagesThatWillRun.isEmpty()) {
            return false;
        }
        Set<BatchStage> allowed = allowedStages();
        for (BatchStage stage : stagesThatWillRun) {
            if (stage == BatchStage.MARKING) {
                // 컨텍스트 적재기 — 마킹 로드 + marks 파싱뿐이라 쓰기가 없다. BatchBundleTogglePolicy 가
                // 어떤 재수행에서도 이 단계를 끄지 않으므로(끄면 뒤 단계가 입력을 못 받는다) 허용한다.
                continue;
            }
            if (stage == null || !allowed.contains(stage)) {
                return false;
            }
        }
        return true;
    }
}
