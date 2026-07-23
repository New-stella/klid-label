package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.video.util.AugTypeParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파생 영상 VMS_CLIP_ID → 증강 종류 정규화 파서 검증 (R3).
 *
 * <p>실데이터(cudo_246) 포맷 드리프트(이중 접두 RESL_RESL_, 구형 RES_)를 정규화값으로 흡수하는지 확인.
 */
class AugTypeParserTest {

    @Test
    @DisplayName("augType파서_RESL_RESL_480P_정규화")
    void reslDoublePrefix() {
        // given: 이중 접두 드리프트 (실데이터)
        String clip = "test-1784778573069_RESL_RESL_480P_1784779138682";
        // when / then
        assertThat(AugTypeParser.parse(clip)).isEqualTo(LsDataAug.AUG_RESL_480P);
    }

    @Test
    @DisplayName("augType파서_구형_RES_480P_정규화")
    void legacyResPrefix() {
        // given: 구형 RES 접두 드리프트 (실데이터)
        String clip = "test-1784680607679_RES_RES_480P_1784683613093";
        // when / then: RESL_480P 로 정규화
        assertThat(AugTypeParser.parse(clip)).isEqualTo(LsDataAug.AUG_RESL_480P);
    }

    @Test
    @DisplayName("augType파서_RESL_1080P_720P_정규화")
    void resl1080And720() {
        assertThat(AugTypeParser.parse("clip_RESL_1080P_123")).isEqualTo(LsDataAug.AUG_RESL_1080P);
        assertThat(AugTypeParser.parse("clip_RESL_720P_123")).isEqualTo(LsDataAug.AUG_RESL_720P);
    }

    @Test
    @DisplayName("augType파서_AUG_WINTER")
    void augWinter() {
        assertThat(AugTypeParser.parse("clip-123_AUG_WINTER_1784683613093"))
                .isEqualTo(LsDataAug.AUG_WINTER);
    }

    @Test
    @DisplayName("augType파서_AUG_NIGHT_RAIN")
    void augNightRain() {
        assertThat(AugTypeParser.parse("clip-123_AUG_NIGHT_999")).isEqualTo(LsDataAug.AUG_NIGHT);
        assertThat(AugTypeParser.parse("clip-123_AUG_RAIN_999")).isEqualTo(LsDataAug.AUG_RAIN);
    }

    @Test
    @DisplayName("augType파서_원본명에_WINTER포함_실제_NIGHT증강_NIGHT반환")
    void originNameContainsWinterButActuallyNight() {
        // given: 원본 clip 이름에 winter 가 섞였으나 실제 증강은 NIGHT (마커 뒤 토큰이 진실)
        String clip = "winter-park_AUG_NIGHT_1784779138682";
        // when / then: 마커(_AUG_) 뒤 토큰만 판별 → NIGHT (구 파서는 WINTER 오판)
        assertThat(AugTypeParser.parse(clip)).isEqualTo(LsDataAug.AUG_NIGHT);
    }

    @Test
    @DisplayName("augType파서_원본명에_480p포함_실제_720P해상도_RESL_720P반환")
    void originNameContains480pButActually720p() {
        // given: 원본 clip 이름에 480p 가 섞였으나 실제 파생은 720P (마커 뒤 코드가 진실)
        String clip = "road480p-clip_RESL_720P_1784779138682";
        // when / then: 마커(_RESL_) 뒤 코드만 판별 → RESL_720P (구 파서는 480P 오판 위험)
        assertThat(AugTypeParser.parse(clip)).isEqualTo(LsDataAug.AUG_RESL_720P);
    }

    @Test
    @DisplayName("augType파서_마커없는_순수원본명_null")
    void noMarkerPlainOriginReturnsNull() {
        // given: 증강/해상도 마커가 전혀 없는 순수 원본명 (우연 토큰 포함해도 오탐 금지)
        assertThat(AugTypeParser.parse("winter480p-plain-clip")).isNull();
    }

    @Test
    @DisplayName("augType파서_미매칭_null")
    void unmatchedReturnsNull() {
        // given: 원본 클립 (증강/해상도 토큰 없음)
        assertThat(AugTypeParser.parse("test-1784680607679_plain_clip")).isNull();
    }

    @Test
    @DisplayName("augType파서_null입력_null")
    void nullOrBlankReturnsNull() {
        assertThat(AugTypeParser.parse(null)).isNull();
        assertThat(AugTypeParser.parse("")).isNull();
        assertThat(AugTypeParser.parse("   ")).isNull();
    }
}
