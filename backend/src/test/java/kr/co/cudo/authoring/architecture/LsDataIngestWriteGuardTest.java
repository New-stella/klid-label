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
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
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

    private static final Pattern UPDATE_LS_DATA_INGEST =
            Pattern.compile("UPDATE\\s+LS_DATA_INGEST", Pattern.CASE_INSENSITIVE);

    /** 인입 행 INSERT 를 보유해도 되는 <b>유일한</b> 파일. */
    private static final String ALLOWED_WRITER = "InternalUploadIngestWriter.java";

    /**
     * 인입 행 UPDATE 를 보유해도 되는 파일과 <b>그 파일이 보유해도 되는 UPDATE 문 개수</b>.
     *
     * <ul>
     *   <li>{@code LsDataIngestRepository} — 저작도구 <b>운영 컬럼</b>만 바꾸는 조건부 UPDATE 7건
     *       (클레임 · 미도착 복귀 · 좀비 회수 · 재큐 단건 · 재큐 일괄 · 도착 통지 · 취소 종결).
     *       관제 수신 29컬럼은 건드리지 않는다.</li>
     *   <li>{@code InternalUploadIngestWriter} — 내부 업로드 행 <b>되살리기</b> 1건(DEV_FIX M1).
     *       관제 수신 컬럼을 갱신하는 <b>유일한</b> 통로이며, 술어({@code FAILED} + {@code RAW_SN IS
     *       NULL})와 호출 측 경로 판정("우리가 만든 행인가")으로 관제 행에 닿지 못하게 막는다.</li>
     * </ul>
     *
     * <p><b>왜 파일이 아니라 문(statement) 단위인가 (DEV_FIX 2차 [C])</b> — 구 가드는 "UPDATE 를
     * 보유한 <b>파일명</b>"만 대조했다. 그래서 <b>이미 allowlist 된 파일 안에</b> 무제한 술어
     * ({@code WHERE RCPTN_SN = :rcptnSn} 뿐)로 관제 소유 컬럼을 갱신하는 문을 추가하면 <b>그대로
     * 통과</b>했다(적대검증 mutation 실증 — BUILD SUCCESSFUL). 새 통로를 만들 가장 자연스러운 자리가
     * 바로 그 파일이므로, 통로 <b>수</b>와 각 문의 <b>술어·대상 컬럼</b>까지 고정한다.
     */
    private static final Map<String, Integer> ALLOWED_UPDATE_STATEMENTS =
            Map.of("LsDataIngestRepository.java", 7, ALLOWED_WRITER, 1);

    /**
     * 관제 소유(수신 29) 컬럼 — 이 컬럼을 {@code SET} 하는 문은 되살리기 <b>하나</b>뿐이어야 한다.
     *
     * <p>{@code VMS_CLIP_ID} 는 되살리기 SET 에도 없다(UK 이자 조회 키). 목록에 포함해 두면 그것을
     * 갱신하는 문이 새로 생길 때도 잡힌다.
     */
    private static final List<String> CONTROL_OWNED_COLUMNS = List.of(
            "VMS_CLIP_ID", "VMS_CCTV_ID", "VDO_FILE_NM", "RAW_FILE_PATH_NM", "SRC_TYPE", "SHT_DT",
            "FILE_FMT", "VDO_CDC", "FILE_SZ", "LCLGV_NM", "VDO_LEN_SEC", "FPS", "FRME_CNT", "ASPRT_RT",
            "WDTH", "VRTC", "RESL", "BIT", "PXL", "WGS84_LAT", "WGS84_LOT", "OG_CD", "CCTV_NM",
            "CCTV_HGT", "MAIN_SURV_PAN_ANG", "EVNT_ID", "EVNT_NM", "MNTR_CN", "LCLGV_CD");

    /** 되살리기 UPDATE 를 호출해도 되는 <b>유일한</b> 파일 — Java 측 신뢰 경계 판정이 여기 있다. */
    private static final String ALLOWED_REVIVE_CALLER = "TusUploadService.java";

    private static final Pattern REVIVE_CALL = Pattern.compile("\\.reviveForUpload\\s*\\(");

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
    @DisplayName("LS_DATA_INGEST_UPDATE_통로는_파일도_문_개수도_고정이다")
    void ingestUpdateHasBoundedWriters() {
        // 되살리기(M1)가 관제 수신 컬럼을 갱신하는 통로를 열었으므로, 그 통로가 <더 늘지 않는지>도
        // 함께 고정한다. 늘면 관제 소유 값의 일반 쓰기 경로가 된다(CWE-915).
        Map<String, Integer> perFile = new TreeMap<>();
        for (UpdateStatement stmt : loadUpdateStatements()) {
            perFile.merge(stmt.fileName(), 1, Integer::sum);
        }

        assertThat(perFile.keySet())
                .as("인입 UPDATE 를 보유한 파일. 발견: %s", perFile)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_UPDATE_STATEMENTS.keySet());
        assertThat(perFile)
                .as("★파일 단위가 아니라 <문 개수>를 고정한다 — allowlist 된 파일 안에 새 통로를"
                        + " 추가하는 것이 가장 자연스러운 우회다. 발견: %s", perFile)
                .containsExactlyInAnyOrderEntriesOf(ALLOWED_UPDATE_STATEMENTS);
    }

    @Test
    @DisplayName("모든_LS_DATA_INGEST_UPDATE는_처리상태_술어를_가진다 — 무제한_술어_금지")
    void everyIngestUpdateIsStateGuarded() {
        // 상태 술어가 없는 UPDATE 는 <어떤 상태의 행이든> 갱신한다 — 처리 중인 행을 뺏고, 종결된
        // 행을 되살리며, 관제가 방금 넣은 행도 덮는다(CWE-362). 조건부 UPDATE 가 이 테이블의
        // 유일한 원자성 근거이므로 술어 존재 자체를 구조로 고정한다.
        for (UpdateStatement stmt : loadUpdateStatements()) {
            assertThat(stmt.hasWhere())
                    .as("%s: WHERE 없는 전체 갱신은 금지다%n%s", stmt.fileName(), stmt.sql())
                    .isTrue();
            assertThat(stmt.wherePart())
                    .as("%s: 상태 술어(PRCS_STTS_CD) 없는 UPDATE 는 처리 중·종결 행을 구분하지"
                            + " 못한다%n%s", stmt.fileName(), stmt.sql())
                    .containsIgnoringCase("PRCS_STTS_CD");
        }
    }

    @Test
    @DisplayName("관제_수신컬럼을_SET하는_UPDATE는_되살리기_하나뿐이고_적재된_행에는_닿지_않는다")
    void controlOwnedColumnsAreWrittenOnlyByRevive() {
        List<UpdateStatement> controlWriters = loadUpdateStatements().stream()
                .filter(LsDataIngestWriteGuardTest::setsControlOwnedColumn)
                .toList();

        // 통로는 <되살리기 1건>뿐이다. 리포지토리에 관제 컬럼 SET 문을 추가하면 여기서 죽는다.
        assertThat(controlWriters)
                .as("관제 소유 컬럼을 갱신하는 UPDATE 문. 발견: %s",
                        controlWriters.stream().map(UpdateStatement::fileName).toList())
                .hasSize(1);
        UpdateStatement revive = controlWriters.get(0);
        assertThat(revive.fileName()).isEqualTo(ALLOWED_WRITER);
        // 술어 2종 — 종결된 행만(FAILED) + 한 번도 적재된 적 없는 행만(RAW_SN IS NULL).
        assertThat(revive.wherePart()).containsIgnoringCase("PRCS_STTS_CD = 'FAILED'");
        assertThat(revive.wherePart().replaceAll("\\s+", " "))
                .as("적재된 영상의 인입 근거를 다른 업로드가 덮으면 역추적이 끊긴다")
                .containsIgnoringCase("RAW_SN IS NULL");
    }

    @Test
    @DisplayName("되살리기_호출부는_TusUploadService_하나뿐이다 — 신뢰경계_판정이_거기에만_있다")
    void reviveHasSingleCaller() {
        // SQL 술어(FAILED + RAW_SN IS NULL)는 <관제가 넣은 실패 행에도 맞는다>. 우리 행과 관제 행을
        // 구분하는 것은 오직 Java 측 경로 판정(InternalUploadPathResolver#isUploadAreaPath)이고
        // 그것은 TusUploadService 안에 있다. 호출부가 늘면 그 판정 없이 관제 행을 되살리는 통로가
        // 생긴다(CWE-915 — 신뢰 경계를 넘는 쓰기).
        List<String> callers = loadMainSources().stream()
                .filter(s -> REVIVE_CALL.matcher(s.content()).find())
                .map(s -> Paths.get(s.path()).getFileName().toString())
                .filter(name -> !ALLOWED_WRITER.equals(name))
                .sorted()
                .toList();

        assertThat(callers)
                .as("되살리기 호출부. 발견: %s", callers)
                .containsExactly(ALLOWED_REVIVE_CALLER);
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

    /**
     * 프로덕션 소스에서 {@code UPDATE LS_DATA_INGEST} <b>문 단위</b>로 잘라낸다.
     *
     * <p>문 끝은 텍스트 블록 종료({@code """}) 또는 문장 종료({@code ;}) 중 <b>먼저 오는 쪽</b>이다 —
     * 어느 표기든 한 문을 넘어 다음 문의 술어를 끌어오지 않게 한다(끌어오면 술어 검사가 위양성 통과).
     */
    private List<UpdateStatement> loadUpdateStatements() {
        List<UpdateStatement> statements = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            String fileName = Paths.get(source.path()).getFileName().toString();
            Matcher matcher = UPDATE_LS_DATA_INGEST.matcher(source.content());
            while (matcher.find()) {
                statements.add(new UpdateStatement(fileName,
                        cutStatement(source.content(), matcher.start())));
            }
        }
        return statements;
    }

    private static String cutStatement(String content, int start) {
        int end = content.length();
        for (String terminator : new String[]{"\"\"\"", ";"}) {
            int at = content.indexOf(terminator, start);
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        return content.substring(start, end);
    }

    private static boolean setsControlOwnedColumn(UpdateStatement stmt) {
        String setPart = stmt.setPart().toUpperCase(java.util.Locale.ROOT);
        return CONTROL_OWNED_COLUMNS.stream()
                .anyMatch(column -> Pattern.compile("\\b" + column + "\\b\\s*=").matcher(setPart).find());
    }

    /** 잘라낸 UPDATE 문 1건 — SET 절과 WHERE 절을 나눠 본다. */
    private record UpdateStatement(String fileName, String sql) {

        private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

        boolean hasWhere() {
            return WHERE.matcher(sql).find();
        }

        /** {@code SET} 이후 <b>첫 WHERE</b> 앞까지(= 바깥 SET 절). */
        String setPart() {
            Matcher where = WHERE.matcher(sql);
            return where.find() ? sql.substring(0, where.start()) : sql;
        }

        /** 첫 {@code WHERE} 이후 전부(서브쿼리 술어 포함 — 술어 존재 판정용). */
        String wherePart() {
            Matcher where = WHERE.matcher(sql);
            return where.find() ? sql.substring(where.start()) : "";
        }
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
