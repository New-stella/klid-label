package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 사유 <b>절단 표기</b> 회귀 가드. @design ADR-058, ERD-028
 *
 * <h3>왜 이 형식이 필요한가</h3>
 * <p>흡수(ADR-058)로 실패 사유가 폭이 좁은 메타 값 칸에 앉는다 — 흡수 전 칸에는 상한이 없었다.
 * 그것이 <b>인지·수용한 대가</b>이고, 그래서 <b>잘렸다는 사실이 반드시 드러나야</b> 한다.
 *
 * <h3>지키는 것 셋</h3>
 * <ol>
 *   <li>잘리지 않았으면 <b>꼬리를 붙이지 않는다</b> — 모든 값에 붙이면 그 표기가 신호가 되지 못해
 *       「값만 보고 잘렸는지 판정한다」가 성립하지 않는다.</li>
 *   <li><b>꼬리까지 포함해</b> 폭 안에 들어간다 — 꼬리를 나중에 덧붙이면 저장 시점에 다시 잘려
 *       원문 길이 표기 자체가 깨진다.</li>
 *   <li>원문 길이와 담긴 길이를 함께 남긴다 — 별도 키로 빼지 않는다(함께 읽지 않으면 모른다).</li>
 * </ol>
 */
class PortalUploadFailReasonTruncationTest {

    @Test
    @DisplayName("★폭_안에_들어가는_사유는_원문_그대로다 — 꼬리를_붙이지_않는다")
    void shortReasonIsUntouched() {
        String reason = "프레임 추출 실패: IOException";

        assertThat(PortalUploadLedger.truncateFailReason(reason)).isEqualTo(reason);
    }

    @Test
    @DisplayName("경계값 — 폭과_정확히_같은_길이도_잘리지_않는다")
    void exactlyMaxLengthIsUntouched() {
        String reason = "가".repeat(PortalUploadLedger.META_VALUE_MAX);

        assertThat(PortalUploadLedger.truncateFailReason(reason)).isEqualTo(reason);
    }

    @Test
    @DisplayName("★폭을_넘으면_잘리고_그_사실이_값에_드러난다")
    void longReasonIsTruncatedVisibly() {
        int total = PortalUploadLedger.META_VALUE_MAX + 500;
        String reason = "가".repeat(total);

        String stored = PortalUploadLedger.truncateFailReason(reason);

        assertThat(stored).contains("…");
        assertThat(stored).contains("원문 " + total + "자 중 ");
        assertThat(stored).endsWith("자)");
    }

    @Test
    @DisplayName("★★꼬리까지_포함해_폭_안이다 — 나중에_덧붙이면_저장에서_다시_잘린다")
    void truncatedValueFitsWithinColumnWidth() {
        for (int extra : new int[]{1, 10, 500, 100_000}) {
            String reason = "가".repeat(PortalUploadLedger.META_VALUE_MAX + extra);

            String stored = PortalUploadLedger.truncateFailReason(reason);

            assertThat(stored.length())
                    .as("초과분 %d — 꼬리 길이를 먼저 빼고 잘라야 한다", extra)
                    .isLessThanOrEqualTo(PortalUploadLedger.META_VALUE_MAX);
        }
    }

    @Test
    @DisplayName("담긴_길이_표기가_실제_본문_길이와_일치한다")
    void keptLengthMatchesActualBody() {
        String reason = "가".repeat(PortalUploadLedger.META_VALUE_MAX + 42);

        String stored = PortalUploadLedger.truncateFailReason(reason);

        int ellipsis = stored.indexOf('…');
        String body = stored.substring(0, ellipsis);
        assertThat(stored)
                .as("표기한 담긴 길이가 실제 본문과 어긋나면 그 표기를 믿을 수 없다")
                .contains("중 " + body.length() + "자)");
    }

    @Test
    @DisplayName("사유가_없으면_그대로_없다 — 값을_지어내지_않는다")
    void nullReasonStaysNull() {
        assertThat(PortalUploadLedger.truncateFailReason(null)).isNull();
    }
}
