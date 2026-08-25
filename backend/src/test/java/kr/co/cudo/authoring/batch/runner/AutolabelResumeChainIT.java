package kr.co.cudo.authoring.batch.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.preset.event.PresetLabelsChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「프리셋 저장 → 보류분 재개」 <b>사슬 전체</b> 통합 시험. [@design ADR-054] [@design AC-115] [@design AC-113]
 *
 * <h3>이 시험이 없으면 무엇이 뚫리는가</h3>
 * <p>사슬은 <b>프리셋 저장 커밋 → {@code PresetLabelsChangedEvent} → {@code AutolabelResumeBridge}
 * ({@code AFTER_COMMIT}) → {@code AutolabelWithheldResumeRunner} → 오케스트레이터 재진입</b> 이다.
 * 조각마다 단위 시험이 있지만 <b>이어붙인 사슬이 실제로 도는지</b>는 어느 시험도 보지 않았다 —
 * {@code PresetControllerTest} 는 {@code @Transactional} 이라 <b>커밋이 일어나지 않고</b>, 그래서
 * {@code AFTER_COMMIT} 리스너가 한 번도 발화하지 않는다. 이벤트 발행을 지우거나 리스너 애노테이션을
 * 떼도 그 시험은 전부 통과한다.
 *
 * <p>⚠ <b>그래서 이 클래스에는 {@code @Transactional} 을 붙이지 않는다.</b> 붙이는 순간 같은 이유로
 * 아무것도 검증하지 못하면서 초록이 된다. 정리는 {@code @BeforeEach}/{@code @AfterEach} 의 DELETE 가
 * 담당한다({@code EventTypeAdminControllerIT} 와 같은 골격).
 *
 * <h3>어디까지 보고 어디부터 안 보는가 (좁힌 경계를 그대로 적는다)</h3>
 * <p>{@link BatchOrchestrator} 는 {@code @MockBean} 이다. 즉 이 시험이 확정하는 것은 <b>보류된 그
 * 영상으로 오토라벨 묶음 재진입이 실제로 호출된다</b>는 데까지이고, 그 뒤의 추론·라벨 적재는 보지
 * 않는다(그 구간은 ai-server 호출이라 통합 시험의 결정성을 잃는다). 재진입의 <b>범위</b>는 전달된
 * 단계 토글로 확인한다 — 탐지가 켜져 있고 외부 시계열 위탁이 꺼져 있어야 한다(재위탁 이중 발송 금지).
 *
 * <h3>비동기 경계</h3>
 * <p>재개 러너는 {@code @Async("batchAsyncExecutor")} 라 요청 스레드 밖에서 돈다. 고정 대기
 * ({@code Thread.sleep})로 때우지 않고 Mockito {@code timeout} 으로 <b>발생을 기다린다</b>.
 * 반대로 <b>부재</b>는 원리상 기다림으로 증명할 수 없으므로, 같은 촉발에서 반드시 일어나는 사건을
 * 먼저 관측한 뒤 유계 대기({@code after})로 판정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AutolabelResumeChainIT {

    /** 프리셋이 걸리는 그룹 대표코드(침수(범람) 그룹의 최소 코드) — 시드 V15 기준. */
    private static final String PRESET_EVENT = "EV01000101";
    /** 그 그룹의 <b>비대표</b> 코드 — 영상은 상세 EV-코드를 갖는다. 그룹 축을 잊으면 후보에서 빠진다. */
    private static final String VIDEO_EVENT = "EV01000103";
    /** 보류 영상이 하나도 없는 별개 그룹 — 삭제 방향 가드 전용. */
    private static final String LONE_EVENT = "EV01000201";
    /** 이 시험이 만든 영상 식별자 접두 — 정리 범위이자 다른 시험과의 격리 축. */
    private static final String CLIP_PREFIX = "ITRESUME";

    @MockBean
    private BatchOrchestrator orchestrator;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BatchStatusService batchStatusService;
    @Autowired private EventTypeService eventTypeService;
    @Autowired private PresetEventRecorder recorder;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String reviewerToken;
    private long mappedLabelId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        recorder.clear();
        reset(orchestrator);
        when(orchestrator.process(anyLong(), any())).thenReturn(BatchStage.COMPLETED);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // AI 검출 클래스에 매핑된 활성 라벨 — 이것이 담겨야 프리셋이 실효한다(시드 V15 의 person).
        mappedLabelId = jdbc.queryForObject(
                "SELECT lbl_id FROM ls_label WHERE dtct_type_cd = 'person' AND use_yn = 'Y'", Long.class);

        // 픽스처 전제 — 영상의 상세 코드가 프리셋 대표코드로 접힌다(시드 표시명 폴백에 의존한다).
        assertThat(eventTypeService.filterKeyOf(VIDEO_EVENT))
                .as("시드가 바뀌어 그룹 대표코드가 달라지면 이 픽스처를 갱신해야 한다")
                .contains(PRESET_EVENT);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_label_preset_code WHERE preset_id IN "
                + "(SELECT preset_id FROM ls_label_preset WHERE evnt_type_cd IN (?, ?))",
                PRESET_EVENT, LONE_EVENT);
        jdbc.update("DELETE FROM ls_label_preset WHERE evnt_type_cd IN (?, ?)", PRESET_EVENT, LONE_EVENT);
        jdbc.update("DELETE FROM ls_batch_proc_log WHERE data_raw_sn IN "
                + "(SELECT raw_sn FROM ls_data_raw WHERE vms_clip_id LIKE ?)", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
    }

    // ---------- 사슬 ----------

    @Test
    @DisplayName("보류된_영상은_실효_프리셋을_저장하면_오토라벨_묶음_재진입까지_실제로_이어진다")
    void withheldVideoResumesWhenEffectivePresetIsSaved() throws Exception {
        // given — 프리셋이 없어 보류된 영상. 영상 코드는 그룹의 비대표 코드다.
        long rawSn = insertVideo(VIDEO_EVENT);
        recordWithheld(rawSn, YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT);

        // when — 그 그룹 대표코드에 매핑된 라벨을 담은 프리셋을 등록한다(실제 API · 실제 커밋)
        createPreset(PRESET_EVENT, mappedLabelId);

        // then — AFTER_COMMIT 리스너가 실제로 발화했고
        assertThat(recorder.afterCommit())
                .as("프리셋 저장 커밋 뒤 재개 트리거가 도달해야 한다 — 여기가 비면 사슬의 앞부분이 끊긴 것이다")
                .containsExactly(PRESET_EVENT);

        // then — 재개 러너가 그 영상으로 오토라벨 묶음 재진입을 호출한다(비동기 경계 — 발생을 기다린다)
        verify(orchestrator, timeout(5_000)).process(eq(rawSn), any());

        // then — 재진입 범위는 오토라벨 묶음이다. 앞선 외부 시계열 위탁이 다시 켜지면 이중 위탁이 된다.
        verify(orchestrator).process(eq(rawSn), org.mockito.ArgumentMatchers.argThat(toggles ->
                Boolean.TRUE.equals(toggles.get(BatchStage.YOLO.name()))
                        && !Boolean.TRUE.equals(toggles.get(BatchStage.VLM.name()))));
    }

    @Test
    @DisplayName("이미_완료된_영상은_프리셋을_다시_저장해도_재진입하지_않는다_재촉발_멱등")
    void completedVideoIsNotResumedAgain() throws Exception {
        // given — 한 번 재개돼 완주한 영상(보류 기록은 append-only 라 그대로 남아 있다)
        long rawSn = insertVideo(VIDEO_EVENT);
        recordWithheld(rawSn, YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT);
        long presetId = createPreset(PRESET_EVENT, mappedLabelId);
        verify(orchestrator, timeout(5_000)).process(eq(rawSn), any());
        jdbc.update("UPDATE ls_data_raw SET data_stts_cd = 'COMPLETED' WHERE raw_sn = ?", rawSn);
        reset(orchestrator);
        when(orchestrator.process(anyLong(), any())).thenReturn(BatchStage.COMPLETED);

        // when — 같은 프리셋을 다시 저장한다(수정)
        updatePreset(presetId, PRESET_EVENT, mappedLabelId);

        // then — 촉발 자체는 다시 일어났다. 여기가 1이면 아래 부재 단언이 공허하다.
        assertThat(recorder.afterCommit()).hasSize(2);

        // then — 그런데 재진입은 하지 않는다(멱등). 부재이므로 유계 대기로 판정한다.
        verify(orchestrator, after(500).never()).process(eq(rawSn), any());
    }

    @Test
    @DisplayName("오토라벨_제외_선언으로_건너뛴_영상은_프리셋을_저장해도_되살아나지_않는다")
    void autolabelExcludedVideoIsNeverResumed() throws Exception {
        // given — 같은 이벤트 그룹에 보류 영상 하나와 「제외 선언」으로 건너뛴 영상 하나
        long withheldRawSn = insertVideo(VIDEO_EVENT);
        recordWithheld(withheldRawSn, YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT);
        long excludedRawSn = insertVideo(VIDEO_EVENT);
        recordWithheld(excludedRawSn, YoloAutolabelStep.SKIP_REASON_AUTOLABEL_EXCLUDED);

        // when
        createPreset(PRESET_EVENT, mappedLabelId);

        // then — 보류분은 재개되고
        verify(orchestrator, timeout(5_000)).process(eq(withheldRawSn), any());
        // then — 제외 선언분은 재개되지 않는다. 사람이 일부러 뺀 영상이 프리셋 수정마다 되살아나면
        //        그 선언이 조용히 뒤집힌다.
        verify(orchestrator, after(500).never()).process(eq(excludedRawSn), any());
    }

    @Test
    @DisplayName("프리셋_삭제는_재개를_촉발하지_않는다_방향_가드")
    void deleteDoesNotTriggerResume() throws Exception {
        // given — 보류 영상이 없는 별개 이벤트 유형의 프리셋(등록 시점에 촉발 1회)
        long presetId = createPreset(LONE_EVENT, mappedLabelId);
        assertThat(recorder.afterCommit()).containsExactly(LONE_EVENT);

        // when — 삭제한다
        mockMvc.perform(delete("/v1/manage/presets/" + presetId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        // then — 촉발이 늘지 않는다. 프리셋이 사라지면 오히려 보류 조건이 성립하므로, 발행하면
        //        재개 러너가 돌았다가 스스로 다시 보류하는 헛일이 된다.
        //        (삭제 응답이 돌아온 시점에는 AFTER_COMMIT 리스너가 이미 실행됐을 것이므로 대기가 없다.)
        //        ⚠ 여기서 「오케스트레이터가 안 불렸다」로 단언하지 않는다 — 이 시험 클래스가 정리하지
        //        않는 다른 보류 후보가 DB 에 남아 있으면 그쪽이 불려 오탐이 되고, 등록 시점의 비동기
        //        재개와 시간이 겹쳐 흔들린다. 삭제 방향의 정확한 계측기는 촉발 기록 그 자체다.
        assertThat(recorder.afterCommit()).containsExactly(LONE_EVENT);
    }

    // ---------- 픽스처 ----------

    private long insertVideo(String eventTypeCd) {
        String clipId = CLIP_PREFIX + "-" + System.nanoTime();
        return jdbc.queryForObject(
                "INSERT INTO ls_data_raw (vms_clip_id, evnt_type_cd, prvc_type_cd, raw_file_path_nm, "
                        + "data_stts_cd, de_ident_yn, src_type, reg_dt) "
                        + "VALUES (?, ?, 'PRVC', '/nas/none.mp4', 'MARKING_READY', 'Y', 'ORIGINAL', "
                        + "CURRENT_TIMESTAMP) RETURNING raw_sn",
                Long.class, clipId, eventTypeCd);
    }

    /** 실제 적재 경로(배치가 쓰는 그 메서드)로 보류·건너뜀 감사 행을 남긴다. */
    private void recordWithheld(long rawSn, String reason) {
        batchStatusService.recordStageSkipped(rawSn, BatchStage.YOLO, reason);
    }

    private long createPreset(String eventTypeCd, long... labelIds) throws Exception {
        String json = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(eventTypeCd, labelIds))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).path("data").path("id").asLong();
    }

    private void updatePreset(long presetId, String eventTypeCd, long... labelIds) throws Exception {
        mockMvc.perform(put("/v1/manage/presets/" + presetId)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(eventTypeCd, labelIds))))
                .andExpect(status().isOk());
    }

    private ObjectNode presetBody(String eventTypeCd, long... labelIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventTypeCd", eventTypeCd);
        ArrayNode ids = body.putArray("labelIds");
        for (long id : labelIds) {
            ids.add(id);
        }
        return body;
    }

    // ---------- 재개 트리거 관측기 ----------

    /**
     * 재개 트리거를 <b>실제 커밋 이후 시점</b>에 관측하는 시험 전용 기록기.
     *
     * <p>{@code AutolabelResumeBridge} 와 같은 {@code AFTER_COMMIT} 단계에 붙는다 — 여기가 비면
     * 「이벤트가 발행됐는가」가 아니라 <b>「커밋 뒤 리스너에 도달했는가」</b> 가 거짓이라는 뜻이다.
     * {@code @Transactional} 시험에서는 이 목록이 영원히 비어 있다(그것이 이 시험의 존재 이유다).
     */
    static class PresetEventRecorder {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(PresetLabelsChangedEvent event) {
            events.add(event.evntTypeCd());
        }

        List<String> afterCommit() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        PresetEventRecorder presetEventRecorder() {
            return new PresetEventRecorder();
        }
    }
}
