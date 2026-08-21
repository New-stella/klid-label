package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습데이터 산출물 개인정보 3필드 — <b>외부 산출물 이관</b> 원천 축(DOMAIN-017).
 *
 * <h3>왜 별도 클래스인가</h3>
 * <p>같은 판정기라도 <b>조달처가 다르다</b>. 인입 축은 관제가 보낸 값이고, 이 축은 산출물 문서를 원문
 * 보관한 메타다(이 경로는 관제 수신 원장을 거치지 않아 인입 행이 <b>구조적으로 없다</b> — ADR-048).
 * 한 클래스에 몰면 어느 축이 깨졌는지 실패 메시지로 구분되지 않는다.
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li>{@code video} 블록 원천 축이 <b>산출물이 준 값</b>을 그대로 싣는다.</li>
 *   <li>{@code image} 블록 원천 축은 종전대로 <b>정책 상수</b>다 — 이관 영상도 원천 영상이 있는 경우라
 *       파생영상과 달리 상수가 실려야 한다.</li>
 *   <li>산출물이 값을 주지 않았으면 {@code null} 이다 — 상수로 메우지 않는다(값을 지어내지 않는다).</li>
 *   <li>비식별 축은 이 변경과 무관하게 그대로다.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design DFEAT-057
 * @design ADR-048
 */
class ExportPrivacyPolicyImportTest {

    /** 확인한 실물 표본의 영상 축 값 — 손으로 지어낸 값이 아니다. */
    private static final String SAMPLE_ANONYMITY = "N";
    private static final String SAMPLE_PSEUDONYMITY = "N";
    private static final String SAMPLE_PRIVACY_INCLUDED = "Y";

    private final SourcePrivacyMeta imported = ExportPrivacyPolicy.importedSource(
            SAMPLE_ANONYMITY, SAMPLE_PSEUDONYMITY, SAMPLE_PRIVACY_INCLUDED);

    @Nested
    @DisplayName("video 블록 — 산출물이 준 원천 값을 그대로 싣는다")
    class VideoBlock {

        @Test
        @DisplayName("원천_산출에는_보관된_산출물_값이_실린다")
        void 원천_산출에는_보관된_산출물_값이_실린다() {
            assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(
                    ExportKind.ORIGINAL, null, imported)).isEqualTo(SAMPLE_ANONYMITY);
            assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(
                    ExportKind.ORIGINAL, null, imported)).isEqualTo(SAMPLE_PSEUDONYMITY);
            assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(
                    ExportKind.ORIGINAL, null, imported)).isEqualTo(SAMPLE_PRIVACY_INCLUDED);
        }

        @Test
        @DisplayName("산출물이_값을_주지_않았으면_null_이고_상수로_메우지_않는다")
        void 산출물이_값을_주지_않았으면_null_이고_상수로_메우지_않는다() {
            SourcePrivacyMeta empty = ExportPrivacyPolicy.importedSource(null, null, null);

            assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, null, empty))
                    .isNull();
            assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, empty))
                    .isNull();
        }

        @Test
        @DisplayName("비식별_산출은_이_축과_무관하게_종전대로_수동값_우선이다")
        void 비식별_산출은_이_축과_무관하게_종전대로_수동값_우선이다() {
            assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(
                    ExportKind.DEIDENTIFIED, null, imported))
                    .isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY);
            assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(
                    ExportKind.DEIDENTIFIED, "N", imported)).isEqualTo("N");
        }
    }

    @Nested
    @DisplayName("image 블록 — 원천 축은 종전대로 정책 상수다")
    class ImageBlock {

        @Test
        @DisplayName("이관_영상도_원천_영상이_있는_경우라_상수가_실린다 — 파생영상과 다르다")
        void 이관_영상도_원천_영상이_있는_경우라_상수가_실린다() {
            assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.ORIGINAL, null, imported))
                    .isEqualTo(ExportPrivacyPolicy.ORGNL_DEFAULT_ANONYMITY);
            assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(
                    ExportKind.ORIGINAL, null, imported))
                    .isEqualTo(ExportPrivacyPolicy.ORGNL_DEFAULT_PRIVACY_INCLUDED);

            // 파생영상은 "비식별 처리 전 원천"이라는 대상 자체가 없어 상수도 싣지 않는다.
            assertThat(ExportPrivacyPolicy.resolveImageAnonymity(
                    ExportKind.ORIGINAL, null, SourcePrivacyMeta.NONE)).isNull();
        }

        @Test
        @DisplayName("산출물의_프레임_축_값은_원천_판정에_쓰이지_않는다")
        void 산출물의_프레임_축_값은_원천_판정에_쓰이지_않는다() {
            // 표본의 프레임 축 값은 Y/N/N 으로 <b>비식별 축</b> 기본값과 같은 모양이다. 그 값을 원천에
            //   실으면 "원천 영상인데 익명처리를 거쳤다"는 성립할 수 없는 산출이 나온다.
            assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.ORIGINAL, "Y", imported))
                    .isEqualTo(ExportPrivacyPolicy.ORGNL_DEFAULT_ANONYMITY);
        }
    }

    @Test
    @DisplayName("이관_원천은_원천_영상_있음으로_판정된다")
    void 이관_원천은_원천_영상_있음으로_판정된다() {
        assertThat(imported.sourceExists()).isTrue();
        assertThat(ExportPrivacyPolicy.importedSource(null, null, null).sourceExists()).isTrue();
    }
}
