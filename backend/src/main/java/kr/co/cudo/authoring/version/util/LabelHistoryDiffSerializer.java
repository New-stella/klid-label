package kr.co.cudo.authoring.version.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.version.entity.LabelChange;

import java.util.List;

/**
 * LS_DATA_LBL_HSTRY.CHG_DTL_CN(JSON) ↔ {@code List<LabelChange>} 직렬화/역직렬화.
 *
 * <p>보안(CWE-502 Insecure Deserialization): 다형 타입 정보를 절대 쓰지 않는다.
 * {@code enableDefaultTyping()} / {@code @JsonTypeInfo(use = CLASS)} 미사용 — 고정된 명시 타입
 * ({@code List<LabelChange>}) 로만 역직렬화하므로 임의 클래스 인스턴스화가 불가능하다.
 *
 * <p>{@link ObjectMapper} 는 thread-safe 이므로 재사용 가능한 static 단일 인스턴스로 구성한다.
 */
public final class LabelHistoryDiffSerializer {

    /** 고정 타입 전용 매퍼 — 다형 타이핑 미활성(명시 타입만). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<LabelChange>> LIST_TYPE = new TypeReference<>() {
    };

    private LabelHistoryDiffSerializer() {
    }

    /**
     * 변경 목록을 JSON 문자열로 직렬화. 빈 목록은 {@code "[]"} 로 직렬화된다.
     *
     * @throws CustomException 직렬화 실패 시 (내부 오류 — 스택트레이스/내부경로 미노출)
     */
    public static String serialize(List<LabelChange> changes) {
        try {
            return MAPPER.writeValueAsString(changes == null ? List.of() : changes);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 변경 이력 직렬화에 실패했습니다.");
        }
    }

    /**
     * JSON 문자열을 변경 목록으로 역직렬화. null/blank 는 빈 목록으로 처리한다.
     *
     * @throws CustomException 역직렬화 실패 시 (내부 오류 — 스택트레이스/내부경로 미노출)
     */
    public static List<LabelChange> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 변경 이력 역직렬화에 실패했습니다.");
        }
    }
}
