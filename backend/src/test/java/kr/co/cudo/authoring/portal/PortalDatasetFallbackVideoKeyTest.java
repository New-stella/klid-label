package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationTxService.DatasetRef;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 문서에 영상 파일명이 없을 때 쓰는 <b>영상 키</b> — 배포 코드·버전으로 만든다(ADR-068).
 *
 * <p>이 값은 그대로 클립 식별자에 들어가므로 <b>여기서 만든 키는 반드시</b>
 * {@link LsDataRaw#portalDatasetClipId} 를 통과해야 한다. 통과하지 못하면 그 배포본은 등록 자체가
 * {@code INVALID_VIDEO_KEY} 로 실패한다.
 *
 * @design ADR-068
 */
class PortalDatasetFallbackVideoKeyTest {

    private static final long DATASET_ID = 5149L;

    private static String keyOf(String code, String version) {
        return new DatasetRef(DATASET_ID, code, version).fallbackVideoKey();
    }

    @Test
    @DisplayName("배포_코드와_버전을_이어_키로_쓴다")
    void joinsCodeAndVersion() {
        assertThat(keyOf("DS-FIRE-2026-01", "1.0")).isEqualTo("DS-FIRE-2026-01_1.0");
    }

    @Test
    @DisplayName("한쪽만_있으면_있는_값만_쓴다")
    void usesWhicheverIsPresent() {
        assertThat(keyOf("DS-FIRE-2026-01", null)).isEqualTo("DS-FIRE-2026-01");
        assertThat(keyOf(" ", "1.0")).isEqualTo("1.0");
    }

    @Test
    @DisplayName("★둘_다_비면_데이터셋_번호로_만든다_키를_비우지_않는다")
    void fallsBackToDatasetId() {
        assertThat(keyOf(null, null)).isEqualTo("5149");
        assertThat(keyOf("", "  ")).isEqualTo("5149");
    }

    @Test
    @DisplayName("★자리_참조뿐인_값은_키로_쓰지_않는다")
    void dotOnlyIsNotAKey() {
        assertThat(keyOf("..", ".")).isEqualTo("5149");
    }

    /**
     * ★ 이 시험이 지키는 것 — 만들어진 키가 <b>클립 식별자 조립을 통과한다</b>.
     *
     * <p>⚠ 지키지 못하는 것 — 포털이 실제로 어떤 코드·버전을 보내는지는 여기서 알 수 없다. 아는 모양
     * (하이픈·점)과 <b>어긋날 수 있는 모양</b>(경로 구분자·공백·제어 문자·아주 긴 값)을 함께 넣는다.
     */
    @Test
    @DisplayName("★★어떤_코드_버전이_와도_클립_식별자_조립을_통과한다")
    void alwaysComposesAValidClipId() {
        String[][] cases = {
                {"DS-FIRE-2026-01", "1.0"},
                {"DS/FLOOD/2025", "v1\n2"},          // 경로 구분자·제어 문자
                {"배포 코드", "버 전"},                 // 공백·한글
                {"a".repeat(300), "b".repeat(300)},  // 컬럼 폭을 한참 넘는 값
                {null, null},
        };
        for (String[] c : cases) {
            String key = keyOf(c[0], c[1]);
            assertThat(key).as("키는 비지 않는다").isNotBlank();
            assertThat(key).as("경로 구분자·제어 문자는 남지 않는다").doesNotMatch(".*[/\\\\\\p{Cntrl}].*");
            assertThatCode(() -> LsDataRaw.portalDatasetClipId(DATASET_ID, key))
                    .as("클립 식별자 조립을 통과한다")
                    .doesNotThrowAnyException();
            assertThat(LsDataRaw.portalDatasetClipId(DATASET_ID, key).length())
                    .isLessThanOrEqualTo(LsDataRaw.VMS_CLIP_ID_MAX);
        }
    }

    /**
     * 자르는 것이 안전한 근거 — 이 키는 <b>데이터셋 하나에 하나</b>뿐이고 클립 식별자에 데이터셋 번호가
     * 이미 들어 있어 서로 다른 영상이 같은 식별자로 접힐 수 없다(영상 파일명 쪽은 반대라 거부한다).
     */
    @Test
    @DisplayName("데이터셋이_다르면_잘린_키라도_클립_식별자가_갈린다")
    void truncatedKeysStillDifferAcrossDatasets() {
        String longCode = "X".repeat(300);
        String a = LsDataRaw.portalDatasetClipId(1L, new DatasetRef(1L, longCode, null).fallbackVideoKey());
        String b = LsDataRaw.portalDatasetClipId(2L, new DatasetRef(2L, longCode, null).fallbackVideoKey());

        assertThat(a).isNotEqualTo(b);
    }
}
