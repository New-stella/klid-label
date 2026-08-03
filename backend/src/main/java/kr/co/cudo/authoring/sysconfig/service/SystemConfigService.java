package kr.co.cudo.authoring.sysconfig.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.ConfigResponse;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 시스템 설정 조회/갱신 서비스 (Phase 12).
 * <p>
 * 캐시: Caffeine "sysconfig" (TTL 60s) — getInt/getString 핫 패스 가속.
 * update 시 전체 무효화하여 다음 호출에서 새 값 반영.
 * <p>
 * 검증 정책 (DB설계서 §5A.4):
 *  - 화이트리스트 4개 키만 허용 (ConfigKeys.ALLOWED).
 *  - CONFIG_TYPE=NUMBER 키는 정수 + 키별 범위([min,max]) 검증.
 *  - REVIEWER 권한 검증은 Controller 레벨(@PreAuthorize) + Service 레벨 이중 체크.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    /** 이벤트 제외 대분류 코드 1건 형식 — 관제 EVNT_CLS_CD 는 2자리 숫자(CWE-20). */
    private static final Pattern CLASS_CD_PATTERN = Pattern.compile("\\d{2}");

    /** 제외 대분류 코드 개수 상한 — 무제한 입력으로 인한 자원 소모 방지(CWE-770). */
    private static final int EXCLUDED_CLASS_CODES_MAX = 20;

    private final LsSystemConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<ConfigResponse> listAll() {
        return repository.findAll().stream()
                .filter(c -> ConfigKeys.ALLOWED.contains(c.getConfigKey()))
                .map(ConfigResponse::from)
                .toList();
    }

    /** NUMBER 타입 캐시 조회. CONFIG_TYPE 이 NUMBER 가 아니면 INVALID_INPUT. */
    @Cacheable(cacheNames = "sysconfig", key = "'int:' + #key")
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Integer getInt(String key) {
        LsSystemConfig cfg = loadOrThrow(key);
        if (!"NUMBER".equals(cfg.getConfigTypeCd())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "CONFIG_TYPE_CD 이 NUMBER 가 아닙니다 key=" + key + " type=" + cfg.getConfigTypeCd());
        }
        try {
            return Integer.parseInt(cfg.getConfigVl());
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "CONFIG_VALUE 가 숫자가 아닙니다 key=" + key);
        }
    }

    /** DECIMAL 타입 캐시 조회. CONFIG_TYPE 이 DECIMAL 이 아니면 INVALID_INPUT. (FEAT-007) */
    @Cacheable(cacheNames = "sysconfig", key = "'dbl:' + #key")
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Double getDouble(String key) {
        LsSystemConfig cfg = loadOrThrow(key);
        if (!"DECIMAL".equals(cfg.getConfigTypeCd())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "CONFIG_TYPE_CD 이 DECIMAL 이 아닙니다 key=" + key + " type=" + cfg.getConfigTypeCd());
        }
        try {
            return Double.parseDouble(cfg.getConfigVl());
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "CONFIG_VALUE 가 숫자가 아닙니다 key=" + key);
        }
    }

    @Cacheable(cacheNames = "sysconfig", key = "'str:' + #key")
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public String getString(String key) {
        return loadOrThrow(key).getConfigVl();
    }

    /**
     * JSON 배열 타입 캐시 조회 — 문자열 집합으로 파싱한다(입력 순서 보존).
     * <p>
     * CONFIG_TYPE 이 JSON 이 아니거나 값이 JSON 배열이 아니면 INVALID_INPUT.
     * 저장 시점 검증({@link #validateByType})을 통과한 값만 들어오지만, 마이그레이션/수기 수정으로
     * 깨진 값이 있을 수 있으므로 조회 시에도 형식을 확인한다(fail-closed).
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_SYSCONFIG, key = "'strset:' + #key")
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Set<String> getStringSet(String key) {
        LsSystemConfig cfg = loadOrThrow(key);
        if (!"JSON".equals(cfg.getConfigTypeCd())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "CONFIG_TYPE_CD 이 JSON 이 아닙니다 key=" + key + " type=" + cfg.getConfigTypeCd());
        }
        // 캐시에 담기는 값이므로 불변 뷰로 반환한다(호출자가 캐시 내용을 바꾸지 못하게).
        return Collections.unmodifiableSet(parseStringArray(cfg.getConfigVl()));
    }

    /**
     * 값 갱신 (REVIEWER 전용 — Controller 단 @PreAuthorize 외 Service 단 이중 검증).
     * 갱신 후 캐시 전체 무효화로 다음 getInt/getString 호출에서 새 값 반영.
     * <p>
     * 이벤트 제외 대분류 코드({@link ConfigKeys#EVENT_EXCLUDED_CLASS_CODES}) 는
     * {@code EventTypeService.filterOptions()} 결과에 반영되는데 그 결과가 장수명(6h)
     * {@code eventType} 캐시에 담기므로, <b>이 키가 바뀔 때만</b> 해당 캐시도 함께 비운다
     * (다른 설정 키 변경 시 관제 코드 캐시를 불필요하게 날리지 않는다).
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.CACHE_SYSCONFIG, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.CACHE_EVENT_TYPE, allEntries = true,
                    condition = "#key == T(kr.co.cudo.authoring.sysconfig.ConfigKeys).EVENT_EXCLUDED_CLASS_CODES")
    })
    @Transactional(value = "controlTransactionManager")
    public ConfigResponse update(String key, String value, TokenClaims actor) {
        verifyReviewer(actor);
        if (!ConfigKeys.ALLOWED.contains(key)) {
            // CWE-117 방어: 사용자 입력 키를 그대로 message 에 넣지 않음 (제어 문자 차단).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않은 설정 키입니다.");
        }

        LsSystemConfig cfg = loadOrThrow(key);
        validateByType(key, cfg.getConfigTypeCd(), value);

        cfg.updateValue(value, actor.sub());
        log.info("[SystemConfig] updated key={} actor={}", key, actor.sub());
        return ConfigResponse.from(cfg);
    }

    /* ========== private ========== */

    private LsSystemConfig loadOrThrow(String key) {
        return repository.findByConfigKey(key)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "설정 키를 찾을 수 없습니다."));
    }

    private void validateByType(String key, String type, String value) {
        switch (type) {
            case "NUMBER" -> validateNumberRange(key, value);
            case "DECIMAL" -> validateDecimalRange(key, value);
            case "BOOLEAN" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new CustomException(ErrorCode.INVALID_INPUT,
                            "BOOLEAN 값은 true/false 만 허용됩니다.");
                }
            }
            case "JSON" -> {
                if (ConfigKeys.EVENT_EXCLUDED_CLASS_CODES.equals(key)) {
                    validateExcludedClassCodes(value);
                }
                // 그 외 JSON 키는 구조 검증 없이 길이 제한(DTO @Size)만 적용 — 기존 동작 유지.
            }
            case "STRING" -> { /* 길이 검증은 DTO @Size */ }
            default -> throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 CONFIG_TYPE 입니다.");
        }
    }

    private void validateNumberRange(String key, String value) {
        int v;
        try {
            v = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "NUMBER 타입 키에 숫자가 아닌 값이 입력되었습니다.");
        }
        int[] range = ConfigKeys.NUMBER_RANGE.get(key);
        if (range != null && (v < range[0] || v > range[1])) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "값이 허용 범위를 벗어났습니다.");
        }
    }

    private void validateDecimalRange(String key, String value) {
        double v;
        try {
            v = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "DECIMAL 타입 키에 숫자가 아닌 값이 입력되었습니다.");
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "유효하지 않은 숫자입니다.");
        }
        double[] range = ConfigKeys.DECIMAL_RANGE.get(key);
        if (range != null && (v < range[0] || v > range[1])) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "값이 허용 범위를 벗어났습니다.");
        }
    }

    /**
     * 이벤트 제외 대분류 코드 값 검증 (CWE-20 / CWE-770).
     * <p>유효 JSON 배열 + 각 원소가 2자리 숫자 문자열 + 개수 상한 20개.
     * 위반 값은 원문을 메시지·로그에 싣지 않는다(CWE-117 로그/응답 오염 방지).
     */
    private void validateExcludedClassCodes(String value) {
        Set<String> codes = parseStringArray(value);
        if (codes.size() > EXCLUDED_CLASS_CODES_MAX) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "제외 대분류 코드는 최대 " + EXCLUDED_CLASS_CODES_MAX + "개까지 지정할 수 있습니다.");
        }
        for (String code : codes) {
            if (!CLASS_CD_PATTERN.matcher(code).matches()) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "제외 대분류 코드는 2자리 숫자여야 합니다.");
            }
        }
    }

    /** JSON 배열 문자열 → 문자열 집합(입력 순서 보존). 배열이 아니거나 문자열 아닌 원소가 있으면 400. */
    private Set<String> parseStringArray(String value) {
        JsonNode root;
        try {
            root = objectMapper.readTree(value == null ? "" : value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "JSON 형식이 올바르지 않습니다.");
        }
        if (root == null || !root.isArray()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "JSON 배열이어야 합니다.");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : root) {
            if (!element.isTextual()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "JSON 배열 원소는 문자열이어야 합니다.");
            }
            values.add(element.textValue());
        }
        return values;
    }

    private void verifyReviewer(TokenClaims actor) {
        if (actor == null || actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
