package kr.co.cudo.authoring.video.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.UnexpectedRollbackException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 인입 픽업 스캔 서비스 단위 테스트 — Phase 3(적재 소스 교체).
 *
 * <p>{@link TrainingVideoIngestService#scanAndIngest()} 는 <b>미처리 인입 행</b>
 * ({@code LS_DATA_INGEST.PROC_STTS_CD='PENDING'}) 조회(READ)만 담당하고, 행별 적재는
 * {@link TrainingVideoIngestTx#ingestOne}(REQUIRES_NEW)에 위임한다. 본 테스트는 스캔 조율 ·
 * 상한(스로틀) · 부분 실패 격리를 검증한다.
 *
 * <p><b>strictness 는 기본(STRICT_STUBS)</b>이다 — 클래스 단위 {@code LENIENT} 는 죽은 stub 을 숨겨
 * "검증한 줄 알았던" 경로를 만든다.
 */
@ExtendWith(MockitoExtension.class)
class TrainingVideoIngestServiceTest {

    @Mock
    private LsDataIngestRepository ingestRepository;

    @Mock
    private TrainingVideoIngestTx ingestTx;

    private TrainingVideoIngestService service;

    @BeforeEach
    void setUp() {
        service = new TrainingVideoIngestService(ingestRepository, ingestTx, STALE_TIMEOUT_MINUTES);
    }

    /** 좀비 회수 임계값(분) — 하한(10) 위의 값이라 clamp 되지 않는다. */
    private static final long STALE_TIMEOUT_MINUTES = 120L;

    @Test
    @DisplayName("좀비회수는_설정_임계값_이전_시각을_기준으로_상한만큼_되돌린다")
    void 좀비회수는_임계값과_상한을_그대로_넘긴다() {
        // given
        when(ingestRepository.reclaimStaleProcessing(any(LocalDateTime.class), anyInt())).thenReturn(2);
        LocalDateTime before = LocalDateTime.now();

        // when
        int reclaimed = service.reclaimStaleProcessing();

        // then — 회수 건수를 그대로 돌려주고, cutoff 는 <우리 시계 − 임계값>이다
        assertThat(reclaimed).isEqualTo(2);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ingestRepository).reclaimStaleProcessing(
                cutoff.capture(), eq(TrainingVideoIngestService.INGEST_SCAN_LIMIT));
        assertThat(cutoff.getValue())
                .as("cutoff = 우리 시계 − 임계값")
                .isAfterOrEqualTo(before.minusMinutes(STALE_TIMEOUT_MINUTES))
                .isBefore(before.minusMinutes(STALE_TIMEOUT_MINUTES - 1));
    }

    @Test
    @DisplayName("좀비회수_임계값_오설정은_하한으로_보정된다 — 살아있는_처리를_뺏지_않는다")
    void 좀비회수_임계값_하한보정() {
        // given — 0 분(또는 음수) 설정이 그대로 먹히면 매 tick 이 방금 클레임한 행을 되돌린다
        TrainingVideoIngestService misconfigured =
                new TrainingVideoIngestService(ingestRepository, ingestTx, 0L);
        when(ingestRepository.reclaimStaleProcessing(any(LocalDateTime.class), anyInt())).thenReturn(0);
        LocalDateTime before = LocalDateTime.now();

        // when
        misconfigured.reclaimStaleProcessing();

        // then — 하한(10분)으로 보정된 cutoff
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ingestRepository).reclaimStaleProcessing(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue())
                .as("하한 10분으로 보정된 cutoff")
                .isAfterOrEqualTo(before.minusMinutes(10))
                .isBefore(before.minusMinutes(9));
    }

    private LsDataIngest row(long rcptnSn, String vmsClipId) {
        LsDataIngest row = newIngest();
        ReflectionTestUtils.setField(row, "rcptnSn", rcptnSn);
        // 시각 픽스처는 상대 시각으로 둔다 — 절대 시각은 "지금"이 멀어지면 판정 분기를 갈아탈 수 있다
        // (이 클래스는 적재 위임을 목으로 격리해 시각 판정이 없지만, 규칙을 클래스별로 예외 두지 않는다).
        ReflectionTestUtils.setField(row, "rcptnDt", LocalDateTime.now().minusMinutes(10));
        ReflectionTestUtils.setField(row, "procSttsCd", LsDataIngest.PROC_STTS_PENDING);
        ReflectionTestUtils.setField(row, "rtyCnt", 0);
        ReflectionTestUtils.setField(row, "vmsClipId", vmsClipId);
        ReflectionTestUtils.setField(row, "vmsCctvId", "CCTV-1");
        ReflectionTestUtils.setField(row, "rawFilePathNm", "/nas-storage/videos/" + vmsClipId + ".mp4");
        ReflectionTestUtils.setField(row, "srcType", "RELAY");
        return row;
    }

    private static LsDataIngest newIngest() {
        try {
            var ctor = LsDataIngest.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("미처리_인입행을_픽업해_적재_위임한다")
    void delegatesIngestForPendingRow() {
        // given
        LsDataIngest row = row(1L, "CLIP-1");
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(row));
        when(ingestTx.ingestOne(row)).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(1);
        verify(ingestTx).ingestOne(row);
    }

    @Test
    @DisplayName("위임_적재가_skip되면_적재건수에_포함되지_않는다")
    void skippedRowNotCounted() {
        // given — 중복/파일 미도착 등은 ingestOne 이 false 반환.
        LsDataIngest row = row(2L, "CLIP-DUP");
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(row));
        when(ingestTx.ingestOne(row)).thenReturn(false);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
    }

    @Test
    @DisplayName("미처리_인입행이_없으면_위임하지_않는다")
    void doesNotDelegateWhenNoPendingRows() {
        // given — 폴링 술어(PENDING)에 걸리는 행이 없다.
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of());

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
        verify(ingestTx, never()).ingestOne(any(LsDataIngest.class));
    }

    @Test
    @DisplayName("한_행의_적재가_실패해도_다음_행_적재는_계속된다")
    void partialFailureDoesNotBlockOtherRows() {
        // given — 첫 행의 REQUIRES_NEW 트랜잭션이 롤백돼도 둘째 행 적재는 영향받지 않아야 한다.
        LsDataIngest bad = row(10L, "CLIP-BAD");
        LsDataIngest good = row(11L, "CLIP-GOOD");
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(bad, good));
        when(ingestTx.ingestOne(bad)).thenThrow(new DataIntegrityViolationException("rollback-only marked tx"));
        when(ingestTx.ingestOne(good)).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(1);
        verify(ingestTx).ingestOne(good);
    }

    @Test
    @DisplayName("여러_행_중_일부_skip_일부_적재가_정확히_집계된다")
    void mixedResultsCountedCorrectly() {
        // given
        LsDataIngest a = row(20L, "CLIP-A");
        LsDataIngest b = row(21L, "CLIP-B");
        LsDataIngest c = row(22L, "CLIP-C");
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(a, b, c));
        when(ingestTx.ingestOne(a)).thenReturn(true);
        when(ingestTx.ingestOne(b)).thenReturn(false);
        when(ingestTx.ingestOne(c)).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(2);
        verify(ingestTx, times(3)).ingestOne(any(LsDataIngest.class));
    }

    @Test
    @DisplayName("스캔결과가_null이면_안전하게_0건_처리한다")
    void nullScanResultIsHandledSafely() {
        // given
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(null);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
        verify(ingestTx, never()).ingestOne(any(LsDataIngest.class));
    }

    @Test
    @DisplayName("후보조회는_tick당_상한건수의_Pageable로_수행된다")
    void scanQueriesWithTickLimitPageable() {
        // given
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of());

        // when
        service.scanAndIngest();

        // then — 상한 없는 전량 조회가 아니라 첫 페이지 + INGEST_SCAN_LIMIT 건 상한(FIFO 정렬은 쿼리가 고정).
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ingestRepository).findPendingReadyForPolling(any(LocalDateTime.class), captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(PageRequest.of(0, TrainingVideoIngestService.INGEST_SCAN_LIMIT));
    }

    @Test
    @DisplayName("상한만큼_조회되면_상한건수만_적재위임하고_잔여분은_다음_tick으로_이월된다")
    void limitReachedProcessesOnlyLimitAndCarriesOverRemainder() {
        // given — 상한(100)만큼 후보가 돌아온 상황(잔여분은 조회 자체에 포함되지 않는다).
        List<LsDataIngest> page = IntStream.range(0, TrainingVideoIngestService.INGEST_SCAN_LIMIT)
                .mapToObj(i -> row(100L + i, "CLIP-" + i))
                .toList();
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(page);
        when(ingestTx.ingestOne(any(LsDataIngest.class))).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then — 한 tick 은 상한 건수만 처리한다(잔여분은 다음 tick 이 이어서 처리).
        assertThat(ingested).isEqualTo(TrainingVideoIngestService.INGEST_SCAN_LIMIT);
        verify(ingestTx, times(TrainingVideoIngestService.INGEST_SCAN_LIMIT))
                .ingestOne(any(LsDataIngest.class));
    }

    @Test
    @DisplayName("중복클립_race로_인한_트랜잭션_롤백은_ERROR가_아니라_INFO로_남는다")
    void duplicateRaceRollbackIsNotLoggedAsError() {
        // given — ingestOne 은 UK(VMS_CLIP_ID) 충돌을 잡아 false 로 흡수하지만, PostgreSQL 은 제약 위반 시
        //   <트랜잭션 전체를 abort> 하므로 REQUIRES_NEW 커밋에서 UnexpectedRollbackException 이 나온다.
        //   이는 설계대로 동작한 정상 race 이며(다음 tick 이 1차 멱등으로 DONE 종결), ERROR 로 남기면
        //   실제 장애 알림과 섞인다.
        LsDataIngest row = row(30L, "CLIP-RACE");
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(ingestTx.ingestOne(row))
                .thenThrow(new UnexpectedRollbackException("Transaction silently rolled back"));
        ListAppender<ILoggingEvent> logs = attachLogAppender();

        try {
            // when — 예외를 밖으로 던지지 않고 흡수한다(다른 행 처리 계속).
            int ingested = service.scanAndIngest();

            // then
            assertThat(ingested).isZero();
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR))
                    .as("정상 race 를 장애로 올리지 않는다").isEmpty();
            assertThat(logs.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getMessage))
                    .as("관측 자체는 남긴다(조용한 유실 금지)")
                    .anyMatch(m -> m.contains("rolled back"));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    @Test
    @DisplayName("적재_예외중_롤백이_아닌_것은_ERROR로_남는다")
    void nonRollbackFailureIsLoggedAsError() {
        // given — 위 완화가 <모든 예외>를 조용하게 만들지 않는지 고정한다(관측 구멍 방지).
        LsDataIngest row = row(31L, "CLIP-BROKEN");
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(ingestTx.ingestOne(row)).thenThrow(new IllegalStateException("unexpected"));
        ListAppender<ILoggingEvent> logs = attachLogAppender();

        try {
            // when
            service.scanAndIngest();

            // then
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR))
                    .as("예기치 못한 실패는 ERROR").isNotEmpty();
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(TrainingVideoIngestService.class);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger().addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("관제_공유_클립마스터는_더이상_스캔하지_않는다")
    void noLongerScansControlSharedTable() {
        // given — 스캔 소스는 인입 테이블 하나다(공유 DB 부하·JOB_DMND_YN 의존 제거).
        when(ingestRepository.findPendingReadyForPolling(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of());

        // when
        service.scanAndIngest();

        // then — 협력자에 관제 클립 리포지토리/엔티티가 남아 있지 않다.
        assertThat(TrainingVideoIngestService.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getSimpleName().startsWith("MngClip"));
        verify(ingestRepository).findPendingReadyForPolling(any(LocalDateTime.class),
                eq(PageRequest.of(0, TrainingVideoIngestService.INGEST_SCAN_LIMIT)));
    }
}
