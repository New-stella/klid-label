package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * export JSON 개인정보 3필드 단일 판정기({@link ExportPrivacyPolicy}) 단위 테스트.
 *
 * <h3>고정하는 정책 (2026-08-04 확정 — 원천 축 전환)</h3>
 * <ul>
 *   <li>{@code video} 원천 = <b>관제 인입값</b>(그대로). 관제가 안 보낸 필드는 {@code null} 유지
 *       — 상수로 지어내지 않는다.</li>
 *   <li>{@code image} 원천 = <b>정책 상수</b> {@code N}/{@code N}/{@code Y}. 프레임 수동값은
 *       비식별 축이라 원천에 실리지 않는다.</li>
 *   <li><b>파생영상은 두 블록 모두 원천 축 {@code null}</b> — 상수도 넣지 않는다.</li>
 *   <li>{@code DEIDENTIFIED} = 수동값 우선 + 비식별 기본상수(Y/N/N) — <b>불변</b>.</li>
 * </ul>
 */
class ExportPrivacyPolicyTest {

    /** 관제가 3필드를 모두 보낸 원본 영상(V170 DEFAULT 로 신규 인입은 항상 이 형태다). */
    private static final SourcePrivacyMeta INGESTED = SourcePrivacyMeta.ofIngest("N", "N", "Y");
    /** 관제가 아무 값도 보내지 않은 원본 영상(V166 이전 레거시 인입 행). */
    private static final SourcePrivacyMeta INGEST_SILENT = SourcePrivacyMeta.ofIngest(null, null, null);

    // ------------------------------------------------------------ video 블록 원천 축

    @Test
    @DisplayName("원천_산출물은_관제_인입값으로_판정된다")
    void 원천_산출물은_관제_인입값으로_판정된다() {
        // given — 관제가 인입 행에 실어 보낸 원천 판정(V166 컬럼)
        // when / then — video 블록은 그 값을 그대로 싣는다(구 정책 ②의 "무조건 null" 은 폐기됐다).
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, null, INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(ExportKind.ORIGINAL, null, INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, INGESTED))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("관제가_보낸_값이_상수와_달라도_그_값이_실린다")
    void 관제가_보낸_값이_상수와_달라도_그_값이_실린다() {
        // given — 관제가 "이 영상 원천에는 개인정보가 없다"고 판정해 보낸 경우
        SourcePrivacyMeta clean = SourcePrivacyMeta.ofIngest("Y", "N", "N");

        // when / then — 상수(N/N/Y)로 덮어쓰지 않는다. 관제 판정이 정본이다.
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, null, clean))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, clean))
                .isEqualTo("N");
    }

    @Test
    @DisplayName("관제가_안보낸_필드는_원천에서_null_이다")
    void 관제가_안보낸_필드는_원천에서_null_이다() {
        // given — V166 이전 레거시 인입 행 또는 관제가 명시적으로 NULL 을 송신한 경우
        // when / then — 값을 지어내지 않는다. 이 조항은 구 정책 ②에서 <존치>된 부분이며,
        //   상수로 메우면 07-31 폐기 정책(원천을 상수로 단정)으로 되돌아간다.
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, null, INGEST_SILENT))
                .isNull();
        assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(ExportKind.ORIGINAL, null, INGEST_SILENT))
                .isNull();
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, INGEST_SILENT))
                .isNull();
    }

    @Test
    @DisplayName("원천_공백값은_미판정으로_보고_null_이다")
    void 원천_공백값은_미판정으로_보고_null_이다() {
        // given — CHAR(1) 공백 패딩/빈 문자열. 비식별 축의 blank 처리와 같은 기준이다.
        SourcePrivacyMeta blank = SourcePrivacyMeta.ofIngest(" ", "", "  ");
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, null, blank)).isNull();
        assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(ExportKind.ORIGINAL, null, blank)).isNull();
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, blank)).isNull();

        // 패딩된 정상값은 trim 후 실린다(CHAR(1) 왕복).
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(
                ExportKind.ORIGINAL, null, SourcePrivacyMeta.ofIngest(null, null, "Y "))).isEqualTo("Y");
    }

    // ------------------------------------------------------------ image 블록 원천 축

    @Test
    @DisplayName("원천_이미지_블록은_정책_상수로_채워진다")
    void 원천_이미지_블록은_정책_상수로_채워진다() {
        // given / when / then — 프레임 단위 원천 판정 데이터가 존재하지 않으므로 상수(N/N/Y)를 싣는다.
        //   export JSON 이 재적재되는 왕복 자산이라 null 이면 "판정 안 함"과 "유실"이 구분되지 않는다.
        assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.ORIGINAL, null, INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveImagePseudonymity(ExportKind.ORIGINAL, null, INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.ORIGINAL, null, INGESTED))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("프레임_수동값은_원천축을_덮어쓰지_않는다")
    void 프레임_수동값은_원천축을_덮어쓰지_않는다() {
        // given — LS_DATA_SRC 수동값은 <비식별 축> 판정(작업자 수동입력)이다.
        // when / then — 원천 분기는 그 값을 읽지 않고 상수를 유지한다(축이 섞이면 안 된다).
        assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.ORIGINAL, "Y", INGESTED))
                .isEqualTo(ExportPrivacyPolicy.ORGNL_DEFAULT_ANONYMITY);
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.ORIGINAL, "N", INGESTED))
                .isEqualTo(ExportPrivacyPolicy.ORGNL_DEFAULT_PRIVACY_INCLUDED);
    }

    @Test
    @DisplayName("이미지_원천은_관제_인입값을_읽지_않는다")
    void 이미지_원천은_관제_인입값을_읽지_않는다() {
        // given — 관제가 영상 축에 "개인정보 없음(N)" 을 보냈다.
        SourcePrivacyMeta clean = SourcePrivacyMeta.ofIngest("Y", "N", "N");

        // when / then — image 는 상수 Y 를 유지한다. 두 블록이 갈리는 것은 모순이 아니라 입도가
        //   다른 사실이다("영상 판정은 관제가 내렸고, 프레임 단위 판정 데이터는 존재하지 않는다").
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.ORIGINAL, null, clean))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, clean))
                .isEqualTo("N");
    }

    // ------------------------------------------------------------ 파생영상

    @Test
    @DisplayName("파생영상은_원천축이_video_image_모두_null_이다")
    void 파생영상은_원천축이_video_image_모두_null_이다() {
        // given — 파생(증강·해상도)은 부모의 <비식별본>으로 만들어져 원천 영상 자체가 없다.
        SourcePrivacyMeta derived = SourcePrivacyMeta.NONE;

        // when / then — 인입값도 상수도 싣지 않는다(결손이 아니라 정상).
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, null, derived)).isNull();
        assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(ExportKind.ORIGINAL, null, derived)).isNull();
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.ORIGINAL, null, derived)).isNull();
        assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.ORIGINAL, null, derived)).isNull();
        assertThat(ExportPrivacyPolicy.resolveImagePseudonymity(ExportKind.ORIGINAL, null, derived)).isNull();
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.ORIGINAL, null, derived)).isNull();
    }

    @Test
    @DisplayName("원천축_입력이_null_이어도_예외없이_null_로_판정된다")
    void 원천축_입력이_null_이어도_예외없이_null_로_판정된다() {
        // given / when / then — fail-secure: 입력 미상이면 값을 지어내지 않는다.
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, "Y", null)).isNull();
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.ORIGINAL, "Y", null)).isNull();
    }

    // ------------------------------------------------------------ 축 교차 금지 / 비식별 불변

    @Test
    @DisplayName("원천축과_비식별축은_서로_교차하지_않는다")
    void 원천축과_비식별축은_서로_교차하지_않는다() {
        // when / then ① 원천(video)은 <비식별 수동값>을 절대 싣지 않는다.
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.ORIGINAL, "Y", INGEST_SILENT))
                .isNull();
        // when / then ② 비식별은 <관제 인입값>을 절대 싣지 않는다(수동 미입력이면 기본상수).
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.DEIDENTIFIED, null, INGESTED))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.DEIDENTIFIED, null, INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.DEIDENTIFIED, null, INGESTED))
                .isEqualTo("N");
    }

    @Test
    @DisplayName("비식별_이미지_판정은_불변이다")
    void 비식별_이미지_판정은_불변이다() {
        // given / when / then — 이번 전환은 원천 축 하나뿐이다. 프레임 수동 미입력 시 Y/N/N 유지.
        //   (LS_DATA_SRC 에 DB DEFAULT 를 걸었다면 여기가 Y→N 으로 뒤집혔다 — 그래서 걸지 않았다.)
        assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.DEIDENTIFIED, null, INGESTED))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveImagePseudonymity(ExportKind.DEIDENTIFIED, null, INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.DEIDENTIFIED, null, INGESTED))
                .isEqualTo("N");
        // 수동값이 있으면 그것이 정본이다.
        assertThat(ExportPrivacyPolicy.resolveImageAnonymity(ExportKind.DEIDENTIFIED, "N", INGESTED))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.DEIDENTIFIED, "Y", INGESTED))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("비식별산출물은_수동값_미입력시_기본상수_YNN을_쓴다")
    void 비식별산출물은_수동값_미입력시_기본상수_YNN을_쓴다() {
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.DEIDENTIFIED, null, SourcePrivacyMeta.NONE))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(ExportKind.DEIDENTIFIED, null, SourcePrivacyMeta.NONE))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.DEIDENTIFIED, null, SourcePrivacyMeta.NONE))
                .isEqualTo("N");
    }

    @Test
    @DisplayName("비식별산출물은_수동값이_있으면_수동값이_기본상수를_덮는다")
    void 비식별산출물은_수동값이_있으면_수동값이_기본상수를_덮는다() {
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.DEIDENTIFIED, "N", SourcePrivacyMeta.NONE))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveVideoPseudonymity(ExportKind.DEIDENTIFIED, "Y", SourcePrivacyMeta.NONE))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveVideoPrivacyIncluded(ExportKind.DEIDENTIFIED, "Y", SourcePrivacyMeta.NONE))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("공백_수동값은_미입력으로_보고_기본상수를_쓴다")
    void 공백_수동값은_미입력으로_보고_기본상수를_쓴다() {
        // given — CHAR(1) 공백 패딩/빈 문자열
        assertThat(ExportPrivacyPolicy.resolveVideoAnonymity(ExportKind.DEIDENTIFIED, " ", SourcePrivacyMeta.NONE))
                .isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.resolveImagePseudonymity(ExportKind.DEIDENTIFIED, "", SourcePrivacyMeta.NONE))
                .isEqualTo("N");
        assertThat(ExportPrivacyPolicy.resolveImagePrivacyIncluded(ExportKind.DEIDENTIFIED, "  ", SourcePrivacyMeta.NONE))
                .isEqualTo("N");
    }

    // ------------------------------------------------------------ 상수 계약

    @Test
    @DisplayName("비식별_기본상수는_화면_프리필과_공유하는_공개상수다")
    void 비식별_기본상수는_화면_프리필과_공유하는_공개상수다() {
        // given / when / then — GET 프리필(VideoPrivacyMetaService · FramePrivacyMetaService)이 자체
        //   상수를 두지 않고 이 값을 참조한다. 원천 축 전환은 이 상수를 건드리지 않으므로 두 화면
        //   패널의 프리필과 export 비식별 값은 계속 일치한다(2026-08-03 DEV_FIX 로 잡은 결함의 회귀 가드).
        assertThat(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY).isEqualTo("Y");
        assertThat(ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED).isEqualTo("N");
    }

    @Test
    @DisplayName("원천_상수는_인입_DB_DEFAULT_와_같은_fail_closed_값이다")
    void 원천_상수는_인입_DB_DEFAULT_와_같은_fail_closed_값이다() {
        // given / when / then — V170 이 LS_DATA_INGEST 에 건 DEFAULT('N'/'N'/'Y')와 같은 값이어야
        //   같은 문서의 video/image 원천이 근거 없이 갈리지 않는다(관제가 다른 값을 보낸 경우는 별개).
        assertThat(ExportPrivacyPolicy.ORGNL_DEFAULT_ANONYMITY).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.ORGNL_DEFAULT_PSEUDONYMITY).isEqualTo("N");
        assertThat(ExportPrivacyPolicy.ORGNL_DEFAULT_PRIVACY_INCLUDED).isEqualTo("Y");
        // 두 축의 상수는 <다르다> — 섞이면 비식별 산출물이 "개인정보 있음"으로 뒤집힌다.
        assertThat(ExportPrivacyPolicy.ORGNL_DEFAULT_PRIVACY_INCLUDED)
                .isNotEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED);
        assertThat(ExportPrivacyPolicy.ORGNL_DEFAULT_ANONYMITY)
                .isNotEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY);
    }
}
