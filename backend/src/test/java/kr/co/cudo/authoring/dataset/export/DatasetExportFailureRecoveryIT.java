package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * D-ISSUE-04(b) — 실패 export 회수 경로 통합 테스트 (실 DB: Testcontainers PostgreSQL).
 *
 * <p>승인 후 export 는 AFTER_COMMIT {@code @Async} 라 실패해도 승인이 롤백되지 않는데, 지금까지는
 * <b>재시도 경로가 없어</b> FAILED 만 남고 데이터마트에 {@code EXPORT_PATH_NM} NULL 행이 영구히 남았다
 * (실측 rawSn=13 — 프레임 11·라벨 35 인데 export 실패. 승인 게이트로는 잡히지 않는 유형).
 *
 * <h3>DEV_FIX(H7①/H7③/H8) — 무엇을 새로 증명하는가</h3>
 * <ul>
 *   <li><b>시도 이력이 반드시 남는다</b>: 재시도가 FAILED 행을 만들지 않는 유형(NO_INPUT early return ·
 *       버전 채번 소진 · {@code @Async} 예외 삼킴)에서도 {@code RTY_NMTM} 이 올라 상한이 실제로 걸린다.
 *       구 테스트는 FAILED 행 3건을 손으로 심어 "행 수 = 시도 횟수"라는 <b>틀린 전제</b>를 고정했다.</li>
 *   <li><b>회수 → 실제 재산출 → 이력 증가</b>가 이어진다: 러너를 완전히 무력화하지 않고 실제
 *       {@code DatasetExportService} 를 태워, 산출이 아무 행도 남기지 않는 경우에도 카운트가 오르는 것을 본다.</li>
 *   <li><b>중복 산출 차단</b>: Quartz {@code isClustered=false} 상태에서 같은 tick 을 두 번(=2노드) 돌려도
 *       한 번만 트리거된다(DB 클레임 보장).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.dataset-export.failure-recovery.max-attempts=3",
        "authoring.dataset-export.failure-recovery.batch-size=50",
        // 유예는 안전 폴백(10분)을 그대로 쓰고, 테스트는 REG_DT/RTY_DT 를 과거로 밀어 통과시킨다.
        "authoring.dataset-export.failure-recovery.retry-delay-minutes=10"
})
class DatasetExportFailureRecoveryIT {

    @Autowired private DatasetExportFailureRecoverer recoverer;
    @Autowired private LsDatasetExportRepository exportRepository;
    @Autowired private VideoRepository rawRepository;

    /**
     * 러너는 스텁이되, 필요한 테스트에서는 <b>실제 산출 서비스를 동기 호출</b>하도록 위임한다
     * ({@code @Async} 스레드 비결정성 제거). 트리거 여부만 세는 게 아니라 "재산출이 실제로 돌았을 때
     * 무슨 흔적이 남는가"까지 본다.
     */
    @MockBean private AsyncDatasetExportRunner runner;
    @Autowired private DatasetExportService exportService;

    private final TransactionTemplate tx;

    DatasetExportFailureRecoveryIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    private long seedVideo() {
        return tx.execute(s -> rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-EXP-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn());
    }

    /** 지정 상태의 export 행을 만들고 REG_DT 를 과거로 밀어 재시도 유예를 통과시킨다. */
    private void seedExport(long rawSn, int version, String status) {
        tx.executeWithoutResult(s -> {
            LsDatasetExport e = LsDatasetExport.create(rawSn, version, "/labeling/" + rawSn + "/v" + version);
            if (LsDatasetExport.STATUS_SUCCEEDED.equals(status)) {
                e.markSucceeded(3);
            } else if (LsDatasetExport.STATUS_FAILED.equals(status)) {
                e.markFailed();
            }
            setField(e, "regDt", LocalDateTime.now().minusHours(1));
            exportRepository.save(e);
        });
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field f = LsDatasetExport.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 회수 잡이 남긴 시도 이력(누적 RTY_NMTM) — 상한 판정의 근거값. */
    private int attemptsOf(long rawSn) {
        return exportRepository.findByDataRawSn(rawSn).stream()
                .mapToInt(LsDatasetExport::getRtyNmtm)
                .sum();
    }

    /** 다음 tick 이 오도록 클레임/생성 타임스탬프를 과거로 민다(유예 경과 시뮬레이션). */
    private void ageClaims(long rawSn) {
        tx.executeWithoutResult(s -> exportRepository.findByDataRawSn(rawSn).forEach(e -> {
            setField(e, "rtyDt", LocalDateTime.now().minusHours(1));
            setField(e, "regDt", LocalDateTime.now().minusHours(1));
            exportRepository.save(e);
        }));
    }

    @Test
    @DisplayName("export_실패시_회수경로에_등록되어_재시도_대상이_됨")
    void failedExportIsRetried() {
        long failed = seedVideo();
        seedExport(failed, 1, LsDatasetExport.STATUS_FAILED);

        // 회수 대상 선정 — 최신 export 가 FAILED 인 영상.
        List<Object[]> targets = exportRepository.findRetryableFailedAnchors(LocalDateTime.now(), 3, 50);
        assertThat(targets).anyMatch(row -> ((Number) row[1]).longValue() == failed);

        // 회수 실행 — HIGH-D(Phase 5C): 승인 러너(runApprovalAsync)로 재산출한다(force=true + 성공 시 완료
        //   이벤트 발행 → 통지 재개).
        recoverer.recover();
        verify(runner).runApprovalAsync(eq(failed));
    }

    @Test
    @DisplayName("이미_성공한_export는_회수대상이_아니다")
    void succeededExportNotRetried() {
        long recovered = seedVideo();
        seedExport(recovered, 1, LsDatasetExport.STATUS_FAILED);
        seedExport(recovered, 2, LsDatasetExport.STATUS_SUCCEEDED);

        List<Object[]> targets = exportRepository.findRetryableFailedAnchors(LocalDateTime.now(), 3, 50);
        assertThat(targets).noneMatch(row -> ((Number) row[1]).longValue() == recovered);

        recoverer.recover();
        verify(runner, never()).runApprovalAsync(eq(recovered));
    }

    @Test
    @DisplayName("재산출이_FAILED행을_남기지_않는_유형도_시도이력이_남아_최대횟수에서_멈춘다")
    void attemptsAreRecordedEvenWhenRetryLeavesNoRow() {
        // given — 프레임/메타가 전혀 없는 영상(=NO_INPUT). 재산출은 export 레코드를 INSERT 하지 않고
        //   early return 하므로, 구 구현("FAILED 행 수 = 시도 횟수")에서는 카운트가 1 로 고정된 채
        //   15분마다 무한 재시도됐다.
        long rawSn = seedVideo();
        seedExport(rawSn, 1, LsDatasetExport.STATUS_FAILED);
        long rowsBefore = exportRepository.countByDataRawSn(rawSn);

        // 러너 → 실제 산출 서비스 동기 위임(재시도가 실제로 돌게 한다). runApprovalAsync 는 force=true 고정.
        doAnswer(inv -> {
            exportService.export(inv.getArgument(0), true);
            return null;
        }).when(runner).runApprovalAsync(anyLong());

        // when — 유예를 넘길 때마다 회수가 도는 상황을 4 tick 으로 재현.
        int triggered = 0;
        for (int tick = 0; tick < 4; tick++) {
            triggered += recoverer.recover();
            ageClaims(rawSn);
        }

        // then — ① 재산출은 실제로 아무 행도 남기지 않았다(NO_INPUT 유형 재현 확인).
        assertThat(exportRepository.countByDataRawSn(rawSn)).isEqualTo(rowsBefore);
        // ② 그럼에도 시도 이력이 남아 max-attempts(3) 에서 정확히 멈춘다(무한 재시도 제거).
        assertThat(triggered).isEqualTo(3);
        assertThat(attemptsOf(rawSn)).isEqualTo(3);
        // ③ 상한 소진 후에는 후보 선정에서도 빠진다.
        assertThat(exportRepository.findRetryableFailedAnchors(LocalDateTime.now(), 3, 50))
                .noneMatch(row -> ((Number) row[1]).longValue() == rawSn);
        verify(runner, times(3)).runApprovalAsync(eq(rawSn));
    }

    @Test
    @DisplayName("Quartz_클러스터링이_꺼져있어도_같은_tick_중복_실행시_한_번만_재산출된다")
    void concurrentTicksClaimOnlyOnce() {
        // isClustered=false 라 2노드가 같은 tick 을 각자 실행한다. 같은 프로세스에서 recover() 를 두 번
        // 부르는 것으로 그 상황을 재현한다 — DB 클레임이 없으면 2회 트리거되어 버전 폴더가 중복 산출된다.
        long rawSn = seedVideo();
        seedExport(rawSn, 1, LsDatasetExport.STATUS_FAILED);

        int first = recoverer.recover();
        int second = recoverer.recover();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        verify(runner, times(1)).runApprovalAsync(eq(rawSn));
        assertThat(attemptsOf(rawSn)).isEqualTo(1);
    }
}
