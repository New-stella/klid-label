package kr.co.cudo.authoring.aiserver.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태점검 폴러 <b>게이팅</b> 검증. [@design ADR-057] [@design AC-1088]
 *
 * <h3>왜 이제 켜고 들어가는가</h3>
 * <p>처음에는 꺼둔 채로 들어갔고 그 조건이 <b>"실행이 용도별로 나뉜 뒤에 켠다"</b> 였다. 상태 점검과
 * 부하 조회는 둘 다 추론 서버가 요청을 받아 응답할 여유가 있어야 성립하는데, 실행을 용도별로 나누기
 * 전에는 추론이 서버의 처리 흐름을 통째로 붙잡아 <b>상태 점검조차 늦어졌기</b> 때문이다. 그 시기에
 * 켰다면 <b>바쁜 장비를 죽은 장비로 오판</b>했을 것이고, 장비가 둘 이상이면 먼저 판정된 하나는
 * 마지막 장비 보호에 걸리지 않아 <b>멀쩡한 장비를 하나 잃는다</b>.
 *
 * <p><b>그 조건은 충족됐다</b> — 추론 서버의 실행이 용도별 슬롯으로 갈렸고, 배치가 포화된 중에도
 * 인터랙티브 응답이 유지되는 것이 실측으로 확인됐다. 그래서 기본을 켬으로 돌린다.
 *
 * <h3>꺼둔 채로 두면 무슨 일이 나는가 — 이 시험이 지키는 것</h3>
 * <p><b>분산이 도입됐는데 동작하지 않는 상태가 조용히 유지된다.</b> 관측이 없으면 장비별 부하가
 * 전부 0 으로 <b>동률</b>이 되고, 동률의 결정 규칙이 식별자 순서라 요청이 <b>항상 한 대로만</b> 간다.
 * 장비를 여러 대 등록해도 소용이 없고 아무 신호도 나지 않는다 — 그래서 기본값을 시험으로 못박는다.
 *
 * <p>⚠ <b>시계열 축은 이 설정과 무관하다</b>. 그 축의 부하 원천은 폴러가 아니라 <b>우리 원장의 미결
 * 위탁 수</b>라 외부를 찌르지 않으며, 폴러가 꺼져 있어도 분산된다.
 */
class AiSrvrHealthPollGatingIT {

    /** 기본 형상 — 설정을 주지 않으면 켜져 있어야 한다. */
    @Nested
    @SpringBootTest
    @ActiveProfiles("local")
    class 기본값 {

        @Autowired private ApplicationContext context;

        @Test
        @DisplayName("★폴링_기본값은_켜짐이라_잡이_등록된다")
        void 폴링_기본값은_켜짐이라_잡이_등록된다() {
            assertThat(context.containsBean("aiSrvrHealthPollJobDetail")).isTrue();
            assertThat(context.containsBean("aiSrvrHealthPollTrigger")).isTrue();
        }
    }

    /**
     * 끄는 길은 남아 있어야 한다 — 관측이 문제를 일으키는 현장에서 되돌릴 수단이 없으면
     * 유일한 대안이 재배포가 된다.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("local")
    @TestPropertySource(properties = "authoring.integration.ai-server.poll-enabled=false")
    class 명시적으로_끈_경우 {

        @Autowired private ApplicationContext context;

        @Test
        @DisplayName("false 로 명시하면 잡이 등록되지 않는다")
        void false_로_명시하면_잡이_등록되지_않는다() {
            assertThat(context.containsBean("aiSrvrHealthPollJobDetail")).isFalse();
            assertThat(context.containsBean("aiSrvrHealthPollTrigger")).isFalse();
        }
    }

    /**
     * ★{@code @ConditionalOnProperty(havingValue="true")} 는 <b>"true" 만</b> 참으로 읽는다.
     * {@code 1}/{@code on}/{@code yes} 는 <b>꺼짐</b>이며, 그것을 켜짐으로 착각하면 현장에서
     * 켰다고 믿은 채 한 대로만 가게 된다.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("local")
    @TestPropertySource(properties = "authoring.integration.ai-server.poll-enabled=1")
    class 참으로_읽히지_않는_값 {

        @Autowired private ApplicationContext context;

        @Test
        @DisplayName("★1 은 켜짐으로 읽히지 않는다 — true 만 참이다")
        void 숫자_1_은_켜짐으로_읽히지_않는다() {
            assertThat(context.containsBean("aiSrvrHealthPollTrigger")).isFalse();
        }
    }
}
