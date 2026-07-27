package kr.co.cudo.authoring.controlnotify;

import kr.co.cudo.authoring.common.client.ControlNotifyStatusException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ControlNotifyStatusException} 의 진단 요약(bodySummary) 정화 계약.
 *
 * <p>외부(관제) 응답 본문을 그대로 로그에 남기면 Log Injection(CWE-117)·정보 노출(CWE-209) 표면이 된다.
 * 절단(200자) + 개행 제거는 로그 위조 방어의 실질이므로 회귀로 고정한다.
 */
class ControlNotifyStatusExceptionTest {

    @Test
    @DisplayName("bodySummary_가_200자를_초과하면_절단된다")
    void bodySummaryIsTruncated() {
        // given — 관제가 장문 에러를 반환
        String longBody = "x".repeat(1000);

        // when
        ControlNotifyStatusException e = new ControlNotifyStatusException(422, longBody);

        // then
        assertThat(e.bodySummary()).hasSize(200);
    }

    @Test
    @DisplayName("bodySummary_에_CRLF_가_섞이면_한줄로_치환된다")
    void bodySummaryStripsControlCharacters() {
        // given — 로그 위조 시도(가짜 로그 라인 주입)
        String forged = "invalid\r\n2026-07-27 ERROR [fake] 관리자 권한 부여됨\nnext";

        // when
        ControlNotifyStatusException e = new ControlNotifyStatusException(400, forged);

        // then — 개행이 제거되어 로그 한 줄을 넘지 않는다.
        assertThat(e.bodySummary()).doesNotContain("\n").doesNotContain("\r");
        assertThat(e.bodySummary()).contains("invalid");
    }

    @Test
    @DisplayName("rawBody_가_null_이거나_공백이면_빈문자열을_반환한다")
    void bodySummaryNeverNull() {
        // given / when / then — 호출부가 null 방어 없이 로그 포맷에 넣는다.
        assertThat(new ControlNotifyStatusException(409, null).bodySummary()).isEmpty();
        assertThat(new ControlNotifyStatusException(409, "").bodySummary()).isEmpty();
        assertThat(new ControlNotifyStatusException(409, "   \n  ").bodySummary()).isEmpty();
    }

    @Test
    @DisplayName("409_404_만_자기치유_판정에_해당한다")
    void statusPredicates() {
        // given / when / then
        assertThat(new ControlNotifyStatusException(409, "").isConflict()).isTrue();
        assertThat(new ControlNotifyStatusException(409, "").isNotFound()).isFalse();
        assertThat(new ControlNotifyStatusException(404, "").isNotFound()).isTrue();
        assertThat(new ControlNotifyStatusException(422, "").isConflict()).isFalse();
        assertThat(new ControlNotifyStatusException(422, "").isNotFound()).isFalse();
    }
}
