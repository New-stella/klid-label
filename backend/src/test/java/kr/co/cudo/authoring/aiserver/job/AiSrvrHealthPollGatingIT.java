package kr.co.cudo.authoring.aiserver.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태점검 폴러 <b>게이팅</b> 검증. [@design ADR-057]
 *
 * <h3>왜 꺼둔 채로 들어가는가</h3>
 * <p>상태 점검과 부하 조회는 둘 다 추론 서버가 요청을 받아 응답할 여유가 있어야 성립한다. 실행을
 * 용도별로 나누기 전에는 추론이 서버의 처리 흐름을 통째로 붙잡아 <b>상태 점검조차 늦어지므로</b>,
 * 그 시기에 관측을 켜면 <b>바쁜 장비를 죽은 장비로 오판</b>한다. 장비가 하나뿐이면 마지막 하나를
 * 지키는 장치가 막아 주지만, 둘 이상이면 먼저 판정된 하나는 그 장치에 걸리지 않아 <b>멀쩡한 장비를
 * 하나 잃는다</b>.
 *
 * <p>그래서 부하 축만이 아니라 <b>상태 점검 축까지 함께</b> 꺼둔 상태로 들어가고, 실행이 용도별로
 * 나뉜 뒤에 켠다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrHealthPollGatingIT {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("폴링_기본값은_꺼짐이라_잡이_등록되지_않는다")
    void 폴링_기본값은_꺼짐이라_잡이_등록되지_않는다() {
        assertThat(context.containsBean("aiSrvrHealthPollJobDetail")).isFalse();
        assertThat(context.containsBean("aiSrvrHealthPollTrigger")).isFalse();
    }
}
