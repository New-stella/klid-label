package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포털 보존기간 만료 예정 시각 <b>단일 판정 지점</b> 단위 테스트. @design AC-033, AC-037, DFEAT-055
 *
 * <p>서비스 계층 테스트가 배선(집계 조회·응답 매핑)을 보는 반면, 여기서는 계산식 자체만 본다 —
 * 기준점 선택(최초 저장/늦은 쪽/전이 시각), 상태별 분기, 설정 부재 시 침묵을 결정적으로 고정한다.
 *
 * <p>★ 보존일수 <b>0·음수</b>는 「설정 없음」과 같이 다뤄 만료 판정을 하지 않는다(fail-closed).
 * 0 을 그대로 적용하면 커트라인이 현재 시각이 되어 <b>방금 저장한 라벨까지 전량이 즉시 삭제 대상</b>이
 * 되고 그 삭제는 비가역이다. 판정 지점은 {@code retentionDays} 한 곳이며 두 축이 공유한다.
 *
 * <p>★ 두 축의 기준점이 <b>다르다</b>는 사실 자체를 고정한다 — 데이터마트는 최초 저장,
 * 업로드 READY 는 늦은 쪽이다. 「일관성」을 이유로 통일하면 확정되지 않은 사양으로 사용자 데이터를
 * 비가역 삭제하게 된다(DFEAT-055).
 */
class PortalRetentionPolicyTest {

    private static final LocalDateTime REG_DT = LocalDateTime.of(2026, 8, 1, 10, 0);

    private SystemConfigService systemConfigService;
    private PortalRetentionPolicy policy;

    @BeforeEach
    void setUp() {
        systemConfigService = mock(SystemConfigService.class);
        policy = new PortalRetentionPolicy(systemConfigService);
    }

    private void stub(String key, Integer days) {
        when(systemConfigService.getInt(key)).thenReturn(days);
    }

    // ======================== 데이터마트 축 ========================

    @Test
    @DisplayName("데이터마트_최초_저장일에_보존일수를_더한_시각이_만료예정이다")
    void datamartExpiryIsFirstSavedPlusDays() {
        // given
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);

        // when / then: 인자는 그 (사용자, 영상) 그룹의 MIN(REG_DT) 다
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("★데이터마트는_최초저장_기준이고_업로드_READY는_늦은쪽_기준이다_두_축을_통일하지_않는다")
    void datamartAndUploadAxesUseDifferentBasePoints() {
        // given: 같은 그룹/자산에 최초 저장 REG_DT, 이후 재저장 REG_DT+3d
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        LocalDateTime firstSaved = REG_DT;
        LocalDateTime lastSaved = REG_DT.plusDays(3);

        // when / then: 데이터마트는 최초 저장에서 굳고(밀리지 않는다)
        assertThat(policy.datamartExpiry().expiresAt(firstSaved))
                .as("데이터마트 축은 재저장으로 만료가 밀리지 않는다(DFEAT-055 확정)")
                .isEqualTo(firstSaved.plusDays(7));

        // 업로드 READY 는 마지막 저장까지 반영해 뒤로 밀린다 — 이 축은 확정 대상이 아니었다
        assertThat(policy.uploadExpiry()
                .expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, lastSaved))
                .as("★업로드 축을 데이터마트 축에 맞춰 「정정」하면 이 단언이 깨진다")
                .isEqualTo(lastSaved.plusDays(7));
    }

    @Test
    @DisplayName("데이터마트_저장_라벨이_없으면_만료예정은_null이다_기준점_부재")
    void datamartExpiryNullWithoutLabel() {
        // given
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);

        // when / then: 기준점이 없으면 값을 지어내지 않는다
        assertThat(policy.datamartExpiry().expiresAt(null)).isNull();
    }

    @Test
    @DisplayName("데이터마트_보존기간_설정이_없으면_만료예정은_null이고_예외가_새지_않는다")
    void datamartExpiryNullWhenConfigMissing() {
        // given: 이 키는 폴백하지 않으므로 getInt 가 예외를 던진다
        when(systemConfigService.getInt(any()))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));

        // when / then
        assertThat(policy.datamartRetentionDays()).isEmpty();
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isNull();
    }

    // ======================== 업로드 축 ========================

    @Test
    @DisplayName("업로드_READY는_등록일과_라벨_마지막저장일_중_늦은쪽이_기준이다")
    void uploadReadyUsesLaterOfRegDtAndLabel() {
        // given
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then: 라벨이 늦을 때 / 등록일이 늦을 때 / 라벨이 없을 때
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, REG_DT.plusDays(3)))
                .isEqualTo(REG_DT.plusDays(3).plusDays(7));
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, REG_DT.minusDays(3)))
                .isEqualTo(REG_DT.plusDays(7));
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, null))
                .isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("업로드_FAILED는_전이시각_기준_단축_보존기간이며_라벨_저장일에_영향받지_않는다")
    void uploadFailedUsesMdfcnDtOnly() {
        // given
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        LocalDateTime failedAt = REG_DT.plusHours(5);

        // when / then: 라벨이 훨씬 뒤에 있어도 FAILED 축은 전이 시각만 본다
        assertThat(policy.uploadExpiry()
                .expiresAt(PortalUploadLedger.STATUS_FAILED, REG_DT, failedAt, REG_DT.plusDays(10)))
                .isEqualTo(failedAt.plusDays(1));
    }

    @Test
    @DisplayName("업로드_READY와_FAILED는_같은_시각_등록이어도_독립적으로_판정된다_AC037")
    void uploadAxesAreIndependent() {
        // given: 두 축의 보존일수가 다르다
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, null))
                .isEqualTo(REG_DT.plusDays(7));
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_FAILED, REG_DT, REG_DT, null))
                .isEqualTo(REG_DT.plusDays(1));
    }

    @Test
    @DisplayName("업로드_PROCESSING과_UPLOADED는_삭제_대상이_아니므로_만료예정이_null이다")
    void uploadNonDeletableStatusesHaveNoExpiry() {
        // given
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_PROCESSING, REG_DT, REG_DT, REG_DT)).isNull();
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_UPLOADED, REG_DT, REG_DT, REG_DT)).isNull();
    }

    @Test
    @DisplayName("업로드_한쪽_보존기간_설정만_없으면_그_축만_null이고_다른_축은_계산된다")
    void uploadMissingOneConfigAffectsOnlyThatAxis() {
        // given: READY 축만 설정이 있다
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, null))
                .isEqualTo(REG_DT.plusDays(7));
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_FAILED, REG_DT, REG_DT, null)).isNull();
    }

    // ============ 보존일수 0·음수는 「설정 없음」과 같다 (fail-closed) — DFEAT-055 ============

    @Test
    @DisplayName("★데이터마트_보존일수가_0이면_만료예정을_내리지_않는다_기준점_그_자체가_되지_않는다")
    void datamartRetentionZeroYieldsNoExpiry() {
        // given: 0 을 그대로 더하면 만료예정 = 기준점(=이미 지난 시각) 이라 전량이 즉시 삭제 대상이 된다.
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 0);

        // when / then: 0 을 적용하지도, 임의 기본값(7 등)으로 때우지도 않는다.
        assertThat(policy.datamartRetentionDays()).isEmpty();
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isNull();
    }

    @Test
    @DisplayName("★데이터마트_보존일수가_음수면_만료예정을_내리지_않는다_기준점보다_이른_시각이_되지_않는다")
    void datamartRetentionNegativeYieldsNoExpiry() {
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, -3);

        assertThat(policy.datamartRetentionDays()).isEmpty();
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isNull();
    }

    @Test
    @DisplayName("★업로드_두_축도_0이하면_만료예정을_내리지_않는다_판정은_두_축_공통_헬퍼가_소유한다")
    void uploadRetentionNotPositiveYieldsNoExpiry() {
        // given: READY 는 0, FAILED 는 음수 — 데이터마트 축과 같은 헬퍼를 공유한다.
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 0);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, -1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then
        assertThat(policy.uploadRetentionDays()).isEmpty();
        assertThat(policy.uploadFailedRetentionDays()).isEmpty();
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, null)).isNull();
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_FAILED, REG_DT, REG_DT, null)).isNull();
    }

    @Test
    @DisplayName("★한_축의_보존일수가_0이어도_다른_축의_정상값은_그대로_계산된다")
    void invalidOneAxisDoesNotDisableTheOther() {
        // given: 데이터마트만 0(비정상), 업로드 READY 는 정상 7일
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 0);
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);

        // when / then: 가드가 공유 헬퍼에 있어도 «키 단위» 판정이라 정상 축을 함께 끄지 않는다.
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isNull();
        assertThat(policy.uploadExpiry()
                .expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, null))
                .isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("보존일수_1은_정상값이라_그대로_계산된다_경계를_0에서_끊는다")
    void retentionOfOneDayIsStillValid() {
        // 경계 확인 — 하한을 1 이 아니라 2 로 잘못 잡으면 여기서 깨진다.
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 1);

        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(1));
    }

    // ======================== 파생값 성질 (AC-033) ========================

    @Test
    @DisplayName("보존기간_설정이_바뀌면_같은_기준점이어도_다시_계산된_값이_나온다_AC033")
    void expiryFollowsCurrentSettingValue() {
        // given: 7일로 한 번 계산
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(7));

        // when: 설정만 14일로 변경
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 14);

        // then: 저장된 값이 아니라 조회 시점 설정 기준 파생값이다
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(14));
    }

    @Test
    @DisplayName("스냅샷은_생성_시점_설정을_굳혀_한_페이지_안에서_기준이_섞이지_않는다")
    void snapshotFreezesSettingForOnePage() {
        // given: 스냅샷 생성 시점 7일
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);
        PortalRetentionPolicy.DatamartExpiry expiry = policy.datamartExpiry();

        // when: 그 뒤에 설정이 바뀌어도
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 14);

        // then: 이미 만든 스냅샷은 같은 기준을 유지한다(다음 조회부터 새 값)
        assertThat(expiry.expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(7));
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(14));
    }
}
