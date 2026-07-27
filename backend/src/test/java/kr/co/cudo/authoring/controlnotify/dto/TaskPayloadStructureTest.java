package kr.co.cudo.authoring.controlnotify.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관제 통지 페이로드 구조 · 직렬화 계약 검증.
 *
 * <ul>
 *   <li>CWE-359: PII / Credential / 원본 경로 필드 부재</li>
 *   <li>관제 계약: JSON 필드명이 <b>snake_case</b> 로 나가야 한다 —
 *       Java 필드명만 바꾸고 직렬화가 camelCase 로 나가는 사고를 여기서 차단한다.</li>
 *   <li>폴백 큐 재전송을 위해 <b>역직렬화 왕복</b>이 되어야 한다.</li>
 * </ul>
 */
class TaskPayloadStructureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 페이로드에 절대 포함되면 안 되는 필드명 (PII / Credential / 원본경로). */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "password", "secret", "token", "credential",
            "ssn", "jumin", "cardNumber", "phoneNumber", "email",
            "rawPath", "originalPath", "imagePath", "filePath");

    private static TaskCompletedPayload completed() {
        return new TaskCompletedPayload("26", "FIRE", "11680", "서울특별시 강남구", 30, 16);
    }

    private static TaskModifiedPayload modified() {
        return new TaskModifiedPayload("26",
                new TaskModifiedPayload.ChangedItems(List.of("0000.jpg"), List.of("0000.json")));
    }

    @Test
    @DisplayName("TaskCompletedPayload에_PII_미포함")
    void taskCompletedPayloadNoPii() {
        // given
        RecordComponent[] components = TaskCompletedPayload.class.getRecordComponents();

        // when
        Set<String> fieldNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // then
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(fieldNames).doesNotContain(forbidden);
        }
        // 관제 계약 6필드 — 그 외 필드가 붙으면 계약 위반이다.
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "jobId", "eventTypeCd", "lclgvCd", "lclgvNm", "durationSec", "imageCount");
    }

    @Test
    @DisplayName("완료_페이로드가_snake_case_평면_JSON_으로_직렬화된다")
    void completedSerializesToSnakeCaseFlatJson() throws Exception {
        // when
        String json = MAPPER.writeValueAsString(completed());

        // then — 계약 키 6개, 중첩·camelCase 없음
        assertThat(json).contains("\"job_id\"", "\"event_type_cd\"", "\"lclgv_cd\"",
                "\"lclgv_nm\"", "\"duration_sec\"", "\"image_count\"");
        assertThat(json).doesNotContain("jobId", "eventTypeCd", "imageCount", "payload");
        assertThat(MAPPER.readTree(json).size()).isEqualTo(6);
    }

    @Test
    @DisplayName("완료_페이로드가_역직렬화_왕복된다_폴백_재전송")
    void completedRoundTrips() throws Exception {
        // given
        String json = MAPPER.writeValueAsString(completed());

        // when — 폴백 큐에 저장된 JSON 을 재시도 Job 이 되살리는 경로
        TaskCompletedPayload restored = MAPPER.readValue(json, TaskCompletedPayload.class);

        // then
        assertThat(restored).isEqualTo(completed());
    }

    @Test
    @DisplayName("TaskModifiedPayload에_PII_미포함")
    void taskModifiedPayloadNoPii() {
        // given
        RecordComponent[] components = TaskModifiedPayload.class.getRecordComponents();

        // when
        Set<String> fieldNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // then
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(fieldNames).doesNotContain(forbidden);
        }
        assertThat(fieldNames).containsExactlyInAnyOrder("jobId", "changedItems");
    }

    @Test
    @DisplayName("수정_페이로드가_changed_items_snake_case_로_직렬화되고_왕복된다")
    void modifiedSerializesAndRoundTrips() throws Exception {
        // when
        String json = MAPPER.writeValueAsString(modified());

        // then
        assertThat(json).contains("\"job_id\"", "\"changed_items\"", "\"images\"", "\"jsons\"");
        assertThat(json).doesNotContain("changedItems", "frameIds", "changeTypes");
        assertThat(MAPPER.readValue(json, TaskModifiedPayload.class)).isEqualTo(modified());
    }

    @Test
    @DisplayName("changed_items_는_null_입력에도_빈_리스트로_정규화된다")
    void changedItemsNeverNull() {
        // given / when
        TaskModifiedPayload.ChangedItems items = new TaskModifiedPayload.ChangedItems(null, null);

        // then — 관제가 null 방어 없이 순회할 수 있어야 한다.
        assertThat(items.images()).isEmpty();
        assertThat(items.jsons()).isEmpty();
    }

    // --- 파일명 규칙 (단일 지점 ExportFileNaming — Phase 5A export writer 와 공유) ---

    @Test
    @DisplayName("FRM_NO_0_이면_0000_jpg_로_zero_pad_된다")
    void frameZeroIsZeroPadded() {
        // given / when / then
        assertThat(ExportFileNaming.imageFileName(0)).isEqualTo("0000.jpg");
        assertThat(ExportFileNaming.jsonFileName(0)).isEqualTo("0000.json");
    }

    @Test
    @DisplayName("FRM_NO_가_10000_이상이면_잘리지_않고_5자리로_확장된다")
    void frameOverflowExpandsInsteadOfTruncating() {
        // given / when / then — 최소 폭 4의 zero-pad 라 상한이 없다.
        assertThat(ExportFileNaming.imageFileName(10000)).isEqualTo("10000.jpg");
        assertThat(ExportFileNaming.jsonFileName(123456)).isEqualTo("123456.json");
        // 중간값 (실측 최대 FRM_NO = 338)
        assertThat(ExportFileNaming.imageFileName(338)).isEqualTo("0338.jpg");
        assertThat(ExportFileNaming.imageFileName(7)).isEqualTo("0007.jpg");
    }

    @Test
    @DisplayName("FRM_NO_9999_는_4자리_10000_은_5자리로_생성된다")
    void frameWidthExpandsExactlyAtBoundary() {
        // given / when / then — 폭 확장 경계 직전/직후
        assertThat(ExportFileNaming.imageFileName(9999)).isEqualTo("9999.jpg");
        assertThat(ExportFileNaming.imageFileName(10000)).isEqualTo("10000.jpg");
        assertThat(ExportFileNaming.jsonFileName(9999)).isEqualTo("9999.json");
        assertThat(ExportFileNaming.jsonFileName(10000)).isEqualTo("10000.json");
    }

    @Test
    @DisplayName("FRM_NO_가_음수이면_예외로_실패한다")
    void negativeFrameNoFailsClosed() {
        // given / when / then — "%04d" 는 -1 을 "-001" 로 만들어 4자리 규칙을 깨고, writer 와 통지가
        //                       서로 다른 이름을 계산할 여지를 남긴다. FRM_NO 는 0-base 라 음수가 없으므로
        //                       조용히 이상한 파일명을 만들지 않고 즉시 실패한다(fail-closed).
        assertThatThrownBy(() -> ExportFileNaming.frameStem(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportFileNaming.imageFileName(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportFileNaming.jsonFileName(Long.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
