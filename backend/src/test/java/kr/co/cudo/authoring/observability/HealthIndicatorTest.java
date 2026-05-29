package kr.co.cudo.authoring.observability;

import kr.co.cudo.authoring.observability.health.AiServerHealthIndicator;
import kr.co.cudo.authoring.observability.health.DeidentifyHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
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
        // 외부 서버 미기동 → down 이지만 에러 본문/스택트레이스 노출 X.
        Health h = aiHealth.health();
        assertThat(h.getStatus()).isIn(Status.UP, Status.DOWN);
        // detail 에 service 태그 포함, error 는 클래스명만 (CWE-209 방어)
        assertThat(h.getDetails()).containsKey("service");
        if (h.getStatus() == Status.DOWN) {
            assertThat(h.getDetails().get("error")).asString().doesNotContain("\n");
        }
    }
}
