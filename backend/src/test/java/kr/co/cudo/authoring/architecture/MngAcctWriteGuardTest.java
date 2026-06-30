package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.user.entity.MngAcctUser;
import org.hibernate.annotations.Immutable;
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
 * 역할 분리 리팩토링 AC1 가드 테스트 — 저작도구 프로덕션 코드가 관제 소유 계정 테이블
 * ({@code MNG_ACCT_USER} / {@code MNG_ACCT_USER_AUTHRT}) 에 <b>쓰기(INSERT/UPDATE/DELETE)</b> 를
 * 하지 않음을 자동 검증한다.
 *
 * <p>Phase 2 에서 {@code MngAcctUserAuthrt} 엔티티/리포 삭제 + {@code MngAcctUser} {@code @Immutable}
 * READ 전용화로 쓰기 경로를 제거했고, 본 테스트가 회귀(누군가 다시 관제 계정 테이블 쓰기를 추가)를 차단한다.
 *
 * <p><b>스캔 방식</b>: ArchUnit 미사용 프로젝트이므로 순수 파일 스캔(방식 A)을 사용한다.
 * {@code src/main/java} 전체(프로덕션 main 코드만 — 테스트/시드/리소스 제외)를 읽어
 * <b>블록·라인 주석을 제거</b>한 뒤 native SQL 쓰기 구문을 정규식으로 탐지한다.
 * 주석을 제거하므로 Javadoc 의 테이블명 언급(예: "MNG_ACCT_USER_AUTHRT(delete/insert) 쓰기는 제거됐다")
 * 은 위반으로 오탐되지 않는다. READ 엔티티/리포/조회 쿼리 자체는 허용한다.
 */
class MngAcctWriteGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /** 블록 주석(/* ... *\/) 제거용. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 라인 주석(// ...) 제거용. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    // MNG_ACCT_USER — 뒤에 '_' 가 오면(=MNG_ACCT_USER_AUTHRT) 매칭 제외(negative lookahead).
    private static final Pattern UPDATE_MNG_ACCT_USER =
            Pattern.compile("UPDATE\\s+MNG_ACCT_USER(?!_)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_MNG_ACCT_USER =
            Pattern.compile("DELETE\\s+FROM\\s+MNG_ACCT_USER(?!_)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_MNG_ACCT_USER =
            Pattern.compile("INSERT\\s+INTO\\s+MNG_ACCT_USER(?!_)", Pattern.CASE_INSENSITIVE);

    // MNG_ACCT_USER_AUTHRT
    private static final Pattern INSERT_MNG_ACCT_USER_AUTHRT =
            Pattern.compile("INSERT\\s+INTO\\s+MNG_ACCT_USER_AUTHRT", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_MNG_ACCT_USER_AUTHRT =
            Pattern.compile("DELETE\\s+FROM\\s+MNG_ACCT_USER_AUTHRT", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_MNG_ACCT_USER_AUTHRT =
            Pattern.compile("UPDATE\\s+MNG_ACCT_USER_AUTHRT", Pattern.CASE_INSENSITIVE);

    // 삭제된 엔티티 타입 식별자 — 단어 경계로 정확히 매칭.
    private static final Pattern MNG_ACCT_USER_AUTHRT_TYPE =
            Pattern.compile("\\bMngAcctUserAuthrt\\b");

    @Test
    @DisplayName("저작도구_코드는_MNG_ACCT_USER에_UPDATE_DELETE하지_않는다")
    void 저작도구_코드는_MNG_ACCT_USER에_UPDATE_DELETE하지_않는다() {
        // given: 프로덕션 main 소스 전체(주석 제거)
        List<JavaSource> sources = loadMainSources();

        // when: MNG_ACCT_USER 대상 UPDATE/DELETE/INSERT 쓰기 구문 탐지
        List<String> violations = scan(sources,
                UPDATE_MNG_ACCT_USER, DELETE_MNG_ACCT_USER, INSERT_MNG_ACCT_USER);

        // then: 쓰기 0건이어야 한다 (READ 전용 @Immutable 엔티티)
        assertThat(violations)
                .as("저작도구 프로덕션 코드는 관제 소유 MNG_ACCT_USER 에 쓰기를 하면 안 된다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("저작도구_코드는_MNG_ACCT_USER_AUTHRT에_INSERT_DELETE하지_않는다")
    void 저작도구_코드는_MNG_ACCT_USER_AUTHRT에_INSERT_DELETE하지_않는다() {
        // given
        List<JavaSource> sources = loadMainSources();

        // when: MNG_ACCT_USER_AUTHRT 대상 INSERT/DELETE/UPDATE 쓰기 구문 탐지
        List<String> violations = scan(sources,
                INSERT_MNG_ACCT_USER_AUTHRT, DELETE_MNG_ACCT_USER_AUTHRT, UPDATE_MNG_ACCT_USER_AUTHRT);

        // then: 저작도구 역할은 LS_USER_ROLE 에서 관리하므로 관제 권한 테이블 쓰기 0건
        assertThat(violations)
                .as("저작도구 프로덕션 코드는 관제 소유 MNG_ACCT_USER_AUTHRT 에 쓰기를 하면 안 된다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("MngAcctUserAuthrt_타입_참조가_프로덕션코드에_없다")
    void MngAcctUserAuthrt_타입_참조가_프로덕션코드에_없다() {
        // given
        List<JavaSource> sources = loadMainSources();

        // when: 삭제된 MngAcctUserAuthrt 엔티티/리포 식별자 참조 탐지
        List<String> violations = scan(sources, MNG_ACCT_USER_AUTHRT_TYPE);

        // then: Phase 2 에서 엔티티/리포가 삭제됐으므로 참조 0건
        assertThat(violations)
                .as("삭제된 MngAcctUserAuthrt 타입 참조가 프로덕션 코드에 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("MngAcctUser_엔티티는_Immutable이다")
    void MngAcctUser_엔티티는_Immutable이다() {
        // given/when: 엔티티 클래스의 Hibernate @Immutable 적용 여부
        Immutable immutable = MngAcctUser.class.getAnnotation(Immutable.class);

        // then: @Immutable 로 JPA dirty checking 갱신이 차단되어야 한다(쓰기 차단 구조 보장)
        assertThat(immutable)
                .as("MngAcctUser 는 관제 소유 READ 전용 테이블이므로 @Immutable 이어야 한다")
                .isNotNull();
    }

    // --- helpers ---

    private List<String> scan(List<JavaSource> sources, Pattern... patterns) {
        List<String> violations = new ArrayList<>();
        for (JavaSource source : sources) {
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source.content());
                if (matcher.find()) {
                    violations.add(source.path() + " (pattern: " + pattern.pattern() + ")");
                    break; // 파일당 1회만 보고
                }
            }
        }
        return violations;
    }

    private List<JavaSource> loadMainSources() {
        assertThat(Files.isDirectory(MAIN_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다 (테스트 작업 디렉토리=backend 모듈 루트)",
                        MAIN_SRC.toAbsolutePath())
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
            String stripped = stripComments(raw);
            return new JavaSource(path.toString(), stripped);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    /** 블록/라인 주석을 제거해 Javadoc 의 테이블명 언급이 SQL 쓰기로 오탐되지 않게 한다. */
    private String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private record JavaSource(String path, String content) {
    }
}
