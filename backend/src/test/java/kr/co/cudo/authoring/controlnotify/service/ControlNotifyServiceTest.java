package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.common.client.ControlNotifyStatusException;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.controlnotify.fallback.ControlNotifyFallbackService;
import kr.co.cudo.authoring.controlnotify.fallback.LsControlNotifyFallback;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ControlNotifyService 단위 테스트.
 *
 * <p>핵심은 <b>최초/재통지 자기치유</b>(409→updated / 404→completed)와 <b>재귀 금지</b>다.
 * 저작도구는 "이미 통지했는가" 플래그를 두지 않고 관제 응답을 진실원으로 삼는다.
 */
class ControlNotifyServiceTest {

    private static final Long RAW_SN = 100L;

    private static final TaskCompletedPayload COMPLETED =
            new TaskCompletedPayload("100", "FIRE", "11680", "서울특별시 강남구", 30, 16);
    private static final TaskModifiedPayload MODIFIED =
            new TaskModifiedPayload("100",
                    new TaskModifiedPayload.ChangedItems(List.of(), List.of("0007.json")));
    private static final TaskModifiedPayload MODIFIED_ALL =
            new TaskModifiedPayload("100",
                    new TaskModifiedPayload.ChangedItems(List.of("0000.jpg"), List.of("0000.json")));

    private ControlNotifyClient client;
    private ControlNotifyFallbackService fallbackService;
    private ControlNotifyMetrics metrics;
    private ControlNotifyPayloadFactory payloadFactory;
    private ControlNotifyService svc;

    @BeforeEach
    void setUp() {
        client = mock(ControlNotifyClient.class);
        fallbackService = mock(ControlNotifyFallbackService.class);
        metrics = mock(ControlNotifyMetrics.class);
        payloadFactory = mock(ControlNotifyPayloadFactory.class);
        svc = new ControlNotifyService(client, fallbackService, metrics, payloadFactory);

        when(payloadFactory.buildCompleted(RAW_SN)).thenReturn(COMPLETED);
        when(payloadFactory.buildModified(eq(RAW_SN), any())).thenReturn(MODIFIED);
        when(payloadFactory.buildModifiedForAllFrames(RAW_SN)).thenReturn(MODIFIED_ALL);
    }

    private ReviewApprovedEvent approved() {
        return new ReviewApprovedEvent(RAW_SN, 1L, Instant.now());
    }

    private List<FrameChangeSet> frameChanges() {
        return List.of(new FrameChangeSet(5001L, Set.of(ChangeType.LABEL_UPDATED)));
    }

    private static ControlNotifyStatusException status(int code) {
        return new ControlNotifyStatusException(code, "");
    }

    // ================= 정상 경로 =================

    @Test
    @DisplayName("TASK_COMPLETED_정상_전송시_Client_호출됨")
    void sendCompleted_success_callsClient() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(approved());

        // then
        verify(client).sendTaskCompleted(COMPLETED);
        verify(client, never()).sendTaskModified(any());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("TASK_COMPLETED_실패시_폴백큐_적재됨")
    void sendCompleted_failure_enqueuesFallback() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        // when
        svc.sendCompleted(approved());

        // then
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), anyString());
    }

    @Test
    @DisplayName("TASK_MODIFIED_정상_전송시_Client_호출됨")
    void sendModified_success_callsClient() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(client).sendTaskModified(MODIFIED);
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("TASK_MODIFIED_실패시_폴백큐_적재됨")
    void sendModified_failure_enqueuesFallback() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("timeout")));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), anyString());
    }

    // ================= 자기치유 (S1·S2·S3) =================

    @Test
    @DisplayName("completed_가_409면_updated_로_재전송된다")
    void completedConflict_resendsAsUpdated() {
        // given — 관제에 이미 등록된 job_id (재승인)
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(status(409)));
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(approved());

        // then — 수정 통지로 전환되고 성공 처리(폴백 큐 미적재).
        //        B-3: 실제로 관제가 받은 것은 updated 이므로 성공 메트릭·감사행도 TASK_MODIFIED 여야 한다.
        verify(client).sendTaskModified(MODIFIED_ALL);
        verify(metrics).incrementSelfHealCompletedToUpdated();
        verify(metrics).incrementModifiedSuccess();
        verify(metrics, never()).incrementCompletedSuccess();
        verify(fallbackService).recordImmediateSuccess(anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), anyString());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("updated_가_404면_completed_로_1회_폴백된다")
    void updatedNotFound_fallsBackToCompletedOnce() {
        // given — 관제에 선행 완료 통지가 없는 job_id
        when(client.sendTaskModified(any())).thenReturn(Mono.error(status(404)));
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then — B-3: 실제 전송분은 completed 이므로 메트릭·감사행도 TASK_COMPLETED.
        verify(client, times(1)).sendTaskCompleted(COMPLETED);
        verify(metrics).incrementSelfHealUpdatedToCompleted();
        verify(metrics).incrementCompletedSuccess();
        verify(metrics, never()).incrementModifiedSuccess();
        verify(fallbackService).recordImmediateSuccess(anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), anyString());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("폴백은_재귀하지_않는다")
    void selfHealingDoesNotRecurse() {
        // given — updated 404 → completed 폴백이 다시 409. 여기서 멈춰야 한다.
        when(client.sendTaskModified(any())).thenReturn(Mono.error(status(404)));
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(status(409)));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then — 각 방향 정확히 1회씩만 호출(무한 왕복 없음) + 실패는 폴백 큐로
        verify(client, times(1)).sendTaskModified(any());
        verify(client, times(1)).sendTaskCompleted(any());
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), anyString());
        verify(metrics).incrementModifiedFailed();
    }

    @Test
    @DisplayName("completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다")
    void completedConflictThenUpdatedFailure_doesNotRecurse() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(status(409)));
        when(client.sendTaskModified(any())).thenReturn(Mono.error(status(404)));

        // when
        svc.sendCompleted(approved());

        // then — completed 재호출 없음(재귀 금지)
        verify(client, times(1)).sendTaskCompleted(any());
        verify(client, times(1)).sendTaskModified(any());
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), anyString());
    }

    @Test
    @DisplayName("409_404_이외의_4xx_는_자기치유하지_않고_실패_처리된다")
    void other4xxIsNotSelfHealed() {
        // given — 422 파라미터 오류
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(status(422)));

        // when
        svc.sendCompleted(approved());

        // then
        verify(client, never()).sendTaskModified(any());
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), anyString());
    }

    // ================= fail-closed 페이로드 조달 =================

    @Test
    @DisplayName("페이로드_조립에_실패하면_통지가_폴백큐에_적재되어_재시도된다")
    void payloadBuildFailure_enqueuesForRebuild() {
        // given — 승인 커밋 직후 커넥션 고갈/락 타임아웃 등으로 조회가 실패한다.
        when(payloadFactory.buildCompleted(RAW_SN)).thenThrow(new IllegalStateException("not found"));

        // when
        svc.sendCompleted(approved());

        // then — 상수로 채워 보내지 않되(0/null self-fill 금지) 통지를 버리지도 않는다.
        //        AFTER_COMMIT 소비라 이벤트가 재발행되지 않으므로 큐에 남지 않으면 영구 소실이다(A-1).
        verify(client, never()).sendTaskCompleted(any());
        verify(metrics).incrementCompletedFailed();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fallbackService).enqueuePending(
                anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), payloadCaptor.capture());
        // 페이로드 미보유 표식 — 재시도 시점에 재조립한다.
        assertThat(payloadCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("MED2_재생성없는_수정통지_조립실패시_빈_changed_items_로_적재되어_재시도때_전프레임_blast_안됨")
    void modifiedBuildFailure_nonRegen_enqueuesEmptyPayloadNotRebuildAll() {
        // given — exportRegenerated=false(메타 수정 등, 파일 미재생성)인데 페이로드 조립이 일시 실패.
        when(payloadFactory.buildModified(eq(RAW_SN), any()))
                .thenThrow(new IllegalStateException("db down"));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then — MED-2: REBUILD_REQUIRED("")로 적재하면 재시도 Job 이 buildModifiedForAllFrames 로
        //   무조건 전 프레임을 발송한다(파일 안 바뀐 메타 수정인데 관제가 수천 파일 헛 재픽업). 대신
        //   changed_items 를 비운 구체 페이로드로 적재해, 재시도 시 그대로 빈 통지가 나가도록 플래그를 보존한다.
        verify(client, never()).sendTaskModified(any());
        verify(metrics).incrementModifiedFailed();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fallbackService).enqueuePending(
                anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), payloadCaptor.capture());
        String queued = payloadCaptor.getValue();
        assertThat(queued).isNotEqualTo(LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED);
        assertThat(queued).contains("\"images\":[]").contains("\"jsons\":[]");
        assertThat(queued).contains("\"job_id\":\"" + RAW_SN + "\"");
    }

    @Test
    @DisplayName("MED2_재생성동반_수정통지_조립실패시_REBUILD_REQUIRED로_적재되어_재시도때_전프레임_재조립됨")
    void modifiedBuildFailure_regen_enqueuesRebuildRequired() {
        // given — exportRegenerated=true(라벨/촬영환경 수정 → 전량 재생성)인데 조립 실패.
        when(payloadFactory.buildModifiedForAllFrames(RAW_SN))
                .thenThrow(new IllegalStateException("db down"));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), true);

        // then — 파일이 전량 재생성됐으므로 재시도 시 전 프레임을 재조립해야 한다(REBUILD_REQUIRED 보존).
        verify(client, never()).sendTaskModified(any());
        verify(metrics).incrementModifiedFailed();
        verify(fallbackService).enqueuePending(
                anyString(), eq("TASK_MODIFIED"), eq(RAW_SN),
                eq(LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED));
    }

    @Test
    @DisplayName("폴백큐가_가득_차도_예외가_호출자로_전파되지_않는다")
    void queueFullDoesNotEscapeToCaller() {
        // given — 큐 상한 초과(IllegalStateException). 이 예외가 새면 디바운서 flush 루프가 끊겨
        //         이미 윈도우에서 제거된 다른 영상의 변경까지 통째로 사라진다.
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("timeout")));
        when(fallbackService.enqueuePending(anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("queue full"));

        // when / then — 예외 없이 반환되어야 한다.
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        verify(metrics).incrementModifiedFailed();
    }

    @Test
    @DisplayName("폴백큐_적재까지_실패하면_소실_카운터가_증가한다")
    void queueFullIncrementsDroppedMetric() {
        // given — 여기서 삼킨 통지는 실제로 사라진다(AFTER_COMMIT 1회 발행 + 윈도우 이미 제거).
        //         ERROR 로그만으로는 운영에서 감지되지 않으므로 전용 카운터가 있어야 한다(B-3).
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("timeout")));
        when(fallbackService.enqueuePending(anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("queue full"));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(metrics).incrementDropped();
    }

    @Test
    @DisplayName("폴백큐_적재가_성공하면_소실_카운터는_증가하지_않는다")
    void successfulEnqueueDoesNotIncrementDropped() {
        // given — 전송은 실패했지만 큐에는 정상 적재(=소실 아님).
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("timeout")));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(metrics, never()).incrementDropped();
    }

    @Test
    @DisplayName("페이로드_직렬화에_실패하면_재조립_표식으로_큐에_적재된다")
    void serializeFailureQueuesForRebuildNotEmptyJson() {
        // given — 직렬화 불가 페이로드(자기참조 등)를 팩토리가 돌려준 상황.
        //         구 구현은 "{}" 를 적재했는데, 이는 재조립 대상으로 인식되지 않고 역직렬화하면
        //         jobId=null → assertValidJobId 예외 → 5회 재시도 후 dead-letter 로 고착됐다(B-2).
        when(payloadFactory.buildModified(eq(RAW_SN), any())).thenReturn(unserializableModified());
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("timeout")));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then — 빈 표식(PAYLOAD_REBUILD_REQUIRED)이어야 재시도 Job 이 재조립 경로를 탄다.
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fallbackService).enqueuePending(
                anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isEqualTo(LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED);
        assertThat(payloadCaptor.getValue()).isNotEqualTo("{}");
    }

    /**
     * Jackson 이 직렬화하지 못하는 페이로드 — 프로퍼티가 없는 익명 객체를 리스트에 넣어
     * {@code InvalidDefinitionException}("No serializer found") 를 유도한다.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TaskModifiedPayload unserializableModified() {
        List raw = new java.util.ArrayList<>();
        raw.add(new Object() { });
        return new TaskModifiedPayload("100",
                new TaskModifiedPayload.ChangedItems(List.of(), (List<String>) raw));
    }

    @Test
    @DisplayName("폴백_재시도가_페이로드_없이_호출되면_dispatch_가_재조립한다")
    void dispatchRebuildsPayloadWhenNull() {
        // given — 큐에 페이로드 없이 적재된 항목의 재시도 경로(A-1).
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.dispatchCompleted(null, RAW_SN);
        svc.dispatchModified(null, RAW_SN);

        // then — 팩토리로 재조립해 전송한다. 수정 통지는 변경 프레임 정보가 없으므로 전 프레임 기준.
        verify(client).sendTaskCompleted(COMPLETED);
        verify(client).sendTaskModified(MODIFIED_ALL);
    }

    @Test
    @DisplayName("수정통지가_404가_아닌_상태코드면_자기치유하지_않고_그대로_실패한다")
    void modifiedOther4xxIsNotSelfHealed() {
        // given — 409(중복) 는 수정 통지 경로에서 자기치유 대상이 아니다(자기치유는 404 만).
        when(client.sendTaskModified(any())).thenReturn(Mono.error(status(409)));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then — completed 로 되돌아가지 않고 폴백 큐로 간다.
        verify(client, never()).sendTaskCompleted(any());
        verify(metrics, never()).incrementSelfHealUpdatedToCompleted();
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), anyString());
    }

    @Test
    @DisplayName("수정통지가_500이면_자기치유하지_않고_폴백큐로_간다")
    void modified5xxIsNotSelfHealed() {
        // given
        when(client.sendTaskModified(any()))
                .thenReturn(Mono.error(new IllegalStateException("관제 통지 실패 status=500")));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(client, never()).sendTaskCompleted(any());
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), anyString());
    }

    // ============ 영상 단위 변경 유실 금지(D-ISSUE-43) + changed_items 범위(A-2) ============

    @Test
    @DisplayName("재export_를_동반하는_영상단위_변경은_전_프레임을_싣는다")
    void videoLevelChangeWithReExportCarriesAllFrames() {
        // given — event_annotation 지연 승인처럼 DatasetReExportEvent 를 함께 발행하는 경로.
        //         export 가 전 프레임 이미지·JSON 을 재생성하므로 빈 changed_items 를 보내면 관제가
        //         아무것도 재픽업하지 않아 보유본이 stale 로 영구 고착된다.
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, List.of(), Set.of(ChangeType.META_UPDATED), true);

        // then — 전 프레임(이미지+JSON)이 실린 페이로드가 나간다.
        ArgumentCaptor<TaskModifiedPayload> captor = ArgumentCaptor.forClass(TaskModifiedPayload.class);
        verify(client).sendTaskModified(captor.capture());
        assertThat(captor.getValue().changedItems().images()).containsExactly("0000.jpg");
        assertThat(captor.getValue().changedItems().jsons()).containsExactly("0000.json");
        verify(payloadFactory).buildModifiedForAllFrames(RAW_SN);
        verify(payloadFactory, never()).buildModified(any(), any());
        verify(metrics).incrementModifiedSuccess();
    }

    @Test
    @DisplayName("재export_없는_영상단위_변경은_changed_items_를_비우고도_통지를_발송한다")
    void videoLevelChangeWithoutReExportSendsEmptyChangedItems() {
        // given — 촬영환경 메타 수정 등: 디스크는 1바이트도 바뀌지 않았다. 전 프레임을 실으면 관제가
        //         수천 개 파일을 헛 재픽업한다. 그렇다고 통지를 생략하면 관제가 변경을 영원히 모른다.
        //         변경 프레임이 없으므로 실제 팩토리는 빈 changed_items 를 돌려준다.
        when(payloadFactory.buildModified(eq(RAW_SN), argThat(c -> c == null || c.isEmpty())))
                .thenReturn(new TaskModifiedPayload("100", TaskModifiedPayload.ChangedItems.empty()));
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, List.of(), Set.of(ChangeType.META_UPDATED), false);

        // then — 통지는 나가되 changed_items 는 비어 있다(관제는 V_COMPLETED_META 뷰로 재조회).
        ArgumentCaptor<TaskModifiedPayload> captor = ArgumentCaptor.forClass(TaskModifiedPayload.class);
        verify(client).sendTaskModified(captor.capture());
        assertThat(captor.getValue().changedItems().images()).isEmpty();
        assertThat(captor.getValue().changedItems().jsons()).isEmpty();
        verify(payloadFactory).buildModified(eq(RAW_SN), any());
        verify(payloadFactory, never()).buildModifiedForAllFrames(any());
        verify(metrics).incrementModifiedSuccess();
    }

    @Test
    @DisplayName("프레임변경과_재export없는_영상변경이_혼재하면_변경된_프레임만_싣는다")
    void mixedFrameAndVideoLevelChangeWithoutReExportCarriesDeltaOnly() {
        // given — 영상 메타만 바뀌고 파일은 그대로이므로 전량 전송은 낭비다. 실제로 JSON 이 다시
        //         쓰인 것은 없으니 프레임 델타(라벨 변경분)만 싣는다.
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(ChangeType.META_UPDATED), false);

        // then
        verify(client).sendTaskModified(MODIFIED);
        verify(payloadFactory).buildModified(eq(RAW_SN), any());
        verify(payloadFactory, never()).buildModifiedForAllFrames(any());
    }

    @Test
    @DisplayName("프레임변경과_재export_동반_영상변경이_혼재하면_전_프레임이_실린다")
    void mixedFrameAndVideoLevelChangeWithReExportCarriesAllFrames() {
        // given — 재생성이 섞이면 산출물이 전량 바뀌므로 프레임 델타만 싣는 것은 누락이다.
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(ChangeType.META_UPDATED), true);

        // then
        verify(client).sendTaskModified(MODIFIED_ALL);
        verify(payloadFactory).buildModifiedForAllFrames(RAW_SN);
        verify(payloadFactory, never()).buildModified(any(), any());
    }

    @Test
    @DisplayName("프레임_변경만_있으면_변경_프레임만_실린다")
    void frameOnlyChangeCarriesDelta() {
        // given — 영상 단위 변경이 없으면 기존대로 변경 프레임 델타만 싣는다(전량 전송 회귀 방지).
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(client).sendTaskModified(MODIFIED);
        verify(payloadFactory).buildModified(eq(RAW_SN), any());
        verify(payloadFactory, never()).buildModifiedForAllFrames(any());
    }

    // ================= 발송 결과 관찰 적재 =================

    @Test
    @DisplayName("즉시_통지_성공시_SEND_RSLT_SUCCESS_행이_적재된다")
    void sendCompleted_success_recordsImmediateSuccess() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(approved());

        // then
        verify(fallbackService).recordImmediateSuccess(anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), anyString());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("즉시_통지_실패시_PENDING_FAILED로_폴백큐_적재되고_성공행은_미적재")
    void sendCompleted_failure_noSuccessRecord() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        // when
        svc.sendCompleted(approved());

        // then
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_COMPLETED"), eq(RAW_SN), anyString());
        verify(fallbackService, never()).recordImmediateSuccess(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Modified_즉시_통지_성공시_SEND_RSLT_SUCCESS_행이_적재된다")
    void sendModified_success_recordsImmediateSuccess() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(fallbackService).recordImmediateSuccess(anyString(), eq("TASK_MODIFIED"), eq(RAW_SN), anyString());
    }

    @Test
    @DisplayName("관찰_적재_실패해도_통지성공_metrics는_유지된다")
    void sendCompleted_recordFailure_doesNotBreakNotification() {
        // given — 통지 자체는 성공했으나 관찰 행 INSERT 가 예외.
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());
        when(fallbackService.recordImmediateSuccess(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        // when — 예외가 밖으로 전파되지 않아야 한다.
        svc.sendCompleted(approved());

        // then
        verify(metrics).incrementCompletedSuccess();
        verify(metrics, never()).incrementCompletedFailed();
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    // ================= 메트릭 =================

    @Test
    @DisplayName("통지_성공시_metrics_completedSuccess_호출됨")
    void sendCompleted_success_incrementsMetric() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(approved());

        // then
        verify(metrics).incrementCompletedSuccess();
        verify(metrics, never()).incrementCompletedFailed();
    }

    @Test
    @DisplayName("통지_실패시_metrics_completedFailed_호출됨")
    void sendCompleted_failure_incrementsMetric() {
        // given
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(new RuntimeException("fail")));

        // when
        svc.sendCompleted(approved());

        // then
        verify(metrics).incrementCompletedFailed();
        verify(metrics, never()).incrementCompletedSuccess();
    }

    @Test
    @DisplayName("Modified_통지_성공시_metrics_modifiedSuccess_호출됨")
    void sendModified_success_incrementsMetric() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(metrics).incrementModifiedSuccess();
        verify(metrics, never()).incrementModifiedFailed();
    }

    @Test
    @DisplayName("Modified_통지_실패시_metrics_modifiedFailed_호출됨")
    void sendModified_failure_incrementsMetric() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("fail")));

        // when
        svc.sendModified(RAW_SN, frameChanges(), Set.of(), false);

        // then
        verify(metrics).incrementModifiedFailed();
        verify(metrics, never()).incrementModifiedSuccess();
    }
}
