package kr.co.cudo.authoring.version.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * <p><b>두 가지 페이로드 형태(D-ISSUE-21):</b>
 * <ul>
 *   <li>일반 저장 이벤트 — 최상위가 배열: {@code [ {LabelChange}, ... ]} (기존 포맷, 불변)</li>
 *   <li>롤백 이벤트 — 최상위가 봉투 객체:
 *       {@code { "rollbackToVersionHash": "...", "changes": [ {LabelChange}, ... ] }}</li>
 * </ul>
 * 롤백은 "누가·언제"(REG_ID/REG_DT) 외에 <b>"어느 버전으로"</b>를 남겨야 하는데 스키마 변경(신규 컬럼)
 * 없이 담아야 하므로, TEXT(JSON) 컬럼 안에서 봉투로 확장한다. 역직렬화는 두 형태를 모두 수용한다.
 *
 * <p>{@link ObjectMapper} 는 thread-safe 이므로 재사용 가능한 static 단일 인스턴스로 구성한다.
 */
public final class LabelHistoryDiffSerializer {

    /** 고정 타입 전용 매퍼 — 다형 타이핑 미활성(명시 타입만). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<LabelChange>> LIST_TYPE = new TypeReference<>() {
    };

    /** 롤백 봉투의 변경 목록 필드명. */
    private static final String FIELD_CHANGES = "changes";
    /** 롤백 봉투의 대상 버전 해시 필드명. */
    private static final String FIELD_ROLLBACK_TO = "rollbackToVersionHash";
    /** 감사 봉투의 이벤트 종류 필드명 (DEV_FIX-B/M5). */
    private static final String FIELD_EVENT = "event";
    /** 감사 봉투의 비식별 신고 식별자 필드명 (DEV_FIX-B/M5). */
    private static final String FIELD_DEIDENT_REPORT_SN = "deidentReportSn";
    /** 개인정보 3필드 리셋 감사 이벤트 값 (DEV_FIX-B/M5). */
    public static final String EVENT_PRIVACY_META_RESET = "PRIVACY_META_RESET";

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
     * 롤백 이벤트 전용 직렬화 — 대상 버전 해시를 함께 담은 봉투 JSON 을 만든다.
     *
     * <p>스키마 변경 없이 "어느 버전으로 되돌렸는가"를 보존하기 위한 형태이며, 변경 목록이 비어 있어도
     * (라벨 델타 0건 롤백) 대상 해시는 반드시 남는다.
     *
     * @param targetVersionHash 롤백 대상 버전 해시(LS_LABEL_VERSION.VERSION_HASH)
     * @throws CustomException 직렬화 실패 시 (내부 오류 — 스택트레이스/내부경로 미노출)
     */
    public static String serializeRollback(String targetVersionHash, List<LabelChange> changes) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(FIELD_ROLLBACK_TO, targetVersionHash);
        root.set(FIELD_CHANGES, MAPPER.valueToTree(changes == null ? List.of() : changes));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 변경 이력 직렬화에 실패했습니다.");
        }
    }

    /**
     * DEV_FIX-B(M5) — 개인정보 메타 리셋 감사 전용 직렬화.
     *
     * <p>★ <b>신규 발생 없음 — 과거 행 판독용 존치 (2026-08-04)</b>: 신고 시 개인정보 3필드를 되돌리던
     * 동작이 폐기돼({@code DeidentReportService}) 이 직렬화를 호출하는 프로덕션 경로는 없다. 이미 적재된
     * 봉투를 읽는 소비자(이력 조회·작업 여부 필터)가 있으므로 <b>포맷과 상수를 그대로 유지</b>한다.
     *
     * <p>구 근거(보존): 비식별 누락 신고 시 프레임의 개인정보 3필드(익명/가명/개인정보 포함여부)를 NULL 로
     * 되돌리는 행위는 PII 표기 변경이라 <b>행 단위 감사</b>가 필요했다(OWASP A09 — Security Logging
     * Failures). 신규 테이블/컬럼 없이 기존 이력 축(LS_DATA_LBL_HSTRY.CHG_DTL_CN TEXT)에 봉투로 담았다.
     *
     * <p>형태: {@code { "event": "PRIVACY_META_RESET", "deidentReportSn": 12, "changes": [] }}
     * — 라벨 델타는 없으므로 {@code changes} 는 항상 빈 배열이며, 기존 {@link #deserialize} 가
     * 그대로 수용한다(라벨 이력 화면 회귀 없음).
     *
     * <p>보안(CWE-359): 지워진 값 자체나 PII 는 담지 않는다 — "어느 프레임이 어느 신고로 리셋됐는가"만.
     *
     * @param deidentReportSn 리셋을 유발한 비식별 신고 RPRT_SN (필수)
     */
    public static String serializePrivacyMetaReset(Long deidentReportSn) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(FIELD_EVENT, EVENT_PRIVACY_META_RESET);
        root.put(FIELD_DEIDENT_REPORT_SN, deidentReportSn);
        root.set(FIELD_CHANGES, MAPPER.valueToTree(List.of()));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 변경 이력 직렬화에 실패했습니다.");
        }
    }

    /**
     * JSON 문자열을 변경 목록으로 역직렬화. null/blank 는 빈 목록으로 처리한다.
     *
     * <p>배열(일반 저장 이벤트)과 롤백 봉투 객체를 모두 수용한다.
     *
     * @throws CustomException 역직렬화 실패 시 (내부 오류 — 스택트레이스/내부경로 미노출)
     */
    public static List<LabelChange> deserialize(String json) {
        JsonNode root = readTreeOrNull(json);
        if (root == null) {
            return List.of();
        }
        JsonNode changes = root.isArray() ? root : root.path(FIELD_CHANGES);
        if (!changes.isArray()) {
            return List.of();
        }
        try {
            return MAPPER.convertValue(changes, LIST_TYPE);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 변경 이력 역직렬화에 실패했습니다.");
        }
    }

    /**
     * 롤백 봉투의 대상 버전 해시를 읽는다. 일반 저장 이벤트(배열 포맷)는 {@code null}.
     *
     * @throws CustomException 역직렬화 실패 시 (내부 오류 — 스택트레이스/내부경로 미노출)
     */
    public static String readRollbackTargetHash(String json) {
        JsonNode root = readTreeOrNull(json);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode hash = root.path(FIELD_ROLLBACK_TO);
        return hash.isTextual() && !hash.asText().isBlank() ? hash.asText() : null;
    }

    private static JsonNode readTreeOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 변경 이력 역직렬화에 실패했습니다.");
        }
    }
}
