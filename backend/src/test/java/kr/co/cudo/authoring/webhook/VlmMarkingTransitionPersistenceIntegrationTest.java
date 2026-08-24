package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VLM 배치 실행 → 위탁(recordIssued) → 콜백 → 완료 전이 관통 통합 검증 (Phase 2 DEV_FIX #1/#2).
 *
 * <p>기존 {@code VlmTimeseriesStepMarkingTest}(단위)는 {@code runWithMarking()} 을 직접 호출해
 * <b>메모리상 마킹 객체의 필드 변경</b>만 확인하므로, 실제 파이프라인 진입점인 {@code execute()} 가
 * <b>DB에 전이를 영속</b>하는지는 검증 사각이었다(결함 #1). 본 테스트는 실제 Spring 빈
 * {@link VlmTimeseriesStep} 을 {@code execute()} 로 구동하고, 마킹은 프로덕션과 동형으로
 * {@link LsMarkingRepository#findByRawSnOrderByRegDtDescMarkingSnDesc}(각 리포지토리 tx 종료 후 detached)로 로드해
 * ctx 에 담는다.
 *
 * <h3>검증(결함 #1/#2 폐쇄)</h3>
 * <ol>
 *   <li>{@code execute()} 실행 후 대상 마킹이 <b>DB 재조회</b> 시 {@code VLM_REQUESTED} 로 전이·영속.
 *       (self-invocation 으로 {@code @Transactional(REQUIRES_NEW)} 미적용 + detached 엔티티 필드 변경만
 *       이면 전이가 유실됨 — 그 결함을 실증/폐쇄.)</li>
 *   <li>이어 동일 {@code request_id} 로 verify 콜백을 실제 엔드포인트로 전송 → {@code VlmResultService}
 *       가 {@code VLM_REQUESTED} 마킹을 {@code VLM_COMPLETED} 로 전이 + {@code LS_DATA_META} 적재.</li>
 * </ol>
 *
 * <p>외부 VLM 호출만 {@link MockBean} 으로 대체(수락 응답 stub). 나머지(Spring 프록시/tx, 영속 원장,
 * 마킹/메타 리포지토리, 콜백 필터→컨트롤러→서비스)는 실제 경로다. 공유 Testcontainers PG 격리를 위해
 * 시드는 {@code VLMTX-} 고유 clipId, 단언은 시드 rawSn 으로만 좁힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VlmMarkingTransitionPersistenceIntegrationTest {

    private static final String CALLBACK_PATH = HmacWebhookFilter.PATH_VLM;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VlmTimeseriesStep vlmTimeseriesStep;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsMarkingRepository markingRepository;
    @Autowired private LsDeidentProcLogRepository deidentProcLogRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private WebhookIdempotencyLedger ledger;

    /** 외부 VLM 호출만 mock — 나머지 경로(프록시/tx/DB/콜백)는 실제. */
    @MockBean private VlmClient vlmClient;

    private final JdbcTemplate jdbc;

    VlmMarkingTransitionPersistenceIntegrationTest(
            @Qualifier("controlDataSource") javax.sql.DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private Long seedVideo() {
        String clipId = "VLMTX-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-VLMTX", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/vlmtx.mp4", LocalDateTime.now(), 30));
        seedIngest(raw.getRawSn(), clipId);
        return raw.getRawSn();
    }

    /**
     * 관제 인입 행 시드 — {@code VRFC_EVNT_TYPE_CD}(검증이벤트유형)는 verify 위탁의 <b>사전 조건</b>이다
     * (@req R6). 없으면 스텝이 외부 호출 없이 SKIPPED 로 끝나 마킹 전이도 일어나지 않는다.
     */
    private void seedIngest(Long rawSn, String clipId) {
        jdbc.update("""
                INSERT INTO LS_DATA_INGEST
                    (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
                     RCPTN_DT, PRCS_STTS_CD, VRFC_EVNT_TYPE_CD)
                VALUES (?, ?, 'CCTV-VLMTX', 'vlmtx.mp4', '/var/raw/vlmtx.mp4', 'ORIGINAL',
                        CURRENT_TIMESTAMP, 'DONE', 'fire')
                """, rawSn, clipId);
    }

    private void seedDeidentSuccess(Long rawSn) {
        LsDeidentProcLog log = LsDeidentProcLog.request(
                rawSn, "REQ-DEID-" + rawSn, "/var/raw/vlmtx.mp4", "test");
        log.succeed("/var/deid/vlmtx-" + rawSn + ".mp4");
        deidentProcLogRepository.save(log);
    }

    private LsMarking seedMarkingPending(Long rawSn) {
        return markingRepository.save(LsMarking.createAuto(
                rawSn, "fire", 5, "/var/deid/vlmtx.mp4",
                "[{\"frameIndex\":0,\"timestamp\":0.0}]", 1L));
    }

    /**
     * verify 규격 콜백 본문 — {@code results} 는 <b>단일 객체</b> {@code {accuracy, description}} 다.
     * (구 describe 배열은 폐기. 이 조립은 컴파일이 아니라 <b>런타임</b>에만 깨지므로 규격 변경 시 필수 점검 대상.)
     */
    private String completedCallbackJson(String requestId) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "request_id", requestId,
                "status", "completed",
                "results", java.util.Map.of(
                        "accuracy", 0.8,
                        "description", "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다.")));
    }

    @Test
    @DisplayName("execute_실행이_마킹을_DB에_VLM_REQUESTED로_영속하고_이어_콜백이_VLM_COMPLETED로_전이한다")
    void executePersistsVlmRequestedThenCallbackCompletes() throws Exception {
        // given — 영상 + 비식별 성공 로그 + PENDING 마킹 시드, 외부 VLM 은 accepted 로 stub
        Long rawSn = seedVideo();
        seedDeidentSuccess(rawSn);
        Long markingSn = seedMarkingPending(rawSn).getMarkingSn();

        when(vlmClient.submitDescribe(any(VlmTimeseriesRequest.class))).thenAnswer(inv -> {
            VlmTimeseriesRequest r = inv.getArgument(0);
            return Mono.just(new VlmTimeseriesResponse(r.requestId(), "accepted"));
        });

        // 프로덕션 동형: 마킹을 리포지토리로 로드(리포지토리 tx 종료 → detached) 후 ctx 적재
        LsDataRaw raw = videoRepository.findById(rawSn).orElseThrow();
        BatchContext ctx = new BatchContext(rawSn, raw);
        ctx.setMarkings(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn));

        // when — 실제 파이프라인 진입점 execute() 구동
        vlmTimeseriesStep.execute(ctx);

        // then #2 — 전이가 DB 에 영속(재조회로 확인). 유실이면 여기서 PENDING 으로 RED.
        LsMarking afterExecute = markingRepository.findById(markingSn).orElseThrow();
        assertThat(afterExecute.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        // 위탁 시 발급된 request_id 확보(콜백 상관키)
        ArgumentCaptor<VlmTimeseriesRequest> reqCaptor =
                ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitDescribe(reqCaptor.capture());
        String requestId = reqCaptor.getValue().requestId();
        assertThat(requestId).isNotBlank();

        // when — 동일 request_id verify 콜백 전송(무서명 규격)
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedCallbackJson(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        // then #1 — 콜백이 VLM_REQUESTED 마킹을 찾아 VLM_COMPLETED 로 전이 + META 적재
        LsMarking afterCallback = markingRepository.findById(markingSn).orElseThrow();
        assertThat(afterCallback.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);

        // ★ 적재 키는 서술 하나다. 일치도는 우리가 연동하지 않는 판정 창구 전용이라 오지 않으며,
        //   조회 집합에는 남겨 둬 "실수로 다시 적재되면 잡히도록" 한다(과거 적재분과 구분).
        List<LsDataMeta> metas = metaRepository.findByRawSnAndMetaKeyIn(rawSn,
                Set.of(VlmResultService.META_KEY_DESCRIPTION, VlmResultService.META_KEY_ACCURACY));
        assertThat(metas)
                .extracting(LsDataMeta::getMetaKey)
                .containsExactly(VlmResultService.META_KEY_DESCRIPTION);
    }

    /**
     * ★ Phase C-1 회귀 가드 — <b>콜백 선행 레이스</b>.
     *
     * <p>제출이 논블로킹이 되면서 저지연 벤더(mock 포함)의 콜백이 ACK 처리보다 먼저 커밋될 수 있다.
     * 구 수신부는 {@code VLM_REQUESTED} 만 조회해 전이했으므로, 그 순간 마킹이 아직 {@code PENDING}
     * 이면 <b>전이 0건</b>으로 끝나고 이후 스텝이 {@code PENDING → VLM_REQUESTED} 로 올려
     * 마킹이 <b>VLM_REQUESTED 에 영구 고착</b>됐다. 수신부 조회를 {@code ACTIVE_STATUSES} 로 넓혀
     * (스텝의 선커밋과 함께 양단으로) 닫았음을 고정한다.
     *
     * <p>여기서는 스텝을 돌리지 않고 <b>ledger 만 선등록한 뒤 콜백을 먼저 보내</b> 그 창을 직접 재현한다.
     */
    @Test
    @DisplayName("콜백이_ACK보다_먼저_도착해_마킹이_아직_PENDING이어도_VLM_COMPLETED로_전이한다")
    void callbackBeforeAckStillCompletesPendingMarking() throws Exception {
        // given — PENDING 마킹 + (request_id → rawSn) 상관키만 선커밋된 상태(제출 직후, ACK 이전)
        Long rawSn = seedVideo();
        seedDeidentSuccess(rawSn);
        Long markingSn = seedMarkingPending(rawSn).getMarkingSn();
        assertThat(markingRepository.findById(markingSn).orElseThrow().getSttsCd())
                .isEqualTo(LsMarking.STATUS_PENDING);

        String requestId = UUID.randomUUID().toString();
        ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);

        // when — ACK 처리보다 먼저 결과 콜백이 도착
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedCallbackJson(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        // then — PENDING 이어도 완료 전이가 성립한다(구 구현이면 PENDING 그대로 RED → 이후 영구 고착)
        assertThat(markingRepository.findById(markingSn).orElseThrow().getSttsCd())
                .isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    /**
     * 회귀(DEV_FIX 2차 #신규): retry 로 파이프라인이 MARKING 부터 전량 재실행될 때, 이미
     * {@code VLM_COMPLETED} 로 전이·META 적재까지 끝난 마킹이 {@code execute()} 재실행으로
     * {@code VLM_REQUESTED} 로 <b>durable 하게 역행</b>하면 안 된다(2차 verify 콜백 실패/미도착 시
     * 유효 META 를 가진 마킹이 VLM_REQUESTED 에 고착됨).
     */
    @Test
    @DisplayName("retry로_execute_재실행돼도_이미_VLM_COMPLETED로_영속된_마킹은_DB에서_VLM_REQUESTED로_역행하지_않는다")
    void reExecuteDoesNotRegressPersistedCompletedMarking() throws Exception {
        // given — 영상 + 비식별 성공 로그 + 이미 VLM_COMPLETED 로 durable 전이된 마킹 시드
        Long rawSn = seedVideo();
        seedDeidentSuccess(rawSn);
        LsMarking seeded = seedMarkingPending(rawSn);
        seeded.markVlmRequested();
        seeded.markVlmCompleted();
        Long markingSn = markingRepository.save(seeded).getMarkingSn();
        assertThat(markingRepository.findById(markingSn).orElseThrow().getSttsCd())
                .isEqualTo(LsMarking.STATUS_VLM_COMPLETED);

        when(vlmClient.submitDescribe(any(VlmTimeseriesRequest.class))).thenAnswer(inv -> {
            VlmTimeseriesRequest r = inv.getArgument(0);
            return Mono.just(new VlmTimeseriesResponse(r.requestId(), "accepted"));
        });

        // 프로덕션 동형: 마킹을 리포지토리로 로드(detached) 후 ctx 적재 → retry 재실행
        LsDataRaw raw = videoRepository.findById(rawSn).orElseThrow();
        BatchContext ctx = new BatchContext(rawSn, raw);
        ctx.setMarkings(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn));

        // when — retry 경로와 동형으로 VLM 단계를 재실행
        vlmTimeseriesStep.execute(ctx);

        // then — DB 재조회 시 여전히 VLM_COMPLETED(역행 금지). 무가드였다면 VLM_REQUESTED 로 RED.
        LsMarking afterReExecute = markingRepository.findById(markingSn).orElseThrow();
        assertThat(afterReExecute.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }
}
