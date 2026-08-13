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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2b — 프레임 폐기·복원의 <b>쓰기 통로가 하나</b>임을 고정한다 (프로덕션 소스 스캔).
 *
 * <h3>왜 필요한가</h3>
 * "한번이라도 검수 완료된 영상은 프레임을 새로 폐기·복원하지 못한다"는 게이트는
 * {@code LabelService.requireDiscardAllowed} <b>한 곳</b>에 있고, 그 앞을 지나는 유일한 쓰기 통로가
 * {@code FrameDiscardApplier} 다. 누가 {@code DSCD_YN} 을 직접 쓰는 경로를 새로 내면 그 게이트를
 * 지나지 않아 <b>가드 있는 문 옆에 가드 없는 문</b>이 생긴다 — 이 저장소가 반복해 겪은 결함
 * ({@code DeidentReportGate} 단일화가 그 해법의 선례)이며, 상태 전이가 조용히 새면 데이터마트 롤백
 * 정합성이 그 프레임 하나에서 깨진다.
 *
 * <h3>무엇을 보지 않는가</h3>
 * 이 스캔은 <b>심볼 존재</b>만 본다 — 게이트를 <b>호출하는지</b>와 회차 적용 예외의 정확성은 행위
 * 테스트가 고정한다({@code label.service.FrameDiscardApprovalGateTest}). 두 축이 짝이다.
 */
class FrameDiscardSingleGateGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /**
     * 폐기 상태를 실제로 바꾸는 심볼.
     *
     * <p>메서드 이름 3종만 보면 <b>다른 이름의 쓰기</b>가 통과한다(F-5). 그래서 컬럼명·필드 대입
     * 패턴까지 함께 본다 — 새 쿼리·새 mutator 는 거의 반드시 이 둘 중 하나를 지나간다.
     */
    private static final List<String> DISCARD_WRITE_MARKERS = List.of(
            "applyDiscardFlag(", ".discard()", ".restore()",
            "SET DSCD_YN",    // 네이티브 UPDATE 로 컬럼을 직접 쓰는 경로
            "this.dscdYn =");  // 엔티티 필드 직접 대입

    private static final java.util.regex.Pattern BLOCK_COMMENT =
            java.util.regex.Pattern.compile("/\\*.*?\\*/", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern LINE_COMMENT =
            java.util.regex.Pattern.compile("//[^\\n\\r]*");

    /**
     * 그 심볼을 가져도 되는 <b>정규화 경로</b> — 파일명만 쓰면 동명 파일이 조용히 면제된다(F-5).
     */
    private static final List<String> ALLOWED = List.of(
            "kr/co/cudo/authoring/batch/repository/LsDataSrcRepository.java", // 쿼리 선언
            "kr/co/cudo/authoring/batch/entity/LsDataSrc.java",              // 엔티티 상태 전이 선언
            "kr/co/cudo/authoring/label/service/FrameDiscardApplier.java",   // 유일한 적용 지점(게이트 뒤)
            "kr/co/cudo/authoring/label/service/LabelService.java");         // 게이트 + 코어 호출부

    @Test
    @DisplayName("폐기_상태_쓰기는_FrameDiscardApplier_한_곳만_한다 — 게이트_우회_통로_금지")
    void discardWritesGoThroughSingleApplier() {
        Set<String> violations = new TreeSet<>();
        for (Path path : sources()) {
            String normalized = path.toString().replace('\\', '/');
            if (ALLOWED.stream().anyMatch(normalized::endsWith)) {
                continue;
            }
            String name = normalized;
            String content = read(path);
            for (String marker : DISCARD_WRITE_MARKERS) {
                if (content.contains(marker)) {
                    violations.add(name + " → " + marker);
                }
            }
        }

        assertThat(violations)
                .as("프레임 폐기·복원 상태를 바꾸는 통로는 FrameDiscardApplier 하나여야 한다. 새 통로는"
                        + " '한번이라도 검수 완료된 영상' 게이트(LabelService.requireDiscardAllowed)를 지나지"
                        + " 않아 데이터마트 롤백 정합성이 그 프레임 하나에서 조용히 깨진다. 발견: %s", violations)
                .isEmpty();
    }

    private static List<Path> sources() {
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 주석을 <b>제거하고</b> 읽는다 — 주석의 심볼 언급은 쓰기가 아니다.
     *
     * <p>제거하지 않으면 {@code LS_DATA_SRC.DSCD_YN} 을 설명하는 javadoc(예: 감사 이벤트 타입 상수)이
     * 위반으로 잡혀, 가드가 오탐으로 신뢰를 잃고 결국 마커가 느슨해진다.
     */
    private static String read(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(raw).replaceAll("")).replaceAll("");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
