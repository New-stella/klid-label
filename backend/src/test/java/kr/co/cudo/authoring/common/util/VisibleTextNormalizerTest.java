package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보이지 않는 문자 정규화 규칙 고정 — {@code @NotBlank}/{@code trim()} 이 놓치는 구멍을 닫는 판정기다.
 *
 * <p>입력은 전부 {@code \}{@code uXXXX} 이스케이프로 쓴다 — 문자를 그대로 붙여넣으면 편집기·인코딩
 * 경로에서 조용히 일반 공백으로 치환돼 "정규화가 걸렀다" 고 오판하는 공허한 테스트가 된다.
 */
class VisibleTextNormalizerTest {

    /**
     * 화면에 아무것도 보이지 않는 입력은 "입력하지 않은 것" 과 같다.
     * {@code Character.isWhitespace(U+00A0) == false}(NBSP 는 non-breaking 이라 whitespace 가 아니다)
     * 이므로 {@code isWhitespace} 기반 판정으로는 걸러낼 수 없다 — 카테고리(Zs/Cf/Zl/Zp) 축이어야 한다.
     */
    @ParameterizedTest(name = "보이지 않는 문자만 입력하면 null: [{0}]")
    @ValueSource(strings = {
            "\u00A0",           // NBSP
            "\u200B",           // ZERO WIDTH SPACE
            "\uFEFF",           // BOM / ZERO WIDTH NO-BREAK SPACE
            "\u2060",           // WORD JOINER
            "\u3000",           // IDEOGRAPHIC SPACE
            "\u2028",           // LINE SEPARATOR
            "\u2029",           // PARAGRAPH SEPARATOR
            "\u200E\u200F",     // LRM + RLM (양방향 제어)
            "\u202E",           // RLO — 표시 위조에 쓰인다
            "\u00A0\u200B\uFEFF",
            " \t\n\r",          // 기존 제어문자·공백 축(회귀)
            ""
    })
    void 보이지_않는_문자만_있으면_null(String invisibleOnly) {
        assertThat(VisibleTextNormalizer.normalizeOrNull(invisibleOnly)).isNull();
    }

    @Test
    @DisplayName("null_입력은_null")
    void null_입력은_null() {
        assertThat(VisibleTextNormalizer.normalizeOrNull(null)).isNull();
    }

    /** 유니코드 공백(Zs)은 단어 사이 구분 의미를 가지므로 <b>지우지 않고</b> 일반 공백으로 바꾼다. */
    @Test
    @DisplayName("유니코드_공백은_일반_공백으로_치환되고_앞뒤는_다듬어진다")
    void 유니코드_공백_치환() {
        assertThat(VisibleTextNormalizer.normalizeOrNull("\u00A0HEAVY\u00A0RAIN\u3000"))
                .isEqualTo("HEAVY RAIN");
    }

    /** 서식 문자(Cf)는 <b>제거</b>한다 — 보이지 않으면서 내용도 없다. */
    @Test
    @DisplayName("보이지_않는_서식문자는_제거되고_보이는_내용은_보존된다")
    void 서식문자_제거() {
        assertThat(VisibleTextNormalizer.normalizeOrNull("\u200BNI\uFEFFGHT\u202E"))
                .isEqualTo("NIGHT");
    }

    /** 제어문자(개행·탭·NUL)는 종전대로 제거 — 로그 위조(CWE-117)·PgJDBC NUL 거부 방어. */
    @Test
    @DisplayName("제어문자는_제거된다")
    void 제어문자_제거() {
        assertThat(VisibleTextNormalizer.normalizeOrNull("NIGHT\n2026 FAKE\tLOG" + (char) 0))
                .isEqualTo("NIGHT2026 FAKELOG");
    }

    /** 보조 평면 문자(이모지 등)는 쪼개지지 않는다 — 코드포인트 단위 순회. */
    @Test
    @DisplayName("보조평면_문자는_보존된다")
    void 보조평면_보존() {
        assertThat(VisibleTextNormalizer.normalizeOrNull("🌨")).isEqualTo("🌨");
    }
}
