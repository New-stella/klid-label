package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BoardWorkStatus} 파싱 계약 회귀 가드 — <b>필터가 조용히 사라지지 않게</b> 한다.
 *
 * <p>구 구현은 정의되지 않은 코드를 {@code Optional.empty()} 로 흘려보냈고, 호출부는 그것을
 * "필터 미적용" 으로 해석했다. 즉 <b>필터를 걸었는데 전체가 반환</b>되는 fail-open 이었다.
 * 이 실패는 화면상 정상으로 보여 발견이 늦다(작업목록 assignments 축은 PR #64 에서 이미 fail-closed 로
 * 전환됐고, 보드 축만 남아 있었다).
 *
 * <p>컨트롤러 {@code @Pattern} 이 앞에서 막고 있으나 그 정규식과 이 enum 은 <b>손으로 적힌 두 목록</b>이라
 * 한쪽만 갱신되면 방어가 사라진다. 그래서 ①런타임 fail-closed ②두 목록의 동치를 함께 고정한다.
 */
class BoardWorkStatusContractTest {

    /** 컨트롤러의 workStatus @Pattern 정규식에서 코드 목록을 추출하기 위한 소스 경로. */
    private static final Path CONTROLLER_SRC = Paths.get(
            "src/main/java/kr/co/cudo/authoring/assignment/controller/TaskBoardController.java");

    /** {@code ^[\x00-\x20]*$|^(A|B|C)$} 형태에서 괄호 안 대안 목록만 뽑는다. */
    private static final Pattern PATTERN_ALTERNATIVES = Pattern.compile("\\^\\(([A-Z_|]+)\\)\\$");

    @ParameterizedTest
    @ValueSource(strings = {"UNASSIGNED", "PENDING", "REVIEW_PENDING", "COMPLETED", "REJECTED"})
    @DisplayName("정의된_코드는_그대로_파싱된다")
    void definedCodes_parse(String code) {
        assertThat(BoardWorkStatus.parse(code)).hasValue(BoardWorkStatus.valueOf(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("빈_값은_필터_미적용을_뜻한다")
    void blank_meansNoFilter(String code) {
        assertThat(BoardWorkStatus.parse(code)).isEmpty();
    }

    @Test
    @DisplayName("null도_필터_미적용이다")
    void nullValue_meansNoFilter() {
        assertThat(BoardWorkStatus.parse(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_REVIEW", "APPROVED", "unassigned", "PENDING ", "DROP TABLE"})
    @DisplayName("정의되지_않은_코드는_400으로_거부한다_필터를_조용히_버리지_않는다")
    void undefinedCode_isRejected(String code) {
        assertThatThrownBy(() -> BoardWorkStatus.parse(code))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("컨트롤러_Pattern_목록과_enum_상수가_일치한다")
    void controllerPattern_matchesEnumConstants() {
        String source = read(CONTROLLER_SRC);
        Matcher m = PATTERN_ALTERNATIVES.matcher(source);
        Set<String> patternCodes = null;
        while (m.find()) {
            Set<String> found = Arrays.stream(m.group(1).split("\\|")).collect(Collectors.toSet());
            // status(배치 상태) 축의 정규식도 같은 형태라, workStatus enum 과 교집합이 있는 쪽을 고른다.
            if (found.contains("REVIEW_PENDING")) {
                patternCodes = found;
            }
        }
        assertThat(patternCodes)
                .as("TaskBoardController 의 workStatus @Pattern 정규식을 찾지 못했다 — 형태가 바뀌었으면 이 테스트도 갱신할 것")
                .isNotNull();

        Set<String> enumCodes = Arrays.stream(BoardWorkStatus.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(patternCodes)
                .as("컨트롤러 정규식과 enum 상수가 어긋나면 한쪽 값이 400 없이 필터만 사라지거나 반대로 막힌다")
                .isEqualTo(enumCodes);
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}
