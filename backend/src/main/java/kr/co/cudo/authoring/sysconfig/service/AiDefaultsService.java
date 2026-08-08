package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.AiDefaultsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * AI 정밀도 기본값 조회 (API-193).
 *
 * <p>노출 키를 <b>두 개로 좁혀</b> 매핑하기만 한다. 저장·타입·파싱 판정은 전부
 * {@link SystemConfigService} 의 것을 그대로 재사용한다 — 판정을 복제하면 한쪽만 갱신돼 어긋난다.
 * 설정 키도 {@link ConfigKeys} 상수를 참조하고 문자열을 새로 적지 않는다.
 *
 * <p>조회 실패는 <b>실패가 아니라 생략</b>이다. 저장값 부재(NOT_FOUND)·비숫자(INTERNAL_ERROR)·
 * 타입 불일치(INVALID_INPUT) 어느 쪽이든 해당 항목을 {@code null} 로 두어 응답에서 빠지게 하고,
 * 화면이 자체 기본값으로 대체한다. 이 값은 슬라이더 <b>초기값 프리필</b>일 뿐이라 없다고 해서
 * 화면이 깨져서는 안 되고, 서버가 임의 상수를 지어내서도 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDefaultsService {

    private final SystemConfigService systemConfigService;

    public AiDefaultsResponse get() {
        return new AiDefaultsResponse(
                omitOnFailure(ConfigKeys.YOLO_CONF_THRESHOLD,
                        () -> systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD)),
                omitOnFailure(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE,
                        () -> systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE))
        );
    }

    /**
     * 설정 조회 실패를 생략(null)으로 낮춘다.
     *
     * <p>{@code key} 는 코드 상수라 사용자 입력이 아니지만, 로그에는 예외 메시지가 아니라 키만
     * 남긴다(설정값 자체를 로그로 흘리지 않는다).
     */
    private <T> T omitOnFailure(String key, Supplier<T> reader) {
        try {
            return reader.get();
        } catch (CustomException e) {
            log.debug("[AiDefaults] config unavailable — omitted key={} code={}", key, e.getErrorCode());
            return null;
        }
    }
}
