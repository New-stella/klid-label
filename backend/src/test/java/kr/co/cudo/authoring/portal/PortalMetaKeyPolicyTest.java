package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.service.PortalColumnMetaField;
import kr.co.cudo.authoring.portal.service.PortalMetaKeyPolicy;
import kr.co.cudo.authoring.portal.service.PortalMetaKeyPolicy.Bucket;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.transfer.ImportMetaKeys;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 메타 키 분류 — <b>내부 원장의 판정을 재사용</b>하고 포털 축 하나만 더한다.
 *
 * @design API-234, API-235
 */
class PortalMetaKeyPolicyTest {

    @Test
    @DisplayName("영상_기술메타는_기술목록으로_간다")
    void technicalKeysGoToTechnicalBucket() {
        assertThat(PortalMetaKeyPolicy.bucketOf("video.fps")).isEqualTo(Bucket.TECHNICAL);
        assertThat(PortalMetaKeyPolicy.bucketOf(PortalUploadLedger.KEY_MIME)).isEqualTo(Bucket.TECHNICAL);
        assertThat(PortalMetaKeyPolicy.bucketOf(PortalUploadLedger.KEY_ORIGINAL_FILENAME))
                .as("업로드 원본 파일명도 기술메타 네임스페이스에 앉는다")
                .isEqualTo(Bucket.TECHNICAL);
    }

    @Test
    @DisplayName("자동_산출_읽기전용과_이관_원문은_표시전용_목록으로_간다")
    void readOnlyAndImportedGoToReadOnlyBucket() {
        assertThat(PortalMetaKeyPolicy.bucketOf("vlm.accuracy")).isEqualTo(Bucket.READ_ONLY);
        assertThat(PortalMetaKeyPolicy.bucketOf(ImportMetaKeys.VIDEO_COORDINATES)).isEqualTo(Bucket.READ_ONLY);
    }

    @Test
    @DisplayName("시계열_서술과_레거시_구간키는_편집_가능하다")
    void timeseriesKeysAreEditable() {
        assertThat(PortalMetaKeyPolicy.bucketOf(VlmResultService.META_KEY_DESCRIPTION)).isEqualTo(Bucket.EDITABLE);
        assertThat(PortalMetaKeyPolicy.bucketOf("0-8"))
                .as("레거시 구간 키는 작업 결과라 계속 편집 가능해야 한다")
                .isEqualTo(Bucket.EDITABLE);
        assertThat(PortalMetaKeyPolicy.bucketOf("manual-timeseries")).isEqualTo(Bucket.EDITABLE);
    }

    /**
     * ★★ 이 시험이 막는 것 — <b>fail-open</b>.
     *
     * <p>내부 원장의 분류기에는 포털 축이 없다(그 창구는 포털 자산을 다루지 않는다). 그래서 포털 축을
     * 더하지 않으면 {@code portal.*} 가 <b>여집합으로 떨어져 편집 가능 목록에 들어가고</b>, 사용자가
     * 업로드 처리 상태를 임의로 바꿔 상태 기계를 통째로 우회한다. 오류 없이 통과하므로 다른 어떤
     * 시험에도 걸리지 않는다.
     */
    @Test
    @DisplayName("★포털_파이프라인_내부키는_편집_대상이_아니다_상태_우회_차단")
    void portalPipelineKeysAreNeverEditable() {
        assertThat(PortalMetaKeyPolicy.bucketOf(PortalUploadLedger.KEY_UPLOAD_STATUS))
                .as("업로드 처리 상태를 고칠 수 있으면 상태 기계가 우회된다")
                .isEqualTo(Bucket.READ_ONLY);
        assertThat(PortalMetaKeyPolicy.bucketOf(PortalUploadLedger.KEY_FAIL_REASON))
                .isEqualTo(Bucket.READ_ONLY);
        assertThat(PortalMetaKeyPolicy.isEditable(PortalUploadLedger.KEY_UPLOAD_STATUS)).isFalse();
        assertThat(PortalMetaKeyPolicy.isEditable(PortalUploadLedger.PORTAL_KEY_PREFIX + "future_key"))
                .as("접두 안의 새 키가 늘어도 편집 경로로 새지 않는다(fail-closed)")
                .isFalse();
    }

    /**
     * ★★ 컬럼 축 셋(촬영환경·프레임 설명·개인정보 판정)은 <b>사용자가 고칠 수 있는</b> 항목이다.
     * 표시 전용으로 넘어가면 저장 요청이 전부 400 이 되어 화면이 그린 값을 아무도 저장하지 못한다.
     */
    @Test
    @DisplayName("★컬럼_축_키는_전부_편집_가능_목록이다")
    void columnAxisKeysAreEditable() {
        for (String key : PortalColumnMetaField.allKeys()) {
            assertThat(PortalMetaKeyPolicy.bucketOf(key))
                    .as("컬럼 축 키가 표시 전용으로 넘어가면 저장이 통째로 막힌다: %s", key)
                    .isEqualTo(Bucket.EDITABLE);
        }
    }

    /**
     * ★ 컬럼 축 판정은 <b>여집합이 아니라 명시 분기</b>다 — 다른 소유자의 판정보다 <b>앞</b>에 있어야
     * 그들이 접두를 늘려도 이 축이 조용히 표시 전용으로 넘어가지 않는다.
     *
     * <p>이 시험은 그 순서를 <b>결과로</b> 확인한다: 다른 판정이 참인 접두를 컬럼 축 키가 갖게 되는
     * 상황을 직접 만들 수는 없으므로, 대신 컬럼 축 키가 다른 어떤 판정에도 걸리지 않는 사실과
     * 컬럼 축 키를 뺀 자리에 그 판정들이 그대로 살아 있는 사실을 함께 못박는다.
     */
    @Test
    @DisplayName("★컬럼_축_판정은_다른_판정과_겹치지_않고_각자_살아_있다")
    void columnAxisDoesNotSwallowOtherPredicates() {
        for (String key : PortalColumnMetaField.allKeys()) {
            assertThat(key)
                    .as("컬럼 축 키가 다른 소유자의 접두를 쓰면 소유가 겹친다")
                    .doesNotStartWith("video.")
                    .doesNotStartWith("vlm.")
                    .doesNotStartWith(ImportMetaKeys.PREFIX)
                    .doesNotStartWith(PortalUploadLedger.PORTAL_KEY_PREFIX);
        }
        assertThat(PortalMetaKeyPolicy.bucketOf("video.fps")).isEqualTo(Bucket.TECHNICAL);
        assertThat(PortalMetaKeyPolicy.bucketOf("vlm.accuracy")).isEqualTo(Bucket.READ_ONLY);
        assertThat(PortalMetaKeyPolicy.bucketOf(ImportMetaKeys.VIDEO_LOCATION)).isEqualTo(Bucket.READ_ONLY);
        assertThat(PortalMetaKeyPolicy.bucketOf(PortalUploadLedger.KEY_UPLOAD_STATUS)).isEqualTo(Bucket.READ_ONLY);
    }

    @Test
    @DisplayName("편집_가능은_편집목록_하나뿐이다_fail_closed")
    void editableIsExactlyTheEditableBucket() {
        assertThat(PortalMetaKeyPolicy.isEditable("vlm.description")).isTrue();
        assertThat(PortalMetaKeyPolicy.isEditable("video.fps")).isFalse();
        assertThat(PortalMetaKeyPolicy.isEditable("vlm.accuracy")).isFalse();
        assertThat(PortalMetaKeyPolicy.isEditable(ImportMetaKeys.VIDEO_LOCATION)).isFalse();
    }
}
