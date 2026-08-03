package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인입 테이블({@code LS_DATA_INGEST}) 쓰기 통로 가드 — 프로덕션 소스 스캔 (DEV_FIX F6).
 *
 * <p>행을 만드는 주체는 원칙적으로 <b>관제</b>다. 저작도구는 읽고 자기 운영 컬럼의 상태만 바꾼다.
 * 유일한 예외가 내부 REVIEWER 업로드(우리가 정당한 origin 인 흐름)이고, 그 통로는
 * {@code InternalUploadIngestWriter} <b>하나</b>여야 한다. 통로가 늘면 그 자체가 관제 소유 29컬럼의
 * 일반 쓰기 경로가 된다(CWE-915).
 *
 * <p><b>왜 여기(architecture 패키지)인가</b> — 프로덕션 소스 트리를 스캔하는 구조 가드는 엔티티
 * 단위 테스트가 아니라 아키텍처 가드다({@code MngAcctWriteGuardTest} 관례). 엔티티 자체의 계약
 * (setter·INSERT 팩토리 부재)은 {@code LsDataIngestTest} 가 계속 담당한다.
 *
 * <p><b>주석 스트리핑 필수</b> — 정규식을 원문에 그대로 걸면 Javadoc 이 테이블명을 언급하기만 해도
 * 위반으로 오탐된다({@code InternalUploadIngestWriter} 클래스 주석이 이미 이 문구를 갖고 있다).
 * {@code MngAcctWriteGuardTest} 와 동일하게 블록·라인 주석을 제거한 뒤 매칭한다.
 */
class LsDataIngestWriteGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /** 블록 주석(/* ... *\/) 제거용. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 라인 주석(// ...) 제거용. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    private static final Pattern INSERT_LS_DATA_INGEST =
            Pattern.compile("INSERT\\s+INTO\\s+LS_DATA_INGEST", Pattern.CASE_INSENSITIVE);

    /** 인입 행 INSERT 를 보유해도 되는 <b>유일한</b> 파일. */
    private static final String ALLOWED_WRITER = "InternalUploadIngestWriter.java";

    @Test
    @DisplayName("LS_DATA_INGEST_INSERT_통로는_InternalUploadIngestWriter_하나뿐이다")
    void ingestInsertHasSingleWriter() {
        // given — 프로덕션 main 소스 전체(주석 제거)
        List<JavaSource> sources = loadMainSources();

        // when — INSERT INTO LS_DATA_INGEST 를 <코드로> 보유한 파일
        List<String> insertSites = sources.stream()
                .filter(s -> INSERT_LS_DATA_INGEST.matcher(s.content()).find())
                .map(JavaSource::path)
                .sorted()
                .toList();

        // then — 통로는 하나이고 그 하나는 내부 업로드 writer 다
        assertThat(insertSites)
                .as("인입 INSERT 통로가 늘면 관제 소유 29컬럼의 일반 쓰기 경로가 된다. 발견: %s", insertSites)
                .hasSize(1);
        assertThat(Paths.get(insertSites.get(0)).getFileName().toString())
                .isEqualTo(ALLOWED_WRITER);
    }

    @Test
    @DisplayName("주석에_INSERT_INTO_LS_DATA_INGEST_를_언급해도_위반으로_오탐하지_않는다")
    void commentMentionIsNotAViolation() {
        // given — Javadoc 이 SQL 문구를 그대로 인용한 가상의 소스
        String source = """
                /**
                 * 이 클래스는 더 이상 INSERT INTO LS_DATA_INGEST 를 하지 않는다.
                 */
                class Sample {
                    // 구 구현: INSERT INTO LS_DATA_INGEST (...) VALUES (...)
                    void noop() { }
                }
                """;

        // when/then — 주석 제거 후에는 매칭되지 않는다
        assertThat(INSERT_LS_DATA_INGEST.matcher(stripComments(source)).find()).isFalse();
        // (원문 그대로면 오탐한다 — 스트리핑이 필요한 이유)
        assertThat(INSERT_LS_DATA_INGEST.matcher(source).find()).isTrue();
    }

    // --- helpers ---

    private List<JavaSource> loadMainSources() {
        assertThat(Files.isDirectory(MAIN_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다 (테스트 작업 디렉토리=backend 모듈 루트)",
                        MAIN_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(LsDataIngestWriteGuardTest::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스 스캔 실패", e);
        }
    }

    private static JavaSource read(Path path) {
        try {
            return new JavaSource(path.toString(),
                    stripComments(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    private static String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private record JavaSource(String path, String content) {
    }
}
