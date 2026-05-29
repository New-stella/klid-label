package kr.co.cudo.authoring.sysconfig.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private final LsSystemConfigRepository repository;

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
        if (!"NUMBER".equals(cfg.getConfigType())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "CONFIG_TYPE 이 NUMBER 가 아닙니다 key=" + key + " type=" + cfg.getConfigType());
        }
        try {
            return Integer.parseInt(cfg.getConfigValue());
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
        if (!"DECIMAL".equals(cfg.getConfigType())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "CONFIG_TYPE 이 DECIMAL 이 아닙니다 key=" + key + " type=" + cfg.getConfigType());
        }
        try {
            return Double.parseDouble(cfg.getConfigValue());
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "CONFIG_VALUE 가 숫자가 아닙니다 key=" + key);
        }
    }

    @Cacheable(cacheNames = "sysconfig", key = "'str:' + #key")
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public String getString(String key) {
        return loadOrThrow(key).getConfigValue();
    }

    /**
     * 값 갱신 (REVIEWER 전용 — Controller 단 @PreAuthorize 외 Service 단 이중 검증).
     * 갱신 후 캐시 전체 무효화로 다음 getInt/getString 호출에서 새 값 반영.
     */
    @CacheEvict(cacheNames = "sysconfig", allEntries = true)
    @Transactional(value = "controlTransactionManager")
    public ConfigResponse update(String key, String value, TokenClaims actor) {
        verifyReviewer(actor);
        if (!ConfigKeys.ALLOWED.contains(key)) {
            // CWE-117 방어: 사용자 입력 키를 그대로 message 에 넣지 않음 (제어 문자 차단).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않은 설정 키입니다.");
        }

        LsSystemConfig cfg = loadOrThrow(key);
        validateByType(key, cfg.getConfigType(), value);

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
            case "JSON", "STRING" -> { /* 길이 검증은 DTO @Size */ }
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

    private void verifyReviewer(TokenClaims actor) {
        if (actor == null || actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
