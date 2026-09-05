package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.video.repository.InternalWorkScope;
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
 * <b>채널 판별자 단일 원천 가드</b> — 프로덕션 소스 스캔 (ADR-058 · ERD-028).
 *
 * <h3>무엇을 막는가</h3>
 * <p>흡수로 포털 업로드 자산이 공용 영상 원장에 함께 앉으면서 「이 영상이 어느 채널의 자산인가」를
 * 묻는 술어가 조회 경로마다 필요해졌다. 그 판정이 흩어지면 새 조회를 만드는 사람이 「여기도 붙여야
 * 하나」를 스스로 판단해야 하고, <b>한 번 잊으면 남의 자산이 조용히 섞인다</b> — 오류로 드러나지 않고
 * 결과 건수만 늘어나는 축이라 발견이 늦다.
 *
 * <p>실제로 흩어져 있었다: JPQL 조회는 갈 곳이 없어 판별자를 <b>쿼리 문자열에 직접 박았고</b>
 * ({@code VideoRepository}), 판정 헬퍼가 이미 있는데 <b>상수를 직접 비교</b>하는 자리도 있었다
 * ({@code DerivativeSourceVideoResolver}).
 *
 * <h3>어떻게 막는가 — 두 축</h3>
 * <ol>
 *   <li><b>값 문자열</b>({@code "PORTAL_ULD"})은 <b>정의 파일 한 곳</b>에만 있다. 새 쿼리가 값을
 *       하드코딩하면 여기서 걸린다.</li>
 *   <li><b>상수 참조</b>({@code SRC_TYPE_PORTAL_ULD})는 allowlist 밖에 없다. 상수를 직접 참조하기
 *       시작했다는 것은 곧 채널 판정을 그 자리에서 재유도하기 시작했다는 뜻이다.</li>
 * </ol>
 *
 * <h3>오탐을 배제한 방법 (실측)</h3>
 * <ul>
 *   <li>바깥에 <b>같은 글자를 갖지만 축이 다른 것</b>이 둘 있다 — 흡수 전 표 이름
 *       ({@code LS_PORTAL_ULD}·{@code LS_PORTAL_ULD_FRME}·{@code LS_PORTAL_ULD_LBL})이 주석에 남아
 *       있고, 클립 식별자 접두 {@code "PORTAL_ULD_"} 는 <b>동작 코드</b>다.</li>
 *   <li>주석은 <b>스트리핑</b>해 배제하고, 값 문자열은 <b>닫는 따옴표까지 포함</b>해 비교하므로
 *       {@code "PORTAL_ULD_"} 는 걸리지 않는다.</li>
 * </ul>
 *
 * <h3>allowlist 가 셋인 이유 — 각각 축이 다르다</h3>
 * <ul>
 *   <li>{@code LsDataRaw} — 값의 <b>정의</b> 소유자(팩토리·판정 헬퍼 포함)</li>
 *   <li>{@code InternalWorkScope} — <b>관제 채널 가시 범위</b>(「포털이 아니다」) 술어의 소유자</li>
 *   <li>{@code PortalUploadLedger} — <b>포털 채널 자산이 공용 원장에 어떤 값으로 앉는가</b>의 소유자.
 *       스스로 값을 재선언하지 않고 정의를 참조하는 <b>위임 별칭</b> 한 줄뿐이라 값이 갈라질 수 없다.</li>
 * </ul>
 *
 * @design ADR-058
 * @design ERD-028
 */
class ChannelDiscriminatorSingleSourceGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /** 값의 정의 소유자. */
    private static final String DEFINITION_FILE = "LsDataRaw.java";

    /**
     * 판별자 <b>값 문자열</b> 리터럴 — <b>닫는 따옴표까지</b> 포함한다.
     *
     * <p>따옴표를 빼면 클립 식별자 접두 {@code "PORTAL_ULD_"}(동작 코드다 — 주석 스트리핑으로 지워지지
     * 않는다)가 함께 걸려 <b>정상 코드가 위반으로 신고된다</b>. 실측으로 확인한 오탐이다.
     */
    private static final String VALUE_LITERAL = "\"PORTAL_ULD\"";

    /** 판별자 <b>상수 이름</b>. */
    private static final String CONSTANT_NAME = "SRC_TYPE_PORTAL_ULD";

    /** 상수를 참조해도 되는 파일 — 각각 축이 다르다(클래스 주석 참조). */
    private static final List<String> CONSTANT_ALLOWLIST = List.of(
            DEFINITION_FILE, "InternalWorkScope.java", "PortalUploadLedger.java");

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

    private static Path mainSource(String fileName) {
        return mainSources().stream()
                .filter(p -> p.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fileName + " 를 찾을 수 없다"));
    }

    @Test
    @DisplayName("판별자_값_문자열은_정의_파일에만_있다")
    void 판별자_값_문자열은_정의_파일에만_있다() {
        // given / when — 새 조회가 값을 직접 박으면 채널 판정이 그 자리에서 갈린다.
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            if (p.getFileName().toString().equals(DEFINITION_FILE)) {
                continue;
            }
            if (strippedSource(p).contains(VALUE_LITERAL)) {
                offenders.add(p.toString());
            }
        }

        // then
        assertThat(offenders)
                .as("채널 판별자 값의 소유자는 %s 한 곳이다 — 값을 직접 박은 파일: %s", DEFINITION_FILE, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("정의_파일이_실제로_판별자_값_문자열을_보유한다")
    void 정의_파일이_실제로_판별자_값_문자열을_보유한다() {
        // given — 위 가드가 "아무 데도 없어서" 통과하는 공허한 성공이 되지 않게 고정한다.
        // when / then
        assertThat(strippedSource(mainSource(DEFINITION_FILE)).contains(VALUE_LITERAL))
                .as("%s 가 판별자 값을 보유해야 위 단일원천 가드가 의미를 갖는다", DEFINITION_FILE)
                .isTrue();
    }

    @Test
    @DisplayName("판별자_상수_참조는_채널_소유자_밖에_없다")
    void 판별자_상수_참조는_채널_소유자_밖에_없다() {
        // given / when — 상수 참조 = 채널 판정을 그 자리에서 재유도하기 시작했다는 신호.
        //   관제 채널 조회는 InternalWorkScope(QueryDSL 2형태 · JPQL 조각)를,
        //   포털 채널 조회는 PortalUploadLedger.SRC_TYPE 을, 엔티티 판정은 isPortalUpload() 를 쓴다.
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            if (CONSTANT_ALLOWLIST.contains(p.getFileName().toString())) {
                continue;
            }
            if (strippedSource(p).contains(CONSTANT_NAME)) {
                offenders.add(p.toString());
            }
        }

        // then
        assertThat(offenders)
                .as("채널 판별자 상수는 %s 안에서만 참조한다 — 밖에서 참조하는 파일: %s",
                        CONSTANT_ALLOWLIST, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("채널_범위_소유자가_실제로_판별자_상수를_참조한다")
    void 채널_범위_소유자가_실제로_판별자_상수를_참조한다() {
        // given — 소유자가 판정을 다른 데로 옮기면 위 allowlist 가드가 공허해진다.
        // when / then
        assertThat(strippedSource(mainSource("InternalWorkScope.java")).contains(CONSTANT_NAME))
                .as("InternalWorkScope 가 판별자 상수를 참조해야 채널 술어의 소유자로서 의미를 갖는다")
                .isTrue();
    }

    @Test
    @DisplayName("JPQL_조각은_별칭_r_규약과_앞뒤_공백을_지킨다")
    void JPQL_조각은_별칭_r_규약과_앞뒤_공백을_지킨다() {
        // given / when / then — 별칭이 흔들리면 이 조각을 붙인 쿼리가 파싱 단계에서 깨진다.
        //   앞뒤 공백은 호출부(텍스트 블록은 후행 공백을 제거한다)가 토큰을 붙여 쓰지 않게 하는 계약이다.
        assertThat(InternalWorkScope.INTERNAL_JPQL)
                .as("JPQL 조각은 별칭 r 로 고정한다")
                .contains("r.srcType");
        assertThat(InternalWorkScope.INTERNAL_JPQL)
                .as("조각이 스스로 앞뒤 공백을 보장해야 호출부가 공백을 신경 쓰지 않는다")
                .startsWith(" ")
                .endsWith(" ");
        assertThat(InternalWorkScope.INTERNAL_JPQL)
                .as("판정은 「포털이 아니다」로 적는다 — 과거 행은 출처 유형이 비어 있다")
                .contains("is null");
    }
}
