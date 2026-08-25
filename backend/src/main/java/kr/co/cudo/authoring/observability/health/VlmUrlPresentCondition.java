package kr.co.cudo.authoring.observability.health;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * "외부 시계열 분석(VLM) 연동 주소가 실제로 주입되었는가" 판정.
 *
 * <p>{@link VlmHealthIndicator} 등록 조건이며, 판정 축은 <b>"키 존재"가 아니라 "값이 비지 않음"</b>이다
 * ({@code null}·빈 문자열·공백만 = 부재). 미연동이면 핑할 대상 자체가 없으므로 빈을 등록하지 않는다 —
 * 등록해서 DOWN 을 내면 집계 {@code /actuator/health} 가 상시 DOWN 이 된다.
 *
 * <h3>왜 애너테이션 조건 두 가지를 모두 쓰지 못하는가 (되돌리지 말 것)</h3>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(name = "vlm.client.url")} — {@code application.yml} 이
 *       {@code vlm.client.url: ${VLM_SERVICE_URL:}} 로 <b>키를 항상 정의</b>하므로 미주입 상태의 실제
 *       형상은 "키 없음"이 아니라 <b>"키가 있고 값이 빈 문자열"</b>이다. 이 애너테이션은
 *       {@code havingValue} 미지정 시 "키 존재 + 값이 {@code false} 가 아님"으로 판정하므로 빈
 *       문자열도 <b>매칭</b>시킨다 — 미연동 환경에서 빈이 등록돼 목적을 달성하지 못한다.</li>
 *   <li>{@code @ConditionalOnExpression("'${vlm.client.url:}'.trim().length() > 0")} — 값 비교는
 *       맞지만 <b>기동을 통째로 실패시킬 수 있다</b>. {@code OnExpressionCondition} 은 SpEL 을 파싱하기
 *       <b>전에</b> {@code Environment.resolvePlaceholders} 로 placeholder 를 치환하므로, 주소 값에
 *       작은따옴표가 하나라도 들어가면 그것이 표현식의 문자열 종결자로 먹혀
 *       {@code SpelParseException(EL1046E)} 이 나고 애플리케이션 컨텍스트가 뜨지 않는다. 즉
 *       <b>외부에서 주입되는 값이 조건식의 문법에 참여</b>하는 구조라 안전하지 않다.</li>
 * </ul>
 *
 * <p>그래서 문자열 파싱이 전혀 없는 이 판정기를 쓴다. 값은 {@link org.springframework.core.env.Environment}
 * 가 그대로 돌려주고 우리는 공백 여부만 본다 — 어떤 문자가 들어와도 조건이 깨지지 않는다.
 * 회귀 가드는 {@code VlmHealthIndicatorRegistrationTest}(빈 등록 4케이스 + 따옴표 기동 케이스)와
 * {@code VlmUrlPresentConditionTest}(판정 단위).
 *
 * @see VlmHealthIndicator
 */
class VlmUrlPresentCondition implements Condition {

    /** 외부 시계열 분석 서버 연동 주소 프로퍼티 키 — 실제 위탁 클라이언트가 쓰는 키와 같다. */
    static final String URL_PROPERTY = "vlm.client.url";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // hasText: null·""·공백만 → false. 위 세 케이스가 모두 "미연동"이다.
        return StringUtils.hasText(context.getEnvironment().getProperty(URL_PROPERTY));
    }
}
