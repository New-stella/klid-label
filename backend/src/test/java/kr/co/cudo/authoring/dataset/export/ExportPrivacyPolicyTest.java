package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * export JSON 개인정보 3필드 단일 판정기({@link ExportPrivacyPolicy}) 단위 테스트.
 *
 * <p><b>2026-08-03 정책 반전 고정</b> — {@code ORIGINAL} 은 판정하지 않고(null),
 * {@code DEIDENTIFIED} 만 수동값 우선 + 비식별 기본상수(Y/N/N) 폴백이다.
 * 구 정책(원천=N/파생, 비식별=상수 고정 + 수동 override 무시)의 회귀를 여기서 막는다.
 */
class ExportPrivacyPolicyTest {

    @Test
    @DisplayName("원천산출물은_개인정보3필드가_모두_null이다")
    void 원천산출물은_개인정보3필드가_모두_null이다() {
        // given / when — 수동값 미입력
        // then — 원천영상은 비식별 처리 전이라 판정 자체를 하지 않는다(null).
        assertThat(ExportPrivacyPolicy.resolveAnonymity(ExportKind.ORIGINAL, null)).isNull();
        assertThat(ExportPrivacyPolicy.resolvePseudonymity(ExportKind.ORIGINAL, null)).isNull();
        assertThat(ExportPrivacyPolicy.resolvePrivacyIncluded(ExportKind.ORIGINAL, null)).isNull();
    }

    @Test
    @DisplayName("원천산출물은_수동값이_있어도_null이다")
    void 원천산출물은_수동값이_있어도_null이다() {
        // given / when / then — 수동 판정은 비식별 산출물에 대한 판단이므로 원천에 싣지 않는다.
        assertThat(ExportPrivacyPolicy.resolveAnonymity(ExportKind.ORIGINAL, "Y")).isNull();
        assertThat(ExportPrivacyPolicy.resolvePseudonymity(ExportKind.ORIGINAL, "Y")).isNull();
        assertThat(ExportPrivacyPolicy.resolvePrivacyIncluded(ExportKind.ORIGINAL, "Y")).isNull();
    }

    @Test
    @DisplayName("비식별산출물은_수동값_미입력시_기본상수_YNN을_쓴다")
    void 비식별산출물은_수동값_미입력시_기본상수_YNN을_쓴다() {
        // given / when / then
        assertThat(ExportPrivacyPolicy.resolveAnonymity(ExportKind.DEIDENTIFIED, null)).isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolvePseudonymity(ExportKind.DEIDENTIFIED, null)).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolvePrivacyIncluded(ExportKind.DEIDENTIFIED, null)).isEqualTo("N");
    }

    @Test
    @DisplayName("비식별산출물은_수동값이_있으면_수동값이_기본상수를_덮는다")
    void 비식별산출물은_수동값이_있으면_수동값이_기본상수를_덮는다() {
        // given / when / then — 사람이 실제로 판정한 값이 정본이다.
        assertThat(ExportPrivacyPolicy.resolveAnonymity(ExportKind.DEIDENTIFIED, "N")).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolvePseudonymity(ExportKind.DEIDENTIFIED, "Y")).isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolvePrivacyIncluded(ExportKind.DEIDENTIFIED, "Y")).isEqualTo("Y");
    }

    @Test
    @DisplayName("공백_수동값은_미입력으로_보고_기본상수를_쓴다")
    void 공백_수동값은_미입력으로_보고_기본상수를_쓴다() {
        // given — CHAR(1) 공백 패딩/빈 문자열
        // when / then
        assertThat(ExportPrivacyPolicy.resolveAnonymity(ExportKind.DEIDENTIFIED, " ")).isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolvePseudonymity(ExportKind.DEIDENTIFIED, "")).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolvePrivacyIncluded(ExportKind.DEIDENTIFIED, "  ")).isEqualTo("N");
    }

    @Test
    @DisplayName("비식별_기본상수는_화면_프리필과_공유하는_공개상수다")
    void 비식별_기본상수는_화면_프리필과_공유하는_공개상수다() {
        // given / when / then — GET 프리필(VideoPrivacyMetaService)이 자체 상수를 두지 않고 이 값을 쓴다.
        //   (소비자가 생산자의 판정을 재유도하면 드리프트한다 — 상수 원천은 여기 하나)
        assertThat(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY).isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED).isEqualTo("N");
    }
}
