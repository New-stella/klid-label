package kr.co.cudo.authoring.version.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5 — 버전 스냅샷의 폐기여부 해석 <b>단일 판정기</b> 테스트.
 *
 * <h3>과도기 호환 (Critical)</h3>
 * 폐기 축 편입 <b>이전</b>에 만들어진 스냅샷에는 {@code dscdYn} 키가 아예 없다. 그 부재를
 * <b>"폐기 아님"</b>으로 읽어야 옛 버전을 시작 버전으로 골랐을 때 멀쩡한 프레임이 폐기되지 않는다.
 * 값 판정은 <b>allowlist</b>({@code "Y"} 만 폐기)라 손상·비규격 값이 폐기로 해석되지 않는다
 * (fail-safe — 잘못 폐기하면 산출물에서 프레임이 사라진다).
 *
 * @design D5
 * @req R6
 */
class SnapshotDiscardPolicyTest {

    /** 판정에 관여하지 않는 관측용 식별자(파싱 실패 WARN 로그에만 실린다). */
    private static final Long SRC_SN = 51L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("폐기여부_필드가_없는_옛_스냅샷은_폐기_아님이다")
    void 폐기여부_필드가_없는_옛_스냅샷은_폐기_아님이다() {
        assertThat(SnapshotDiscardPolicy.resolve("{\"srcSn\":1,\"items\":[]}", objectMapper, SRC_SN))
                .isEqualTo("N");
    }

    @Test
    @DisplayName("폐기여부가_Y_면_폐기로_읽는다")
    void 폐기여부가_Y_면_폐기로_읽는다() {
        assertThat(SnapshotDiscardPolicy.resolve("{\"dscdYn\":\"Y\",\"items\":[]}", objectMapper, SRC_SN))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("폐기여부가_N_이면_사용으로_읽는다")
    void 폐기여부가_N_이면_사용으로_읽는다() {
        assertThat(SnapshotDiscardPolicy.resolve("{\"dscdYn\":\"N\",\"items\":[]}", objectMapper, SRC_SN))
                .isEqualTo("N");
    }

    @Test
    @DisplayName("빈_스냅샷은_폐기_아님이다")
    void 빈_스냅샷은_폐기_아님이다() {
        assertThat(SnapshotDiscardPolicy.resolve(null, objectMapper, SRC_SN)).isEqualTo("N");
        assertThat(SnapshotDiscardPolicy.resolve("   ", objectMapper, SRC_SN)).isEqualTo("N");
    }

    @Test
    @DisplayName("손상_JSON_이나_비규격_값은_폐기로_읽지_않는다")
    void 손상_JSON_이나_비규격_값은_폐기로_읽지_않는다() {
        assertThat(SnapshotDiscardPolicy.resolve("{not json", objectMapper, SRC_SN)).isEqualTo("N");
        assertThat(SnapshotDiscardPolicy.resolve("{\"dscdYn\":\"y\"}", objectMapper, SRC_SN)).isEqualTo("N");
        assertThat(SnapshotDiscardPolicy.resolve("{\"dscdYn\":true}", objectMapper, SRC_SN)).isEqualTo("N");
        assertThat(SnapshotDiscardPolicy.resolve("{\"dscdYn\":null}", objectMapper, SRC_SN)).isEqualTo("N");
    }

    @Test
    @DisplayName("파싱_실패는_무음으로_삼키지_않고_WARN_으로_관측된다 — 본문은_남기지_않는다")
    void 파싱_실패는_WARN_으로_관측된다() {
        // given — 손상 스냅샷은 되돌리기 결과를 조용히 바꾼다(폐기였던 프레임이 살아난다).
        //   판정은 안전한 쪽(N)으로 두되 운영에서 관측 가능해야 한다.
        Logger logger = (Logger) LoggerFactory.getLogger(SnapshotDiscardPolicy.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            // when
            SnapshotDiscardPolicy.resolve("{\"dscdYn\": ", objectMapper, SRC_SN);

            // then — 식별자만 실린다. 스냅샷 본문·좌표·경로는 남기지 않는다(CWE-359/117).
            assertThat(logs.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .isNotEmpty()
                    .allSatisfy(e -> {
                        assertThat(e.getFormattedMessage()).contains("srcSn=" + SRC_SN);
                        assertThat(e.getFormattedMessage()).doesNotContain("dscdYn\": ");
                    });
        } finally {
            logger.detachAppender(logs);
        }
    }
}
