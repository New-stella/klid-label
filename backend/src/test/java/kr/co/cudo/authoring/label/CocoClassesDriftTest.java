package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.label.domain.CocoClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * BE COCO allowlist ↔ ai-server {@code COCO_ID2LABEL}(80종) 계약(드리프트) 검증.
 *
 * <p>BE 는 이 allowlist 로 라벨 매핑 입력을 검증하고 검출 대상을 재구성한다. ai-server 와 값/개수가
 * 어긋나면 매핑된 COCO 를 보내도 검출되지 않으므로, 개수·유일성 + (파일 접근 가능 시) ai-server 정본과
 * 정확 일치를 계약으로 강제한다.
 */
class CocoClassesDriftTest {

    @Test
    @DisplayName("COCO allowlist 는 80종이고 중복이 없다")
    void allowlist_80_unique() {
        assertThat(CocoClasses.LABELS).hasSize(80);
        assertThat(new LinkedHashSet<>(CocoClasses.LABELS)).hasSize(80);
        // 경계 spot-check — id 0/2/5/79.
        assertThat(CocoClasses.LABELS.get(0)).isEqualTo("person");
        assertThat(CocoClasses.LABELS.get(2)).isEqualTo("car");
        assertThat(CocoClasses.LABELS.get(5)).isEqualTo("bus");
        assertThat(CocoClasses.LABELS.get(79)).isEqualTo("toothbrush");
    }

    @Test
    @DisplayName("isValid 는 COCO 정규명만 허용하고 미지원/자유텍스트는 거부한다")
    void isValid_allowlist() {
        assertThat(CocoClasses.isValid("person")).isTrue();
        assertThat(CocoClasses.isValid(" traffic light ")).isTrue(); // trim
        assertThat(CocoClasses.isValid("not-a-coco")).isFalse();
        assertThat(CocoClasses.isValid("")).isFalse();
        assertThat(CocoClasses.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("ai-server COCO_ID2LABEL 정본과 순서까지 정확 일치한다(파일 접근 가능 시)")
    void matchesAiServerSource() throws Exception {
        // 저장소 레이아웃: backend/ 와 ai-server/ 가 형제. 접근 불가 환경(CI 분리 등)에선 스킵.
        Path py = Path.of("..", "ai-server", "app", "models", "detector_backend.py");
        assumeTrue(Files.exists(py), "ai-server 소스 미접근 — 드리프트 정밀검증 스킵");

        String src = Files.readString(py);
        int begin = src.indexOf("COCO_ID2LABEL");
        assertThat(begin).isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', begin);
        int close = src.indexOf('}', open);
        String body = src.substring(open + 1, close);

        // "<id>: "<label>"" 쌍을 id 순서대로 추출.
        Matcher m = Pattern.compile("(\\d+)\\s*:\\s*\"([^\"]+)\"").matcher(body);
        List<String> parsed = new java.util.ArrayList<>();
        int expectedId = 0;
        while (m.find()) {
            assertThat(Integer.parseInt(m.group(1))).isEqualTo(expectedId++);
            parsed.add(m.group(2));
        }
        assertThat(parsed).as("ai-server COCO_ID2LABEL 과 BE allowlist 정확 일치")
                .isEqualTo(CocoClasses.LABELS);
    }
}
