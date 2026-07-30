package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.support.RejectingScheduler;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2 — KPST 위탁의 <b>완료 신호 기록</b>이 전용 풀 밖(호출 스레드 = 이벤트 루프)에서 실행되지 않음을
 * 고정하는 회귀 가드.
 *
 * <h3>막는 실패 모드</h3>
 * <p>구 구현({@code publishOn(kpstSubmitScheduler)})에서는 전용 풀 포화(AbortPolicy)가 곧 onError 이고,
 * 그 onError 는 <b>시그널을 나른 스레드(reactor-netty 이벤트 루프)</b>에서 downstream 으로 흐른다.
 * downstream 이 실패 핸들러의 JPA 쓰기면 이벤트 루프가 커넥션 대기에 묶여 같은 루프를 쓰는 모든 외부
 * 호출이 동반 지연된다 — 논블로킹으로 얻으려던 것을 정확히 되돌린다. 동기 {@code subscribe()} 구간을
 * 감싼 try/catch 는 이 거부를 잡지 못한다(거부는 응답이 도착한 <b>뒤</b> 발생하기 때문).
 *
 * <p>기록을 포기해도 위탁 사실은 잃지 않는다 — 원장이 {@code WAITING + prjId null} 로 선커밋돼 있어
 * 폴러가 ACK 대기 유예 만료로 회수한다({@code KPST_ACK_MISSING}).
 *
 * <h3>RED 실증</h3>
 * <p>{@code KpstDeidentService.subscribeSubmit} 의 {@code SubmitSignalDispatch.run(...)} 을 걷어내고
 * 핸들러를 직접 호출하도록(또는 구 {@code publishOn} 으로) 되돌리면, 호출 스레드에서 핸들러가 실행되어
 * 아래 두 테스트가 모두 RED 다.
 *
 * <p>증강({@code AugmentSubmitSerializationGuardTest}) · VLM({@code VlmTimeseriesStepNonBlockingTest})
 * 과 동형의 대칭 가드다.
 */
class KpstSubmitSignalDispatchGuardTest {

    @TempDir
    Path tmp;

    private KpstDeidentifyClient kpstClient;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private KpstDeidentTxService txService;
    private BatchTransitionService batchTransitionService;
    private KpstSubmitOutcomeRecorder outcomeRecorder;

    /** 완료 핸들러가 <b>어느 스레드에서든</b> 호출되면 그 스레드명이 남는다(호출 자체가 위반). */
    private final AtomicReference<String> handlerThread = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        kpstClient = mock(KpstDeidentifyClient.class);
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        txService = mock(KpstDeidentTxService.class);
        batchTransitionService = mock(BatchTransitionService.class);
        outcomeRecorder = mock(KpstSubmitOutcomeRecorder.class);

        // 원장 선커밋은 별도 REQUIRES_NEW 빈 위임 — 단위 테스트에서는 procLogSn 발급 스텁으로 대체.
        when(txService.issueSubmitLedger(any(), any(), anyBoolean())).thenAnswer(inv -> {
            LsDeidentProcLog p = LsDeidentProcLog.request(
                    inv.getArgument(0), null, inv.getArgument(1), "batch");
            p.markKpstSubmitPending();
            setField(p, "procLogSn", 1L);
            return p;
        });

        doAnswer(inv -> {
            handlerThread.set(Thread.currentThread().getName());
            return null;
        }).when(outcomeRecorder).onAccepted(any(), any(), any());
        doAnswer(inv -> {
            handlerThread.set(Thread.currentThread().getName());
            return null;
        }).when(outcomeRecorder).onSubmitFailed(any(), any(), any());
    }

    /** 스케줄러만 교체 가능한 서비스 인스턴스 — @Value 필드는 반사로 주입한다. */
    private KpstDeidentService newService(Scheduler scheduler) {
        Path baseDeid = tmp.resolve("deid");
        VideoArtifactRootResolver resolver = ArtifactRootTestSupport.labelingRoot(
                tmp.resolve("labeling"), baseDeid);
        KpstDeidentService s = new KpstDeidentService(kpstClient, videoRepository, procLogRepository,
                txService, resolver, batchTransitionService, outcomeRecorder, scheduler);
        setField(s, "deidPath", baseDeid.toString());
        setField(s, "creatorId", "authoring");
        setField(s, "reqUserId", "authoring");
        setField(s, "pollMaxAttempts", 3);
        setField(s, "pollTimeoutMinutes", 60L);
        setField(s, "verifySourceExists", true);
        setField(s, "resultRecheckDelayMs", 0L);
        setField(s, "submitAckGraceSec", 180L);
        invoke(s, "initBasePath");
        return s;
    }

    private LsDataRaw newRaw() {
        Path rawFile = tmp.resolve("clip.mp4");
        try {
            Files.writeString(rawFile, "video");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-dispatch", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60);
        setField(raw, "rawSn", 9101L);
        return raw;
    }

    @Test
    @DisplayName("전용풀_거부시_ACK기록을_호출스레드에서_수행하지_않고_예외도_새지_않는다")
    void poolRejectionDropsAckRecordWithoutEventLoopJpa() {
        // given — ACK 는 정상 도착하지만 전용 풀이 포화라 기록을 스케줄할 수 없다.
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.just(new KpstProjectResponse("success", 101L)));
        KpstDeidentService service = newService(new RejectingScheduler());

        // when / then — 거부가 호출자에게 예외로 새어 나가지 않는다(위탁은 이미 개시됐다).
        assertThatCode(() -> service.submit(newRaw())).doesNotThrowAnyException();

        // then — ①prjId 기록(JPA) 미수행 ②따라서 호출 스레드에서 JPA 가 돌지 않는다.
        //   회수는 폴러의 ACK 대기 유예 만료가 담당한다(선커밋 원장이 방치되지 않는다).
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
        verify(outcomeRecorder, never()).onSubmitFailed(any(), any(), any());
        assertThat(handlerThread.get())
                .as("거부된 기록이 호출 스레드(운영에서는 이벤트 루프)에서 실행되면 안 된다")
                .isNull();
    }

    @Test
    @DisplayName("전용풀_거부시_실패기록도_호출스레드에서_수행하지_않는다")
    void poolRejectionDropsFailureRecordWithoutEventLoopJpa() {
        // given — 외부가 에러로 종료(실패 기록 경로) + 전용 풀 포화.
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("boom")));
        KpstDeidentService service = newService(new RejectingScheduler());

        // when / then
        assertThatCode(() -> service.submit(newRaw())).doesNotThrowAnyException();

        verify(outcomeRecorder, never()).onSubmitFailed(any(), any(), any());
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
        assertThat(handlerThread.get()).isNull();
    }

    /**
     * 대조군 — 풀이 정상이면 같은 흐름에서 기록이 <b>반드시</b> 수행된다.
     * (위 두 테스트가 "아무 일도 안 일어나서" 통과하는 위양성이 아님을 고정한다.)
     */
    @Test
    @DisplayName("전용풀이_정상이면_같은_흐름에서_ACK기록이_수행된다_위양성_방지_대조군")
    void healthyPoolStillRecordsAck() {
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.just(new KpstProjectResponse("success", 101L)));
        // immediate 스케줄러 = 거부 없이 즉시 실행(결정적).
        KpstDeidentService service = newService(reactor.core.scheduler.Schedulers.immediate());

        service.submit(newRaw());

        verify(outcomeRecorder).onAccepted(any(), any(), any());
        assertThat(handlerThread.get()).isNotNull();
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 상위 타입으로 계속 탐색
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invoke(Object target, String method) {
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
