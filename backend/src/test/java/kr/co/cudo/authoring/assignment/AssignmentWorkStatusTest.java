package kr.co.cudo.authoring.assignment;

import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.assignment.controller.AssignmentController;
import kr.co.cudo.authoring.assignment.domain.AssignmentWorkStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배정 목록 워크플로 상태의 <b>정방향(표시) ≡ 역방향(필터)</b> 동치성과 커버리지를 전수 고정한다.
 *
 * <p>이 동치가 깨지면 "작업중으로 필터했는데 목록엔 대기로 표시" 되는 모순이 생긴다(R8).
 * 리포지토리의 SQL 조건은 이 enum 이 노출하는 값만 읽으므로, 여기서 고정한 의미가 곧 WHERE 절의 의미다.
 */
class AssignmentWorkStatusTest {

    /** 실제로 관측되는 상태 코드 + 미지 코드 + row 없음(null). */
    private static final List<String> CODES = new ArrayList<>(Arrays.asList(
            null, "ASSIGNED", "PENDING", "IN_REVIEW", "APPROVED", "REJECTED",
            "BATCH_QUEUED", "PROCESSING", "COMPLETED", "FAILED", "미지코드"));

    @Test
    @DisplayName("모든_상태코드x저장이력_조합은_정확히_한_상태에만_매칭된다")
    void everyCombinationMatchesExactlyOneStatus() {
        for (String code : CODES) {
            for (boolean hasSaveHistory : new boolean[] {true, false}) {
                long matched = Arrays.stream(AssignmentWorkStatus.values())
                        .filter(s -> s.matches(code, hasSaveHistory))
                        .count();
                assertThat(matched)
                        .as("code=%s hasSaveHistory=%s 가 %d 개 상태에 매칭 (누락/중복)",
                                code, hasSaveHistory, matched)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("표시_상태와_필터_상태가_모든_조합에서_동일하다")
    void displayAndFilterAgree() {
        for (String code : CODES) {
            for (boolean hasSaveHistory : new boolean[] {true, false}) {
                AssignmentWorkStatus displayed = AssignmentWorkStatus.of(code, hasSaveHistory);
                assertThat(displayed.matches(code, hasSaveHistory))
                        .as("표시=%s 인데 그 상태 필터에는 걸리지 않는다 code=%s hasSaveHistory=%s",
                                displayed, code, hasSaveHistory)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("배정_직후_상태는_저장이력_존재로_대기와_작업중이_갈린다")
    void baseStatusSplitsOnLabelPresence() {
        assertThat(AssignmentWorkStatus.of("ASSIGNED", false)).isEqualTo(AssignmentWorkStatus.PENDING);
        assertThat(AssignmentWorkStatus.of("ASSIGNED", true)).isEqualTo(AssignmentWorkStatus.IN_PROGRESS);
        // 상태 row 미생성(레거시)·미지 코드도 같은 축으로 갈린다.
        assertThat(AssignmentWorkStatus.of(null, false)).isEqualTo(AssignmentWorkStatus.PENDING);
        assertThat(AssignmentWorkStatus.of(null, true)).isEqualTo(AssignmentWorkStatus.IN_PROGRESS);
        assertThat(AssignmentWorkStatus.of("미지코드", true)).isEqualTo(AssignmentWorkStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("검수단계_상태는_저장이력_존재와_무관하다")
    void reviewStagesIgnoreLabelPresence() {
        assertThat(AssignmentWorkStatus.of("PENDING", true)).isEqualTo(AssignmentWorkStatus.REVIEW_PENDING);
        assertThat(AssignmentWorkStatus.of("PENDING", false)).isEqualTo(AssignmentWorkStatus.REVIEW_PENDING);
        assertThat(AssignmentWorkStatus.of("IN_REVIEW", true)).isEqualTo(AssignmentWorkStatus.REVIEW_PENDING);
        assertThat(AssignmentWorkStatus.of("APPROVED", true)).isEqualTo(AssignmentWorkStatus.COMPLETED);
        assertThat(AssignmentWorkStatus.of("APPROVED", false)).isEqualTo(AssignmentWorkStatus.COMPLETED);
        assertThat(AssignmentWorkStatus.of("REJECTED", true)).isEqualTo(AssignmentWorkStatus.REJECTED);
        assertThat(AssignmentWorkStatus.of("REJECTED", false)).isEqualTo(AssignmentWorkStatus.REJECTED);

        // 검수 단계에는 저장 이력 조건을 걸지 않는다 — 걸면 필터 결과가 표시와 어긋난다.
        assertThat(AssignmentWorkStatus.REVIEW_PENDING.requiresSaveHistory()).isNull();
        assertThat(AssignmentWorkStatus.COMPLETED.requiresSaveHistory()).isNull();
        assertThat(AssignmentWorkStatus.REJECTED.requiresSaveHistory()).isNull();
    }

    @Test
    @DisplayName("검수단계_코드집합은_상수정의에서_파생된다")
    void reviewStageCodesAreDerived() {
        assertThat(AssignmentWorkStatus.reviewStageCodes())
                .containsExactlyInAnyOrder("PENDING", "IN_REVIEW", "APPROVED", "REJECTED");
        // 기저 상태는 코드 화이트리스트를 갖지 않는다(= 위 집합의 여집합).
        assertThat(AssignmentWorkStatus.PENDING.statusIn()).isEmpty();
        assertThat(AssignmentWorkStatus.IN_PROGRESS.statusIn()).isEmpty();
    }

    @Test
    @DisplayName("정의되지_않은_workStatus_문자열은_필터가_사라지지_않고_400으로_거부된다")
    void parseRejectsUnknownCodes() {
        // 정의된 코드는 그대로 파싱된다.
        assertThat(AssignmentWorkStatus.parse("IN_PROGRESS")).contains(AssignmentWorkStatus.IN_PROGRESS);
        // 빈 값만 '필터 미적용' — 이 경로만 조건 없이 통과한다.
        assertThat(AssignmentWorkStatus.parse("  ")).isEmpty();
        assertThat(AssignmentWorkStatus.parse(null)).isEmpty();

        // fail-closed — 미지 값에 빈 값을 돌려주면 WHERE 절이 통째로 사라져
        // 요청보다 넓은 결과가 200 으로 나간다(fail-open).
        for (String bogus : List.of("in_progress", "UNASSIGNED", "' OR 1=1 --", "PENDING ")) {
            assertThatThrownBy(() -> AssignmentWorkStatus.parse(bogus))
                    .as("미지 workStatus '%s' 가 조용히 무시됨", bogus)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("거부_메시지는_입력값을_반사하지_않는다")
    void rejectMessageDoesNotReflectInput() {
        assertThatThrownBy(() -> AssignmentWorkStatus.parse("<script>alert(1)</script>"))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining("script")
                .hasMessageNotContaining("alert");
    }

    /**
     * 허용값이 컨트롤러 {@code @Pattern} 정규식과 이 enum <b>두 곳</b>에 정의돼 있으므로, 둘이
     * 어긋나면(상수 추가/rename) 한쪽만 통과시키는 구멍이 생긴다. 드리프트를 테스트 시점에 고정한다.
     */
    @Test
    @DisplayName("컨트롤러_workStatus_정규식과_enum_상수집합이_일치한다")
    void controllerPatternMatchesEnumConstants() throws Exception {
        Pattern annotation = findWorkStatusPattern();
        assertThat(annotation).as("컨트롤러 workStatus 파라미터의 @Pattern 이 사라졌다").isNotNull();

        // 정규식이 열거하는 값 집합 == enum 상수 이름 집합
        Matcher alternation = java.util.regex.Pattern
                .compile("\\(([^)]*)\\)")
                .matcher(annotation.regexp());
        assertThat(alternation.find()).as("정규식에서 허용값 목록을 찾을 수 없다").isTrue();
        Set<String> declaredInRegex = Set.of(alternation.group(1).split("\\|"));
        Set<String> enumNames = Arrays.stream(AssignmentWorkStatus.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        assertThat(declaredInRegex)
                .as("정규식 허용값과 AssignmentWorkStatus 상수가 어긋났다 (한쪽만 통과 = fail-open 입구)")
                .isEqualTo(enumNames);

        // 행위 검증 — 모든 상수는 정규식을 실제로 통과하고, 미지 값은 통과하지 못한다.
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(annotation.regexp());
        for (String name : enumNames) {
            assertThat(compiled.matcher(name).matches())
                    .as("enum 상수 %s 가 컨트롤러 정규식에서 400 으로 막힌다", name)
                    .isTrue();
        }
        assertThat(compiled.matcher("UNASSIGNED").matches()).isFalse();
        // 빈 값/공백만은 'blank = 필터 미적용' 규약이라 통과해야 한다(q·eventTypeCd 와 동일 의미).
        assertThat(compiled.matcher("").matches()).isTrue();
        assertThat(compiled.matcher("   ").matches()).isTrue();
    }

    private Pattern findWorkStatusPattern() {
        for (Method method : AssignmentController.class.getDeclaredMethods()) {
            if (!"list".equals(method.getName())) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                Pattern pattern = parameter.getAnnotation(Pattern.class);
                if (pattern != null) {
                    return pattern;
                }
            }
        }
        return null;
    }
}
