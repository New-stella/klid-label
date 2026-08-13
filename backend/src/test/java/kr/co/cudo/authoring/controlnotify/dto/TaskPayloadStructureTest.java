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
        return new TaskCompletedPayload("26", "FIRE", "01", "0101", "11680",
                "서울특별시 강남구", 30, 16, "N", 3);
    }

    private static TaskModifiedPayload modified() {
        return new TaskModifiedPayload("26",
                new TaskModifiedPayload.ChangedItems(List.of("0000.jpg"), List.of("0000.json")),
                "라벨 수정 3건", 3);
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
        // 관제 계약 필드 — 그 외 필드가 붙으면 계약 위반이다(API-251 v17, 규격서 §4-1).
        // ⚠ 9 → 10 으로 바뀐 사유: 관제가 "어느 통지가 어느 산출 버전 폴더 v{n} 에 대응하는지 알 수
        //   없다"고 요청해 선택 필드 output_ver_no 를 추가했다(2026-08-12 회신 수용, @design INT-007).
        //   기존 9필드의 이름·타입·순서·직렬화 동작은 불변이다.
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "jobId", "eventTypeCd", "evntClsCd", "evntCtgryCd", "lclgvCd", "lclgvNm",
                "durationSec", "imageCount", "genAiYn", "outputVerNo");
    }

    @Test
    @DisplayName("완료통지_페이로드에_10필드가_모두_직렬화됨")
    void completedSerializesToSnakeCaseFlatJson() throws Exception {
        // when
        String json = MAPPER.writeValueAsString(completed());

        // then — 규격서 §4-1 의 계약 키 9개(required 8 + optional 1) + output_ver_no(선택, @design INT-007).
        //   중첩·camelCase 없음. 6필드로 보내면 관제가 전량 422 VALIDATION_FAILED 로 거부한다.
        assertThat(MAPPER.readTree(json).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "job_id", "event_type_cd", "evnt_cls_cd", "evnt_ctgry_cd", "lclgv_cd",
                "lclgv_nm", "duration_sec", "image_count", "gen_ai_yn", "output_ver_no");
        assertThat(json).doesNotContain("jobId", "eventTypeCd", "imageCount", "genAiYn",
                "outputVerNo", "payload");
        assertThat(MAPPER.readTree(json).size()).isEqualTo(10);
        assertThat(MAPPER.readTree(json).get("output_ver_no").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("★완료통지의_required_9필드는_값이_null_이어도_키가_남는다_output_ver_no_추가_회귀가드")
    void requiredFieldsKeepExplicitNullKeysAfterOptionalFieldAdded() throws Exception {
        // given — output_ver_no 에 @JsonInclude(NON_NULL) 을 <클래스 레벨>로 걸면 required 필드까지
        //   생략되어 관제 통지가 전량 422 로 깨진다. NON_NULL 은 반드시 <필드 레벨>이어야 한다.
        //   값이 전부 null 인 극단 케이스로 그 경계를 고정한다.
        TaskCompletedPayload allNull =
                new TaskCompletedPayload(null, null, null, null, null, null, null, 0, null, null);

        // when
        String json = MAPPER.writeValueAsString(allNull);

        // then — required 8 + optional ver_expln 성격의 9키는 남고, 신설 output_ver_no 만 빠진다.
        assertThat(MAPPER.readTree(json).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "job_id", "event_type_cd", "evnt_cls_cd", "evnt_ctgry_cd", "lclgv_cd",
                "lclgv_nm", "duration_sec", "image_count", "gen_ai_yn");
        assertThat(MAPPER.readTree(json).size()).isEqualTo(9);
        for (String required : List.of("job_id", "event_type_cd", "evnt_cls_cd", "evnt_ctgry_cd",
                "lclgv_cd", "lclgv_nm", "duration_sec", "gen_ai_yn")) {
            assertThat(MAPPER.readTree(json).has(required))
                    .withFailMessage("required 키 %s 가 사라지면 관제가 422 로 거부한다", required)
                    .isTrue();
            assertThat(MAPPER.readTree(json).get(required).isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("완료통지의_output_ver_no_가_null_이면_키_자체를_내보내지_않는다")
    void completedOmitsOutputVerNoKeyWhenAbsent() throws Exception {
        // given — 산출 이력이 없는 영상(또는 이 필드 도입 이전에 폴백 큐에 적재된 JSON). 관제는
        //   "키 없음 = 산출물 변경 없음 → 재픽업 불요" 로 처리하므로 명시적 null 을 보내지 않는다.
        TaskCompletedPayload noExport = new TaskCompletedPayload("26", "FIRE", "01", "0101",
                "11680", "서울특별시 강남구", 30, 16, "N", null);

        // when
        String json = MAPPER.writeValueAsString(noExport);

        // then
        assertThat(json).doesNotContain("output_ver_no");
        assertThat(MAPPER.readTree(json).has("output_ver_no")).isFalse();
        assertThat(MAPPER.readTree(json).size()).isEqualTo(9);
    }

    @Test
    @DisplayName("output_ver_no_가_없는_구_JSON_도_역직렬화된다_폴백_큐_하위호환")
    void legacyJsonWithoutOutputVerNoDeserializes() throws Exception {
        // given — 이 필드 도입 <이전에> 폴백 큐에 적재된 완료 통지 JSON
        String legacy = MAPPER.writeValueAsString(new TaskCompletedPayload("26", "FIRE", "01",
                "0101", "11680", "서울특별시 강남구", 30, 16, "N", null));

        // when
        TaskCompletedPayload restored = MAPPER.readValue(legacy, TaskCompletedPayload.class);

        // then — 값이 없으면 null 이고, 재전송 시에도 키가 생기지 않는다.
        assertThat(restored.outputVerNo()).isNull();
        assertThat(MAPPER.writeValueAsString(restored)).doesNotContain("output_ver_no");
    }

    @Test
    @DisplayName("evnt_cls_cd_키명이_우리_컬럼명_CLSF_가_아니라_관제_스펙명_CLS")
    void eventClassKeyFollowsControlSpecNotOurColumnName() throws Exception {
        // given — 우리 인입 컬럼은 EVNT_CLSF_CD(CLSF) 지만 관제 계약 키는 evnt_cls_cd(CLS) 다.
        //   오타처럼 보인다고 컬럼명에 맞추면 관제가 필수 필드 누락으로 422 를 낸다.
        String json = MAPPER.writeValueAsString(completed());

        // when / then
        assertThat(MAPPER.readTree(json).has("evnt_cls_cd")).isTrue();
        assertThat(json).doesNotContain("evnt_clsf_cd");
        assertThat(MAPPER.readTree(json).get("evnt_cls_cd").asText()).isEqualTo("01");
        // 카테고리는 반대로 우리 컬럼명과 같은 철자다(EVNT_CTGRY_CD → evnt_ctgry_cd).
        assertThat(MAPPER.readTree(json).get("evnt_ctgry_cd").asText()).isEqualTo("0101");
    }

    @Test
    @DisplayName("인입_미제공시_evnt_cls_cd_evnt_ctgry_cd_가_null_로_직렬화됨")
    void absentIngestCodesSerializeAsExplicitNull() throws Exception {
        // given — 관제는 현재 이 두 코드를 보내지 않는다(dev 실측 40행 전량 NULL). 값을 지어내지
        //   않는 것이 정책이고(D-ISSUE-41), 관제 계약상 required 라 <키 자체는 남아야> 한다.
        TaskCompletedPayload payload = new TaskCompletedPayload(
                "26", "FIRE", null, null, "11680", null, 30, 16, "N", 2);

        // when
        String json = MAPPER.writeValueAsString(payload);

        // then — required 9 + output_ver_no(선택, 값 있음) = 10
        assertThat(MAPPER.readTree(json).size()).isEqualTo(10);
        assertThat(MAPPER.readTree(json).get("evnt_cls_cd").isNull()).isTrue();
        assertThat(MAPPER.readTree(json).get("evnt_ctgry_cd").isNull()).isTrue();
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
        // ⚠ outputVerNo 추가 사유는 완료 통지와 같다(@design INT-007) — 관제가 이 통지가 어느 산출
        //   버전 폴더 v{n} 에 대응하는지 알 수 있게 한다. 기존 3필드는 불변이다.
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "jobId", "changedItems", "verExpln", "outputVerNo");
    }

    @Test
    @DisplayName("수정_페이로드가_changed_items_snake_case_로_직렬화되고_왕복된다")
    void modifiedSerializesAndRoundTrips() throws Exception {
        // when
        String json = MAPPER.writeValueAsString(modified());

        // then
        assertThat(json).contains("\"job_id\"", "\"changed_items\"", "\"images\"", "\"jsons\"",
                "\"ver_expln\"", "\"output_ver_no\"");
        assertThat(json).doesNotContain("changedItems", "frameIds", "changeTypes", "verExpln",
                "outputVerNo");
        assertThat(MAPPER.readTree(json).get("output_ver_no").asInt()).isEqualTo(3);
        assertThat(MAPPER.readValue(json, TaskModifiedPayload.class)).isEqualTo(modified());
    }

    @Test
    @DisplayName("수정통지의_output_ver_no_가_null_이면_키_자체를_내보내지_않는다")
    void modifiedOmitsOutputVerNoKeyWhenAbsent() throws Exception {
        // given — 산출 폴더가 새로 만들어지지 않은 수정 통지(촬영환경 메타 수정 등). 관제는 키 부재를
        //   "산출물 변경 없음 → 재픽업 불요" 로 읽으므로 명시적 null 을 보내지 않는다.
        TaskModifiedPayload metaOnly = new TaskModifiedPayload("26",
                TaskModifiedPayload.ChangedItems.empty(), "촬영환경 수정", null);

        // when
        String json = MAPPER.writeValueAsString(metaOnly);

        // then
        assertThat(json).doesNotContain("output_ver_no");
        assertThat(MAPPER.readTree(json).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("job_id", "changed_items", "ver_expln");
    }

    @Test
    @DisplayName("수정통지의_data_info_는_계약에_없으므로_페이로드에_존재하지_않는다")
    void modifiedHasNoDataInfoField() throws Exception {
        // given — 관제 명세에 data_info 키 스키마가 없다(규격서 §7-E, 회신 대기).
        //   추정 스키마로 필드를 만들면 관제가 422 를 내거나 잘못된 값을 적재한다.
        String json = MAPPER.writeValueAsString(modified());

        // when / then
        assertThat(MAPPER.readTree(json).has("data_info")).isFalse();
        assertThat(MAPPER.readTree(json).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("job_id", "changed_items", "ver_expln", "output_ver_no");
    }

    @Test
    @DisplayName("ver_expln_이_null_이면_키_자체를_내보내지_않는다")
    void nullVerExplnIsOmittedNotSentAsNull() throws Exception {
        // given — 이 필드 도입 <이전에> 폴백 큐에 적재된 JSON 을 재시도 Job 이 역직렬화하면 null 이다.
        //   관제 dataset_versions.ver_expln 은 NOT NULL 이라, 명시적 null 을 밀어 넣는 것보다
        //   "미전송"(관제가 스스로 채우는 기존 동작)이 안전하다.
        TaskModifiedPayload legacy = MAPPER.readValue(
                "{\"job_id\":\"26\",\"changed_items\":{\"images\":[],\"jsons\":[]}}",
                TaskModifiedPayload.class);
        assertThat(legacy.verExpln()).isNull();

        // when
        String json = MAPPER.writeValueAsString(legacy);

        // then
        assertThat(MAPPER.readTree(json).has("ver_expln")).isFalse();
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
