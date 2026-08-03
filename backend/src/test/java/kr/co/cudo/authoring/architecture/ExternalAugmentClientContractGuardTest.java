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
 * 외부 증강(생성형 AI) 연동 계층 계약 가드 — DEV_FIX MED-3.
 *
 * <h3>왜 Javadoc 으로는 부족한가</h3>
 * <p>웹훅 경로는 {@code GenAiCallbackService}/{@code AugmentFrameProducer} 가
 * {@code verifyExternalReadablePath(...)} 를 <b>실제로 호출</b>하는 기계적 가드다. 반면 조회 경로
 * (INT-030)의 {@code output_file_path} 는 주석 경고뿐이고 반환 타입이 평범한 {@code String} 이라,
 * 소비자가 {@code Paths.get(item.outputFilePath())} 를 써도 <b>컴파일·테스트가 전부 통과</b>한다.
 *
 * <p>이 리포지토리에는 동일 유형 실사고 전례가 있다 — 컨트롤러가 정책 판정을 복제 보유해 형제
 * 엔드포인트만 미배선되어 라벨링 캔버스가 마스킹 전 원본 프레임을 서빙했다(2026-07-30). 거기서
 * "판정은 서비스 한 곳에만" 이라는 구속 규칙이 나왔다. 규약을 <b>사람의 주의력</b>에 맡기면 반드시 샌다.
 *
 * <h3>선택안 — 정적 스캔 가드(경량)</h3>
 * <p>타입 봉쇄({@code UnverifiedExternalPath})는 S6(경로검증 위임)만 막고 S4(DB 미접촉)는 못 막는데다,
 * Jackson 역직렬화 대상 DTO 필드 타입을 바꿔야 해 커스텀 디시리얼라이저까지 끌고 온다. 정적 스캔은
 * <b>두 계약을 한 번에</b> 고정하고 이 프로젝트에 이미 같은 패턴이 있다
 * ({@code FileServingLinkFollowGuardTest} / {@code MngAcctWriteGuardTest}).
 *
 * <h3>고정하는 계약</h3>
 * <ol>
 *   <li><b>S4 — DB 미접촉</b>: {@code augment/integration/**} 은 순수 HTTP 어댑터다. 여기서 DB 를
 *       읽고 쓰기 시작하면 웹훅 경로({@code OTSD_JOB_ID} UNIQUE + {@code FOR UPDATE} 락)와
 *       <b>이중 적용</b>이 되어 파생 산출물이 중복 생성된다.</li>
 *   <li><b>S6 — 경로검증 위임</b>: {@code augment/integration/**} 은 파일시스템에 손대지 않는다.
 *       또한 외부 산출 경로를 만지면서 파일시스템 API 를 함께 쓰는 프로덕션 파일은
 *       반드시 {@code verifyExternalReadablePath} 를 거쳐야 한다(CWE-22).</li>
 * </ol>
 *
 * <h3>⚠ 이 가드의 한계 — <b>1차 신호이지 봉쇄가 아니다</b> (DEV_FIX MED-2)</h3>
 * <p>"S6 를 기계적으로 고정했다" 는 <b>과장</b>이었다. 정직한 수준은 다음과 같다.
 * <ul>
 *   <li><b>규칙 1·2(연동 계층 스캔)는 실질적으로 봉쇄에 가깝다</b> — 대상이
 *       {@code augment/integration/**} 라는 <b>고정 경로</b>이고, 그 안의 <b>모든</b> 파일에서
 *       DB·FS 토큰을 금지하기 때문이다.</li>
 *   <li><b>규칙 3(소비처 스캔)은 우회 가능하다</b> — 판정 단위가 <b>파일</b>이고 신호가 <b>식별자</b>라,
 *       ① 값을 뽑는 파일과 파일을 여는 파일을 <b>분리</b>하거나 ② 값을 <b>다른 이름의 지역변수</b>로
 *       옮기면 침묵한다.</li>
 * </ul>
 * <p>가정이 아니라 <b>이 리포지토리에 이미 있는 형태</b>다 — {@code AugmentExtractSnapshot} 은
 * 외부 산출 경로를 {@code output} 이라는 지역변수로 받아 {@code Paths.get(output)} 으로 다루는데
 * {@code outputFilePath}·{@code externalSource} 어느 식별자도 쓰지 않아 규칙 3 이 침묵한다
 * (실제로는 안전하다 — 검증은 파일을 <b>여는</b> Phase B {@code AugmentFrameProducer} 가 수행한다.
 * 다만 그 안전은 <b>이 가드가 보장한 것이 아니다</b>).
 * <p>그래서 이번에 넓힌 것은 두 가지뿐이다: <b>토큰 목록 보강</b>
 * ({@code FileInputStream}/{@code ProcessBuilder} 등 — 구 정규식 {@code \bjava\.io\.File\b} 는
 * {@code File} 뒤 {@code I} 가 word character 라 {@code java.io.FileInputStream} 에 <b>매칭되지 않았다</b>)
 * 과 <b>외부 경로 식별자 확장</b>({@code externalSource} 추가 → 실제로 파일을 여는
 * {@code AugmentFrameProducer} 가 비로소 규칙 3 의 사정권에 들어온다).
 * <p><b>이 이상 복잡한 정적 분석(데이터 흐름 추적)은 만들지 않는다.</b> 커버리지 착시를 키우는 대신
 * 한계를 여기에 적어 둔다 — 리뷰어는 이 가드가 초록이라는 사실을
 * <b>"외부 경로가 검증 없이 열릴 수 없다" 는 증명으로 읽으면 안 된다</b>. 새 소비처를 만들 때는
 * 가드와 무관하게 {@code verifyExternalReadablePath} 통과 여부를 <b>직접</b> 확인해야 한다.
 */
class ExternalAugmentClientContractGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");
    private static final String INTEGRATION_PKG =
            "kr/co/cudo/authoring/augment/integration".replace('/', java.io.File.separatorChar);

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /** S4 — 영속성 접촉 신호. */
    private static final List<Pattern> PERSISTENCE_TOKENS = List.of(
            Pattern.compile("\\bJpaRepository\\b"),
            Pattern.compile("\\bEntityManager\\b"),
            Pattern.compile("@Repository\\b"),
            Pattern.compile("@Transactional\\b"),
            Pattern.compile("\\bjakarta\\.persistence\\b"));

    /**
     * S6 — 파일시스템·프로세스 <b>사용</b> 신호(실제 I/O 를 개시하는 형태).
     *
     * <p>{@code new FileInputStream(...)}/{@code new ProcessBuilder(...)} 계열이 <b>개별 항목</b>으로
     * 들어 있는 이유: 구 목록의 {@code \bjava\.io\.File\b} 는 {@code java.io.FileInputStream} 에
     * 매칭되지 않고({@code File} 뒤 {@code I} 가 word character 라 {@code \b} 불성립),
     * {@code \bnew\s+File\s*\(} 역시 {@code new FileInputStream(} 을 잡지 못한다. 즉 구 가드는
     * {@code new FileInputStream(item.outputFilePath())} 나 ffmpeg {@code ProcessBuilder} 를
     * <b>전부 통과</b>시켰다.
     */
    private static final List<Pattern> FILESYSTEM_CALL_TOKENS = List.of(
            Pattern.compile("\\bPaths\\.get\\s*\\("),
            Pattern.compile("\\bPath\\.of\\s*\\("),
            Pattern.compile("\\bFiles\\.[a-zA-Z]"),
            // new File(...) 계열 — 완전수식명({@code new java.io.FileInputStream(...)})도 함께 잡는다.
            Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?"
                    + "(?:FileInputStream|FileOutputStream|RandomAccessFile|FileReader|FileWriter"
                    + "|FileSystemResource|ProcessBuilder|File)\\s*\\("),
            Pattern.compile("\\bRuntime\\.getRuntime\\s*\\("));

    /**
     * S6 — 파일시스템·프로세스 <b>선언</b> 신호(import·타입 참조). 연동 계층은 선언조차 금지한다
     * (규칙 2 는 고정 패키지 전수 스캔이라 과잉 차단이 아니라 의도한 엄격함이다).
     */
    private static final List<Pattern> FILESYSTEM_TYPE_TOKENS = List.of(
            Pattern.compile("\\bjava\\.nio\\.file\\b"),
            Pattern.compile("\\bjava\\.io\\.File\\w*\\b"),
            Pattern.compile("\\bFileInputStream\\b"),
            Pattern.compile("\\bFileOutputStream\\b"),
            Pattern.compile("\\bRandomAccessFile\\b"),
            Pattern.compile("\\bFileReader\\b"),
            Pattern.compile("\\bFileWriter\\b"),
            Pattern.compile("\\bFileSystemResource\\b"),
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("\\bRuntime\\.getRuntime\\b"));

    /** 연동 계층(규칙 2) 스캔 대상 = 선언 + 사용 전부. */
    private static final List<Pattern> FILESYSTEM_TOKENS =
            Stream.concat(FILESYSTEM_TYPE_TOKENS.stream(), FILESYSTEM_CALL_TOKENS.stream()).toList();

    /**
     * 외부(생성형 AI)가 준 경로 값을 나르는 식별자.
     *
     * <p>{@code externalSource} 는 {@code AugmentExtractPlan.FrameSpec} 이 그 경로를 담아 Phase B 로
     * 넘길 때 쓰는 이름이다. 이걸 넣어야 <b>실제로 파일을 여는</b> {@code AugmentFrameProducer} 가
     * 규칙 3 의 사정권에 들어온다(그 파일은 {@code outputFilePath} 를 쓰지 않아 구 가드가 침묵했다).
     * 지역변수로 이름을 갈면 여전히 빠져나간다 — 클래스 Javadoc 의 "한계" 절 참조.
     */
    private static final List<Pattern> EXTERNAL_PATH_TOKENS = List.of(
            Pattern.compile("\\boutputFilePath\\b"),
            Pattern.compile("\\boutput_file_path\\b"),
            Pattern.compile("\\bexternalSource\\b"));

    private static final Pattern VERIFY_CALL = Pattern.compile("\\bverifyExternalReadablePath\\b");

    @Test
    @DisplayName("S4_외부증강_연동계층은_DB에_접촉하지_않는다")
    void integrationLayerNeverTouchesPersistence() {
        // given: augment/integration 패키지의 프로덕션 소스(주석 제거)
        List<JavaSource> sources = integrationSources();

        // when: 영속성 접촉 신호 탐지
        List<String> violations = scan(sources, PERSISTENCE_TOKENS);

        // then: 순수 HTTP 어댑터 — DB 접촉 0건 (웹훅 경로와의 이중 적용 방지)
        assertThat(violations)
                .as("ExternalAugmentClient 계층은 DB 를 쓰지 않는다(결과 적용은 소비 계층 책임). 위반: %s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("S6_외부증강_연동계층은_파일시스템에_접촉하지_않는다")
    void integrationLayerNeverTouchesFilesystem() {
        // given
        List<JavaSource> sources = integrationSources();

        // when
        List<String> violations = scan(sources, FILESYSTEM_TOKENS);

        // then: output_file_path 는 파싱만 한다 — 열지 않는다(경로검증은 소비 계층에 위임)
        assertThat(violations)
                .as("연동 계층은 외부 경로를 파일시스템에 넘기지 않는다(CWE-22). 위반: %s", violations)
                .isEmpty();
    }

    /**
     * 소비 계층 계약 — 외부 산출 경로를 <b>파일시스템과 함께</b> 다루는 프로덕션 파일은 반드시 허용
     * 루트 검증을 거친다. 검증 없이 경로를 여는 소비처가 <b>같은 파일 안에서</b> 생기면 즉시 실패한다.
     *
     * <p><b>범위 주의</b>: 판정 단위가 파일 + 식별자라 파일 분리·변수명 변경으로 우회된다
     * (클래스 Javadoc "한계" 절). 이 테스트가 초록이라고 "검증 없이 열리는 경로가 없다" 로 읽지 말 것.
     *
     * <p>파일시스템 신호는 <b>사용 토큰</b>({@link #FILESYSTEM_CALL_TOKENS})만 본다 — 경로를 필드로
     * <b>담기만</b> 하는 값 객체({@code AugmentExtractPlan} 등)까지 잡으면 검증 책임이 없는 곳을
     * 오탐해 가드를 무력화(주석 처리·예외 목록화)시키는 압력이 된다.
     */
    @Test
    @DisplayName("S6_외부_산출경로를_파일시스템에_넘기는_코드는_허용루트_검증을_거친다")
    void outputFilePathConsumersVerifyAllowedRoot() {
        // given: 프로덕션 main 소스 전체
        List<JavaSource> sources = loadSources(MAIN_SRC);

        // when: 외부 산출 경로 식별자 + 파일시스템 사용 API 를 함께 쓰면서 검증 호출이 없는 파일
        List<String> violations = new ArrayList<>();
        for (JavaSource source : sources) {
            boolean touchesExternalPath = EXTERNAL_PATH_TOKENS.stream()
                    .anyMatch(p -> p.matcher(source.content()).find());
            boolean touchesFilesystem = FILESYSTEM_CALL_TOKENS.stream()
                    .anyMatch(p -> p.matcher(source.content()).find());
            boolean verifies = VERIFY_CALL.matcher(source.content()).find();
            if (touchesExternalPath && touchesFilesystem && !verifies) {
                violations.add(source.path());
            }
        }

        // then
        assertThat(violations)
                .as("외부가 준 산출 경로는 verifyExternalReadablePath 없이 파일시스템에 "
                        + "넘길 수 없다(CWE-22). 위반: %s", violations)
                .isEmpty();
    }

    /**
     * 가드 자체의 자기검증 — 토큰 목록이 <b>실제 소비 형태</b>를 잡는지 확인한다.
     *
     * <p>구 목록은 {@code new FileInputStream(item.outputFilePath())} 과 ffmpeg
     * {@code ProcessBuilder} 를 <b>3개 규칙 전부 통과</b>시켰다. 정규식은 조용히 빗나가므로
     * (검사 대상 코드가 없으면 항상 초록이라 <b>구멍이 드러나지 않는다</b>) 합성 소스로 못박는다.
     */
    @Test
    @DisplayName("가드_토큰목록이_FileInputStream과_ProcessBuilder를_실제로_탐지한다")
    void tokenListDetectsStreamAndProcessConsumers() {
        // given: 구 가드를 통과했던 위반 형태들
        List<String> shouldDetect = List.of(
                "new FileInputStream(item.outputFilePath())",
                "new java.io.FileInputStream(path)",
                "new FileOutputStream(dst)",
                "new RandomAccessFile(f, \"r\")",
                "new FileReader(f)",
                "new FileWriter(f)",
                "new ProcessBuilder(\"ffmpeg\", \"-i\", item.outputFilePath()).start()",
                "Runtime.getRuntime().exec(cmd)");

        // when / then: 사용 토큰(규칙 3 판정축)과 전체 토큰(규칙 2 판정축) 양쪽에서 탐지돼야 한다
        for (String snippet : shouldDetect) {
            assertThat(FILESYSTEM_CALL_TOKENS.stream().anyMatch(p -> p.matcher(snippet).find()))
                    .as("사용 토큰 미탐지: %s", snippet).isTrue();
            assertThat(FILESYSTEM_TOKENS.stream().anyMatch(p -> p.matcher(snippet).find()))
                    .as("연동계층 토큰 미탐지: %s", snippet).isTrue();
        }
        // 구 정규식이 빗나갔던 지점을 직접 고정한다.
        assertThat(Pattern.compile("\\bjava\\.io\\.File\\b").matcher("java.io.FileInputStream").find())
                .as("구 패턴은 FileInputStream 을 잡지 못했다(회귀 기준점)").isFalse();
        assertThat(Pattern.compile("\\bjava\\.io\\.File\\w*\\b").matcher("java.io.FileInputStream").find())
                .isTrue();
        // 외부 경로 식별자 — 실제로 파일을 여는 소비처가 쓰는 이름을 포함한다.
        assertThat(EXTERNAL_PATH_TOKENS.stream()
                .anyMatch(p -> p.matcher("Path src = frame.externalSource();").find()))
                .as("externalSource 미탐지 시 AugmentFrameProducer 가 규칙 3 밖에 남는다").isTrue();
    }

    // --- helpers ---

    private List<JavaSource> integrationSources() {
        List<JavaSource> sources = loadSources(MAIN_SRC).stream()
                .filter(s -> s.path().contains(INTEGRATION_PKG))
                .toList();
        assertThat(sources)
                .as("스캔 대상(augment/integration)이 비어 있으면 가드가 무력화된다")
                .isNotEmpty();
        return sources;
    }

    private List<String> scan(List<JavaSource> sources, List<Pattern> patterns) {
        List<String> violations = new ArrayList<>();
        for (JavaSource source : sources) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(source.content()).find()) {
                    violations.add(source.path() + " (pattern: " + pattern.pattern() + ")");
                    break;
                }
            }
        }
        return violations;
    }

    private List<JavaSource> loadSources(Path root) {
        assertThat(Files.isDirectory(root))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다", root.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(root)) {
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

    /** Javadoc 의 경고 문구가 위반으로 오탐되지 않도록 주석을 먼저 제거한다. */
    private String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private record JavaSource(String path, String content) {
    }
}
