package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.event.DatasetExportCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AsyncDatasetExportRunner} 단위 테스트 — 위임(forceRegenerate 관통) + @Async 예외 삼킴 +
 * C-2(Phase 5C) export → 통지 순서 보장 검증.
 */
class AsyncDatasetExportRunnerTest {

    private DatasetExportService exportService;
    private ApplicationEventPublisher eventPublisher;
    private AsyncDatasetExportRunner runner;

    @BeforeEach
    void setUp() {
        exportService = mock(DatasetExportService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        runner = new AsyncDatasetExportRunner(exportService, eventPublisher);
    }

    @Test
    @DisplayName("runAsync가_forceTrue를_export로_관통위임한다 (재동결/회수 경로)")
    void delegatesToExportWithForceTrue() {
        runner.runAsync(42L, true);

        verify(exportService).export(42L, true);
    }

    @Test
    @DisplayName("runAsync가_forceFalse를_export로_관통위임한다 (재동결 경로)")
    void delegatesToExportWithForceFalse() {
        runner.runAsync(43L, false);

        verify(exportService).export(43L, false);
    }

    @Test
    @DisplayName("Async_러너_예외는_삼켜진다 — 호출부로 전파 안 됨")
    void swallowsExportException() {
        doThrow(new RuntimeException("boom")).when(exportService).export(eq(7L), anyBoolean());

        // @Async 이므로 산출 예외가 호출부(승인 이후 스케줄)로 전파되지 않는다.
        assertThatCode(() -> runner.runAsync(7L, true)).doesNotThrowAnyException();

        verify(exportService).export(7L, true);
    }

    @Test
    @DisplayName("rawSn이_null이면_무처리")
    void nullRawSnNoOp() {
        runner.runAsync(null, true);

        verify(exportService, never()).export(anyLong(), anyBoolean());
    }

    // ── C-2 — 승인 통지가 export SUCCEEDED 이후에 발송된다 ────────────────────────────────

    @Test
    @DisplayName("통지가_export_SUCCEEDED_이후에_발송된다 — runApprovalAsync 는 export 후 완료이벤트 발행")
    void runApprovalAsync_publishesCompletedAfterExport() {
        runner.runApprovalAsync(55L);

        // export(55L, true) 가 먼저, 그 다음 DatasetExportCompletedEvent 발행(통지 트리거)이어야 한다.
        InOrder order = inOrder(exportService, eventPublisher);
        order.verify(exportService).export(55L, true);
        order.verify(eventPublisher).publishEvent(new DatasetExportCompletedEvent(55L));
    }

    @Test
    @DisplayName("승인_export_실패시_완료이벤트를_발행하지_않는다 — 통지 보류 (HIGH-D)")
    void runApprovalAsync_doesNotPublishCompletedWhenExportFails() {
        // given — export 가 예외로 실패한다(SUCCEEDED 아님).
        doThrow(new RuntimeException("io")).when(exportService).export(eq(55L), anyBoolean());

        // when — @Async 이므로 예외는 삼켜진다.
        assertThatCode(() -> runner.runApprovalAsync(55L)).doesNotThrowAnyException();

        // then — HIGH-D: 실패했으므로 완료 이벤트(통지 트리거)를 발행하지 않는다. 관제가 구/없는 버전
        //   폴더를 픽업하지 않도록 통지를 보류한다. 재산출은 회수기가 성공 후 완료 이벤트로 재개한다.
        verify(eventPublisher, never()).publishEvent(any(DatasetExportCompletedEvent.class));
    }

    @Test
    @DisplayName("runReExportThenNotify_는_export를_먼저_마친_뒤_통지콜백을_실행한다 (C-2 수정 경로)")
    void runReExportThenNotify_exportBeforeCallback() {
        Runnable notify = mock(Runnable.class);

        runner.runReExportThenNotify(66L, true, notify);

        InOrder order = inOrder(exportService, notify);
        order.verify(exportService).export(66L, true);
        order.verify(notify).run();
    }

    @Test
    @DisplayName("재산출_실패시_통지콜백을_실행하지_않는다 — 통지 보류 (HIGH-D)")
    void runReExportThenNotify_doesNotRunCallbackWhenExportFails() {
        // given — 재산출이 예외로 실패한다.
        doThrow(new RuntimeException("io")).when(exportService).export(eq(66L), anyBoolean());
        Runnable notify = mock(Runnable.class);

        // when
        assertThatCode(() -> runner.runReExportThenNotify(66L, true, notify)).doesNotThrowAnyException();

        // then — HIGH-D: export 가 성공하지 못했으므로 통지 콜백을 실행하지 않는다(관제 구 버전 픽업 방지).
        verify(notify, never()).run();
    }

    @Test
    @DisplayName("완료이벤트_리스너_예외는_격리 안 함 — 콜백 예외는 삼켜 러너를 지킨다")
    void runReExportThenNotify_swallowsCallbackException() {
        Runnable notify = mock(Runnable.class);
        doThrow(new RuntimeException("notify boom")).when(notify).run();

        assertThatCode(() -> runner.runReExportThenNotify(66L, true, notify)).doesNotThrowAnyException();

        verify(exportService).export(66L, true);
        verify(notify).run();
    }
}
