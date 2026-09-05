package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>「표식이 붙는다 ⟺ 노드 원장에서 고른 절대 목적지다」</b> 를 무는 구조 가드 — 프로덕션 소스 스캔.
 * [@design ADR-057]
 *
 * <h3>이 시험이 지키는 것</h3>
 * <p>{@code IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE} 표식은 두 가지를 <b>동시에</b>
 * 결정한다:
 * <ol>
 *   <li>URL 재작성 필터가 그 요청의 목적지를 <b>덮지 않는다</b>(설정 override 가 원장 선택을 못 이긴다).</li>
 *   <li>자격증명 가드가 그 요청의 인증 헤더를 <b>떼지 않는다</b>
 *       ({@code IntegrationEndpointTransportGuards#stripCredentialOnHostChange}).</li>
 * </ol>
 * ②는 <b>「그 목적지가 우리가 원장에서 고른 곳이다」</b> 라는 전제 위에 있다. 원장을 거치지 않고
 * 표식만 다는 경로가 생기면, 배포 기본값과 다른 <b>임의의</b> 호스트로 자격증명이 따라가게 된다(CWE-522).
 *
 * <h3>★ 이 가드가 두 방식으로 우회됐다 (2026-09-01 보강)</h3>
 * <ol>
 *   <li><b>상수명만 훑었다</b> — 표식의 <b>값</b>은 클래스명 + 접미사라 재구성이 쉽다. 새 호출부가
 *       그 값을 <b>문자열 리터럴로 직접</b> 쓰면 상수명이 소스에 없어 스캔에 걸리지 않았고, 필터·가드는
 *       그 요청을 정상적으로 「고른 목적지」로 대우한다. 그래서 지금은 상수명과 <b>값의 접미사</b>를
 *       함께 훑는다({@link #MARKER_TOKENS}).</li>
 *   <li><b>파일을 셌고 문장을 세지 않았다</b> — 같은 파일 안에 <b>조건 없는 부착</b>을 하나 더 넣어도
 *       단언이 전부 초록이었다(파일 수는 그대로 1). 그래서 지금은 <b>부착 지점(문장) 개수</b>를 센다.</li>
 * </ol>
 *
 * <h3>★ 왜 런타임 행위 시험으로는 못 잡는가</h3>
 * <p>잘못된 경로가 생겨도 <b>요청은 정상적으로 나가고 자격증명도 붙는다</b> — 관측 가능한 실패가 없다.
 * 틀린 것은 「그 목적지가 원장에서 온 것인가」뿐이고 그건 <b>코드의 모양</b>이지 실행 결과가 아니다.
 * 그래서 이 축의 유일한 수단이 소스 스캔이다.
 *
 * <h3>⚠ 이 시험이 막아설 때 — 숫자만 올리고 지나가지 말 것</h3>
 * <p>표식 부착 지점을 <b>정당하게</b> 늘리려면 그 지점이 아래 둘을 만족해야 한다:
 * <ul>
 *   <li>목적지가 <b>노드 원장에서 고른 장비</b>({@code LS_AI_SRVR.SRVR_ADDR})에서 왔을 것 —
 *       설정값·요청 파라미터·문자열 조합에서 온 목적지에 표식을 달면 안 된다.</li>
 *   <li>표식을 <b>목적지가 실제로 정해졌을 때만</b> 달 것(목적지가 없으면 상대 경로 + 표식 없음).
 *       무조건 달면 배포 기본 주소로 나가는 요청까지 자격증명 가드를 통과한다.</li>
 * </ul>
 * 그 둘을 확인했다면 아래 {@code EXPECTED_ATTACHMENT_SITES} 를 올리고, <b>왜 그 지점이 위 조건을 만족하는지</b>
 * 를 이 주석에 함께 남긴다.
 */
class ExplicitTargetMarkerCallSiteGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /** 표식 상수의 이름 — 상수를 참조하는 부착 지점이 이 토큰으로 나타난다. */
    private static final String MARKER = "EXPLICIT_TARGET_ATTRIBUTE";

    /**
     * 표식 <b>값</b>의 고유 접미사 — 상수를 쓰지 않고 값을 리터럴로 적는 우회를 잡는다.
     *
     * <p>값 전체({@code 클래스 FQN + 접미사})가 아니라 접미사를 쓰는 이유는, 우회 코드가 값을
     * {@code "…Filter" + ".explicitTarget"} 처럼 <b>쪼개 적어도</b> 걸리게 하기 위해서다. 값 자체는
     * 하드코딩하지 않고 상수에서 파생해 대조한다({@link #표식_값의_접미사가_실제_값과_일치한다}).
     */
    private static final String MARKER_VALUE_SUFFIX = "explicitTarget";

    /** 부착 지점을 세는 토큰 — 상수 참조와 값 리터럴 양쪽. */
    private static final List<String> MARKER_TOKENS = List.of(MARKER, MARKER_VALUE_SUFFIX);

    /** 표식을 <b>정의</b>하는 파일(선언 1 + 재작성 필터의 소비 1). */
    private static final String FILTER = "IntegrationEndpointExchangeFilter.java";

    /** 표식을 <b>소비</b>하는 가드(자격증명 유지 판정). */
    private static final String CREDENTIAL_GUARD = "IntegrationEndpointTransportGuards.java";

    /**
     * 표식을 <b>붙이는</b> 지점의 예상 개수 — <b>파일이 아니라 부착 문장</b>의 수다. 지금은 시계열 위탁
     * 클라이언트의 한 문장뿐이다.
     *
     * <p>이 숫자를 올리기 전에 위 클래스 주석 §「이 시험이 막아설 때」를 읽을 것.
     */
    private static final int EXPECTED_ATTACHMENT_SITES = 1;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    @Test
    @DisplayName("★표식은_원장에서_고른_목적지에만_붙는다 — 부착 지점이 늘면 자격증명 가드가 조용히 약해진다")
    void 표식은_원장에서_고른_목적지에만_붙는다() {
        List<Path> referencing = sourcesReferencing(MARKER_TOKENS);

        // ① 표식을 붙이는(정의·소비가 아닌) <b>문장</b>이 예상 개수 그대로여야 한다.
        //    파일 수로 세면 같은 파일에 부착을 하나 더 넣는 우회가 통과한다(실측).
        List<Path> callSites = referencing.stream()
                .filter(p -> !p.getFileName().toString().equals(FILTER))
                .filter(p -> !p.getFileName().toString().equals(CREDENTIAL_GUARD))
                .toList();
        int attachments = callSites.stream().mapToInt(p -> countTokens(read(p), MARKER_TOKENS)).sum();
        assertThat(attachments)
                .as("표식을 붙이는 지점이 늘었다(파일=%s) — 그 목적지가 노드 원장(LS_AI_SRVR)에서 왔고 "
                        + "목적지가 실제로 정해졌을 때만 붙는지 확인하고, 그렇다면 "
                        + "EXPECTED_ATTACHMENT_SITES 를 올리며 <왜 그 지점이 그 두 조건을 만족하는지>를 "
                        + "이 클래스 주석에 남길 것. 원장을 거치지 않은 목적지에 표식을 달면 URL 재작성과 "
                        + "자격증명 가드가 동시에 면제되어 임의 호스트로 자격증명이 따라간다(CWE-522).",
                        callSites)
                .isEqualTo(EXPECTED_ATTACHMENT_SITES);
        assertThat(callSites)
                .as("부착 지점이 있어야 할 파일이 사라졌다 — 배선이 통째로 빠지면 이중화 위탁이 "
                        + "설정 override 에 덮인다")
                .isNotEmpty();

        // ② 그 지점은 <b>절대 목적지가 있을 때만</b> 표식을 단다.
        String body = stripComments(read(callSites.get(0)));
        assertThat(body)
                .as("표식이 목적지 유무와 무관하게 붙으면, 배포 기본 주소로 나가는 요청까지 "
                        + "자격증명 가드를 통과한다")
                .containsPattern("if\\s*\\(\\s*target\\s*!=\\s*null\\s*\\)\\s*\\{\\s*"
                        + "[^}]*" + MARKER);

        // ③ 그 목적지는 노드 원장에서 고른 장비 주소를 푼 값이어야 한다(설정값·문자열 조합이 아니다).
        assertThat(body)
                .as("목적지 해석은 선택기와 같은 단일 술어(PinnedTarget)를 통해야 한다 — "
                        + "여기서 자기 기준을 쓰면 「고를 수는 있는데 보낼 수는 없는 장비」가 생긴다")
                .contains("PinnedTarget.resolve(srvrAddr");

        // ④ 자격증명 가드가 실제로 이 표식을 읽고 있어야 한다(배선이 사라지면 이중화 위탁이 무인증).
        Path guard = referencing.stream()
                .filter(p -> p.getFileName().toString().equals(CREDENTIAL_GUARD))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "자격증명 가드가 표식을 읽지 않는다 — 이중화된 두 번째 장비로 가는 정상 위탁이 "
                                + "전량 무인증(401 확정 실패)이 된다"));
        assertThat(stripComments(read(guard)))
                .as("표식 확인이 호스트 비교보다 <b>앞</b>에 있어야 헤더가 유지된다")
                .containsPattern(MARKER + "[\\s\\S]{0,200}?sameHost");
    }

    /**
     * 표식 값의 접미사가 <b>실제 상수 값</b>과 일치하는지 고정한다 — 값이 개명되면 스캔이 조용히
     * 아무것도 못 잡게 되므로, 그 어긋남을 여기서 시끄럽게 만든다.
     */
    @Test
    @DisplayName("표식_값의_접미사가_실제_값과_일치한다 — 개명되면 값 리터럴 스캔이 조용히 무력해진다")
    void 표식_값의_접미사가_실제_값과_일치한다() {
        assertThat(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE)
                .as("표식 값이 바뀌었다 — MARKER_VALUE_SUFFIX 를 새 값에 맞춰 갱신할 것"
                        + "(안 맞추면 값 리터럴 우회를 다시 놓친다)")
                .endsWith("." + MARKER_VALUE_SUFFIX);
    }

    private static List<Path> sourcesReferencing(List<String> tokens) {
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            List<Path> hits = new ArrayList<>();
            paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> countTokens(read(p), tokens) > 0)
                    .forEach(hits::add);
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 주석을 지운 본문에서 토큰 등장 <b>횟수</b>를 센다(문장 단위 판정의 근거). */
    private static int countTokens(String source, List<String> tokens) {
        String body = stripComments(source);
        int count = 0;
        for (String token : tokens) {
            int from = body.indexOf(token);
            while (from >= 0) {
                count++;
                from = body.indexOf(token, from + token.length());
            }
        }
        return count;
    }

    /** 주석을 지운 뒤 매칭한다 — 설명문이 상수명을 언급하기만 해도 위반으로 오탐된다. */
    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
