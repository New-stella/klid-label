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
 * 기준점 선택(늦은 쪽/전이 시각), 상태별 분기, 설정 부재 시 침묵을 결정적으로 고정한다.
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
    @DisplayName("데이터마트_마지막_저장일에_보존일수를_더한_시각이_만료예정이다")
    void datamartExpiryIsLastSavedPlusDays() {
        // given
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);

        // when / then
        assertThat(policy.datamartExpiry().expiresAt(REG_DT)).isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("데이터마트_저장_라벨이_없으면_만료예정은_null이다")
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
