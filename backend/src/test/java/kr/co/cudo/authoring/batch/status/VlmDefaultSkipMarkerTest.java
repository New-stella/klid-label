package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 전체 건너뛰기 설정이 켜져 있을 때 <b>시계열 묶음에만</b> 자동 표식을 세우는 판정.
 * [@design ADR-050]
 *
 * <h3>고정하는 것</h3>
 * <ul>
 *   <li>표식 축은 사람이 누른 것과 <b>같다</b>({@link ManualStageSkip#ERR_CD_SKIPPED}) — 재개 판정의
 *       키라 새 코드값을 만들면 과거 보류분이 영구 고착된다.</li>
 *   <li><b>사람이 남긴 표식을 덮지 않는다</b> — 특히 해제 표식을 덮으면 사람이 되살린 영상이 조용히
 *       다시 건너뛰어진다.</li>
 *   <li>대상은 시계열 묶음 하나뿐 — 오토라벨은 이 스위치의 대상이 아니다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmDefaultSkipMarkerTest {

    private static final Long RAW_SN = 700L;

    @Mock
    private BatchStatusService statusService;

    @Mock
    private SystemConfigService systemConfigService;

    private VlmDefaultSkipMarker marker;

    @BeforeEach
    void setUp() {
        marker = new VlmDefaultSkipMarker(statusService, systemConfigService);
        when(statusService.latestManualSkipMarker(anyLong(), any())).thenReturn(Optional.empty());
        switchValue(null);
        reasonValue(null);
    }

    private void switchValue(String value) {
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT))
                .thenReturn(Optional.ofNullable(value));
    }

    private void reasonValue(String value) {
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON))
                .thenReturn(Optional.ofNullable(value));
    }

    private String capturedReason() {
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(statusService).recordManualStageSkipInNewTx(
                eq(RAW_SN), eq(BatchStageBundle.VLM), reason.capture(), anyString());
        return reason.getValue();
    }

    @Test
    @DisplayName("전체_건너뛰기가_켜져_있고_표식이_없으면_시계열_묶음에_건너뜀_표식을_남긴다")
    void marksVlmBundleWhenSwitchIsOn() {
        // given
        switchValue("true");
        reasonValue("외부 시계열 벤더 미연동 구간");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then
        verify(statusService).recordManualStageSkipInNewTx(
                eq(RAW_SN), eq(BatchStageBundle.VLM), anyString(), anyString());
    }

    @Test
    @DisplayName("표식_사유에_설정_사유가_함께_실린다")
    void reasonCarriesConfiguredText() {
        // given
        switchValue("true");
        reasonValue("외부 시계열 벤더 미연동 구간");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then — 설정에서 비롯했다는 사실 + 설정 사유 원문이 함께 남는다.
        assertThat(capturedReason())
                .startsWith(ManualStageSkip.DEFAULT_SKIP_REASON_PREFIX)
                .contains("외부 시계열 벤더 미연동 구간");
    }

    @Test
    @DisplayName("사유의_개행_제어문자는_제거된다_로그_인젝션_차단")
    void reasonIsSanitized() {
        // given — 사람이 자유 입력하는 값이므로 개행이 들어올 수 있다(CWE-117).
        switchValue("true");
        reasonValue("벤더 점검\n2026-01-01 INFO 위조 로그");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then
        assertThat(capturedReason()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("표식의_ERR_CD_는_기존_수동_스킵과_같은_값이다_재개_판정_키_보존")
    void usesExistingManualSkipErrCd() {
        // given
        switchValue("true");
        reasonValue("사유");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then — 새 코드값을 만들지 않는다. 기록은 기존 수동 스킵 기록기를 그대로 태운다.
        verify(statusService).recordManualStageSkipInNewTx(
                eq(RAW_SN), eq(BatchStageBundle.VLM), anyString(), anyString());
        verify(statusService, never()).recordManualStageSkipCleared(
                anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("★사람이_남긴_해제_표식은_자동_표식이_덮지_않는다")
    void doesNotOverrideHumanCleared() {
        // given — 사람이 건너뛰기를 해제해 되살린 영상.
        switchValue("true");
        reasonValue("사유");
        LsBatchProcLog cleared = markerRow(ManualStageSkip.ERR_CD_CLEARED);
        when(statusService.latestManualSkipMarker(RAW_SN, BatchStageBundle.VLM))
                .thenReturn(Optional.of(cleared));

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then — 덮으면 사람이 되살린 영상이 조용히 다시 건너뛰어진다.
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("★이미_건너뜀_표식이_있으면_다시_남기지_않는다_멱등")
    void isIdempotentWhenAlreadySkipped() {
        // given
        switchValue("true");
        reasonValue("사유");
        LsBatchProcLog skipped = markerRow(ManualStageSkip.ERR_CD_SKIPPED);
        when(statusService.latestManualSkipMarker(RAW_SN, BatchStageBundle.VLM))
                .thenReturn(Optional.of(skipped));

        // when — 같은 영상에 배치가 두 번 진입해도 표식이 중복 적재되지 않는다.
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("전체_건너뛰기가_꺼져_있으면_아무것도_하지_않는다_동작보존")
    void doesNothingWhenSwitchIsOff() {
        // given
        switchValue("false");
        reasonValue("사유");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("설정_행이_아예_없으면_아무것도_하지_않는다_기본은_꺼짐")
    void doesNothingWhenConfigRowAbsent() {
        // given — 시드하지 않는 키라 «행 없음»이 정상 상태다.
        switchValue(null);
        reasonValue(null);

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.VLM);

        // then
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("오토라벨_묶음_단계는_이_스위치의_대상이_아니다")
    void autolabelBundleIsNotTargeted() {
        // given
        switchValue("true");
        reasonValue("사유");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.YOLO);
        marker.applyBeforeStage(RAW_SN, BatchStage.SAM2);
        marker.applyBeforeStage(RAW_SN, BatchStage.INTERPOLATE);

        // then
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("묶음에_속하지_않는_단계와_null_입력은_무시된다")
    void ignoresNonBundleStagesAndNulls() {
        // given
        switchValue("true");
        reasonValue("사유");

        // when
        marker.applyBeforeStage(RAW_SN, BatchStage.FRAME_EXTRACT);
        marker.applyBeforeStage(RAW_SN, null);
        marker.applyBeforeStage(null, BatchStage.VLM);

        // then
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    /**
     * ★★표식 적재는 <b>독립 트랜잭션</b>이어야 한다 — readOnly 경계에서 서지 못하는 INSERT 차단.
     *
     * <p>이 컴포넌트는 위탁 직전 게이트에서도 불리는데 그 자리는 {@code VlmTimeseriesStep.run} 의
     * {@code readOnly=true} 트랜잭션 안이다. REQUIRED 로 되돌리면 그 INSERT 가 <b>read-only 커넥션에서
     * 거부</b>돼({@code cannot execute INSERT in a read-only transaction}) 표식이 서지 못하고, 직후 게이트
     * 조회가 그 행을 찾지 못해 스위치가 켜져 있는데도 외부 벤더 호출이 나간다. 목에는 트랜잭션이 없어
     * 목 기반 테스트로는 이 축이 드러나지 않으므로 전파 속성 자체를 기계로 고정한다(런타임 가시성의
     * 실 DB 실증은 {@link VlmDefaultSkipMarkerVisibilityIT}).
     *
     * <p>구 서술 「Hibernate 가 {@code FlushMode.MANUAL} 이어서 flush 되지 않고 조용히 사라진다」는
     * <b>폐기</b> — 기전이 다르고 결론(REQUIRES_NEW 필요)만 같다. 상세·근거는
     * {@link BatchStatusService#recordManualStageSkipInNewTx} javadoc.
     */
    @Test
    @DisplayName("★★표식_적재는_REQUIRES_NEW다_readOnly_경계에서_유실되지_않게")
    void markerWriteRunsInItsOwnTransaction() throws NoSuchMethodException {
        java.lang.reflect.Method m = BatchStatusService.class.getMethod(
                "recordManualStageSkipInNewTx", Long.class,
                kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle.class,
                String.class, String.class);
        org.springframework.transaction.annotation.Transactional tx =
                m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        org.assertj.core.api.Assertions.assertThat(tx)
                .as("표식 적재 메서드에 @Transactional 이 없다 — readOnly 경계 안에서 표식이 서지 못한다.")
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(tx.propagation())
                .as("표식 적재는 REQUIRES_NEW 여야 한다. REQUIRED 로 되돌리면 VlmTimeseriesStep.run 의"
                        + " readOnly 트랜잭션에 참여해 read-only 커넥션이 그 INSERT 를 거부한다.")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    private static LsBatchProcLog markerRow(String errCd) {
        LsBatchProcLog row = mock(LsBatchProcLog.class);
        when(row.getErrorCd()).thenReturn(errCd);
        return row;
    }
}
