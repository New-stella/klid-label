package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 노출면(exposure) 가드 — 목 서버 포트 발행 범위와 신규 운영 문서의 평문 IP 를 차단한다.
 *
 * <p>배경(2026-07-27 보안 심층 리뷰):
 * <ul>
 *   <li><b>HIGH-1</b> mock-server 는 인증이 전혀 없고, VLM 콜백이 요청자가 지정한 임의 URL 로
 *       서버측 outbound POST 를 발사한다(SSRF, CWE-918). {@code 9400:9400} 으로 호스트
 *       0.0.0.0 에 상시 발행하면 같은 네트워크의 누구나 내부망 스캔·디스크 고갈을 유도할 수 있다
 *       → 루프백({@code 127.0.0.1:9400:9400})에만 발행한다. backend 는 컨테이너 네트워크
 *       이름({@code klid-mock-server})으로 호출하므로 정상 경로는 무영향.</li>
 *   <li><b>MED-2</b> yml 에서 제거한 내부/벤더 IP 가 운영 문서에 평문으로 다시 적히면
 *       평문 IP 제거 목적이 무산된다(CWE-200).</li>
 * </ul>
 *
 * <p>기존 {@code architecture/*GuardTest} 들과 동일하게 컨텍스트 기동 없이 파일 스캔만 사용한다
 * (테스트 작업 디렉토리 = backend 모듈 루트).
 */
class SecurityExposureGuardTest {

    private static final Path REPO_ROOT = Paths.get("..");
    private static final Path BASE_COMPOSE = REPO_ROOT.resolve("docker-compose.yml");
    private static final Path LOCAL_COMPOSE = REPO_ROOT.resolve("docker-compose.local.yml");
    /** 이번 설정 전수조사에서 새로 추가된 운영 문서 디렉터리. */
    private static final Path OPERATIONS_DOCS = REPO_ROOT.resolve("docs/operations");

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    @Test
    @DisplayName("mock서버_포트는_루프백에만_발행된다")
    void mockServerPortIsPublishedOnLoopbackOnly() {
        // given: base compose 의 mock-server 포트 발행 목록
        List<String> published = publishedPorts(BASE_COMPOSE, "mock-server");

        // then: 인증 없는 목 서버(SSRF outbound + 임의 파일 생성)를 호스트 전체 인터페이스에 열지 않는다
        assertThat(published)
                .as("mock-server 포트 발행 선언이 사라졌다(개발자 확인용 루프백 발행은 유지한다)")
                .isNotEmpty();
        assertThat(published)
                .as("mock-server 는 인증이 없고 임의 URL 로 outbound POST 를 발사한다 — 루프백 바인딩 필수: %s",
                        published)
                .allMatch(p -> p.startsWith("127.0.0.1:"));

        // and: local override 가 포트를 다시 열어 base 의 제한을 무력화하면 안 된다
        assertThat(publishedPorts(LOCAL_COMPOSE, "mock-server"))
                .as("local override 가 mock-server 포트를 재선언하면 base 의 루프백 제한이 갈라진다")
                .isEmpty();
    }

    @Test
    @DisplayName("신규_문서에_내부IP_리터럴이_없다")
    void newOperationsDocsHaveNoIpLiteral() {
        // given: 이번 Phase 에서 추가된 운영 문서
        List<String> findings = new ArrayList<>();

        // when
        for (Path doc : markdownFiles(OPERATIONS_DOCS)) {
            Matcher matcher = IPV4.matcher(read(doc));
            while (matcher.find()) {
                String literal = matcher.group();
                // 루프백은 구성 설명상 정상(번들 DB·로컬 바인딩)이므로 제외한다
                if (literal.startsWith("127.") || literal.equals("0.0.0.0")) {
                    continue;
                }
                findings.add(doc.getFileName() + ": " + literal);
            }
        }

        // then: yml 에서 지운 내부망/벤더 주소가 문서로 다시 새면 안 된다(마스킹해 문맥만 남긴다)
        assertThat(findings)
                .as("운영 문서에 내부/벤더 IP 가 평문으로 적혀 있다(마스킹 필요): %s", findings)
                .isEmpty();
    }

    /** compose 서비스의 호스트 포트 발행 목록({@code ports:})을 읽는다. */
    private List<String> publishedPorts(Path composeFile, String service) {
        String prefix = "services." + service + ".ports";
        return yamlKeys(composeFile).stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> String.valueOf(yamlValue(composeFile, k)))
                .toList();
    }

    private List<Path> markdownFiles(Path dir) {
        assertThat(Files.isDirectory(dir)).as("문서 디렉터리가 없습니다: %s", dir.toAbsolutePath()).isTrue();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("문서 목록 조회 실패: " + dir, e);
        }
    }

    private Object yamlValue(Path path, String key) {
        for (PropertySource<?> source : loadYaml(path)) {
            if (source.containsProperty(key)) {
                return source.getProperty(key);
            }
        }
        return null;
    }

    private Set<String> yamlKeys(Path path) {
        Set<String> keys = new LinkedHashSet<>();
        for (PropertySource<?> source : loadYaml(path)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                keys.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        return keys;
    }

    private List<PropertySource<?>> loadYaml(Path path) {
        assertThat(Files.isReadable(path)).as("파일을 읽을 수 없습니다: %s", path.toAbsolutePath()).isTrue();
        try {
            return new YamlPropertySourceLoader().load(path.toString(), new FileSystemResource(path));
        } catch (IOException e) {
            throw new UncheckedIOException("yml 로딩 실패: " + path, e);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path.toAbsolutePath(), e);
        }
    }
}
