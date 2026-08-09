package kr.co.cudo.authoring.controlnotify.debounce;

import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import kr.co.cudo.authoring.controlnotify.service.FrameChangeSet;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 7a-2 — 재검토 표시({@code LS_RAW_DATA_STATUS.REVLT_YN='Y'}) 동안 디바운스 flush 가
 * <b>보류</b>되는지, 표시 해제 후 축적분이 그대로 나가는지를 실 DB(PostgreSQL Testcontainer)로 검증한다.
 *
 * <p>이 보류 판정은 {@code LsMonNotiAcmlRepository#findFlushableAnchors} 의 native SQL
 * {@code NOT EXISTS} 서브쿼리로 구현돼 있어 {@link FakeControlNotifyDebounceStore}(순수 인메모리)로는
 * 재현할 수 없다 — 반드시 실 저장소({@link JpaControlNotifyDebounceStore})와 실 {@code LS_RAW_DATA_STATUS}
 * 행이 함께 있어야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ControlNotifyDebounceRecheckHoldIT {

    /** 윈도우 만료를 기다리지 않도록 windowSec=0 (축적 즉시 flush 대상). */
    private static final long IMMEDIATE_WINDOW_SEC = 0L;
    private static final long LEASE_SEC = 300L;

    @Autowired
    private ControlNotifyDebounceStore store;

    @Autowired
    private LsMonNotiAcmlRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> usedRawSns = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        usedRawSns.forEach(rawSn -> repository.findByRawSn(rawSn).forEach(repository::delete));
        usedRawSns.forEach(rawSn -> jdbcTemplate.update(
                "DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn));
        usedRawSns.forEach(rawSn -> RawVideoFixture.deleteRaws(jdbcTemplate, rawSn));
        usedRawSns.clear();
    }

    private Long nextRawSn() {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        usedRawSns.add(rawSn);
        return rawSn;
    }

    /** 검수 상태 행을 지정 재검토표시로 시드한다(APPROVED 고정 — 이 테스트가 다루는 상황은 승인 후 수정). */
    private void seedReviewStatus(Long rawSn, String revltYn) {
        jdbcTemplate.update("""
                INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER, REVLT_YN)
                VALUES (?, 'APPROVED', CURRENT_TIMESTAMP, 0, ?)
                """, rawSn, revltYn);
    }

    private ControlNotifyDebouncer node(ControlNotifyService notifyService, AsyncDatasetExportRunner runner) {
        return new ControlNotifyDebouncer(store, notifyService, IMMEDIATE_WINDOW_SEC, null, runner,
                false, 10_000L, LEASE_SEC, 100, true);
    }

    private long rowCountOf(Long rawSn) {
        return repository.findByRawSn(rawSn).size();
    }

    @Test
    @DisplayName("재검토_표시가_선_영상은_윈도우가_만료돼도_flush되지_않고_축적분이_보존된다")
    void heldWindowIsNotFlushedWhileRecheckFlagIsSet() {
        // given — REVLT_YN='Y' 인 영상에 변경이 축적된다.
        Long rawSn = nextRawSn();
        seedReviewStatus(rawSn, "Y");
        ControlNotifyService notifyService = mock(ControlNotifyService.class);
        AsyncDatasetExportRunner runner = mock(AsyncDatasetExportRunner.class);
        ControlNotifyDebouncer node = node(notifyService, runner);
        node.accumulate(new TaskModifiedEvent(rawSn, 1L, ChangeType.LABEL_UPDATED, 10L, true, true));

        // when — 윈도우는 즉시 만료 대상(windowSec=0)이므로 flush 를 시도한다.
        node.flushExpiredWindows();

        // then — 보류 대상이라 아무것도 전송되지 않고, 축적분도 사라지지 않는다(유실 아님).
        verify(notifyService, never()).sendModified(any(), anyList(), anySet(), anyBoolean());
        verify(runner, never()).runReExportThenNotify(any(), anyBoolean(), any());
        assertThat(rowCountOf(rawSn)).as("보류된 윈도우는 삭제되지 않고 그대로 남는다").isEqualTo(1);
    }

    @Test
    @DisplayName("재검토_표시가_해제되면_보류됐던_축적분이_다음_flush에서_그대로_나간다")
    void releasedWindowFlushesAccumulatedChangesAfterFlagCleared() {
        // given — REVLT_YN='Y' 상태에서 변경이 축적되고, 먼저 한 번 flush 를 시도해 보류를 확인한다.
        Long rawSn = nextRawSn();
        seedReviewStatus(rawSn, "Y");
        ControlNotifyService notifyService = mock(ControlNotifyService.class);
        AsyncDatasetExportRunner runner = mock(AsyncDatasetExportRunner.class);
        ControlNotifyDebouncer node = node(notifyService, runner);
        node.accumulate(new TaskModifiedEvent(rawSn, 5L, ChangeType.LABEL_UPDATED, 10L, true, true));
        node.flushExpiredWindows();
        verify(notifyService, never()).sendModified(any(), anyList(), anySet(), anyBoolean());

        // when — 표시가 해제된다(재승인) — 새 윈도우 없이 같은 축적 윈도우가 그대로 남아 있는 상태.
        jdbcTemplate.update("UPDATE LS_RAW_DATA_STATUS SET REVLT_YN = 'N' WHERE RAW_DATA_ID = ?", rawSn);
        node.flushExpiredWindows();

        // then — 보류 해제 즉시(REG_DT 는 이미 오래전에 만료됐으므로) 축적 전체가 나간다.
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(runner).runReExportThenNotify(eq(rawSn), eq(true), callback.capture());
        callback.getValue().run();
        ArgumentCaptor<List<FrameChangeSet>> frames = frameChangeCaptor();
        verify(notifyService).sendModified(eq(rawSn), frames.capture(), org.mockito.ArgumentMatchers.<Set<String>>any(), eq(true));
        assertThat(frames.getValue()).extracting(FrameChangeSet::srcSn).containsExactly(5L);
        assertThat(rowCountOf(rawSn)).isZero();
    }

    @Test
    @DisplayName("후보_선정_이후_클레임_직전에_재검토_표시가_서면_클레임이_실패한다")
    void claimFailsWhenRecheckFlagIsSetAfterCandidateSelection() {
        // given — 표시 없이(REVLT_YN='N') 윈도우가 열려 후보 조회에 포함된다(만료 즉시 대상, windowSec=0).
        Long rawSn = nextRawSn();
        seedReviewStatus(rawSn, "N");
        ControlNotifyDebouncer node = node(mock(ControlNotifyService.class), mock(AsyncDatasetExportRunner.class));
        node.accumulate(new TaskModifiedEvent(rawSn, 7L, ChangeType.LABEL_UPDATED, 10L, true, true));

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<Long> candidates = store.findFlushableIds(now, now, 100);
        assertThat(candidates).as("표시가 없던 시점의 후보 조회에는 포함된다").isNotEmpty();
        Long acmlSn = candidates.stream()
                .filter(id -> repository.findById(id).map(r -> rawSn.equals(r.getRawSn())).orElse(false))
                .findFirst()
                .orElseThrow();

        // when — 후보 선정과 클레임 사이에 검수자가 승인 후 수정을 발견해 재검토 표시가 선다(경합 재현).
        jdbcTemplate.update("UPDATE LS_RAW_DATA_STATUS SET REVLT_YN = 'Y' WHERE RAW_DATA_ID = ?", rawSn);

        // then — 클레임 자체가 실패한다(영향행수 0) — 검수자가 아직 보지 않은 내용이 flush 되지 않는다.
        java.util.Optional<DebounceWindow> claimed = store.claim(acmlSn, now, now);
        assertThat(claimed).as("클레임이 실패해야 한다(구멍1 — findFlushableAnchors 만으로는 막지 못하는 경합)").isEmpty();
        assertThat(rowCountOf(rawSn)).as("클레임 실패 행은 PENDING 그대로 남아 다음 tick 이 재시도한다").isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<FrameChangeSet>> frameChangeCaptor() {
        return ArgumentCaptor.forClass((Class<List<FrameChangeSet>>) (Class<?>) List.class);
    }
}
