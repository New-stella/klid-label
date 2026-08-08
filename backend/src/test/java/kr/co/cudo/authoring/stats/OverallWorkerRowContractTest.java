package kr.co.cudo.authoring.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse.WorkerRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/stats/overall} 의 {@code workers[]} 항목 <b>JSON 계약</b> 회귀 가드.
 *
 * <p>이 목록은 <b>외부 프론트엔드 팀도 사용</b>하므로 기존 필드의 이름·타입이 바뀌면 그쪽 화면이
 * 조용히 빈 값으로 떨어진다. 서비스 단위 테스트는 값을 볼 뿐 <b>직렬화된 키 이름</b>은 보지 않으므로
 * 여기서 키 집합을 고정한다 — 필드를 지우거나 이름을 바꾸면 먼저 깨진다.
 */
class OverallWorkerRowContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 변경 전부터 있던 5필드 — 이름·타입 불변 대상. */
    private static final List<String> LEGACY_FIELDS =
            List.of("userId", "name", "labeled", "reviewed", "approvalRate");

    private JsonNode serialize() {
        WorkerRow row = new WorkerRow(7L, "작업자A", 10L, 3L, 90.0, 4L, 25.0);
        return MAPPER.valueToTree(row);
    }

    @Test
    @DisplayName("기존_작업자행_5필드는_이름과_타입이_그대로다")
    void legacyFieldsUnchanged() {
        // given / when
        JsonNode json = serialize();

        // then — 이름이 살아있고 타입도 그대로다 (외부 FE 팀 계약)
        assertThat(json.has("userId")).isTrue();
        assertThat(json.get("userId").isIntegralNumber()).isTrue();
        assertThat(json.get("userId").asLong()).isEqualTo(7L);

        assertThat(json.get("name").isTextual()).isTrue();
        assertThat(json.get("name").asText()).isEqualTo("작업자A");

        assertThat(json.get("labeled").isIntegralNumber()).isTrue();
        assertThat(json.get("labeled").asLong()).isEqualTo(10L);

        assertThat(json.get("reviewed").isIntegralNumber()).isTrue();
        assertThat(json.get("reviewed").asLong()).isEqualTo(3L);

        assertThat(json.get("approvalRate").isNumber()).isTrue();
        assertThat(json.get("approvalRate").asDouble()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("작업자행에_진행중_건수와_오토라벨비율이_추가된다")
    void newFieldsPresent() {
        // given / when
        JsonNode json = serialize();

        // then
        assertThat(json.has("inProgress")).isTrue();
        assertThat(json.get("inProgress").isIntegralNumber()).isTrue();
        assertThat(json.get("inProgress").asLong()).isEqualTo(4L);

        assertThat(json.has("autoLabelRate")).isTrue();
        assertThat(json.get("autoLabelRate").isNumber()).isTrue();
        assertThat(json.get("autoLabelRate").asDouble()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("작업자행은_기존5필드에_2필드만_더한_7필드다")
    void exactlySevenFields() {
        // given / when
        JsonNode json = serialize();
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);

        // then — 추가만 한다(삭제·개명 금지). 늘어난 필드가 있으면 계약 검토 후 이 목록을 갱신한다.
        assertThat(names).containsAll(LEGACY_FIELDS);
        assertThat(names).containsExactlyInAnyOrder(
                "userId", "name", "labeled", "reviewed", "approvalRate", "inProgress", "autoLabelRate");
    }

    /**
     * 백분율 축 정합 — 사양 서술이 한때 {@code 0.0~1.0} 이었으나 실제 구현·화면은 0~100 이었고
     * 사양이 그에 맞춰 정정됐다. 새 비율 필드도 <b>같은 축</b>이어야 화면이 한 규칙으로 포맷한다.
     */
    @Test
    @DisplayName("두_비율_필드는_같은_백분율_축을_쓴다")
    void bothRatesArePercentAxis() {
        // given — 전건 승인 + 전건 자동라벨
        WorkerRow allIn = new WorkerRow(1L, "작업자B", 5L, 0L, 100.0, 0L, 100.0);

        // when
        JsonNode json = MAPPER.valueToTree(allIn);

        // then — 1.0 이 아니라 100.0 (0~1 비율 축이 아니다)
        assertThat(json.get("approvalRate").asDouble()).isEqualTo(100.0);
        assertThat(json.get("autoLabelRate").asDouble()).isEqualTo(100.0);
    }
}
