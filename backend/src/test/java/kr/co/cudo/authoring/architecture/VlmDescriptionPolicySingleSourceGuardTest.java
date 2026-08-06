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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code video.vd_description} <b>조달 규칙 단일 원천 가드</b> — 프로덕션 소스 스캔. [req: R10]
 *
 * <h3>무엇을 막는가</h3>
 * <p>조달 규칙(전문 우선 → 레거시 구간 `start_sec` 오름차순 이어붙임 → 없으면 null)은
 * {@code VlmDescriptionPolicy} 한 곳에만 존재해야 한다. 복제되면 한쪽만 갱신돼 조용히 어긋난다 —
 * 이 저장소의 반복 결함 패턴이며 개인정보 3필드에서 실제로 두 번 났다
 * ({@link ExportPrivacyPolicySingleSourceGuardTest} 클래스 주석 참조).
 *
 * <h3>어떻게 막는가 (mutation 로 검증된 판정축)</h3>
 * <ol>
 *   <li><b>레거시 구간 키 정규식</b>은 정책 파일에만 선언된다 — 다른 곳에서 키 형식을 재파싱하면
 *       정렬 규칙(숫자 오름차순)이 그 지점에서 갈린다.</li>
 *   <li><b>{@code META_KEY_DESCRIPTION} 참조는 allowlist 3파일뿐</b>이다 — export 조립부
 *       ({@code VideoMetaMapper}·{@code NiaJsonBuilder})는 <b>판정된 값만</b> 받으므로 이 상수를 알 필요가 없다.
 *       거기서 참조가 생긴다는 것은 곧 규칙을 재유도하기 시작했다는 뜻이다.</li>
 * </ol>
 *
 * <p>주석 스트리핑 필수 — Javadoc 이 상수명을 언급하기만 해도 오탐되기 때문
 * ({@code ExportPrivacyPolicySingleSourceGuardTest} 와 동일 관례).
 */
class VlmDescriptionPolicySingleSourceGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    private static final String POLICY_FILE = "VlmDescriptionPolicy.java";

    /**
     * 레거시 구간 키({@code {start_sec}-{end_sec}}) 파싱 정규식 <b>선언</b> 패턴 —
     * 숫자 클래스({@code \d}) + <b>캡처그룹 사이의 하이픈</b>({@code )-(} )이라는 이 키 고유의 형태를 겨냥한다.
     *
     * <p>하이픈+숫자만 보면 CIDR({@code WebhookCidrParser})·주민번호 마스킹({@code LogMaskingPatterns})
     * 같은 무관한 정규식이 오탐된다(실측 확인). 두 축을 함께 요구해 그 오탐을 배제한다.
     *
     * <p>정책이 캡처그룹을 쓰지 않게 리팩터되면 아래
     * {@link #정책_파일이_실제로_구간_키_정규식을_보유한다()} 가 먼저 실패해 <b>공허한 성공</b>을 막는다.
     */
    private static final Pattern SEGMENT_KEY_REGEX_DECLARATION = Pattern.compile(
            "Pattern\\.compile\\(\\s*\"[^\"]*\\\\\\\\d[^\"]*\\)-\\([^\"]*\"");

    /**
     * {@code META_KEY_DESCRIPTION} 을 참조해도 되는 파일 — 각각 <b>축이 다르다</b>.
     * <ul>
     *   <li>{@code VlmResultService} — 상수의 <b>소유자</b>(적재·검수큐 화이트리스트)</li>
     *   <li>{@code VlmDescriptionPolicy} — 조달 규칙의 <b>단일 소유자</b></li>
     *   <li>{@code MetaService} — <b>편집 허용</b> 화이트리스트(조달과 무관한 별개 축)</li>
     * </ul>
     */
    private static final List<String> DESCRIPTION_KEY_ALLOWLIST = List.of(
            "VlmResultService.java", POLICY_FILE, "MetaService.java");

    private static List<Path> mainSources() {
        try (Stream<Path> walk = Files.walk(MAIN_SRC)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String strippedSource(Path path) {
        try {
            String src = Files.readString(path, StandardCharsets.UTF_8);
            return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(src).replaceAll(" ")).replaceAll(" ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("레거시_구간_키_정규식은_정책_파일에만_선언된다")
    void 레거시_구간_키_정규식은_정책_파일에만_선언된다() {
        // given / when
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            if (p.getFileName().toString().equals(POLICY_FILE)) {
                continue;
            }
            if (SEGMENT_KEY_REGEX_DECLARATION.matcher(strippedSource(p)).find()) {
                offenders.add(p.toString());
            }
        }

        // then — 키 형식을 다른 곳에서 재파싱하면 정렬 규칙이 그 지점에서 갈린다.
        assertThat(offenders)
                .as("레거시 구간 키 파싱은 %s 한 곳에만 둔다 — 재파싱하는 파일: %s", POLICY_FILE, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("정책_파일이_실제로_구간_키_정규식을_보유한다")
    void 정책_파일이_실제로_구간_키_정규식을_보유한다() {
        // given — 위 가드가 "아무 데도 없어서" 통과하는 공허한 성공이 되지 않게 고정한다.
        Path policy = mainSources().stream()
                .filter(p -> p.getFileName().toString().equals(POLICY_FILE))
                .findFirst()
                .orElseThrow(() -> new AssertionError(POLICY_FILE + " 를 찾을 수 없다"));

        // when / then
        assertThat(SEGMENT_KEY_REGEX_DECLARATION.matcher(strippedSource(policy)).find())
                .as("%s 가 구간 키 정규식을 보유해야 위 단일원천 가드가 의미를 갖는다", POLICY_FILE)
                .isTrue();
    }

    /**
     * 수동 전문 슬롯 키({@code manual-timeseries}) <b>리터럴</b>. BE 소유자는 {@code VlmDescriptionPolicy}
     * 한 곳이며 다른 프로덕션 파일은 상수를 참조해야 한다(문자열 복제 금지).
     *
     * <p>FE 는 {@code features/auto/metaKeys.ts} 에 자기 미러를 두며(언어 경계) 이 스캔 대상이 아니다.
     */
    private static final String MANUAL_KEY_LITERAL = "\"manual-timeseries\"";

    @Test
    @DisplayName("수동_전문_슬롯_키_리터럴은_정책_파일에만_있다")
    void 수동_전문_슬롯_키_리터럴은_정책_파일에만_있다() {
        // given / when — 복제되면 조달 우선순위 2가 그 지점에서 갈린다.
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            if (p.getFileName().toString().equals(POLICY_FILE)) {
                continue;
            }
            if (strippedSource(p).contains(MANUAL_KEY_LITERAL)) {
                offenders.add(p.toString());
            }
        }

        // then
        assertThat(offenders)
                .as("manual-timeseries 키의 소유자는 %s 다 — 리터럴을 복제한 파일: %s", POLICY_FILE, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("정책_파일이_실제로_수동_전문_슬롯_키를_보유한다")
    void 정책_파일이_실제로_수동_전문_슬롯_키를_보유한다() {
        // given — 위 가드가 "아무 데도 없어서" 통과하는 공허한 성공이 되지 않게 고정한다.
        Path policy = mainSources().stream()
                .filter(p -> p.getFileName().toString().equals(POLICY_FILE))
                .findFirst()
                .orElseThrow(() -> new AssertionError(POLICY_FILE + " 를 찾을 수 없다"));

        // when / then
        assertThat(strippedSource(policy).contains(MANUAL_KEY_LITERAL))
                .as("%s 가 manual-timeseries 키를 보유해야 위 단일원천 가드가 의미를 갖는다", POLICY_FILE)
                .isTrue();
    }

    @Test
    @DisplayName("export_조립부는_서술_metaKey_상수를_참조하지_않는다")
    void export_조립부는_서술_metaKey_상수를_참조하지_않는다() {
        // given / when — 조립부는 <판정된 값>만 받는다. 상수 참조 = 규칙 재유도의 시작.
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            String fileName = p.getFileName().toString();
            if (DESCRIPTION_KEY_ALLOWLIST.contains(fileName)) {
                continue;
            }
            if (strippedSource(p).contains("META_KEY_DESCRIPTION")) {
                offenders.add(p.toString());
            }
        }

        // then
        assertThat(offenders)
                .as("vd_description 조달 규칙은 %s 한 곳이다 — 상수를 참조하는 파일: %s", POLICY_FILE, offenders)
                .isEmpty();
    }
}
