package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이관 산출물이 준 <b>프레임 축 개인정보 3필드</b>의 착지 규칙(ERD-031 프레임 행 절).
 *
 * <h3>왜 통합 시험만으로 부족한가</h3>
 * <p>확인한 표본의 프레임 축 값이 우리 <b>적재 기본값과 같은 모양</b>({@code Y}/{@code N}/{@code N})이라,
 * 통합 시험에서 컬럼 값만 보면 두 축이 구분되지 않는다. 그래서 착지 분기 자체와 표기 변환을 여기서
 * 직접 고정한다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 */
class ImportFramePrivacyAxisTest {

    @Test
    @DisplayName("비식별이_끝난_것으로_지정한_경우에만_비식별_축_컬럼에_착지한다")
    void 비식별이_끝난_것으로_지정한_경우에만_비식별_축_컬럼에_착지한다() {
        // 그 판정은 이미 비식별을 마친 화면에 대한 것이라 우리 비식별 축과 뜻이 같다.
        assertThat(ExportPrivacyPolicy.importedFrameValuesLandOnDeidentAxis(true)).isTrue();
        // 원본이라고 지정한 경우는 원천 축의 사실이라 착지할 컬럼이 없다 — 메타에 원문으로 보관한다.
        assertThat(ExportPrivacyPolicy.importedFrameValuesLandOnDeidentAxis(false)).isFalse();
    }

    @Test
    @DisplayName("여부_값은_표기만_맞추고_옮길_수_없는_값은_지어내지_않는다")
    void 여부_값은_표기만_맞추고_옮길_수_없는_값은_지어내지_않는다() {
        assertThat(ExternalNameSanitizer.yn("Y")).isEqualTo("Y");
        assertThat(ExternalNameSanitizer.yn("N")).isEqualTo("N");
        // 표기를 바꾸는 것이지 뜻을 바꾸는 것이 아니다 — 공백·대소문자만 맞춘다.
        assertThat(ExternalNameSanitizer.yn(" y ")).isEqualTo("Y");

        // ★ 짐작해 한쪽으로 접지 않는다. 접으면 그 짐작이 곧 개인정보 판정 사실이 되어 산출물에 실리고,
        //   저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다.
        assertThat(ExternalNameSanitizer.yn("YES")).isNull();
        assertThat(ExternalNameSanitizer.yn("true")).isNull();
        assertThat(ExternalNameSanitizer.yn("1")).isNull();
        // 컬럼 폭을 넘는 원문이 그대로 흘러가면 이관 트랜잭션이 통째로 깨진다.
        assertThat(ExternalNameSanitizer.yn("해당없음")).isNull();
        assertThat(ExternalNameSanitizer.yn("  ")).isNull();
        assertThat(ExternalNameSanitizer.yn(null)).isNull();
    }
}
