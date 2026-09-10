package kr.co.cudo.authoring.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 회귀 가드 — <b>브라우저가 부를 API 접두어</b>가 설정으로 갈리고, 잘못된 값은 기동을 막는다.
 *
 * <h2>왜 이 축이 생겼나 (2026-09-10 실측)</h2>
 * <p>백엔드가 응답 <b>본문에</b> 절대 경로를 담아 내려주는 자리 셋이 {@code "/api/v1/..."} 를
 * <b>하드코딩</b>하고 있었다. 그 주소는 브라우저가 {@code <video src>}·{@code <img src>} 에
 * 그대로 무는 값이라, 포털 향에서는 앞단이 {@code /api/v1/} 을 <b>포털 자신의 WAS</b> 로
 * 보내 재생·이미지가 전부 남의 서버로 날아간다.
 *
 * <p>⚠ 이 결함은 <b>아무 신호가 없다</b> — 서버는 200 을 주고 화면도 뜨며, 영상만 재생되지
 * 않는다. 그래서 값 축으로 못박는다.
 */
@DisplayName("PublicApiPath — 브라우저 호출 접두어")
class PublicApiPathTest {

    @Nested
    @DisplayName("기본값 — 지정하지 않으면 동작이 바뀌지 않는다")
    class Defaults {

        /**
         * ★ 이 상수의 존재 이유가 「설정을 안 하면 지금 그대로」라는 보장이다.
         * 값을 옮기면 그 보장이 사라지므로 값 축으로 고정한다.
         */
        @Test
        @DisplayName("기본_접두어는_종전_하드코딩과_같은_값이다")
        void 기본_접두어는_종전_하드코딩과_같은_값이다() {
            assertThat(PublicApiPathDefaults.DEFAULT_BASE_PATH).isEqualTo("/api/v1");
        }

        @Test
        @DisplayName("미설정(null·공백)이면 기본값으로 떨어진다 — 단위 시험의 직접 생성 경로")
        void 미설정이면_기본값으로_떨어진다() {
            assertThat(PublicApiPathDefaults.join(null, "/videos/1/stream")).isEqualTo("/api/v1/videos/1/stream");
            assertThat(PublicApiPathDefaults.join("   ", "/videos/1/stream")).isEqualTo("/api/v1/videos/1/stream");
        }
    }

    @Nested
    @DisplayName("정규화")
    class Normalize {

        @Test
        @DisplayName("★포털 향 값을 주면 그 접두어로 조립된다 — 이 축이 깨지면 영상이 남의 서버로 간다")
        void 포털향_접두어가_그대로_반영된다() {
            assertThat(PublicApiPathDefaults.join("/authoring-api/v1", "/videos/9001/stream"))
                    .isEqualTo("/authoring-api/v1/videos/9001/stream");
        }

        @Test
        @DisplayName("관제 향 두 세그먼트 접두어도 그대로 반영된다")
        void 관제향_접두어가_그대로_반영된다() {
            assertThat(PublicApiPathDefaults.join("/label-studio/api/v1", "/videos/9001/stream"))
                    .isEqualTo("/label-studio/api/v1/videos/9001/stream");
        }

        @Test
        @DisplayName("뒤 슬래시는 떼어 '//' 가 생기지 않게 한다")
        void 뒤_슬래시는_떼어낸다() {
            assertThat(PublicApiPathDefaults.join("/authoring-api/v1/", "/videos/1/image"))
                    .isEqualTo("/authoring-api/v1/videos/1/image");
            assertThat(PublicApiPathDefaults.join("/authoring-api/v1///", "/videos/1/image"))
                    .isEqualTo("/authoring-api/v1/videos/1/image");
        }

        /**
         * ⚠ <b>기동 가드는 이 값을 거부한다</b>(아래 {@code Guard}). 두 층의 역할이 다르다 —
         * 가드는 「운영자가 제대로 적었는가」를 배포 시점에 묻고, 정규화는 그럼에도 흘러든 값이
         * 조용히 이상한 주소를 만들지 않게 하는 안전망이다. 둘을 하나로 합치지 말 것.
         */
        @Test
        @DisplayName("앞 슬래시가 없으면 붙인다 — 가드는 거부하지만 정규화는 안전망으로 메운다")
        void 앞_슬래시가_없으면_붙인다() {
            assertThat(PublicApiPathDefaults.normalize("authoring-api/v1")).isEqualTo("/authoring-api/v1");
        }

        @Test
        @DisplayName("suffix 가 '/' 로 시작하지 않으면 거부한다 — 조용히 붙여 주지 않는다")
        void suffix_형식을_강제한다() {
            assertThatThrownBy(() -> PublicApiPathDefaults.join("/api/v1", "videos/1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("★소스 스캔 — 접두어를 다시 하드코딩하지 못하게 한다")
    class NoHardcodedPrefix {

        /**
         * 접두어 리터럴이 <b>다시 코드로 돌아오는 것</b>을 막는다.
         *
         * <p>단위 시험으로 {@code join} 의 계산만 지키면, 누군가 서비스에서 그 함수를 안 쓰고
         * 문자열을 다시 적어도 <b>전부 통과한다.</b> 실제로 이 결함의 원래 모습이 그것이었다 —
         * 세 서비스가 각자 {@code "/api/v1/..."} 을 적고 있었고 아무도 몰랐다.
         *
         * <p>⚠ 값의 단일 출처인 {@link PublicApiPathDefaults} 자신은 제외한다(거기 있어야 한다).
         * 시험 코드도 제외한다 — 기대값으로 그 문자열을 적는 것이 정상이다.
         */
        @Test
        @DisplayName("운영 코드에 브라우저 호출 접두어를 다시 적지 않는다")
        void 운영_코드에_접두어를_다시_적지_않는다() throws Exception {
            Path mainRoot = Path.of("src/main/java");
            String owner = PublicApiPathDefaults.class.getSimpleName() + ".java";

            List<String> offenders = new ArrayList<>();
            try (Stream<Path> files = Files.walk(mainRoot)) {
                for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (f.getFileName().toString().equals(owner)) continue;
                    String body = stripComments(Files.readString(f));
                    if (body.contains("\"/api/v1")) {
                        offenders.add(mainRoot.relativize(f).toString());
                    }
                }
            }

            assertThat(offenders)
                    .as("브라우저가 부를 주소의 접두어는 %s 가 소유한다. "
                            + "이 목록에 파일이 있으면 그 파일이 접두어를 다시 굳힌 것이며, "
                            + "포털 향에서 그 주소가 «포털 자신의 WAS» 로 날아간다.",
                            PublicApiPathDefaults.class.getSimpleName())
                    .isEmpty();
        }

        /**
         * 주석을 걷어낸 코드 본문 — 스캔은 <b>코드</b>만 본다.
         *
         * <p>이 저장소는 주석에 결정 근거를 싣는다. 「왜 {@code /api/v1} 을 여기 적지 않는가」를
         * 설명하려면 그 문자열이 주석에 나올 수밖에 없고, 원문 그대로 훑으면 그 설명이 위반으로
         * 잡혀 <b>설명을 지우는 쪽으로 압력</b>이 간다.
         */
        private String stripComments(String source) {
            StringBuilder kept = new StringBuilder();
            boolean inBlock = false;
            for (String line : source.split("\n", -1)) {
                String trimmed = line.strip();
                if (inBlock) {
                    if (trimmed.contains("*/")) inBlock = false;
                    continue;
                }
                if (trimmed.startsWith("//")) continue;
                if (trimmed.startsWith("/*")) {
                    if (!trimmed.contains("*/")) inBlock = true;
                    continue;
                }
                kept.append(line).append('\n');
            }
            return kept.toString();
        }
    }

    @Nested
    @DisplayName("기동 가드 — 잘못된 값은 배포 시점에 시끄럽게 실패한다")
    class Guard {

        @Test
        @DisplayName("정상값과 미설정은 통과한다")
        void 정상값은_통과한다() {
            assertThatCode(() -> new PublicApiPathGuard("/authoring-api/v1").validate()).doesNotThrowAnyException();
            assertThatCode(() -> new PublicApiPathGuard("/label-studio/api/v1").validate()).doesNotThrowAnyException();
            assertThatCode(() -> new PublicApiPathGuard("").validate()).doesNotThrowAnyException();
            assertThatCode(() -> new PublicApiPathGuard(null).validate()).doesNotThrowAnyException();
        }

        /**
         * ★ 절대 주소를 허용하면 우리가 내려준 주소가 곧 <b>바깥으로 끌고 가는 통로</b>가 된다
         * (CWE-601). {@code ..} 는 접두어를 벗어나는 주소를 만든다(CWE-22).
         * 공백·제어문자는 응답 본문과 로그에 그대로 실린다(CWE-117).
         */
        @ParameterizedTest(name = "부적합 값은 기동을 막는다: [{0}]")
        @ValueSource(strings = {
                "api/v1",                       // 앞 슬래시 없음
                "//other.example/api/v1",       // 프로토콜 상대
                "http://other.example/api/v1",  // 스킴·호스트
                "/api/../../v1",                // 상위 경로 순회
                "/api/v1?x=1",                  // 질의
                "/api/v1#frag",                 // 조각
                "/api/v1 with space",           // 공백
                "/api/v1\nInjected: 1",         // 제어문자(로그 인젝션)
        })
        void 부적합_값은_기동을_막는다(String bad) {
            assertThatThrownBy(() -> new PublicApiPathGuard(bad).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PublicApiPathDefaults.PROPERTY_KEY);
        }
    }
}
