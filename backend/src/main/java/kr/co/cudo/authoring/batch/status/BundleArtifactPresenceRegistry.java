package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 묶음 → 산출물 판정기 매핑의 <b>단일 지점</b>. [@design SCREEN-009] [@design AC-051]
 *
 * <h3>왜 레지스트리인가</h3>
 * <p>호출부가 {@code if (bundle == VLM) ... else if (bundle == AUTOLABEL) ...} 로 분기하면 묶음이
 * 늘었을 때 그 분기가 <b>조용히 빠진다</b>(새 묶음은 아무 판정도 받지 않고 통과한다). 매핑을 한 곳에
 * 모으면 "어느 묶음이 판정기를 갖고 있고 어느 묶음이 없는가"를 <b>물어볼 수 있는 대상</b>이 된다.
 *
 * <h3>미배선 묶음은 「산출물 없음」이다 (fail-safe 방향)</h3>
 * <p>판정기가 없는 묶음은 {@link #hasArtifact} 가 {@code false} 를 준다 — 즉 <b>조치 필요 목록에
 * 그대로 남는다</b>. 반대로 두면(판정 불가 = 있음으로 간주) 조치가 필요한 영상이 화면에서 조용히
 * 사라져 재수행 창구가 없어진다. <b>모르면 감추지 않는다</b>가 이 축의 안전한 방향이다.
 *
 * <p>미배선 사실은 기동 시 WARN 1회로 남긴다 — 매 호출 로깅은 상세 조회가 폴링 경로라 잡음이 된다.
 *
 * <h3>한 묶음에 판정기가 둘이면 기동을 실패시킨다</h3>
 * <p>둘이 서로 다른 답을 내면 어느 쪽이 이겼는지 실행마다 흔들리고(빈 주입 순서), 그 흔들림은
 * 화면 깜빡임으로만 드러나 원인을 찾기 어렵다. 조용한 승자 결정보다 기동 실패가 낫다.
 */
@Slf4j
@Component
public class BundleArtifactPresenceRegistry {

    private final Map<BatchStageBundle, BundleArtifactPresence> byBundle =
            new EnumMap<>(BatchStageBundle.class);

    public BundleArtifactPresenceRegistry(List<BundleArtifactPresence> presences) {
        for (BundleArtifactPresence presence : presences) {
            BundleArtifactPresence previous = byBundle.put(presence.bundle(), presence);
            if (previous != null) {
                throw new IllegalStateException(
                        "작업 묶음 산출물 판정기가 중복 등록됐습니다 — bundle=" + presence.bundle()
                                + " (" + previous.getClass().getName() + " vs " + presence.getClass().getName()
                                + "). 묶음당 판정기는 하나여야 한다.");
            }
        }
        Set<BatchStageBundle> unwired = unwiredBundles();
        if (!unwired.isEmpty()) {
            // 미배선 = 그 묶음은 산출물 유무를 판정하지 못해 조치 필요 목록에 계속 남는다(과잉 노출).
            //   기능이 깨지지는 않으므로 기동을 막지 않되, 왜 배너가 안 사라지는지 물었을 때
            //   답이 로그에 남아 있어야 한다.
            log.warn("[BundleArtifact] 산출물 판정기가 없는 작업 묶음 {} — 해당 묶음은 조치 필요 목록에서 걸러지지 않는다",
                    unwired);
        }
    }

    /**
     * 그 묶음의 산출물이 있는가 — 판정기가 없으면 {@code false}(위 fail-safe).
     *
     * @param rawSn  영상 PK. {@code null} 이면 {@code false}
     * @param bundle 작업 묶음. {@code null} 이면 {@code false}
     */
    public boolean hasArtifact(Long rawSn, BatchStageBundle bundle) {
        if (rawSn == null || bundle == null) {
            return false;
        }
        BundleArtifactPresence presence = byBundle.get(bundle);
        return presence != null && presence.exists(rawSn);
    }

    /**
     * 그 묶음은 <b>파이프라인 종결 = 작업 완료</b> 인가 — 종결 축 적용 여부. [@design AC-051]
     *
     * <p>판정을 여기서 하지 않는다. {@link BundleArtifactPresence#completionIsSynchronous()} 로
     * <b>묶음이 스스로 선언</b>한 값을 읽어 전달할 뿐이다 — 호출부가 묶음별로 분기하면 묶음이 늘 때
     * 그 분기가 조용히 빠진다.
     *
     * <p>판정기가 없는 묶음은 {@code false} 다. 미배선은 "모른다"이고, 모를 때 종결 축을 붙이면
     * <b>조치가 필요한 영상이 화면에서 사라진다</b>({@link #hasArtifact} 와 같은 fail-safe 방향).
     */
    public boolean completionIsSynchronous(BatchStageBundle bundle) {
        if (bundle == null) {
            return false;
        }
        BundleArtifactPresence presence = byBundle.get(bundle);
        return presence != null && presence.completionIsSynchronous();
    }

    /**
     * 아직 판정기가 붙지 않은 묶음 — 배선 상태를 <b>테스트가 물을 수 있게</b> 노출한다.
     *
     * <p>이 집합이 비어 있는 것이 목표 상태다. 비어 있지 않다는 것은 그 묶음이 조치 필요 판정에서
     * 항상 "산출물 없음"으로 취급된다는 뜻이다.
     */
    public Set<BatchStageBundle> unwiredBundles() {
        return Arrays.stream(BatchStageBundle.values())
                .filter(bundle -> !byBundle.containsKey(bundle))
                .collect(java.util.stream.Collectors.toCollection(
                        () -> java.util.EnumSet.noneOf(BatchStageBundle.class)));
    }
}
