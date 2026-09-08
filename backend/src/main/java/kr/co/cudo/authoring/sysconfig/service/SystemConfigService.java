package kr.co.cudo.authoring.sysconfig.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.SafeUrl;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.ConfigResponse;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointUrlValidator;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 시스템 설정 조회/갱신 서비스 (Phase 12).
 * <p>
 * 캐시: Caffeine "sysconfig" (TTL 60s) — getInt/getString 핫 패스 가속.
 * update 시 전체 무효화하여 다음 호출에서 새 값 반영.
 * <p>
 * 검증 정책 (DB설계서 §5A.4):
 *  - 화이트리스트({@code ConfigKeys.ALLOWED})에 등록된 키만 허용. (개수는 적지 않는다 — 키가 늘 때마다 낡는다)
 *  - CONFIG_TYPE=NUMBER 키는 정수 + 키별 <b>허용값 집합</b>({@code NUMBER_ALLOWED_VALUES})
 *    또는 <b>허용 범위</b>({@code NUMBER_RANGE}) 검증. 두 맵에 다 등록되면 둘 다 통과해야 한다.
 *  - CONFIG_TYPE=DECIMAL 키는 실수 + 키별 범위({@code DECIMAL_RANGE}) 검증.
 *  - ⚠ 어느 맵에도 등록되지 않은 NUMBER/DECIMAL 키는 <b>파싱만 통과하면 무제한 허용</b>된다
 *    (등록 누락 = 무검증). 신규 키는 반드시 한쪽에 등록한다.
 *  - REVIEWER 권한 검증은 Controller 레벨(@PreAuthorize) + Service 레벨 이중 체크.
 * <p>
 * R11 — <b>연동 서버 주소 키</b>({@link IntegrationEndpoint} — 비식별 · AI 추론 · 외부 시계열 분석
 * 벤더 · 관제 통지 · 외부 증강 벤더)는 위 검증에 더해 두 가지를 요구한다:
 * <ul>
 *   <li><b>관리자 단기 유효창</b> — REVIEWER 권한만으로는 저장되지 않는다. 게이트를 컨트롤러가 아니라
 *       <b>여기</b>에 두어 진입점이 늘어도 우회되지 않게 한다.</li>
 *   <li><b>주소 값 판정</b> — http/https 스키마 + 형식. <b>IP 대역으로는 막지 않는다</b>
 *       (2026-08-10 확정 — 이 연동들은 내부망에 있을 수 있고 망 통제는 인프라 계층 책임이다).</li>
 * </ul>
 * 또 이 키들은 <b>시드하지 않는 것이 설계</b>라(행이 없으면 배포 기본값을 쓴다) 최초 저장 시
 * 행을 새로 만든다({@code loadOrCreate}). <b>그 외 키의 동작은 전혀 바뀌지 않는다.</b>
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

    /**
     * R11 — 연동 주소 키 저장 시 요구하는 관리자 단기 유효창 <b>게이트</b>. [@design ADR-046]
     *
     * <p>검증자를 직접 부르지 않고 게이트를 통한다. 같은 요구가 사용자 역할 변경·업로드 시작·관리자
     * 자격 교체로 넓어지는데, 그때마다 검증자를 각자 부르면 「호출처마다 배선하면 샌다」가 그대로
     * 재현된다. 게이트는 <b>얇은 층</b>이라 판정은 여전히 한 곳이 소유한다.
     *
     * <p>⚠ 이 창구는 <b>애노테이션이 아니라 프로그램적 경로</b>를 쓴다 — 요구가 설정 키 단위로
     * 갈리기 때문이다(연동 주소 키에만 요구, 그 외 키는 검수자 권한만으로 저장). 창구 전체에
     * 애노테이션을 붙이면 요구가 없는 키까지 함께 막힌다.
     */
    private final AdminSessionGate adminSessionGate;

    /** R11 — 연동 주소 값(스키마·형식) 판정기. 대역 차단은 하지 않는다. */
    private final IntegrationEndpointUrlValidator endpointUrlValidator;

    /**
     * R11 — <b>비식별</b> 주소 전용 신뢰 판정기(목/시뮬레이터 호스트 축).
     *
     * <p>이 가드는 원래 기동 시 배포값만 봤는데, 주소가 화면에서 바뀌게 되면서 가드가 보는 값과 실제
     * 호출 주소가 갈렸다 — <b>prd 에서도 화면으로 목 주소를 저장해 게이트를 통째로 우회</b>할 수 있었다.
     * 저장 시점에도 <b>같은 판정 함수</b>를 태워 그 구멍을 닫는다(판정 복제 금지).
     */
    private final DeidentifyEndpointTrustGuard deidentifyEndpointTrustGuard;

    /**
     * AI 대기 예산 <b>하한</b> 도출기 — {@link ConfigKeys#AI_WAIT_BUDGET_CEILING_SEC} 저장 검증에 쓴다.
     *
     * <p>순수 도출기(설정을 읽지 않는다)라 여기 주입해도 순환이 생기지 않는다. 설정을 반영한
     * 실효값은 {@code AiWaitBudgetProvider} 가 담당한다.
     */
    private final AiWaitBudgetPolicy aiWaitBudgetPolicy;

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
     * <b>부재를 값으로 돌려주는</b> 문자열 조회 — 행이 없어도 예외를 던지지 않는다.
     *
     * <h3>왜 {@link #getString(String)} 으로는 안 되나 (캐시가 채워지지 않는다)</h3>
     * <p>연동 주소 키는 <b>행이 없는 것이 정상 상태</b>다(시드하지 않는다 — 없으면 배포 기본값을 쓴다).
     * 그런데 {@code getString} 은 그때 {@code NOT_FOUND} 를 던지고, Spring 캐시는 <b>예외를 캐시하지
     * 않는다</b>. 즉 override 를 한 번도 저장하지 않은 정상 배포에서는 캐시 엔트리가 <b>영영 만들어지지
     * 않아</b> 외부 호출마다 DB 왕복 + 예외 생성이 반복됐다("Caffeine TTL 60s 가 막는다"는 근거가
     * 정상 상태에서 성립하지 않았다). 영향 경로에 라벨링 캔버스의 온라인 오토라벨·SAM2 처럼
     * <b>사용자 클릭당 발생하는 대화형 핫패스</b>가 있다.
     *
     * <p>{@code Optional.empty()} 는 Spring 이 {@code null} 로 언랩해 캐시에 담고
     * ({@code CaffeineCache} 는 null 값을 허용한다) 조회 시 다시 {@code Optional} 로 감싸 준다 —
     * 그래서 <b>부재도 캐시 히트</b>가 된다.
     *
     * <p><b>즉시 반영은 그대로다</b> — {@code update} 가 {@code sysconfig} 캐시를
     * {@code allEntries=true} 로 비우므로 이 키(prefix {@code optstr:})도 함께 무효화된다.
     * 그것이 R11 의 핵심 요구(저장하면 다음 호출부터 새 주소)라 캐시 키를 추가하더라도 별도 배선이
     * 필요하지 않다.
     *
     * <p>⚠ {@code getString} 의 계약(행 없으면 404)은 <b>건드리지 않았다</b> — 기존 호출자가 그 예외에
     * 의존한다.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_SYSCONFIG, key = "'optstr:' + #key")
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<String> findString(String key) {
        return repository.findByConfigKey(key).map(LsSystemConfig::getConfigVl);
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
        return doUpdate(key, value, actor, null);
    }

    /**
     * 값 갱신 — <b>관리자 단기 유효창 토큰</b>을 함께 받는 형태 (R11).
     *
     * <p>연동 서버 주소 키({@link IntegrationEndpoint})는 이 토큰이 있어야 저장된다. 그 외 키는
     * 토큰과 무관하게 <b>기존 계약 그대로</b> 동작한다(3-인자 호출도 그대로 유효하다).
     *
     * <p>3-인자 오버로드를 남겨 둔 이유는 <b>기존 호출자·테스트가 그대로 컴파일·동작</b>하게 하기
     * 위해서다. 두 진입점 모두 판정은 {@code doUpdate} 한 곳에서만 한다.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.CACHE_SYSCONFIG, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.CACHE_EVENT_TYPE, allEntries = true,
                    condition = "#key == T(kr.co.cudo.authoring.sysconfig.ConfigKeys).EVENT_EXCLUDED_CLASS_CODES")
    })
    @Transactional(value = "controlTransactionManager")
    public ConfigResponse update(String key, String value, TokenClaims actor, String adminSessionToken) {
        return doUpdate(key, value, actor, adminSessionToken);
    }

    private ConfigResponse doUpdate(String key, String value, TokenClaims actor, String adminSessionToken) {
        verifyReviewer(actor);
        if (!ConfigKeys.ALLOWED.contains(key)) {
            // CWE-117 방어: 사용자 입력 키를 그대로 message 에 넣지 않음 (제어 문자 차단).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않은 설정 키입니다.");
        }

        // R11 — 연동 주소 키는 REVIEWER 권한 위에 <b>관리자 단기 유효창</b>을 하나 더 요구한다.
        // 요구 여부를 <이 호출 지점>에서 판정하는 이유: 요구가 <설정 키 단위>로 갈리기 때문이다.
        //   컨트롤러(창구)에 애노테이션으로 걸면 요구가 없는 배치·추론·정밀도·비식별 키까지 함께
        //   막혀, 검수자가 늘 쓰던 설정 변경이 잠긴다. 어느 키에 요구가 걸리는가는 아래
        //   IntegrationEndpoint 가 계속 소유하고, 게이트는 <걸린 뒤>의 판정만 맡는다.
        IntegrationEndpoint endpoint = IntegrationEndpoint.byConfigKey(key).orElse(null);
        if (endpoint != null) {
            adminSessionGate.require(adminSessionToken, actor.sub());
            endpointUrlValidator.validateForSave(endpoint, value);
            if (endpoint == IntegrationEndpoint.DEIDENTIFY) {
                // 기동 시 가드는 @Value 배포값만 본다 — 화면에서 바꾼 값은 그 판정 밖이므로
                // 여기서 같은 함수를 다시 태운다(운영이면 400, 그 외 프로파일은 WARN).
                deidentifyEndpointTrustGuard.verifyForSave(value);
            }
        }

        LsSystemConfig cfg = loadOrCreate(key, actor);
        validateByType(key, cfg.getConfigTypeCd(), value);

        cfg.updateValue(value, actor.sub());
        if (endpoint != null) {
            // 감사 — 새 테이블을 두지 않는다. LS_SYSTEM_CONFIG 의 MDFR_ID/MDFCN_DT 가 "누가·언제·
            // 어느 키·현재값"을 이미 남기므로, 로그는 그 위에 "변경이 있었다"는 사실을 더한다.
            // ★주소 값은 남기고 패스워드·토큰은 남기지 않는다.
            // userinfo(http://user:pass@host)는 위 validateForSave 가 400 으로 이미 막지만, 여기서도
            // 한 번 더 가린다 — 로그 마스킹 규칙은 키워드 기반이라 이 형태를 잡지 못하므로(실측),
            // 입구 검증이 바뀌면 그대로 평문 자격증명이 남는다(CWE-532 이중 방어).
            log.info("[SystemConfig] 연동 주소 변경 target={} actor={} url={}",
                    endpoint.name(), LogSanitizer.sanitize(actor.sub()),
                    LogSanitizer.sanitize(SafeUrl.maskUserInfo(value)));
        } else {
            // CWE-117 — 행위자 식별자는 외부 토큰 유래라 정제해서 싣는다(연동 주소 분기와 같은 축).
            //   key 는 화이트리스트 통과값이라 안전하지만, 두 분기가 다르게 처리되면 그 자체가 드리프트다.
            log.info("[SystemConfig] updated key={} actor={}", key, LogSanitizer.sanitize(actor.sub()));
        }
        return ConfigResponse.from(cfg);
    }

    /* ========== private ========== */

    private LsSystemConfig loadOrThrow(String key) {
        return repository.findByConfigKey(key)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "설정 키를 찾을 수 없습니다."));
    }

    /**
     * 저장 대상 행을 얻는다 — 없으면 <b>선언 타입이 있는 키에 한해</b> 새로 만든다 (R11).
     *
     * <p>연동 주소 키는 <b>시드하지 않는 것이 설계</b>다(행이 없으면 배포 기본값을 쓴다). 그런데
     * 기존 {@code update} 는 CONFIG_TYPE_CD 를 기존 행에서 읽으므로 행이 없으면 404 였고, 그대로면
     * 이 키들은 <b>한 번도 저장할 수 없다</b>. {@link ConfigKeys#DECLARED_TYPE} 에 등록된 키만 이
     * 경로를 타므로 임의의 키가 DB 에 생기지는 않는다(화이트리스트 통과가 이미 선행됐다).
     */
    private LsSystemConfig loadOrCreate(String key, TokenClaims actor) {
        return repository.findByConfigKey(key).orElseGet(() -> {
            String declaredType = ConfigKeys.DECLARED_TYPE.get(key);
            if (declaredType == null) {
                throw new CustomException(ErrorCode.NOT_FOUND, "설정 키를 찾을 수 없습니다.");
            }
            return repository.save(
                    LsSystemConfig.create(key, null, declaredType, null, actor.sub()));
        });
    }

    private void validateByType(String key, String type, String value) {
        switch (type) {
            case "NUMBER" -> {
                validateNumberRange(key, value);
                if (ConfigKeys.AI_WAIT_BUDGET_CEILING_SEC.equals(key)) {
                    validateAiWaitBudgetCeiling(value);
                }
            }
            case "DECIMAL" -> validateDecimalRange(key, value);
            case "BOOLEAN" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new CustomException(ErrorCode.INVALID_INPUT,
                            "BOOLEAN 값은 true/false 만 허용됩니다.");
                }
                if (ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT.equals(key)) {
                    validateVlmSkipByDefault(value);
                }
            }
            case "JSON" -> {
                if (ConfigKeys.EVENT_EXCLUDED_CLASS_CODES.equals(key)) {
                    validateExcludedClassCodes(value);
                }
                // 그 외 JSON 키는 구조 검증 없이 길이 제한(DTO @Size)만 적용 — 기존 동작 유지.
            }
            case "STRING" -> {
                // 길이 검증은 DTO @Size. 짝이 되는 판정이 필요한 키만 여기서 추가로 본다.
                if (ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON.equals(key)) {
                    validateVlmSkipByDefaultReason(value);
                }
            }
            default -> throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 CONFIG_TYPE 입니다.");
        }
    }

    /**
     * 시계열 위탁 <b>전체 건너뛰기</b>는 사유 없이 켤 수 없다 — 위반은 400. [@design ADR-050]
     *
     * <h3>왜 사유가 필수인가</h3>
     * <p>사람이 누르는 단건 건너뛰기가 사유를 필수로 두는 것과 <b>같은 축</b>이다. 건너뛴 이유가 남지
     * 않으면 그 영상의 시계열이 왜 비어 있는지 나중에 되짚을 수 없다.
     *
     * <h3>왜 «저장된» 값을 읽는가</h3>
     * <p>이 API 에는 일괄 저장이 없어 두 키가 <b>각각</b> 저장된다. 요청 한 건에는 스위치 값만 실려
     * 있으므로, 짝이 되는 사유는 그 시점에 <b>이미 저장돼 있어야</b> 한다. 캐시 조회({@code findString})
     * 대신 리포지토리를 직접 읽는 것은 이 판정이 <b>같은 트랜잭션 안의 최신 상태</b>를 봐야 하기
     * 때문이다.
     *
     * <p>끄는 저장({@code false})은 사유를 요구하지 않는다 — 되돌리는 길을 막지 않는다.
     */
    private void validateVlmSkipByDefault(String value) {
        if (!Boolean.parseBoolean(value)) {
            return;
        }
        boolean reasonPresent = repository
                .findByConfigKey(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON)
                .map(LsSystemConfig::getConfigVl)
                .filter(reason -> !reason.isBlank())
                .isPresent();
        if (!reasonPresent) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "시계열 전체 건너뛰기를 켜려면 사유를 먼저 저장해야 합니다.");
        }
    }

    /**
     * 스위치가 <b>켜져 있는 동안</b>에는 사유를 비울 수 없다 — 위반은 400. [@design ADR-050]
     *
     * <h3>왜 필요한가 (한쪽만 막으면 절반만 달성된다)</h3>
     * <p>{@link #validateVlmSkipByDefault} 는 <b>켜는 쓰기</b>만 본다. 그래서 켠 뒤에 사유를 빈 값으로
     * 저장하는 요청은 아무 판정도 만나지 않았고, 그러면 이후 자동 표식이 전부 「사유 미입력」로 남아
     * <b>건너뛴 이유를 나중에 되짚을 수 없다</b> — 이 설계가 사유를 필수로 둔 이유가 그대로 사라진다.
     * 화면이 막더라도 API 를 직접 부르면 열리므로 판정은 서버가 갖는다.
     *
     * <p><b>축은 켤 때 검사와 같다</b> — "스위치가 켜짐이면 사유가 비어 있지 않다"는 <b>하나의 불변식</b>을
     * 두 쓰기 방향에서 각각 지키는 것이며, 규칙을 새로 만들지 않는다. 저장된 값을 리포지토리에서 직접
     * 읽는 것도 같은 이유다(일괄 저장이 없어 두 키가 각각 저장되므로 짝은 이미 저장돼 있어야 한다).
     *
     * <p><b>되돌리는 길은 막지 않는다</b> — 스위치를 끄고 나면 사유는 언제든 비울 수 있다. 그래서 화면은
     * <b>끌 때는 스위치를 먼저, 켤 때는 사유를 먼저</b> 저장해야 두 검사 사이에 갇히지 않는다. 순서가
     * 어긋난 요청은 안전한 방향(거부)으로 떨어진다.
     */
    private void validateVlmSkipByDefaultReason(String value) {
        if (value != null && !value.isBlank()) {
            return;
        }
        boolean switchOn = repository
                .findByConfigKey(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT)
                .map(LsSystemConfig::getConfigVl)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (switchOn) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "시계열 전체 건너뛰기가 켜져 있는 동안에는 사유를 비울 수 없습니다.");
        }
    }

    /**
     * NUMBER 키 값 검증 — <b>허용값 집합</b>과 <b>허용 범위</b>를 둘 다 적용한다.
     *
     * <p>연속 범위가 아닌 코드값 키(예: 마스킹 방식 {0,2,3} — 1 은 벤더 미할당)는
     * {@link ConfigKeys#NUMBER_ALLOWED_VALUES} 로 판정해야 한다. 범위로 두면 목록에 없는
     * 중간값이 통과한다.
     *
     * <p>⚠ 두 맵 어디에도 등록되지 않은 키는 <b>정수 파싱만 통과하면 무제한 허용</b>된다.
     * 즉 <b>등록 누락 = 무검증</b>이므로 신규 NUMBER 키는 반드시 한쪽에 등록한다.
     */
    private void validateNumberRange(String key, String value) {
        int v;
        try {
            v = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "NUMBER 타입 키에 숫자가 아닌 값이 입력되었습니다.");
        }
        Set<Integer> allowed = ConfigKeys.NUMBER_ALLOWED_VALUES.get(key);
        if (allowed != null && !allowed.contains(v)) {
            // CWE-117 — 입력 원문을 메시지에 싣지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않은 값입니다.");
        }
        int[] range = ConfigKeys.NUMBER_RANGE.get(key);
        if (range != null && (v < range[0] || v > range[1])) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "값이 허용 범위를 벗어났습니다.");
        }
    }

    /**
     * AI 대기 예산 절대 상한의 <b>파생 하한</b> 검증 — 숫자를 여기 적지 않는다.
     *
     * <p>하한은 {@link AiWaitBudgetPolicy#minimumCeilingSeconds()} 가 <b>재시도 예산에서 도출</b>한다.
     * 리터럴로 박으면 yml 의 재시도 설정을 늘렸을 때 하한이 따라 움직이지 않아, 어느 종류는 프레임
     * 한 건도 완주할 수 없는 상한이 그대로 저장된다 — 그것이 곧 이 라운드가 고친 결함의 재도입이다.
     *
     * <p>{@link ConfigKeys#NUMBER_RANGE} 는 정적 맵이라 파생값을 담을 수 없어 형식·상한만 거른다.
     * 실효 하한은 여기가 소유한다.
     *
     * <p>거부 응답은 다른 범위 위반과 <b>같은 통로·같은 형태</b>({@code INVALID_INPUT})다 —
     * 화면이 이 키만 다르게 분기하지 않아도 되게 한다. 메시지에 입력 원문은 싣지 않는다(CWE-117).
     */
    private void validateAiWaitBudgetCeiling(String value) {
        int seconds = Integer.parseInt(value); // 형식은 validateNumberRange 가 이미 통과시켰다.
        int floor = aiWaitBudgetPolicy.minimumCeilingSeconds();
        if (seconds < floor) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "AI 대기 예산 상한은 " + floor + "초 이상이어야 합니다."
                            + " 그보다 짧으면 정상 추론이 실패로 처리됩니다.");
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

    /**
     * 설정 저장의 역할 게이트 — <b>검수자 이상</b>이면 통과한다. [@design ADR-055] [@design ROLE-004]
     *
     * <p>판정은 {@link TokenClaims#hasRole(TokenClaims, Role)} <b>한 곳</b>이 소유한다. 역할을 여기서
     * 그대로 동등 비교하면 계층이 권한 축에만 걸리고 이 자리에서는 끊겨, 관리자가 자기 소유 화면
     * (연동 서버 주소)에서 거부된다. 정적 진입점을 쓰므로 행위자 없음도 그 판정 안에서 거짓으로
     * 떨어진다(fail-closed) — 앞에서 따로 검사하지 않는다.
     *
     * <p>⚠ 이 게이트는 <b>역할 축만</b> 본다. 연동 주소 키가 추가로 요구하는 관리자 단기 유효창은
     * 별개 축이며 서로를 대체하지 않는다 — 역할을 통과했다고 유효창이 면제되지 않는다.
     */
    private void verifyReviewer(TokenClaims actor) {
        if (!TokenClaims.hasRole(actor, Role.REVIEWER)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
