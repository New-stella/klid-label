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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * export 개인정보 3필드 <b>판정·상수 단일 원천 가드</b> — 프로덕션 소스 스캔.
 *
 * <h3>무엇을 막는가</h3>
 * <p>{@code anonymity}/{@code pseudonymity}/{@code privacy_included} 의 <b>판정 로직과 기본값 상수</b>는
 * {@code ExportPrivacyPolicy} 한 곳에만 존재해야 한다. 복제되면 한쪽만 갱신돼 조용히 어긋난다 —
 * 이 저장소에서 실제로 두 번 났던 사고다:
 * <ul>
 *   <li>2026-08-03 — 프레임 패널이 구 {@code PRVC_TYPE_CD} 파생을 들고 있어 <b>화면은 N / export 는 Y</b>
 *       로 갈렸다(사용자가 보는 값 ≠ 파일 값).</li>
 *   <li>2026-08-04 — 원천 축 전환. 판정이 매퍼에 인라인돼 있었다면 두 블록 중 하나만 바뀌었을 것이다.</li>
 * </ul>
 *
 * <h3>어떻게 막는가 (mutation 로 검증된 판정축)</h3>
 * <ol>
 *   <li>기본값 <b>상수 정의</b>({@code DEID_DEFAULT_*}/{@code ORGNL_DEFAULT_*})는 정책 클래스에만 있다 —
 *       다른 파일은 <b>참조만</b> 한다.</li>
 *   <li>export JSON 조립 2종({@code VideoMetaMapper}·{@code NiaJsonBuilder})은 3필드를 각각
 *       {@code ExportPrivacyPolicy.resolve*} <b>3회 호출</b>로만 산출한다 — 한 필드라도 리터럴/삼항으로
 *       인라인하면 호출 수가 줄어 실패한다.</li>
 *   <li>화면 프리필 2종({@code VideoPrivacyMetaService}·{@code FramePrivacyMetaService})은 상수를
 *       하드코딩하지 않고 정책 상수를 참조한다 — 화면↔export 정합의 기계적 근거다.</li>
 * </ol>
 *
 * <p>주석 스트리핑 필수 — Javadoc 이 상수명을 언급하기만 해도 오탐되기 때문
 * ({@code MngAcctWriteGuardTest}·{@code LsDataIngestWriteGuardTest} 와 동일 관례).
 */
class ExportPrivacyPolicySingleSourceGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /** 기본값 상수 <b>정의</b>(선언) 패턴 — 참조({@code ExportPrivacyPolicy.DEID_DEFAULT_X})는 걸리지 않는다. */
    private static final Pattern DEFAULT_CONSTANT_DECLARATION = Pattern.compile(
            "static\\s+final\\s+String\\s+(DEID_DEFAULT_|ORGNL_DEFAULT_)\\w+\\s*=\\s*\"");

    private static final String POLICY_FILE = "ExportPrivacyPolicy.java";

    @Test
    @DisplayName("개인정보_기본값_상수는_정책클래스에만_정의된다")
    void 개인정보_기본값_상수는_정책클래스에만_정의된다() {
        // given / when — main 트리 전체를 주석 제거 후 스캔
        List<String> violations = new ArrayList<>();
        forEachJavaFile((path, source) -> {
            if (path.getFileName().toString().equals(POLICY_FILE)) {
                return;
            }
            Matcher m = DEFAULT_CONSTANT_DECLARATION.matcher(source);
            if (m.find()) {
                violations.add(path.toString());
            }
        });

        // then — 값의 단일 원천은 정책 클래스 하나다(복제하면 한쪽만 갱신돼 어긋난다).
        assertThat(violations)
                .as("개인정보 기본값 상수를 <정의>한 파일 — 참조만 허용된다")
                .isEmpty();
    }

    @Test
    @DisplayName("export_조립_2종은_3필드를_정책_판정기_호출로만_산출한다")
    void export_조립_2종은_3필드를_정책_판정기_호출로만_산출한다() {
        // given / when
        String videoMapper = strippedSourceOf("VideoMetaMapper.java");
        String jsonBuilder = strippedSourceOf("NiaJsonBuilder.java");

        // then — 각 파일에서 정확히 3회(익명·가명·개인정보). 하나라도 인라인하면 수가 줄어 실패한다.
        assertThat(countResolveCalls(videoMapper))
                .as("VideoMetaMapper 의 ExportPrivacyPolicy.resolve* 호출 수")
                .isEqualTo(3);
        assertThat(countResolveCalls(jsonBuilder))
                .as("NiaJsonBuilder 의 ExportPrivacyPolicy.resolve* 호출 수")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("화면_프리필_2종은_정책_상수를_참조한다")
    void 화면_프리필_2종은_정책_상수를_참조한다() {
        // given / when — 화면이 상수를 하드코딩하면 export 와 어긋난다(2026-08-03 실사고).
        for (String file : List.of("VideoPrivacyMetaService.java", "FramePrivacyMetaService.java")) {
            String source = strippedSourceOf(file);

            // then — 정책 상수 참조가 3필드 모두 존재한다.
            assertThat(source).as("%s 의 익명 기본상수 참조", file)
                    .contains("ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY");
            assertThat(source).as("%s 의 가명 기본상수 참조", file)
                    .contains("ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY");
            assertThat(source).as("%s 의 개인정보 기본상수 참조", file)
                    .contains("ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int countResolveCalls(String source) {
        Matcher m = Pattern.compile("ExportPrivacyPolicy\\.resolve\\w+\\(").matcher(source);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static String strippedSourceOf(String fileName) {
        List<String> found = new ArrayList<>();
        forEachJavaFile((path, source) -> {
            if (path.getFileName().toString().equals(fileName)) {
                found.add(source);
            }
        });
        assertThat(found).as("%s 파일을 찾지 못했다 — 파일명이 바뀌면 가드가 무력화된다", fileName)
                .hasSize(1);
        return found.get(0);
    }

    private static void forEachJavaFile(SourceVisitor visitor) {
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String raw = Files.readString(p, StandardCharsets.UTF_8);
                    String stripped = LINE_COMMENT.matcher(
                            BLOCK_COMMENT.matcher(raw).replaceAll("")).replaceAll("");
                    visitor.visit(p, stripped);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface SourceVisitor {
        void visit(Path path, String strippedSource);
    }
}
