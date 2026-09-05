package kr.co.cudo.authoring.observability;

import kr.co.cudo.authoring.observability.health.AiServerHealthIndicator;
import kr.co.cudo.authoring.observability.health.DeidentifyHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12 — 외부 시스템 헬스 인디케이터 등록 검증.
 * <p>
 * 외부 서버는 local 환경에서 미기동 상태이므로 health() 호출 시 down 으로 응답한다.
 * 본 테스트는 빈 등록 + 안전한 down 응답(에러 메시지 노출 없음)을 검증한다.
 *
 * <p>관제/포털 양방향 통합은 deprecated 되어 헬스 인디케이터에서 제외.
 */
@SpringBootTest
@ActiveProfiles("local")
class HealthIndicatorTest {

    @Autowired private AiServerHealthIndicator aiHealth;
    @Autowired private DeidentifyHealthIndicator deidHealth;

    @Test
    @DisplayName("Health_외부_시스템_인디케이터_등록_및_안전한_응답")
    void externalHealthIndicatorsRegistered() {
        // 빈 등록 확인.
        assertThat(aiHealth).isNotNull();
        assertThat(deidHealth).isNotNull();

        // health() 호출이 절대 throw 하지 않고 Health 객체 반환.
        Health ai = aiHealth.health();
        Health deid = deidHealth.health();

        assertThat(ai.getDetails()).containsKey("service");
        assertThat(deid.getDetails()).containsKey("service");

        // ★ ai-server 인디케이터는 원장 노드별 집계로 바뀌었다(ADR-057) — 노드 미응답 DOWN 에는
        //   예외가 없으므로 error 축이 <없다는 것 자체>를 단언한다. 조건부(if)로 감싸면 축이 없을 때
        //   단언이 통째로 건너뛰어져 가드가 조용히 멈춘다(이 저장소의 실사고 유형).
        assertThat(ai.getDetails())
                .as("노드 미응답은 예외가 아니다 — error 축이 생기면 예외 정보가 새는 경로가 열린 것")
                .doesNotContainKey("error");
        assertNoLeakedExceptionDetail(ai);
        assertNoLeakedExceptionDetail(deid);
    }

    /**
     * CWE-209 가드 — <b>조건 없이 항상 실행된다</b>.
     *
     * <p>검사 대상은 상세 <b>전체</b>다. 특정 축이 있을 때만 보면, 축 구성이 바뀐 순간 단언이 조용히
     * 죽는다. 스택트레이스·외부 응답 본문이 새면 반드시 개행이나 프레임 표식이 함께 실린다.
     * 주소(내부 토폴로지 — CWE-497)도 함께 막는다.
     */
    private void assertNoLeakedExceptionDetail(Health health) {
        String rendered = String.valueOf(health.getDetails());
        assertThat(rendered)
                .as("헬스 상세에 스택트레이스·응답 본문이 실리면 안 된다(CWE-209): %s", rendered)
                .doesNotContain("\n", "\r", "\tat ", "Caused by", "Exception:");
        assertThat(rendered)
                .as("헬스 상세에 서버 주소를 싣지 않는다(내부 토폴로지 — CWE-497)")
                .doesNotContain("http://", "https://");

        // error 축이 존재한다면 그것은 <클래스명 하나>여야 한다(메시지·패키지 경로 금지).
        Object error = health.getDetails().get("error");
        if (error != null) {
            assertThat(String.valueOf(error))
                    .as("error 축은 예외 클래스의 단순명만 담는다")
                    .matches("[A-Za-z0-9_$]+");
        }
    }
}
