package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.dto.AugmentDiscardStateResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 폐기 원장 비석({@link LsDataAugDscd}) → 조회 응답({@link AugmentDiscardStateResponse}) 판정 단일 원천.
 *
 * <h2>왜 "최신 1행" 을 그대로 노출하면 안 되는가 (판정의 핵심)</h2>
 * <p>폐기 원장은 <b>반려마다 새 행</b>을 만들고, 복구는 기존 행을 <b>닫을 뿐 지우지 않는다</b>
 * ({@link LsDataAugDscd#restore}). 따라서 한 {@code dataAugSn} 에 반려 → 복구 → 재반려 이력이 여러
 * 행으로 쌓인다. 최신 행을 <b>상태 확인 없이</b> 그대로 실으면, 복구되어 지금은 멀쩡한 항목이 과거의
 * 닫힌 폐기행 때문에 화면에 <b>"곧 삭제됨" 으로 표시</b>된다.
 *
 * <pre>
 *   최신 행이 …                          → 응답
 *   ─────────────────────────────────────────────────────────────
 *   RSTR_DT != null (복구됨)              → null      … 지금은 폐기 상태가 아니다
 *   DEL_DT  != null (실삭제됨)            → purged=true , restorable=false
 *   열림 + DEL_PRCS_DT != null (클레임 중) → purged=false, restorable=false
 *   열림 + 클레임 없음                     → purged=false, restorable=true
 *   행 자체가 없음                         → null
 * </pre>
 *
 * <p>판정을 조회 서비스에서 떼어낸 이유는 두 가지다 — ①스윕 설정({@code enabled}/{@code graceDays})에
 * 의존하므로 설정 소유자 옆에 두는 편이 드리프트가 적고 ②컨테이너 없이 분기표 전량을 단위 고정할 수
 * 있어 회귀 비용이 낮다.
 */
@Component
@RequiredArgsConstructor
public class AugmentDiscardStateMapper {

    private final AugmentDiscardProperties properties;

    /**
     * @param latest 그 증강의 <b>최신</b> 폐기 원장 행({@code DATA_AUG_DSCD_SN} 최대). 없으면 null.
     * @return 폐기 축. <b>지금 폐기 상태가 아니면 null</b>(표식 없음 · 복구됨).
     */
    public AugmentDiscardStateResponse toResponse(LsDataAugDscd latest) {
        if (latest == null || latest.getRstrDt() != null) {
            return null;
        }
        boolean purged = latest.getDelDt() != null;
        // 클레임(DEL_PRCS_DT)이 잡힌 뒤에도 복구 자체는 성립하지만(최종 DELETE 가 표식을 재평가한다),
        // 화면에 버튼을 그리면 사용자가 누르는 순간 삭제 커밋과 경합해 409 가 난다. 힌트는 보수적으로 낸다.
        boolean restorable = !purged && latest.getDelPrcsDt() == null;
        return new AugmentDiscardStateResponse(
                latest.getDscdDt(), purgeAt(latest.getDscdDt()), purged, restorable);
    }

    /**
     * 실삭제 예정 시각 — 스윕이 비활성이면 {@code null}(거짓 예정 시각을 내리지 않는다).
     *
     * <p>이 값이 이미 과거인데 아직 삭제되지 않은 상태는 <b>정상</b>이다(스윕 주기 기본 1시간).
     */
    private LocalDateTime purgeAt(LocalDateTime discardedAt) {
        if (!properties.enabled() || discardedAt == null) {
            return null;
        }
        return discardedAt.plusDays(properties.graceDays());
    }
}
