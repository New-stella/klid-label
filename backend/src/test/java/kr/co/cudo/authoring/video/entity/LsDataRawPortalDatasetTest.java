package kr.co.cudo.authoring.video.entity;

import kr.co.cudo.authoring.video.repository.InternalWorkScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포털 데이터셋 영상 출처({@code PORTAL_DATASET})의 값 규약 — 팩토리·판별자·채널 집합 (ADR-068 · ERD-012).
 *
 * @design ADR-068
 */
class LsDataRawPortalDatasetTest {

    @Test
    @DisplayName("포털_데이터셋_영상은_미상_개인정보유형_비식별완료_소유자없음_원본으로_적재된다")
    void 포털_데이터셋_영상은_미상_개인정보유형_비식별완료_소유자없음_원본으로_적재된다() {
        // given
        String clipId = LsDataRaw.portalDatasetClipId(42L, "video-a");

        // when
        LsDataRaw raw = LsDataRaw.createPortalDataset(clipId, "/portal/materials/42/current/content/video-a");

        // then
        assertThat(raw.getVmsClipId()).isEqualTo("PORTAL_DATASET_42_video-a");
        assertThat(raw.getSrcType()).isEqualTo(LsDataRaw.SRC_TYPE_PORTAL_DATASET);
        assertThat(raw.getPrvcTypeCd())
                .as("판정한 적이 없으므로 「비식별 불필요」 판정값(ANONY)을 쓰지 않는다")
                .isEqualTo(LsDataRaw.PRVC_TYPE_UNKNOWN)
                .isNotEqualTo(LsDataRaw.PRVC_TYPE_ANONY);
        assertThat(raw.getDeIdntfYn()).as("배포본 이미지는 비식별본이다").isEqualTo("Y");
        assertThat(raw.getPortalUserNo()).as("데이터셋은 공유 소재라 소유자가 없다").isNull();
        assertThat(raw.getOrgnlRawSn()).as("파생이 아니다").isNull();
        assertThat(raw.isDerivative()).isFalse();
        assertThat(raw.getDataSttsCd())
                .as("외부 이관 적재와 같은 배치 단계 규약(빌더 기본값)")
                .isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(raw.isPortalDataset()).isTrue();
        assertThat(raw.isPortalUpload()).isFalse();
        assertThat(raw.genAiYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("외부_이관_적재와_배치_단계_값이_같다")
    void 외부_이관_적재와_배치_단계_값이_같다() {
        // given — 선례와 대조한다(값을 하드코딩한 기대가 선례가 바뀔 때 조용히 갈라지지 않게).
        LsDataRaw imported = LsDataRaw.createFromImport(
                "IMPORT-1", null, null, LsDataRaw.PRVC_TYPE_UNKNOWN, "/x/1.mp4", null, null, true);

        // when
        LsDataRaw dataset = LsDataRaw.createPortalDataset(
                LsDataRaw.portalDatasetClipId(1L, "k"), "/x/k");

        // then
        assertThat(dataset.getDataSttsCd()).isEqualTo(imported.getDataSttsCd());
    }

    @Test
    @DisplayName("출처_판별자는_서로_배타적이다_포털_업로드는_데이터셋_영상이_아니다")
    void 출처_판별자는_서로_배타적이다_포털_업로드는_데이터셋_영상이_아니다() {
        // given
        LsDataRaw upload = LsDataRaw.createPortalUpload("portal-user-1", "/portal/uploads/a.mp4");
        LsDataRaw control = LsDataRaw.createFromIngest(
                "CLIP-1", "CCTV", "EVT", "11680", LsDataRaw.PRVC_TYPE_ANONY, "/raw/1.mp4", null, 30);

        // when / then
        assertThat(upload.isPortalDataset()).isFalse();
        assertThat(control.isPortalDataset()).isFalse();
        assertThat(control.isPortalUpload()).isFalse();
    }

    @Test
    @DisplayName("클립_식별자_규칙을_벗어난_값은_거부한다")
    void 클립_식별자_규칙을_벗어난_값은_거부한다() {
        // when / then — 관제 클립 식별자·접두만 있는 값·null 은 멱등 키를 흐린다.
        assertThatThrownBy(() -> LsDataRaw.createPortalDataset("CLIP-FROM-CONTROL", "/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataRaw.createPortalDataset(LsDataRaw.PORTAL_DATASET_CLIP_ID_PREFIX, "/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataRaw.createPortalDataset(null, "/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataRaw.createPortalDataset(
                LsDataRaw.PORTAL_DATASET_CLIP_ID_PREFIX + "x".repeat(LsDataRaw.VMS_CLIP_ID_MAX), "/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("원천_위치가_비면_거부한다")
    void 원천_위치가_비면_거부한다() {
        String clipId = LsDataRaw.portalDatasetClipId(1L, "k");

        assertThatThrownBy(() -> LsDataRaw.createPortalDataset(clipId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataRaw.createPortalDataset(clipId, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("클립_식별자_조립은_영상_키가_비거나_상한을_넘으면_자르지_않고_거부한다")
    void 클립_식별자_조립은_영상_키가_비거나_상한을_넘으면_자르지_않고_거부한다() {
        // given — 상한 정확히 맞는 키는 통과한다(경계).
        String prefix = LsDataRaw.PORTAL_DATASET_CLIP_ID_PREFIX + "7_";
        String fits = "k".repeat(LsDataRaw.VMS_CLIP_ID_MAX - prefix.length());

        // when / then
        assertThat(LsDataRaw.portalDatasetClipId(7L, fits)).hasSize(LsDataRaw.VMS_CLIP_ID_MAX);
        assertThatThrownBy(() -> LsDataRaw.portalDatasetClipId(7L, fits + "k"))
                .as("자르면 서로 다른 영상 키가 같은 식별자로 접힌다")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataRaw.portalDatasetClipId(7L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataRaw.portalDatasetClipId(7L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("인입_적재면과_업로드_입력면_허용목록에_넣지_않는다")
    void 인입_적재면과_업로드_입력면_허용목록에_넣지_않는다() {
        assertThat(LsDataIngest.ALLOWED_SRC_TYPES).doesNotContain(LsDataRaw.SRC_TYPE_PORTAL_DATASET);
        assertThat(LsDataIngest.UPLOAD_SRC_TYPES).doesNotContain(LsDataRaw.SRC_TYPE_PORTAL_DATASET);
    }

    @Test
    @DisplayName("포털_채널_출처_집합은_업로드와_데이터셋_둘이다")
    void 포털_채널_출처_집합은_업로드와_데이터셋_둘이다() {
        assertThat(LsDataRaw.PORTAL_CHANNEL_SRC_TYPES)
                .containsExactlyInAnyOrder(LsDataRaw.SRC_TYPE_PORTAL_ULD, LsDataRaw.SRC_TYPE_PORTAL_DATASET);
    }

    @Test
    @DisplayName("JPQL_조각은_포털_채널_출처_집합의_원소를_빠짐없이_배제한다")
    void JPQL_조각은_포털_채널_출처_집합의_원소를_빠짐없이_배제한다() {
        // given — 조각은 컴파일 타임 상수라 집합을 쓰지 못하고 상수를 직접 잇는다. 집합에 값이 늘었는데
        //   조각을 안 늘리면 JPQL 경로(통계·증강 이력·고착 회수)만 새는 값이 생긴다.
        // when / then
        for (String srcType : LsDataRaw.PORTAL_CHANNEL_SRC_TYPES) {
            assertThat(InternalWorkScope.INTERNAL_JPQL)
                    .as("JPQL 조각이 %s 를 배제해야 한다", srcType)
                    .contains("'" + srcType + "'");
        }
        assertThat(InternalWorkScope.INTERNAL_JPQL).contains("not in (");
    }
}
