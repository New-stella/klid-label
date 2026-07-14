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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 DEV_FIX 이슈 5 (AC5 회귀 방지) 가드 테스트 — 포털 복제본 저장소
 * ({@code PortalDatasetVideoMetaRepository}) 는 <b>{@code PortalMetaReplicaWriter} 단일 진입점</b>에서만
 * 주입/사용돼야 한다.
 *
 * <p>포털 write(deactivate/upsert/activate) + 단조성 가드가 한 곳(writer)에 응집돼야 활성 1건 불변식과
 * 순서 역전 방어(이슈 1B)가 유지된다. 누군가 다른 서비스/컨트롤러에서 포털 리포를 직접 주입해 write 하면
 * 불변식이 깨질 수 있으므로 파일 스캔으로 회귀를 차단한다(ArchUnit 미사용 프로젝트 — 순수 파일 스캔).
 *
 * <p>주석을 제거해 Javadoc 의 타입명 언급은 위반으로 오탐되지 않는다.
 */
class PortalReplicaWriteGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    private static final Pattern PORTAL_REPO_TYPE = Pattern.compile("\\bPortalDatasetVideoMetaRepository\\b");

    /** 포털 복제본 리포 참조가 허용되는 파일(정의 파일 + 단일 write 진입점). */
    private static final List<String> ALLOWED = List.of(
            "PortalDatasetVideoMetaRepository.java", // 리포 인터페이스 정의
            "PortalMetaReplicaWriter.java"           // 단일 write 진입점
    );

    @Test
    @DisplayName("포털_복제본_리포는_PortalMetaReplicaWriter에서만_사용된다")
    void portalReplicaRepository_isOnlyUsedByWriter() {
        // given: 프로덕션 main 소스 전체(주석 제거)
        List<JavaSource> sources = loadMainSources();

        // when: PortalDatasetVideoMetaRepository 타입을 참조하는 파일 탐지(허용 파일 제외)
        List<String> violations = new ArrayList<>();
        for (JavaSource source : sources) {
            String fileName = Paths.get(source.path()).getFileName().toString();
            if (ALLOWED.contains(fileName)) {
                continue;
            }
            if (PORTAL_REPO_TYPE.matcher(source.content()).find()) {
                violations.add(source.path());
            }
        }

        // then: writer/정의 파일 외에는 포털 복제본 리포 참조 0건
        assertThat(violations)
                .as("포털 복제본 리포는 PortalMetaReplicaWriter 단일 진입점에서만 사용해야 한다. 위반 파일: %s", violations)
                .isEmpty();
    }

    private List<JavaSource> loadMainSources() {
        assertThat(Files.isDirectory(MAIN_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다", MAIN_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::read)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스 스캔 실패", e);
        }
    }

    private JavaSource read(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return new JavaSource(path.toString(), stripComments(raw));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    private String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private record JavaSource(String path, String content) {
    }
}
