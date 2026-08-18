package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.AiDefaultsResponse;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudget;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudgets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * API-193 경계 — 저장값이 없거나 숫자로 해석되지 않으면 그 항목을 <b>생략</b>한다.
 *
 * <p>화면이 자체 기본값으로 대체하므로, 조회 실패를 500 으로 올리거나 임의 상수를 지어내지 않는다.
 * 판정은 기존 {@link SystemConfigService} 의 타입·파싱 검증을 그대로 재사용한다(복제 금지).
 */
@ExtendWith(MockitoExtension.class)
class AiDefaultsServiceTest {

    @Mock private SystemConfigService systemConfigService;

    /** 대기 예산은 여기서 계산하지 않는다 — 조립만 하는지 확인하려고 목으로 둔다. */
    @Mock private AiWaitBudgetProvider aiWaitBudgetProvider;

    @InjectMocks private AiDefaultsService service;

    /** 조립 확인용 표식값 — 서비스가 값을 만들어내면 이 값과 달라져 테스트가 죽는다. */
    private static final AiWaitBudgets BUDGETS = new AiWaitBudgets(
            new AiWaitBudget(1, 0, 9), new AiWaitBudget(2, 0, 9),
            new AiWaitBudget(3, 4, 9), new AiWaitBudget(5, 6, 9));

    @BeforeEach
    void stubBudgets() {
        given(aiWaitBudgetProvider.budgets()).willReturn(BUDGETS);
    }

    @Test
    @DisplayName("대기_예산은_설정_조회가_실패해도_공급자가_준_값_그대로_실린다")
    void alwaysCarriesWaitBudgets() {
        // given: 두 슬라이더 값은 둘 다 조회 실패(=생략 대상)
        given(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD))
                .willThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 키를 찾을 수 없습니다."));
        given(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE))
                .willThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 키를 찾을 수 없습니다."));

        // when
        AiDefaultsResponse result = service.get();

        // then: 슬라이더 값은 생략돼도 예산은 «생략되지 않는다». 없으면 화면이 자기 상수로 돌아간다.
        assertThat(result.confThreshold()).isNull();
        assertThat(result.simplifyTolerance()).isNull();
        assertThat(result.waitBudgets()).isEqualTo(BUDGETS);
    }

    @Test
    @DisplayName("두_설정이_모두_있으면_그대로_반환한다")
    void returnsBothWhenPresent() {
        // given
        given(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD)).willReturn(25);
        given(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)).willReturn(1.0);

        // when
        AiDefaultsResponse result = service.get();

        // then
        assertThat(result.confThreshold()).isEqualTo(25);
        assertThat(result.simplifyTolerance()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("설정이_저장돼_있지_않으면_해당_항목을_생략한다")
    void omitsMissingConfig() {
        // given: 인식 민감도만 없음
        given(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD))
                .willThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 키를 찾을 수 없습니다."));
        given(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)).willReturn(2.5);

        // when
        AiDefaultsResponse result = service.get();

        // then
        assertThat(result.confThreshold()).isNull();
        assertThat(result.simplifyTolerance()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("값이_숫자로_해석되지_않으면_해당_항목을_생략한다")
    void omitsNonNumericConfig() {
        // given: 경계 세밀함 값이 숫자가 아님(수기 수정·마이그레이션 손상)
        given(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD)).willReturn(25);
        given(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE))
                .willThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "CONFIG_VALUE 가 숫자가 아닙니다"));

        // when
        AiDefaultsResponse result = service.get();

        // then
        assertThat(result.confThreshold()).isEqualTo(25);
        assertThat(result.simplifyTolerance()).isNull();
    }

    @Test
    @DisplayName("설정_타입이_기대와_다르면_해당_항목을_생략한다")
    void omitsWrongTypeConfig() {
        // given: 두 키 모두 CONFIG_TYPE_CD 불일치
        given(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD))
                .willThrow(new CustomException(ErrorCode.INVALID_INPUT, "CONFIG_TYPE_CD 이 NUMBER 가 아닙니다"));
        given(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE))
                .willThrow(new CustomException(ErrorCode.INVALID_INPUT, "CONFIG_TYPE_CD 이 DECIMAL 이 아닙니다"));

        // when
        AiDefaultsResponse result = service.get();

        // then
        assertThat(result.confThreshold()).isNull();
        assertThat(result.simplifyTolerance()).isNull();
    }
}
