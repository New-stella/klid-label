package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>전체 설정 건너뛰기</b>의 자동 표식이 «세운 직후 게이트에 실제로 보이는가» 를 실 DB 로 실증한다.
 * [@design ADR-050]
 *
 * <h2>무엇이 검증 공백이었나</h2>
 * <p>{@code VlmTimeseriesStep.doSubmit} 은 ①{@link VlmDefaultSkipMarker#applyBeforeStage} 로 표식을
 * INSERT 하고 ②<b>바로 다음 줄</b>의 게이트({@link BatchStatusService#isStageManuallySkipped})가 그 행을
 * <b>재조회</b>해 건너뛸지 정한다. 이 배선의 사활은 ②가 ①이 쓴 행을 보느냐 하나에 달렸다 — 못 보면
 * 게이트를 그냥 통과해 <b>외부 벤더로 나간다</b>(고치기 전과 똑같은 상태이면서 테스트만 초록이다).
 *
 * <p>그 축을 지금까지 고정하던 것은 {@code VlmDefaultSkipMarkerTest.markerWriteRunsInItsOwnTransaction}
 * 하나인데, 그것은 <b>리플렉션으로 애노테이션 값만</b> 본다. "{@code REQUIRES_NEW} 가 붙어 있다"와
 * "런타임에 실제로 보인다"는 다른 명제이고, 목 기반 테스트는 이 축을 원리적으로 드러내지 못한다
 * (목에는 트랜잭션이 없다).
 *
 * <h2>왜 이 구성이 「가시성」을 증명하는가</h2>
 * <p>여기서 목으로 세우는 것은 <b>외부 벤더 클라이언트 하나뿐</b>이다. 표식 기록·게이트 조회는 실제 빈과
 * 실제 DB 로 가고, 호출은 {@code @Transactional(readOnly = true, REQUIRES_NEW)} 이 붙은
 * {@link VlmTimeseriesStep#run} 을 <b>프록시 경유</b>로 하므로 그 트랜잭션 경계가 런타임에 그대로 산다.
 * 게이트가 표식을 못 보면 실행은 그 아래로 흘러 벤더를 호출하게 되므로, <b>「벤더 호출 0회」가 곧
 * 가시성의 증거</b>다. 그래서 이 테스트는 위탁이 <b>끝까지 갈 수 있는 상태</b>(비식별 경로 실재)로
 * 준비한다 — 준비를 생략하면 벤더 앞에서 다른 사유로 멈춰 「0회」가 아무것도 증명하지 못한다.
 *
 * <p>스위치를 끈 회귀({@code 스위치가_꺼져_있으면_종전대로_위탁이_시도된다})가 짝으로 필요하다 —
 * 없으면 「항상 건너뛴다」로도 이 테스트를 만족시킬 수 있다.
 *
 * <h2>커밋 시드를 쓰는 이유</h2>
 * <p>클래스에 {@code @Transactional} 을 걸면 시드가 미커밋이라 {@code REQUIRES_NEW} 로 열리는
 * {@code run} 트랜잭션이 그 영상을 <b>보지 못한다</b>. {@link ManualStageSkipIT} 와 같은 관례로
 * 커밋 시드 + {@code @AfterEach} 정리를 쓴다.
 * @design AC-055
 */
@SpringBootTest
@ActiveProfiles("local")
class VlmDefaultSkipMarkerVisibilityIT {

    /** 표식 행 카운트 — 1차 캐시가 아니라 <b>DB 를 직접</b> 본다(가시성 증명의 핵심). */
    private static final String COUNT_MARKERS = """
            SELECT COUNT(*) FROM LS_BATCH_PROC_LOG
             WHERE DATA_RAW_SN = ? AND PROC_STEP_CD = ? AND PROC_STTS_CD = 'SKIPPED'
               AND ERR_CD IN ('MANUAL_SKIP', 'MANUAL_SKIP_CLEARED')
            """;

    @Autowired private VlmTimeseriesStep step;
    @Autowired private BatchStatusService statusService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CacheManager cacheManager;

    /** 유일한 목 — <b>외부 경계</b>. 표식·게이트는 실제 빈과 실 DB 를 쓴다. */
    @MockBean private VlmClient vlmClient;

    private Long rawSn;

    @BeforeEach
    void setUp() {
        rawSn = RawVideoFixture.newRaw(jdbc);
        // 위탁이 벤더까지 도달할 수 있는 상태로 준비한다 — 비식별 경로가 없으면 게이트가 새더라도
        //   그 앞에서 예외로 멈춰 「벤더 0회」가 가시성을 증명하지 못한다.
        seedDeidentSuccess(rawSn);
        // 신호 없는 Mono — 완료 핸들러(비동기 DB 쓰기)가 돌지 않아 정리가 결정적이다.
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.never());
        setSkipSwitch(null, null);
    }

    @AfterEach
    void tearDown() {
        setSkipSwitch(null, null);
        if (rawSn != null) {
            jdbc.update("DELETE FROM LS_WEBHOOK_IDEMPOTENCY WHERE RAW_SN = ?", rawSn);
            RawVideoFixture.deleteRaws(jdbc, rawSn);
            rawSn = null;
        }
    }

    @Test
    @DisplayName("★★전체_건너뛰기_스위치가_켜지면_표식이_실제로_커밋되고_직후_게이트가_그것을_보아_벤더_호출이_0건이다")
    void markerIsCommittedAndVisibleToTheGateThatFollowsIt() {
        // given — 스위치 ON. 이 영상에는 아직 어떤 표식도 없다.
        setSkipSwitch("true", "벤더 미연동");
        assertThat(countMarkers(BatchStageBundle.VLM)).isZero();

        // when — 프록시 경유 호출이라 run() 의 readOnly REQUIRES_NEW 경계가 런타임에 그대로 산다.
        VlmTimeseriesResponse response = step.run(rawSn);

        // then ① 표식이 <b>DB 에 실제로 남는다</b> — 별도 조회(JdbcTemplate)이므로 어떤 1차 캐시도 개입하지 않는다.
        assertThat(countMarkers(BatchStageBundle.VLM))
                .as("표식이 자기 트랜잭션 없이 readOnly 경계에 참여하면 이 자리에 1 이 남지 않는다"
                        + "(그 형상은 read-only 커넥션이 INSERT 를 거부한다)")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT ERR_MSG_CN FROM LS_BATCH_PROC_LOG
                         WHERE DATA_RAW_SN = ? AND ERR_CD = 'MANUAL_SKIP'
                         ORDER BY BATCH_PROC_LOG_SN DESC LIMIT 1
                        """, String.class, rawSn))
                .startsWith(ManualStageSkip.DEFAULT_SKIP_REASON_PREFIX)
                .contains("벤더 미연동");

        // then ② 그 표식을 <b>직후 게이트가 보았다</b> — 못 봤다면 실행이 아래로 흘러 벤더를 불렀을 것이다.
        //   즉 이 단언이 곧 가시성의 증거다.
        verify(vlmClient, never()).submitTimeseries(any());
        assertThat(response.status()).isEqualTo("skipped");

        // then ③ 선커밋(상관키)도 일어나지 않았다 — 게이트가 그보다 앞에서 끊었다는 뜻.
        assertThat(countLedgerRows()).isZero();

        // then ④ 표식은 판정 축에도 반영된다(다른 묶음은 무관).
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isTrue();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isFalse();
    }

    @Test
    @DisplayName("★스위치가_꺼져_있으면_종전대로_위탁이_시도된다_표식도_서지_않는다")
    void switchOffKeepsSubmitting() {
        // given — 설정 행 자체가 없는 것이 기본 상태이며 그때는 «꺼짐»이다.
        assertThat(countMarkers(BatchStageBundle.VLM)).isZero();

        // when
        VlmTimeseriesResponse response = step.run(rawSn);

        // then — 「항상 건너뛴다」로 앞 테스트를 만족시키는 구현을 배제한다.
        verify(vlmClient, times(1)).submitTimeseries(any(VlmTimeseriesRequest.class));
        assertThat(response.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        assertThat(countMarkers(BatchStageBundle.VLM)).isZero();
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isFalse();
    }

    @Test
    @DisplayName("★스위치가_켜져_있어도_사람의_건너뜀_표식을_덮지_않는다_행은_한_건_그대로다")
    void doesNotOverwriteHumanSkipMarker() {
        // given — 사람이 이미 건너뜀을 눌러 둔 영상 + 스위치 ON
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "운영자 판단", "1");
        setSkipSwitch("true", "벤더 미연동");

        // when
        step.run(rawSn);

        // then — 자동 표식이 덧붙지 않는다(멱등). 사람의 사유가 그대로 남는다.
        assertThat(countMarkers(BatchStageBundle.VLM)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT ERR_MSG_CN FROM LS_BATCH_PROC_LOG
                         WHERE DATA_RAW_SN = ? ORDER BY BATCH_PROC_LOG_SN DESC LIMIT 1
                        """, String.class, rawSn))
                .startsWith(ManualStageSkip.REASON_PREFIX);
        verify(vlmClient, never()).submitTimeseries(any());
    }

    @Test
    @DisplayName("★★스위치가_켜져_있어도_사람이_되살린_영상을_다시_건너뛰지_않는다")
    void doesNotOverwriteHumanClearMarker() {
        // given — 사람이 건너뜀을 <b>해제</b>해 되살려 둔 영상 + 스위치 ON.
        //   자동 표식이 해제를 덮으면 그 영상은 조용히 다시 건너뛰어진다(되살린 조작이 무효화된다).
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordManualStageSkipCleared(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");
        setSkipSwitch("true", "벤더 미연동");

        // when
        step.run(rawSn);

        // then — 표식 행은 둘(건너뜀 + 해제) 그대로이고 자동 표식이 덧붙지 않았다.
        assertThat(countMarkers(BatchStageBundle.VLM)).isEqualTo(2);
        assertThat(statusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isFalse();
        // 되살린 결정이 살아 있으므로 위탁은 그대로 나간다.
        verify(vlmClient, times(1)).submitTimeseries(any(VlmTimeseriesRequest.class));
    }

    // ------------------------------------------------------------------ 지원

    private int countMarkers(BatchStageBundle bundle) {
        Integer n = jdbc.queryForObject(COUNT_MARKERS, Integer.class, rawSn, bundle.name());
        return n == null ? 0 : n;
    }

    private int countLedgerRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_WEBHOOK_IDEMPOTENCY WHERE RAW_SN = ? AND CHNL_CD = ?",
                Integer.class, rawSn, LsWebhookIdempotency.CHANNEL_VLM);
        return n == null ? 0 : n;
    }

    /**
     * 전체 건너뛰기 스위치를 DB 에 직접 심고 {@code sysconfig} 캐시를 비운다.
     *
     * <p>{@code SystemConfigService.update} 를 쓰지 않는 이유: 그 경로는 REVIEWER 토큰과 「사유가 이미
     * 저장돼 있어야 한다」는 짝 검증을 요구해 <b>준비 절차가 검증 대상보다 커진다</b>. 여기서 고정하려는
     * 것은 설정 저장 경로가 아니라 <b>표식의 가시성</b>이다.
     *
     * @param value {@code null} 이면 행을 지운다(= 설정 행이 없는 기본 상태 = 꺼짐)
     */
    private void setSkipSwitch(String value, String reason) {
        upsertConfig(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT, value, "BOOLEAN");
        upsertConfig(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON, reason, "STRING");
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_SYSCONFIG);
        if (cache != null) {
            cache.clear();
        }
    }

    private void upsertConfig(String key, String value, String type) {
        if (value == null) {
            jdbc.update("DELETE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY = ?", key);
            return;
        }
        jdbc.update("""
                INSERT INTO LS_SYSTEM_CONFIG (STNG_KEY, STNG_VALUE, STNG_TYPE_CD, EXPLN, MDFR_ID)
                VALUES (?, ?, ?, '통합시험 시드', 'TEST')
                ON CONFLICT (STNG_KEY) DO UPDATE SET STNG_VALUE = EXCLUDED.STNG_VALUE
                """, key, value, type);
    }

    /** 비식별 성공 이력 1행 — {@code media.path} 조달처. 없으면 위탁이 벤더 앞에서 멈춘다. */
    private void seedDeidentSuccess(long raw) {
        jdbc.update("""
                INSERT INTO LS_DEIDENT_PROC_LOG
                    (DATA_RAW_SN, ORGNL_FILE_PATH_NM, DE_IDNTF_FILE_PATH_NM, PROC_STTS_CD, REQ_DT, REG_DT)
                VALUES (?, ?, ?, 'SUCCEEDED', ?, ?)
                """, raw, "/nas-storage/raw/fixture-" + raw + ".mp4",
                "/nas-storage/videos/" + raw + "/deidentified.mp4",
                LocalDateTime.now(), LocalDateTime.now());
    }
}
