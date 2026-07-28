package kr.co.cudo.authoring.support;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code src/main/resources/application*.yml} 을 테스트에서 직접 읽기 위한 지원 클래스.
 *
 * <p>클래스패스({@code new ClassPathResource("application-local.yml")})로 읽으면 동명의
 * {@code src/test/resources/application-local.yml} 이 앞서 잡혀 <b>운영/로컬 설정 원본이 가려진다</b>.
 * 따라서 파일 경로로 직접 읽는다(Gradle test 작업 디렉토리 = backend 모듈 루트 — 기존
 * {@code architecture/*GuardTest} 들과 동일 전제).
 *
 * <p>구성한 {@link StandardEnvironment} 에서는 시스템 프로퍼티/환경변수 소스를 제거해
 * 실행 머신의 환경변수에 좌우되지 않고 yml 의 기본값({@code ${VAR:default}})만 반영되게 한다.
 */
public final class MainResourceYaml {

    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");
    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    private MainResourceYaml() {
    }

    /** 단일 yml 파일을 PropertySource 목록으로 읽는다(멀티 도큐먼트 대응). */
    public static List<PropertySource<?>> load(String fileName) {
        Path path = MAIN_RESOURCES.resolve(fileName);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("main 리소스를 읽을 수 없습니다: " + path.toAbsolutePath());
        }
        try {
            return LOADER.load(fileName, new FileSystemResource(path));
        } catch (IOException e) {
            throw new UncheckedIOException("yml 로딩 실패: " + fileName, e);
        }
    }

    /**
     * 주어진 yml 들을 합친 Environment 를 만든다. <b>뒤에 오는 파일이 앞을 덮어쓴다</b>
     * (application.yml → application-{profile}.yml 순으로 전달하면 프로파일 override 재현).
     */
    public static StandardEnvironment environment(String... fileNames) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (String fileName : fileNames) {
            for (PropertySource<?> source : load(fileName)) {
                env.getPropertySources().addFirst(source);
            }
        }
        return env;
    }

    /** yml 에 선언된 프로퍼티 키 전체(placeholder 미해석). */
    public static Set<String> keys(String fileName) {
        Set<String> keys = new LinkedHashSet<>();
        for (PropertySource<?> source : load(fileName)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                keys.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        return keys;
    }

    /** yml 원문 값(placeholder 미해석). 없으면 null. */
    public static Object rawValue(String fileName, String key) {
        for (PropertySource<?> source : load(fileName)) {
            if (source.containsProperty(key)) {
                return source.getProperty(key);
            }
        }
        return null;
    }
}
