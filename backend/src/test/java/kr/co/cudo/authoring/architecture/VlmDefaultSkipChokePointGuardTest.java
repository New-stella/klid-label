package kr.co.cudo.authoring.architecture;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>전체 설정 건너뛰기의 자동 표식은 위탁 코드와 «같은 메서드» 안에 있어야 한다</b>는 구조 가드.
 * [@design ADR-050]
 *
 * <h2>무엇이 깨져 있었나</h2>
 * <p>자동 표식({@code VlmDefaultSkipMarker.applyBeforeStage})의 호출부가 오케스트레이터 <b>한 곳뿐</b>
 * 이었다. 그런데 시계열 위탁 진입점은 그 밖에도 있다 — {@code VlmWithheldResumeRunner} 가
 * {@code run}/{@code runWithMarking} 을 <b>직접</b> 부르고, 그 러너는 비식별 신고 해소 이벤트와
 * <b>주기 미결 스위퍼</b>(사람 개입 0)에서 도달한다. 스텝 안의 게이트는 표식을 <b>읽기만</b> 하므로
 * 표식을 세우는 자가 없는 그 경로는 <b>항상 통과</b>했고, 스위치가 켜져 있어도 외부 벤더가 호출됐다.
 *
 * <h2>왜 「호출부마다 배선」이 아니라 「choke point」인가</h2>
 * <p>호출부마다 표식을 세우면 <b>새 진입점이 생길 때마다 다시 새는</b>다 — 실제로 그렇게 샜다. 표식을
 * 전송 코드와 같은 메서드({@code doSubmit})에 두면 <b>어떤 호출자도 우회할 수 없다</b>(비식별 신고
 * 게이트·수동 스킵 게이트를 그 메서드에 둔 것과 같은 논리). 그래서 이 가드는 호출부를 세지 않고
 * <b>choke point 가 온전한지</b>를 고정한다 — 호출부 개수를 단언하면 진입점이 하나 늘 때마다
 * 가드가 낡아 사람이 숫자만 고치고 지나간다.
 *
 * <h2>★왜 파일 하나가 아니라 {@code src/main} 전역을 스캔하는가</h2>
 * <p>구 가드는 스캔 대상이 {@code VlmTimeseriesStep.java} <b>한 파일</b>이었다. 그러면
 * <b>다른 클래스가 외부 전송을 새로 호출해도</b> 그 경로는 표식·게이트를 지나지 않는데 가드는 전건
 * 통과한다 — choke point 라는 전제가 무너진 바로 그 상태를 초록불로 덮는다(이 가드를 만들게 한
 * 재개 경로 누수가 정확히 그 형태였다). 그래서 「어느 파일을 읽는가」를 전역으로 넓혀
 * <b>허용된 단일 전송 지점 밖에는 전송 호출이 없다</b>를 고정한다. 판정 자체(주석 제거 · 중괄호
 * 균형으로 메서드 본문 절단)는 그대로 재사용한다.
 *
 * <h2>무엇을 보지 않는가</h2>
 * <p>이 스캔은 <b>구조</b>만 본다. 「그래서 실제로 외부 호출이 0 이 되는가」는 행위 테스트가 고정한다
 * ({@code batch.step.VlmDefaultSkipResumePathTest}). 두 축이 짝이다.
 * <p>전송 심볼은 <b>메서드 이름</b>으로 식별한다. 전송을 다른 이름의 래퍼로 감싸 부르면 이 스캔은
 * 보지 못한다 — 그 경우는 래퍼 자신이 여기 걸리므로(래퍼도 결국 이 심볼을 부른다) 한 겹까지는
 * 잡히지만, 두 겹 이상은 사람이 봐야 한다.
 */
class VlmDefaultSkipChokePointGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");
    /** 유일하게 허용된 전송 지점(choke point)을 가진 파일. */
    private static final Path STEP = MAIN_SRC.resolve(
            "kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java");

    /**
     * 외부 벤더로 나가는 호출 — 이 심볼이 등장하는 메서드가 곧 「전송 코드」다.
     *
     * <p>수신자 이름을 <b>가리지 않는다</b>(점 + 메서드명). 필드명을 바꾸거나 다른 빈에 주입해도
     * 걸리게 하기 위해서다. {@code VlmClient} 의 선언부는 앞에 점이 없어 걸리지 않는다.
     */
    private static final String VENDOR_SUBMIT = ".submitDescribe(";
    /** 자동 표식 적용 — choke point 의 표식이다. */
    private static final String AUTO_SKIP_MARK = "vlmDefaultSkipMarker.applyBeforeStage(";
    /** 건너뛰기 게이트 — 표식을 <b>읽는</b> 쪽. */
    private static final String SKIP_GATE = "batchStatusService.isStageManuallySkipped(";

    /**
     * 전역 스캔이 <b>헛돌지 않았다</b>는 최소 근거(작업 디렉터리가 어긋나 0건을 훑고 통과하는 것을
     * 막는다). 소스는 줄기만 하지 않으므로 이 하한은 낡지 않는다 — 「호출부가 N개」류의 개수 단언이
     * 아니다.
     */
    private static final int MIN_SCANNED_SOURCES = 100;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /**
     * ★자동 표식은 게이트보다 <b>앞</b>에 있어야 한다 — 뒤에 두면 그 회차에는 게이트가 표식을 못 보고
     * 그대로 통과해 외부 호출이 나간다(다음 회차부터 막히므로 «가끔 새는» 형태가 되어 더 나쁘다).
     */
    @Test
    @DisplayName("★★자동_건너뛰기_표식은_위탁_게이트보다_앞에서_적용된다")
    void autoSkipMarkPrecedesGate() {
        String src = read(STEP);
        int mark = src.indexOf(AUTO_SKIP_MARK);
        int gate = src.indexOf(SKIP_GATE);

        assertThat(mark)
                .as("VlmTimeseriesStep 이 전체 설정 자동 표식(%s)을 적용하지 않는다. 그러면 재개 러너"
                        + "(신고 해소 · 미결 스위퍼) 경로에는 표식을 세우는 자가 없어 게이트가 항상"
                        + " 통과하고, 스위치가 켜져 있는데도 외부 벤더가 호출된다.", AUTO_SKIP_MARK)
                .isGreaterThanOrEqualTo(0);
        assertThat(gate).as("건너뛰기 게이트(%s)가 사라졌다.", SKIP_GATE).isGreaterThanOrEqualTo(0);
        assertThat(mark)
                .as("자동 표식은 게이트보다 앞에서 적용돼야 한다 — 뒤에 두면 그 회차에는 게이트가"
                        + " 표식을 보지 못하고 외부 호출이 나간다.")
                .isLessThan(gate);
    }

    /**
     * ★★{@code src/main} 전역에서 외부 전송 호출은 <b>choke point 파일 밖에 존재하지 않는다</b>.
     *
     * <p>파일 하나만 읽는 가드는 <b>다른 클래스의 신규 전송자</b>를 영영 못 잡는다 — 그 경로는 표식도
     * 게이트도 지나지 않는데 가드는 초록불이 된다. 그것이 정확히 이 가드를 만들게 한 누수의 형태다.
     */
    @Test
    @DisplayName("★★외부_전송_호출은_src_main_전역에서_choke_point_밖에_존재하지_않는다")
    void noVendorSubmitOutsideChokePoint() {
        List<Path> sources = mainSources();
        assertThat(sources.size())
                .as("스캔이 사실상 비어 있다(%d개) — 작업 디렉터리(%s)가 어긋나면 이 가드는 아무것도"
                        + " 훑지 않고 통과한다.", sources.size(), MAIN_SRC.toAbsolutePath())
                .isGreaterThan(MIN_SCANNED_SOURCES);

        Set<String> outside = new TreeSet<>();
        for (Path java : sources) {
            if (java.equals(STEP)) {
                continue;
            }
            if (read(java).contains(VENDOR_SUBMIT)) {
                outside.add(rel(java));
            }
        }

        assertThat(outside)
                .as("choke point(%s) 밖에서 외부 전송(%s)을 호출하는 소스가 있다: %s."
                        + " 그 경로는 자동 표식(%s)도 수동 스킵 게이트도 지나지 않으므로, 스위치가"
                        + " 켜져 있어도 외부 벤더로 영상이 나간다. 전송은 choke point 한 곳에서만 하라.",
                        rel(STEP), VENDOR_SUBMIT, outside, AUTO_SKIP_MARK)
                .isEmpty();

        assertThat(read(STEP))
                .as("choke point(%s) 가 외부 전송(%s)을 더 이상 갖고 있지 않다 — 전송이 다른 곳으로"
                        + " 옮겨갔다면 이 가드의 허용 지점도 함께 갱신해야 한다.", rel(STEP), VENDOR_SUBMIT)
                .contains(VENDOR_SUBMIT);
    }

    /**
     * ★★전송 코드와 표식이 <b>같은 메서드</b> 안에 있어야 한다 — 표식이 다른 메서드로 옮겨가면
     * 그 메서드를 거치지 않는 새 진입점이 곧바로 게이트를 우회한다.
     *
     * <p>동시에 <b>전송 지점이 하나</b>임을 고정한다. 표식을 지나지 않는 두 번째 전송 메서드가 생기면
     * choke point 라는 전제 자체가 무너진다. 이 판정도 {@code src/main} 전역을 훑는다 — 두 번째
     * 전송 메서드가 <b>다른 클래스</b>에 생기는 것이 가장 흔한 형태이기 때문이다.
     */
    @Test
    @DisplayName("★★외부_전송_지점은_하나이고_그_메서드_안에서_자동_표식이_적용된다")
    void vendorSubmitLivesInTheMarkedMethod() {
        List<String> submitting = new ArrayList<>();
        Set<String> owners = new TreeSet<>();
        for (Path java : mainSources()) {
            String src = read(java);
            if (!src.contains(VENDOR_SUBMIT)) {
                continue;
            }
            owners.add(rel(java));
            methodBodies(src).stream()
                    .filter(body -> body.contains(VENDOR_SUBMIT))
                    .forEach(submitting::add);
        }

        assertThat(owners)
                .as("외부 벤더 전송(%s)을 가진 소스는 choke point 하나여야 한다. 발견: %s",
                        VENDOR_SUBMIT, owners)
                .containsExactly(rel(STEP));
        assertThat(submitting)
                .as("외부 벤더 전송(%s) 지점은 하나여야 한다. 둘 이상이면 자동 표식을 지나지 않는"
                        + " 전송 경로가 생겨 choke point 전제가 무너진다.", VENDOR_SUBMIT)
                .hasSize(1);
        assertThat(submitting.get(0))
                .as("외부 전송과 자동 표식(%s)은 같은 메서드 안에 있어야 한다 — 표식이 다른 메서드로"
                        + " 옮겨가면 그 메서드를 거치지 않는 새 진입점이 게이트를 우회한다.", AUTO_SKIP_MARK)
                .contains(AUTO_SKIP_MARK);
    }

    /**
     * ★{@code run}/{@code runWithMarking} 은 <b>choke point 로 위임하기만</b> 해야 한다.
     *
     * <p>둘은 재개 러너가 직접 부르는 public 진입점이다. 그 안에서 전송·게이트를 자체 구현하면
     * 표식을 지나지 않는 두 번째 경로가 되며, 그것이 정확히 이번에 고친 결함의 형태다.
     */
    @Test
    @DisplayName("★재개_러너가_부르는_공개_진입점은_choke_point로만_위임한다")
    void publicEntryPointsOnlyDelegate() {
        String src = read(STEP);
        Set<String> violations = new TreeSet<>();
        for (String entry : List.of("run(Long rawSn)", "runWithMarking(Long rawSn, LsMarking marking)")) {
            String body = methodBodyStartingWith(src, entry);
            assertThat(body).as("공개 진입점 %s 을 찾지 못했다(시그니처가 바뀌었으면 이 가드도 함께 갱신).",
                    entry).isNotNull();
            if (!body.contains("doSubmit(")) {
                violations.add(entry + " → doSubmit 위임 없음");
            }
            if (body.contains(VENDOR_SUBMIT)) {
                violations.add(entry + " → 전송을 자체 구현");
            }
        }
        assertThat(violations)
                .as("run/runWithMarking 은 choke point(doSubmit)로만 위임해야 한다. 발견: %s", violations)
                .isEmpty();
    }

    /* ========== helpers ========== */

    /** {@code src/main/java} 아래 모든 자바 소스(전역 스캔의 모집단). */
    private static List<Path> mainSources() {
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 메시지에 싣는 소스 경로(모듈 루트 기준 상대). */
    private static String rel(Path path) {
        return MAIN_SRC.relativize(path).toString();
    }

    /** 최상위 메서드 본문들을 중괄호 균형으로 잘라 낸다(주석 제거 후 호출). */
    private static List<String> methodBodies(String src) {
        List<String> bodies = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("\\)\\s*\\{").matcher(src);
        while (m.find()) {
            String body = balancedBlock(src, m.end() - 1);
            if (body != null) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /** 시그니처 조각으로 시작하는 메서드의 본문. 못 찾으면 {@code null}. */
    private static String methodBodyStartingWith(String src, String signatureFragment) {
        int at = src.indexOf(signatureFragment);
        if (at < 0) {
            return null;
        }
        int brace = src.indexOf('{', at);
        return brace < 0 ? null : balancedBlock(src, brace);
    }

    /** {@code openBrace} 위치의 여는 중괄호부터 짝이 맞는 닫는 중괄호까지. */
    private static String balancedBlock(String src, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(openBrace, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 주석을 <b>제거하고</b> 읽는다 — 주석의 심볼 언급은 호출이 아니다. 제거하지 않으면 이 결함을
     * 설명하는 javadoc·인라인 주석이 그대로 「호출」로 잡혀 가드가 오탐이 된다.
     */
    private static String read(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(raw).replaceAll("")).replaceAll("");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 스캔 대상 확인용 — 파일이 옮겨가면 위 상수도 함께 갱신돼야 한다. */
    @Test
    @DisplayName("스캔_대상_파일이_실재한다")
    void scanTargetExists() {
        assertThat(Files.exists(STEP)).as("%s 가 없다 — 파일이 옮겨갔으면 이 가드의 경로 상수를 갱신하라.",
                STEP).isTrue();
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            assertThat(paths.anyMatch(p -> p.toString().endsWith("VlmDefaultSkipMarker.java"))).isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
