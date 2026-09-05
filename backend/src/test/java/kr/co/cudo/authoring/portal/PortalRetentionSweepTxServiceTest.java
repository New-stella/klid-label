package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository.RetentionAxis;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService.Axis;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService.ExpiredUpload;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 보존기간 만료 삭제의 <b>트랜잭션 경계 서비스</b> 단위 테스트. @design DFEAT-055, AC-032, AC-036, AC-037
 *
 * <p>여기서 지키는 것은 <b>"설정이 없으면 아무것도 지우지 않는다"</b>와 <b>"READY·FAILED 두 축이 각자의
 * 보존기간으로 독립 판정된다"</b> 둘이다. 실 SQL 의 삭제 조건은 {@code PortalRetentionSweepIT} 가,
 * 파일→DB 순서와 경로 판정 실패 처리는 {@code PortalRetentionSweepJobTest} 가 담당한다.
 *
 * <p>정책({@link PortalRetentionPolicy})은 mock 하지 않고 <b>실물</b>을 쓴다 — 설정 부재가
 * {@code OptionalInt.empty} 로 전달되는 경로 자체가 이 테스트의 검증 대상이라 그 사이에 mock 을
 * 끼우면 정작 지켜야 할 배선이 빠진다.
 */
class PortalRetentionSweepTxServiceTest {

    private LsPortalUserLabelRepository userLabelRepository;
    private PortalUploadAssetRepository assetRepository;
    private SystemConfigService systemConfigService;
    private PortalRetentionSweepTxService txService;

    @BeforeEach
    void setUp() {
        userLabelRepository = mock(LsPortalUserLabelRepository.class);
        assetRepository = mock(PortalUploadAssetRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        txService = new PortalRetentionSweepTxService(
                userLabelRepository, assetRepository,
                new PortalRetentionPolicy(systemConfigService));
    }

    // ------------------------------------------------------------- 설정 부재 = 아무것도 지우지 않는다

    @Test
    @DisplayName("데이터마트_보존기간_설정이_없으면_라벨을_한_건도_지우지_않고_조회조차_하지_않는다")
    void datamartSweepSkippedWhenSettingAbsent() {
        settingAbsent(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS);

        int deleted = txService.sweepDatamartLabels();

        assertThat(deleted).isZero();
        // 상수 폴백이 생기면 여기서 후보 조회·삭제가 일어난다 — 폴백 금지의 실효 지점.
        verifyNoInteractions(userLabelRepository);
    }

    @Test
    @DisplayName("업로드_보존기간_설정이_둘_다_없으면_후보를_한_건도_찾지_않는다")
    void uploadSweepSkippedWhenBothSettingsAbsent() {
        settingAbsent(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS);
        settingAbsent(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS);

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        assertThat(candidates).isEmpty();
        verifyNoInteractions(assetRepository);
    }

    @Test
    @DisplayName("READY_설정만_없으면_READY축만_건너뛰고_FAILED축은_그대로_동작한다")
    void axesAreGatedIndependentlyBySetting() {
        settingAbsent(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
        when(assetRepository.findExpired(eq(RetentionAxis.FAILED), any(LocalDateTime.class)))
                .thenReturn(List.of(7L));

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        assertThat(candidates).extracting(ExpiredUpload::uldSn).containsExactly(7L);
        assertThat(candidates).extracting(ExpiredUpload::axis).containsExactly(Axis.FAILED);
        // 마킹 대기 축은 READY 와 같은 설정을 쓰므로 함께 꺼진다.
        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                .findExpired(eq(RetentionAxis.UPLOADED), any(LocalDateTime.class));
    }

    // --------------------------------------------- 보존일수 0·음수 = 아무것도 지우지 않는다 (fail-closed)

    @Test
    @DisplayName("★데이터마트_보존일수가_0이면_설정부재와_같이_한_건도_지우지_않는다_커트라인이_현재시각이_되지_않는다")
    void datamartSweepSkippedWhenRetentionDaysIsZero() {
        // given: 0 을 그대로 적용하면 커트라인 = now 라 «방금 저장한 라벨까지» 전량이 삭제 대상이 된다.
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(0);

        int deleted = txService.sweepDatamartLabels();

        assertThat(deleted).isZero();
        // 후보 조회조차 하지 않는다 — 가드가 사라지면 여기서 findExpiredLabelGroups 가 호출된다.
        verifyNoInteractions(userLabelRepository);
    }

    @Test
    @DisplayName("★데이터마트_보존일수가_음수여도_한_건도_지우지_않는다_커트라인이_미래가_되지_않는다")
    void datamartSweepSkippedWhenRetentionDaysIsNegative() {
        // given: 음수면 커트라인이 «미래»가 되어 0 보다도 넓게 전량이 걸린다.
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(-1);

        assertThat(txService.sweepDatamartLabels()).isZero();
        verifyNoInteractions(userLabelRepository);
    }

    @Test
    @DisplayName("★업로드_보존일수가_0이하면_그_축만_후보를_찾지_않고_다른_축은_그대로_동작한다")
    void uploadAxisSkippedWhenRetentionDaysIsNotPositive() {
        // given: READY 는 0(비정상), FAILED 는 정상 1일 — 가드는 두 축 공통 헬퍼에 있으나 판정은 축별이다.
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(0);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
        when(assetRepository.findExpired(eq(RetentionAxis.FAILED), any(LocalDateTime.class)))
                .thenReturn(List.of(7L));

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        // READY 축은 조회조차 하지 않고, FAILED 축은 평소대로 후보를 낸다.
        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                .findExpired(eq(RetentionAxis.READY), any(LocalDateTime.class));
        assertThat(candidates).extracting(ExpiredUpload::axis).containsExactly(Axis.FAILED);
    }

    // ------------------------------------------------------------- 데이터마트 축

    @Test
    @DisplayName("만료_그룹마다_조건부_삭제를_호출하고_실제로_지워진_그룹만_센다")
    void datamartSweepCountsOnlyActuallyDeletedGroups() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);
        when(userLabelRepository.findExpiredLabelGroups(any(LocalDateTime.class)))
                .thenReturn(List.of(new Object[]{"alice", 11L}, new Object[]{"bob", 22L}));
        // alice 는 이 노드가 삭제(3행), bob 은 타 노드 선점 또는 재작업으로 만료 해제(0행).
        when(userLabelRepository.deleteExpiredLabelGroup(eq("alice"), eq(11L), any(LocalDateTime.class)))
                .thenReturn(3);
        when(userLabelRepository.deleteExpiredLabelGroup(eq("bob"), eq(22L), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThat(txService.sweepDatamartLabels()).isEqualTo(1);
    }

    @Test
    @DisplayName("데이터마트_커트라인은_설정_일수만큼_과거다")
    void datamartCutoffIsRetentionDaysAgo() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);
        when(userLabelRepository.findExpiredLabelGroups(any(LocalDateTime.class))).thenReturn(List.of());

        txService.sweepDatamartLabels();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(userLabelRepository).findExpiredLabelGroups(cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), LocalDateTime.now()).toHours())
                .isBetween(167L, 169L);
    }

    // ------------------------------------------------------------- 업로드 축

    @Test
    @DisplayName("READY축과_FAILED축이_각자의_보존기간으로_독립_조회된다")
    void readyAndFailedAxesUseTheirOwnCutoff() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
        when(assetRepository.findExpired(eq(RetentionAxis.READY), any(LocalDateTime.class)))
                .thenReturn(List.of(1L));
        when(assetRepository.findExpired(eq(RetentionAxis.FAILED), any(LocalDateTime.class)))
                .thenReturn(List.of(2L));

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        ArgumentCaptor<LocalDateTime> ready = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> failed = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(assetRepository)
                .findExpired(eq(RetentionAxis.READY), ready.capture());
        org.mockito.Mockito.verify(assetRepository)
                .findExpired(eq(RetentionAxis.FAILED), failed.capture());

        // 7일 vs 1일 — 두 축이 같은 커트라인을 쓰면 한쪽은 반드시 틀린 기간으로 지운다.
        assertThat(Duration.between(ready.getValue(), LocalDateTime.now()).toHours())
                .isBetween(167L, 169L);
        assertThat(Duration.between(failed.getValue(), LocalDateTime.now()).toHours())
                .isBetween(23L, 25L);
        assertThat(candidates).extracting(ExpiredUpload::axis)
                .containsExactly(Axis.READY, Axis.FAILED);
        // 후보가 스캔 커트라인을 그대로 실어 삭제문이 같은 기준으로 재판정한다.
        assertThat(candidates.get(0).cutoff()).isEqualTo(ready.getValue());
        assertThat(candidates.get(1).cutoff()).isEqualTo(failed.getValue());
    }

    /**
     * ★ 두 축의 커트라인이 <b>한 회차 기준시각 하나</b>에서 나오는지 고정한다. 축마다
     * {@code now()} 를 다시 뜨면 어긋남이 밀리초라 눈으로 보이지 않으므로, 각자의 보존일수만큼
     * 되돌려 한 시각으로 모이는지로 잡는다. 단일 창구({@code PortalRetentionPolicy.uploadCutoffs()})
     * 를 우회해 여기서 커트라인을 다시 유도하면 이 단언이 깨진다.
     */
    @Test
    @DisplayName("★업로드_두_축의_커트라인이_한_회차_기준시각에서_나온다_회차_안에서_기준이_갈리지_않는다")
    void uploadAxesShareOneRoundCapturedAt() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
        when(assetRepository.findExpired(any(RetentionAxis.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        txService.findExpiredUploads();

        ArgumentCaptor<LocalDateTime> uploaded = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ready = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> failed = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(assetRepository)
                .findExpired(eq(RetentionAxis.UPLOADED), uploaded.capture());
        org.mockito.Mockito.verify(assetRepository)
                .findExpired(eq(RetentionAxis.READY), ready.capture());
        org.mockito.Mockito.verify(assetRepository)
                .findExpired(eq(RetentionAxis.FAILED), failed.capture());

        assertThat(ready.getValue().plusDays(7))
                .as("축이 각자 now() 를 뜨면 한 회차 안에서 판정 기준이 여럿이 된다")
                .isEqualTo(failed.getValue().plusDays(1));
        assertThat(uploaded.getValue().plusDays(7))
                .as("★마킹 대기 축이 자기 now() 를 뜨면 여기서 깨진다")
                .isEqualTo(failed.getValue().plusDays(1));
    }

    // ------------------------------------------------------- 마킹 대기 축 (AC-1070, 2026-09-05 확정)

    /**
     * ★ 이 축이 없으면 마킹하지 않은 자산은 어느 스윕에도 걸리지 않아 <b>파일째 영구히</b> 남는다 —
     * 방치 전이의 출발 상태는 {@code PROCESSING} 하나라 마킹 대기를 회수하지 않기 때문이다.
     * ⚠ 그렇다고 이것을 방치 판정과 혼동하지 말 것 — 분 단위 실패 마감이 아니라 일 단위 정상 만료다.
     */
    @Test
    @DisplayName("★마킹_대기_축이_후보에_포함된다_그_축이_빠지면_자산이_영구히_남는다")
    void markingPendingAxisIsScanned() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
        when(assetRepository.findExpired(eq(RetentionAxis.UPLOADED), any(LocalDateTime.class)))
                .thenReturn(List.of(5L));

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        assertThat(candidates).extracting(ExpiredUpload::uldSn).containsExactly(5L);
        assertThat(candidates).extracting(ExpiredUpload::axis).containsExactly(Axis.UPLOADED);
        // 후보가 스캔 커트라인을 그대로 실어야 삭제문이 같은 기준으로 재판정한다.
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(assetRepository)
                .findExpired(eq(RetentionAxis.UPLOADED), cutoff.capture());
        assertThat(candidates.get(0).cutoff()).isEqualTo(cutoff.getValue());
    }

    @Test
    @DisplayName("★마킹_대기_축은_준비완료_설정을_공유해_그_설정이_없으면_함께_건너뛴다")
    void markingPendingAxisSharesReadySetting() {
        // given: 공유 설정만 없다 — 실패 축은 자기 설정이 있어 계속 동작해야 한다.
        settingAbsent(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
        when(assetRepository.findExpired(eq(RetentionAxis.FAILED), any(LocalDateTime.class)))
                .thenReturn(List.of(7L));

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                .findExpired(eq(RetentionAxis.UPLOADED), any(LocalDateTime.class));
        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                .findExpired(eq(RetentionAxis.READY), any(LocalDateTime.class));
        assertThat(candidates).extracting(ExpiredUpload::axis).containsExactly(Axis.FAILED);
    }

    @Test
    @DisplayName("★마킹_대기_보존일수가_0이하면_그_축도_후보를_찾지_않는다_fail_closed")
    void markingPendingAxisSkippedWhenRetentionDaysIsNotPositive() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(0);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);

        txService.findExpiredUploads();

        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                .findExpired(eq(RetentionAxis.UPLOADED), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("후보는_프레임_파일과_원본_파일_경로를_중복없이_모은다")
    void candidateCollectsFrameAndSourceFilePaths() {
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(7);
        settingAbsent(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS);
        when(assetRepository.findExpired(eq(RetentionAxis.READY), any(LocalDateTime.class)))
                .thenReturn(List.of(9L));
        // 경로 수집·중복 제거는 리포지토리가 소유한다(프레임 + 원본을 한 통로에서 모은다).
        when(assetRepository.findFilePaths(9L)).thenReturn(
                List.of("/store/frames/0.jpg", "/store/frames/1.jpg", "/store/v.mp4"));

        List<ExpiredUpload> candidates = txService.findExpiredUploads();

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).filePaths())
                .containsExactly("/store/frames/0.jpg", "/store/frames/1.jpg", "/store/v.mp4");
    }

    @Test
    @DisplayName("축에_맞는_조건부_삭제로_위임한다 — 축이_곧_삭제_조건이다")
    void deleteDelegatesToAxisSpecificQuery() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 10, 0, 0);
        when(assetRepository.deleteExpired(RetentionAxis.READY, 1L, cutoff)).thenReturn(1);
        when(assetRepository.deleteExpired(RetentionAxis.FAILED, 2L, cutoff)).thenReturn(1);
        when(assetRepository.deleteExpired(RetentionAxis.UPLOADED, 3L, cutoff)).thenReturn(1);

        assertThat(txService.deleteExpiredUpload(
                new ExpiredUpload(1L, Axis.READY, cutoff, List.of()))).isEqualTo(1);
        assertThat(txService.deleteExpiredUpload(
                new ExpiredUpload(2L, Axis.FAILED, cutoff, List.of()))).isEqualTo(1);
        // ★ 매핑이 「나머지는 전부 FAILED」로 뭉개지면 마킹 대기 자산을 FAILED 술어로 지우려 해
        //   0행이 되어 파일만 사라진다. 축 하나가 늘 때 가장 조용히 깨지는 자리다.
        assertThat(txService.deleteExpiredUpload(
                new ExpiredUpload(3L, Axis.UPLOADED, cutoff, List.of()))).isEqualTo(1);
    }

    // ------------------------------------------------------------- 헬퍼

    /** 설정 행이 없을 때의 실제 동작 — {@code SystemConfigService.getInt} 는 예외를 던진다. */
    private void settingAbsent(String key) {
        when(systemConfigService.getInt(key))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 없음"));
    }

}
