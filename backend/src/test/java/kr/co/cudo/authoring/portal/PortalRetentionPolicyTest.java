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
import java.util.List;

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

    /**
     * ★ 2026-09-05 확정으로 <b>마킹 대기에도 만료가 생겼다</b>(AC-1070). 삭제 대상이 아닌 상태는
     * {@code PROCESSING} 하나로 좁아졌다 — 프레임 추출과 경쟁하면 파일과 원장이 어긋나기 때문이며
     * 그 상태는 방치 판정이 따로 회수한다. ⚠ 이 변화를 「방치 판정을 되살린 것」으로 읽지 말 것 —
     * 방치는 분 단위로 <b>실패 마감</b>하는 경로이고 이것은 일 단위 <b>정상 만료</b>다.
     */
    @Test
    @DisplayName("★업로드_PROCESSING만_만료예정이_null이다_마킹대기는_이제_값이_실린다")
    void onlyProcessingHasNoExpiry() {
        // given
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_PROCESSING, REG_DT, REG_DT, REG_DT))
                .as("처리 중은 프레임 추출과 경쟁하므로 삭제 대상이 아니고 고지할 만료도 없다")
                .isNull();
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_UPLOADED, REG_DT, REG_DT, REG_DT))
                .as("★마킹 대기를 null 로 되돌리면 그 자산은 파일째 영구히 남는다")
                .isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("★마킹_대기는_등록일_기산이다_라벨_저장일에_밀리지_않는다")
    void uploadedUsesRegDtOnly() {
        // given: 준비 완료 축이라면 늦은 쪽(=라벨)이 기준이 되지만 마킹 대기는 등록일 하나다.
        //        (그 상태에는 프레임이 없어 라벨이 존재할 수 없다 — 인자가 와도 무시한다.)
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        // when / then: 상태 변경 시각·라벨 저장일이 훨씬 뒤여도 등록일 + 보존일수다.
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_UPLOADED,
                REG_DT, REG_DT.plusDays(5), REG_DT.plusDays(9)))
                .as("★기산점을 등록일이 아닌 것(전이 시각·라벨 저장일)으로 바꾸면 여기서 깨진다")
                .isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("★마킹_대기는_준비완료_축_설정을_재사용한다_실패축_설정을_쓰지_않는다")
    void uploadedReusesReadyRetentionSetting() {
        // given: 두 설정을 다르게 둬 어느 쪽을 읽는지 드러나게 한다(7 vs 1).
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);

        assertThat(policy.uploadExpiry()
                .expiresAt(PortalUploadLedger.STATUS_UPLOADED, REG_DT, REG_DT, null))
                .as("새 설정 키를 만들지 않기로 확정했다(AC-1070) — 준비 완료 축 값을 그대로 쓴다")
                .isEqualTo(REG_DT.plusDays(7));
    }

    @Test
    @DisplayName("★준비완료_설정이_없으면_마킹_대기도_함께_만료예정이_null이다_설정을_공유한다")
    void uploadedFollowsReadySettingAbsence() {
        // given: 공유 설정만 없다. 실패 축은 정상이라 그 축은 계속 계산된다.
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);
        PortalRetentionPolicy.UploadExpiry expiry = policy.uploadExpiry();

        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_UPLOADED, REG_DT, REG_DT, null)).isNull();
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_READY, REG_DT, REG_DT, null)).isNull();
        assertThat(expiry.expiresAt(PortalUploadLedger.STATUS_FAILED, REG_DT, REG_DT, null))
                .isEqualTo(REG_DT.plusDays(1));
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


    // ============ 삭제 커트라인 — 읽기 축의 역함수이며 같은 클래스가 소유한다 (DFEAT-055) ============

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);

    @Test
    @DisplayName("★커트라인은_만료예정의_역함수다_고지한_만료가_지났다와_삭제대상이다가_같은_사실이다")
    void cutoffIsInverseOfExpiry() {
        // given: 두 산술이 서로 다른 클래스에서 독립 유도되면 고지한 날과 실제 삭제일이 갈린다.
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);
        LocalDateTime cutoff = policy.datamartCutoff(NOW).orElseThrow();
        PortalRetentionPolicy.DatamartExpiry expiry = policy.datamartExpiry();

        // when / then: 경계(커트라인과 같은 시각) 양옆을 포함해 두 축의 판정이 항상 일치한다.
        for (LocalDateTime base : List.of(
                cutoff.minusDays(30), cutoff.minusSeconds(1), cutoff, cutoff.plusSeconds(1), NOW)) {
            boolean expiredByReadAxis = expiry.expiresAt(base).isBefore(NOW);
            boolean expiredByDeleteAxis = base.isBefore(cutoff);
            assertThat(expiredByDeleteAxis)
                    .as("기준점=%s 에서 「고지한 만료가 지났다」와 「삭제 대상이다」가 갈렸다", base)
                    .isEqualTo(expiredByReadAxis);
        }
    }

    @Test
    @DisplayName("데이터마트_커트라인은_기준시각에서_보존일수만큼_과거다")
    void datamartCutoffIsRetentionDaysBeforeNow() {
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 7);

        assertThat(policy.datamartCutoff(NOW)).contains(NOW.minusDays(7));
    }

    @Test
    @DisplayName("★업로드_두_축_커트라인은_한_기준시각에서_파생된다_각자_now를_뜨지_않는다")
    void uploadCutoffsShareOneCapturedAt() {
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);

        PortalRetentionPolicy.UploadCutoffs cutoffs = policy.uploadCutoffs(NOW);

        assertThat(cutoffs.capturedAt()).isEqualTo(NOW);
        assertThat(cutoffs.uploaded()).contains(NOW.minusDays(7));
        assertThat(cutoffs.ready()).contains(NOW.minusDays(7));
        assertThat(cutoffs.failed()).contains(NOW.minusDays(1));
    }

    @Test
    @DisplayName("★마킹_대기_커트라인은_준비완료와_같은_설정이라_값이_같고_같은_capturedAt_에서_나온다")
    void uploadedCutoffSharesReadySettingAndCapturedAt() {
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);

        PortalRetentionCutoffsProbe probe = PortalRetentionCutoffsProbe.of(policy.uploadCutoffs());

        assertThat(probe.uploaded())
                .as("같은 설정을 공유하므로 값도 같다 — 가르는 것은 기산점이지 보존일수가 아니다")
                .isEqualTo(probe.ready());
        assertThat(probe.uploaded().plusDays(7))
                .as("★새 축이 자기 now() 를 뜨면 한 회차 안에서 판정 기준이 갈린다")
                .isEqualTo(probe.failed().plusDays(1));
    }

    /** 세 축 커트라인을 한 번에 꺼내 비교하기 위한 시험 전용 뷰(설정은 모두 있어야 한다). */
    private record PortalRetentionCutoffsProbe(LocalDateTime uploaded, LocalDateTime ready,
                                               LocalDateTime failed) {
        static PortalRetentionCutoffsProbe of(PortalRetentionPolicy.UploadCutoffs c) {
            return new PortalRetentionCutoffsProbe(
                    c.uploaded().orElseThrow(), c.ready().orElseThrow(), c.failed().orElseThrow());
        }
    }

    /**
     * ★ 기준시각을 주입하지 않는 실운영 경로({@code uploadCutoffs()})도 <b>한 회차 기준시각 하나</b>를
     * 두 축이 공유해야 한다. 축마다 {@code now()} 를 다시 뜨면 이 단언이 깨진다 — 어긋남이 밀리초라
     * 눈으로는 보이지 않으므로 「각자의 보존일수만큼 되돌리면 한 시각으로 모인다」로 잡는다.
     */
    @Test
    @DisplayName("★no_arg_커트라인_창구도_두_축이_한_회차_기준시각을_공유한다")
    void uploadCutoffsNoArgSharesOneCapturedAt() {
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        stub(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS, 1);

        PortalRetentionPolicy.UploadCutoffs cutoffs = policy.uploadCutoffs();

        assertThat(cutoffs.ready().orElseThrow().plusDays(7))
                .as("축이 각자 now() 를 뜨면 한 회차 안에서 판정 기준이 갈린다")
                .isEqualTo(cutoffs.failed().orElseThrow().plusDays(1));
        assertThat(cutoffs.uploaded().orElseThrow().plusDays(7))
                .as("★마킹 대기 축도 같은 회차 기준시각에서 파생돼야 한다")
                .isEqualTo(cutoffs.failed().orElseThrow().plusDays(1));
    }

    @Test
    @DisplayName("★보존일수가_0이하거나_설정이_없으면_커트라인_자체를_만들지_않는다_삭제축_fail_closed")
    void cutoffAbsentWhenSettingInvalid() {
        // given: 0(즉시 전량 삭제) · 음수(미래 커트라인) · 설정 부재 — 셋 다 값을 만들면 안 된다.
        stub(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, 0);
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, -1);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));

        PortalRetentionPolicy.UploadCutoffs cutoffs = policy.uploadCutoffs(NOW);

        assertThat(policy.datamartCutoff(NOW)).isEmpty();
        assertThat(cutoffs.uploaded()).as("마킹 대기 축도 fail-closed 다").isEmpty();
        assertThat(cutoffs.ready()).isEmpty();
        assertThat(cutoffs.failed()).isEmpty();
    }

    @Test
    @DisplayName("업로드_한쪽_설정만_없으면_그_축_커트라인만_비고_다른_축은_그대로다")
    void cutoffMissingOneAxisDoesNotDisableTheOther() {
        stub(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, 7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));

        PortalRetentionPolicy.UploadCutoffs cutoffs = policy.uploadCutoffs(NOW);

        assertThat(cutoffs.uploaded()).contains(NOW.minusDays(7));
        assertThat(cutoffs.ready()).contains(NOW.minusDays(7));
        assertThat(cutoffs.failed()).isEmpty();
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
