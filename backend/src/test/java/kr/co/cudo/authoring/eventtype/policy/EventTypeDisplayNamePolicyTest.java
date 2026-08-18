package kr.co.cudo.authoring.eventtype.policy;

import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy.Resolved;
import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표시명 4단 폴백의 <b>채택 단계</b> 판정 회귀 가드. @design API-185, API-186
 *
 * <p>서버가 채택 단계를 함께 내려주는 이유: 화면이 {@code optrIndctNm}/{@code evntNm}/
 * {@code evntCtgryNm} 을 보고 폴백을 재현하면 그것이 <b>두 번째 판정</b>이 되어, 이 정책이 바뀔 때
 * 화면만 조용히 옛 규칙으로 남는다(이 저장소의 반복 결함 패턴).
 *
 * <p><b>픽스처 값은 단계마다 다르게 둔다</b> — 네 인자가 모두 {@code String} 이라 서비스가 순서를
 * 뒤바꿔 넘겨도 컴파일·실행 어디서도 걸리지 않는다. 값이 겹치면 그 오류가 통과한다.
 */
class EventTypeDisplayNamePolicyTest {

    /** 단계마다 서로 다른 픽스처 — 어느 값이 채택됐는지가 단언으로 드러난다. */
    private static final String OPERATOR = "운영자지정명";
    private static final String CONTROL = "관제수신명";
    private static final String CATEGORY = "카테고리명";
    private static final String CODE = "EV01000101";

    // ---------------------------------------------------------------- 4단 채택

    @Test
    @DisplayName("운영자_지정명이_있으면_operator_단계다")
    void operatorNameWins() {
        // given / when
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource(OPERATOR, CONTROL, CATEGORY, CODE);

        // then — 사람이 정한 값이 가장 세다.
        assertThat(resolved.dsplNm()).isEqualTo(OPERATOR);
        assertThat(resolved.source()).isEqualTo(Source.OPERATOR);
        assertThat(resolved.source().wireValue()).isEqualTo("operator");
    }

    @Test
    @DisplayName("운영자_지정명이_없고_관제_수신명이_있으면_control_단계다")
    void controlNameWinsWhenNoOperatorName() {
        // given / when — 운영자 칸이 비어 있다(해제된 상태)
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource(null, CONTROL, CATEGORY, CODE);

        // then
        assertThat(resolved.dsplNm()).isEqualTo(CONTROL);
        assertThat(resolved.source()).isEqualTo(Source.CONTROL);
        assertThat(resolved.source().wireValue()).isEqualTo("control");
    }

    @Test
    @DisplayName("고유_이름이_둘_다_없고_카테고리명이_있으면_category_단계다")
    void categoryNameWinsWhenNoOwnName() {
        // given / when — 관제 마스터에 유형별 이름이 애초에 없던 정상 상태
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource(null, null, CATEGORY, CODE);

        // then — 결함이 아니라 "아직 고유 이름이 없어 카테고리명으로 표시 중"이라는 사실이다.
        assertThat(resolved.dsplNm()).isEqualTo(CATEGORY);
        assertThat(resolved.source()).isEqualTo(Source.CATEGORY);
        assertThat(resolved.source().wireValue()).isEqualTo("category");
    }

    @Test
    @DisplayName("후보가_전부_없으면_code_단계이며_유형코드를_표시한다")
    void codeIsFinalFallback() {
        // given / when
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource(null, null, null, CODE);

        // then — 예외도 빈 화면도 만들지 않는다.
        assertThat(resolved.dsplNm()).isEqualTo(CODE);
        assertThat(resolved.source()).isEqualTo(Source.CODE);
        assertThat(resolved.source().wireValue()).isEqualTo("code");
    }

    // ---------------------------------------------------------------- 경계

    @Test
    @DisplayName("공백만_채워진_값은_값이_아니라_다음_단계로_내려간다")
    void blankCandidatesFallThrough() {
        // given / when — 관제 수신값·수기 입력 모두 공백만 담길 수 있다
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource("   ", "\t", CATEGORY, CODE);

        // then — SQL 경로의 NULLIF(TRIM(...), '') 와 같은 기준이다.
        assertThat(resolved.dsplNm()).isEqualTo(CATEGORY);
        assertThat(resolved.source()).isEqualTo(Source.CATEGORY);
    }

    @Test
    @DisplayName("채택된_표시명은_앞뒤_공백이_제거된다")
    void adoptedNameIsTrimmed() {
        // given / when
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource("  " + OPERATOR + "  ",
                CONTROL, CATEGORY, CODE);

        // then
        assertThat(resolved.dsplNm()).isEqualTo(OPERATOR);
        assertThat(resolved.source()).isEqualTo(Source.OPERATOR);
    }

    @Test
    @DisplayName("유형코드마저_없으면_표시명은_null이지만_단계는_code다")
    void sourceIsCodeEvenWhenEverythingIsNull() {
        // given / when
        Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource(null, null, null, null);

        // then — 폴백 사슬의 마지막까지 내려간 것은 사실이므로 단계는 code 이고 null 이 아니다.
        assertThat(resolved.dsplNm()).isNull();
        assertThat(resolved.source()).isEqualTo(Source.CODE);
    }

    @Test
    @DisplayName("채택_단계_표기는_모두_소문자다")
    void wireValuesAreLowercase() {
        // given / when / then — 응답 계약이다(화면이 대소문자로 분기하지 않게).
        assertThat(Source.values()).extracting(Source::wireValue)
                .containsExactly("operator", "control", "category", "code");
    }

    // ---------------------------------------------------------------- 판정 단일화

    @Test
    @DisplayName("기존_resolve는_resolveWithSource와_항상_같은_표시명을_낸다")
    void resolveDelegatesToResolveWithSource() {
        // given — 4단계를 모두 거치는 입력 조합
        String[][] cases = {
                {OPERATOR, CONTROL, CATEGORY, CODE},
                {null, CONTROL, CATEGORY, CODE},
                {null, null, CATEGORY, CODE},
                {null, null, null, CODE},
                {"  ", " ", " ", CODE},
                {null, null, null, null},
        };

        for (String[] c : cases) {
            // when / then — 판정 로직이 한 벌뿐이라면 두 경로의 표시명이 갈릴 수 없다.
            assertThat(EventTypeDisplayNamePolicy.resolve(c[0], c[1], c[2], c[3]))
                    .as("입력 %s", java.util.Arrays.toString(c))
                    .isEqualTo(EventTypeDisplayNamePolicy.resolveWithSource(c[0], c[1], c[2], c[3]).dsplNm());
        }
    }
}
