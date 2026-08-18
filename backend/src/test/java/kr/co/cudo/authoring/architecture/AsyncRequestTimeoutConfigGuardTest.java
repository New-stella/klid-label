package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비동기 스트리밍 응답 제한시간({@code spring.mvc.async.request-timeout}) 고정 가드.
 *
 * <h3>이 가드가 없으면 무슨 일이 벌어지나 (실증)</h3>
 * <p>이 키가 <b>어느 프로파일에도 없으면</b> 컨테이너 기본값 <b>30초</b>가 적용된다. 그리고 그 값은
 * "다음 쓰기까지의 공백"이 아니라 <b>비동기 처리 시작 이후의 절대 경과시간</b>이라, 서버가 쉬지 않고
 * 데이터를 쓰고 있어도 <b>리셋되지 않는다</b>(격리 재현: 2초 간격 20청크를 연속으로 쓰는 중
 * t=30.1s 에 강제 종료, 클라이언트는 16개만 수신).
 *
 * <p>그래서 포털 작업 데이터 내려받기
 * ({@code GET /v1/portal/datamart/videos/{rawSn}/download} — 유일한 {@code StreamingResponseBody}
 * 경로, 비식별 영상이 포함되면 GB 급)는 <b>사실상 전부 30초에 잘린다</b>.
 *
 * <h3>고정하는 것 두 가지</h3>
 * <ol>
 *   <li><b>공통 yml 에 선언</b> — 프로파일별 파일에 흩어 두면 한 곳이 빠진 환경만 조용히 30초로
 *       되돌아간다(그 환경에서만 재현되는 결함이라 발견이 가장 늦다).</li>
 *   <li><b>어떤 프로파일도 덮어쓰지 않는다</b> — 덮어쓰면 위 (1)의 취지가 그대로 무너진다.</li>
 * </ol>
 *
 * <p>⚠ <b>값 자체를 다른 계층(프록시·화면)과 맞추는 것은 이 가드의 범위가 아니다.</b> 실제 상한은
 * 언제나 <b>경로에 걸린 상한들 중 가장 작은 값</b>이므로, 이 값만 늘려도 앞단이 더 짧으면 여전히
 * 잘린다. 그 정렬은 별도 과제이며 여기서는 <b>서버가 스스로 30초에 끊는 것</b>만 막는다.
 */
class AsyncRequestTimeoutConfigGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String ASYNC_TIMEOUT_KEY = "spring.mvc.async.request-timeout";

    /** 30분(ms) — 근거는 {@code application.yml} 의 해당 키 주석. */
    private static final long EXPECTED_TIMEOUT_MS = 30L * 60L * 1000L;

    private static final List<String> PROFILE_YMLS = List.of(
            "application-local.yml", "application-dev.yml", "application-stg.yml", "application-prd.yml");

    @Test
    @DisplayName("비동기_요청_제한시간이_공통_yml에_선언돼_전_프로파일에_적용된다")
    void asyncRequestTimeoutIsDeclaredInCommonYaml() {
        assertThat(MainResourceYaml.keys(COMMON_YML))
                .as("%s 가 공통 yml 에 없으면 컨테이너 기본값 30초가 적용돼 대용량 내려받기가 전부 잘린다",
                        ASYNC_TIMEOUT_KEY)
                .contains(ASYNC_TIMEOUT_KEY);

        Environment env = MainResourceYaml.environment(COMMON_YML);
        assertThat(env.getProperty(ASYNC_TIMEOUT_KEY))
                .as("공통 yml 이 해석되는 값(ms)")
                .isEqualTo(String.valueOf(EXPECTED_TIMEOUT_MS));
    }

    @Test
    @DisplayName("어떤_프로파일도_비동기_요청_제한시간을_덮어쓰지_않는다")
    void noProfileOverridesAsyncRequestTimeout() {
        for (String profileYml : PROFILE_YMLS) {
            assertThat(MainResourceYaml.keys(profileYml))
                    .as("%s 가 %s 를 덮어쓰면 그 환경만 다른 상한으로 조용히 갈린다",
                            profileYml, ASYNC_TIMEOUT_KEY)
                    .doesNotContain(ASYNC_TIMEOUT_KEY);
        }
    }
}
