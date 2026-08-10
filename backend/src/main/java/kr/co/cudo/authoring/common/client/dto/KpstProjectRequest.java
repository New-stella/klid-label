package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * KPST {@code POST /project} 요청 DTO (application/json).
 *
 * <p>규격: 22-deid-solution-api.md §22.3.3. 코드성 선택 필드는 규격 기본값을 명시 상수로 보유한다.
 * 동일 이름 프로젝트가 존재하면 서버가 409 를 반환한다.
 *
 * <p>스네이크케이스 JSON 직렬화를 위해 {@link JsonProperty} 로 명시 매핑한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KpstProjectRequest(
        @JsonProperty("project_name") String projectName,
        String creator,
        @JsonProperty("export_path") String exportPath,
        @JsonProperty("input_path") String inputPath,
        List<String> files,
        @JsonProperty("masking_type") int maskingType,
        @JsonProperty("db_save") int dbSave,
        @JsonProperty("masking_range") double maskingRange,
        @JsonProperty("exp_quality") int expQuality,
        @JsonProperty("exp_format") int expFormat
) {
    /** 규격 기본값 (§22.4 부록 A). 매직 넘버 금지 — 호출자 미지정 시 사용. */
    public static final int DEFAULT_MASKING_TYPE = 0;
    public static final int DEFAULT_DB_SAVE = 0;
    /**
     * 마스킹 영역 배율 기본값. <b>실수</b>(0.5~2.0)다 — 벤더 확인 결과 이 필드는 코드값이 아니라
     * 배율이며, {@code int} 로 두면 0.5 가 0 으로 잘려 전송 자체가 불가능하다(선행 결함 교정).
     */
    public static final double DEFAULT_MASKING_RANGE = 1.0;
    public static final int DEFAULT_EXP_QUALITY = 0;
    public static final int DEFAULT_EXP_FORMAT = 1;

    /** 코드성 선택 필드를 규격 기본값으로 채워 생성하는 팩토리. */
    public static KpstProjectRequest withDefaults(String projectName, String creator,
                                                  String exportPath, String inputPath,
                                                  List<String> files) {
        return new KpstProjectRequest(projectName, creator, exportPath, inputPath, files,
                DEFAULT_MASKING_TYPE, DEFAULT_DB_SAVE, DEFAULT_MASKING_RANGE,
                DEFAULT_EXP_QUALITY, DEFAULT_EXP_FORMAT);
    }
}
