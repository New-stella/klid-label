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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파일 <b>서빙</b> 클래스가 링크를 따라가는 open API 로 되돌아가는 것을 막는 정적 가드.
 *
 * <p>배경: 저장소는 {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH}(={@code /nas-storage}) 인
 * 형상이 정상이라, 경로 문자열만 보는 lexical 검사는 <b>같은 base 안의 심링크</b>를 걸러내지 못한다.
 * {@code frames/deid/**} 안의 링크가 {@code frames/raw/**}(마스킹 전 원본)를 가리키면
 * {@code FileSystemResource}/{@code Files.newInputStream}(기본 옵션)/{@code new FileInputStream} 은 모두
 * <b>링크를 따라가</b> 원본 픽셀을 "비식별본"으로 200 서빙한다 — 외부 채널(포털)로도 나간다
 * (CWE-59/367/359).
 *
 * <p>그래서 서빙 경로의 규약은 ①실경로({@code toRealPath})로 서브트리를 판정하고 ②<b>판정에 쓴 그
 * 실경로</b>를 ③{@code NOFOLLOW_LINKS} 로 여는 것이다({@code FrameImageService.openNoFollow} 단일 헬퍼).
 * 이 계약은 여러 경로에 흩어져 있어 <b>한 곳만 되돌아가도 아무도 알아채지 못한다</b> — 실제로 포털
 * 서빙 2곳이 그렇게 남아 있었다(2026-07-30 발견·정합). 파일 스캔으로 회귀를 고정한다.
 *
 * <p>검사 대상은 "사용자에게 파일 바이트를 내보내는" 클래스로 한정한다. 업로드 수신·배치 변환 등은
 * 같은 API 를 써도 노출 표면이 아니므로 제외한다(과탐 방지).
 */
class FileServingLinkFollowGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /** 서빙 클래스 — 응답 본문으로 파일 바이트를 내보내는 곳. */
    private static final List<String> SERVING_CLASSES = List.of(
            "FrameImageService.java",       // 내부 라벨링 프레임(원본/비식별)
            "FrameImageController.java",    // 내부 프레임 서빙 진입점
            "VideoStreamService.java",      // 비식별 영상 스트리밍(Range)
            "PortalLabelService.java",      // 포털향 데이터마트 비식별 프레임
            "PortalUploadService.java",     // 포털 업로드 자산 프레임
            "PortalUploadLabelService.java",// 포털 업로드 원본 다운로드
            "FrameSource.java"              // export 산출 원천(복사 대상 파일 해석)
    );

    /**
     * 링크를 따라가는 파일 open/조회 API.
     *
     * <p>{@code Files.newInputStream} 은 {@code NOFOLLOW_LINKS} 인자를 주면 안전하므로 <b>같은 줄에
     * 옵션이 없을 때만</b> 위반으로 본다({@code openNoFollow} 구현 자체를 오탐하지 않기 위함).
     */
    private static final Pattern FILE_SYSTEM_RESOURCE = Pattern.compile("\\bnew\\s+FileSystemResource\\s*\\(");
    private static final Pattern FILE_INPUT_STREAM = Pattern.compile("\\bnew\\s+FileInputStream\\s*\\(");
    private static final Pattern NEW_INPUT_STREAM = Pattern.compile("Files\\.newInputStream\\s*\\([^)]*\\)");

    @Test
    @DisplayName("서빙_클래스는_링크추종_open_API를_쓰지_않는다")
    void servingClasses_doNotUseLinkFollowingOpen() {
        List<JavaSource> sources = loadServingSources();
        assertThat(sources)
                .as("서빙 클래스 목록(%s)이 소스에서 발견되어야 한다 — 파일명이 바뀌었으면 이 목록도 갱신할 것",
                        SERVING_CLASSES)
                .hasSize(SERVING_CLASSES.size());

        List<String> violations = new ArrayList<>();
        for (JavaSource source : sources) {
            String name = Paths.get(source.path()).getFileName().toString();
            if (FILE_SYSTEM_RESOURCE.matcher(source.content()).find()) {
                violations.add(name + ": new FileSystemResource(...) — 링크를 따라간다");
            }
            if (FILE_INPUT_STREAM.matcher(source.content()).find()) {
                violations.add(name + ": new FileInputStream(...) — 링크를 따라간다");
            }
            Matcher m = NEW_INPUT_STREAM.matcher(source.content());
            while (m.find()) {
                if (!m.group().contains("NOFOLLOW_LINKS")) {
                    violations.add(name + ": Files.newInputStream(...) 에 NOFOLLOW_LINKS 누락");
                }
            }
        }

        assertThat(violations)
                .as("파일 서빙은 실경로 판정 + NOFOLLOW open 규약을 지켜야 한다"
                        + "(FrameImageService.openNoFollow 재사용). 위반: %s", violations)
                .isEmpty();
    }

    private List<JavaSource> loadServingSources() {
        assertThat(Files.isDirectory(MAIN_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다", MAIN_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> SERVING_CLASSES.contains(p.getFileName().toString()))
                    .map(this::read)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스 스캔 실패", e);
        }
    }

    /** 주석을 제거해 설명문 안의 API 이름 언급이 위반으로 오탐되지 않게 한다. */
    private JavaSource read(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String noBlock = BLOCK_COMMENT.matcher(raw).replaceAll(" ");
            return new JavaSource(path.toString(), LINE_COMMENT.matcher(noBlock).replaceAll(" "));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    private record JavaSource(String path, String content) {
    }
}
