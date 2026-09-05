package kr.co.cudo.authoring.webhook.idempotency;

import kr.co.cudo.authoring.batch.vlm.VlmSubmitReclaimTxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위탁 원장의 <b>장비 축</b> — DB 강제 검증 (Testcontainers PostgreSQL). [@design ERD-021] [@design ADR-057]
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>여기서 고정하는 것은 전부 <b>쿼리 자체의 성질</b>이다 — 어떤 상태를 세는가, 어떤 행이 회수
 * 후보에 드는가, 두 임계가 각각 판정하는가. 리포지토리를 mock 하면 그 성질은 <b>시험이 스스로
 * 지어낸 답</b>이 되어 아무것도 증명하지 못한다.
 *
 * <h3>고정하는 네 가지</h3>
 * <ol>
 *   <li>고른 장비가 <b>실제로 원장 행에 남는다</b>.</li>
 *   <li>부하로 세는 것은 <b>수락(ACCEPTED)</b>뿐이다 — 발급(ISSUED)을 세면 제출이 몰린 장비를
 *       과대평가해 다음 요청이 반대편으로 쏠린다.</li>
 *   <li>미결 회수는 <b>장비 축을 보지 않는다</b> — 장비 미상 행도, 죽은 장비의 몫도 회수된다.
 *       그 스윕이 시계열 메타의 무증상 영구 결손을 막는 유일한 경로다.</li>
 *   <li>ACK 창과 콜백 창은 <b>각각의 임계</b>로 판정한다(12배 차이라 하나로 덮으면 분석 중인
 *       정상 위탁을 뺏는다).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class WebhookIdempotencySrvrIdIT {

    private static final String KEY_PREFIX = "it-srvrid-";
    private static final String NODE_ALIVE = "tsalive";
    private static final String NODE_DEAD = "tsdead";
    private static final List<String> VLM_CHANNELS =
            List.of(LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.CHANNEL_VLM_SUB);

    @Autowired private LsWebhookIdempotencyRepository repository;
    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private JdbcTemplate jdbc;
    /** 실제 회수 경로 — 클레임은 트랜잭션 경계를 가진 이 빈이 소유한다(자기호출 유실 방지). */
    @Autowired private VlmSubmitReclaimTxService reclaimTxService;

    private Long rawSn;

    @BeforeEach
    void seed() {
        cleanUp();
        rawSn = jdbc.queryForObject(
                "INSERT INTO ls_data_raw (vms_clip_id, prvc_type_cd, raw_file_path_nm, de_ident_yn, "
                        + "src_type, data_stts_cd, reg_dt) "
                        + "VALUES (?, 'ANONY', '/var/raw/srvrid-it.mp4', 'Y', 'CONTROL', 'PENDING', "
                        + "CURRENT_TIMESTAMP) RETURNING raw_sn",
                Long.class, "SRVRID-IT-" + System.nanoTime());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ls_webhook_idempotency WHERE idmp_key LIKE ?", KEY_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE 'SRVRID-IT-%'");
    }

    /** 원장 행을 <b>시각까지 지정해</b> 심는다 — 회수 임계를 대기 없이 재현하기 위해서다. */
    private void insertRow(String suffix, String channel, String status, String srvrId,
                           LocalDateTime modifiedAt) {
        jdbc.update("INSERT INTO ls_webhook_idempotency "
                        + "(idmp_key, chnl_cd, stts_cd, raw_sn, srvr_id, reg_dt, mdfcn_dt) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                KEY_PREFIX + suffix, channel, status, rawSn, srvrId, modifiedAt, modifiedAt);
    }

    private Map<String, Long> acceptedLoads(String... srvrIds) {
        return repository.countAcceptedBySrvrId(List.of(srvrIds)).stream()
                .collect(Collectors.toMap(
                        LsWebhookIdempotencyRepository.ServerLoadCount::getSrvrId,
                        LsWebhookIdempotencyRepository.ServerLoadCount::getLoadCount));
    }

    private List<String> staleIssuedKeys(LocalDateTime cutoff) {
        return repository.findStaleIssued(VLM_CHANNELS, cutoff, PageRequest.of(0, 50)).stream()
                .map(LsWebhookIdempotency::getIdmpKey).toList();
    }

    private List<String> staleAcceptedKeys(LocalDateTime cutoff) {
        return repository.findStaleAccepted(VLM_CHANNELS, cutoff, PageRequest.of(0, 50)).stream()
                .map(LsWebhookIdempotency::getIdmpKey).toList();
    }

    @Test
    @DisplayName("★위탁시_선택한_장비가_원장에_기록된다")
    void 위탁시_선택한_장비가_원장에_기록된다() {
        // when — 위탁 스텝이 선커밋하는 것과 같은 통로.
        ledger.recordIssued(KEY_PREFIX + "record", LsWebhookIdempotency.CHANNEL_VLM, null,
                rawSn, NODE_ALIVE);

        // then
        LsWebhookIdempotency row = repository.findById(KEY_PREFIX + "record").orElseThrow();
        assertThat(row.getSrvrId()).isEqualTo(NODE_ALIVE);
        assertThat(row.getRawSn()).isEqualTo(rawSn);
        assertThat(row.getSttsCd()).isEqualTo(LsWebhookIdempotency.STATE_ISSUED);
    }

    @Test
    @DisplayName("장비를_고르지_못한_위탁은_장비미상으로_남는다 — 값을 지어내지 않는다")
    void 장비를_고르지_못한_위탁은_장비미상으로_남는다() {
        // when
        ledger.recordIssued(KEY_PREFIX + "unknown", LsWebhookIdempotency.CHANNEL_VLM, null, rawSn, null);

        // then
        assertThat(repository.findById(KEY_PREFIX + "unknown").orElseThrow().getSrvrId()).isNull();
    }

    @Test
    @DisplayName("★ISSUED_건수는_장비_부하로_세지_않는다")
    void ISSUED_건수는_장비_부하로_세지_않는다() {
        // given — 같은 장비에 상태가 다른 행을 섞어 둔다.
        LocalDateTime now = LocalDateTime.now();
        insertRow("i1", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ISSUED, NODE_ALIVE, now);
        insertRow("i2", LsWebhookIdempotency.CHANNEL_VLM_SUB, LsWebhookIdempotency.STATE_ISSUED, NODE_ALIVE, now);
        insertRow("a1", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ACCEPTED, NODE_ALIVE, now);
        insertRow("a2", LsWebhookIdempotency.CHANNEL_VLM_SUB, LsWebhookIdempotency.STATE_ACCEPTED, NODE_ALIVE, now);
        insertRow("p1", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_PROCESSED, NODE_ALIVE, now);
        insertRow("f1", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_FAILED, NODE_ALIVE, now);

        // when
        Map<String, Long> loads = acceptedLoads(NODE_ALIVE, NODE_DEAD);

        // then — 수락된 2건만 부하다. 발급까지 세면 4가 되어 방금 제출이 몰린 장비를 과대평가한다.
        assertThat(loads.get(NODE_ALIVE))
                .as("수락(ACCEPTED)만 부하로 센다 — 발급·처리완료·회수표식은 부하가 아니다")
                .isEqualTo(2L);
        // 행이 없는 장비는 결과에 없다(호출측이 0으로 채운다 — 「모름」으로 다루면 새 장비가 후보에서 빠진다).
        assertThat(loads).doesNotContainKey(NODE_DEAD);
    }

    @Test
    @DisplayName("★기존_장비미상_행도_회수_대상이다")
    void 기존_장비미상_행도_회수_대상이다() {
        // given — 이 컬럼이 생기기 전에 적재된 행(장비 미상)이 ACK 창을 넘겼다.
        LocalDateTime old = LocalDateTime.now().minusHours(2);
        insertRow("legacy", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ISSUED, null, old);

        // when
        List<String> candidates = staleIssuedKeys(LocalDateTime.now().minusMinutes(30));

        // then — 회수 조회에 장비 조건을 걸면 이 행이 영영 회수되지 않는다(무증상 영구 결손).
        assertThat(candidates).contains(KEY_PREFIX + "legacy");
        // 그리고 실제로 클레임까지 된다(조회만 되고 클레임에서 막히면 회수가 성립하지 않는다).
        assertThat(reclaimTxService.claim(KEY_PREFIX + "legacy",
                LocalDateTime.now().minusMinutes(30))).isTrue();
    }

    @Test
    @DisplayName("★장비가_죽어도_그_장비로_나간_미결분이_회수된다")
    void 장비가_죽어도_그_장비로_나간_미결분이_회수된다() {
        // given — 죽은 장비로 나간 미결 위탁(ACK 창 경과). 살아 있는 장비의 최신 위탁도 함께 둔다.
        LocalDateTime old = LocalDateTime.now().minusHours(2);
        insertRow("dead", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ISSUED,
                NODE_DEAD, old);
        insertRow("fresh", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ISSUED,
                NODE_ALIVE, LocalDateTime.now());

        // when
        List<String> candidates = staleIssuedKeys(LocalDateTime.now().minusMinutes(30));

        // then — 죽은 장비의 몫일수록 더 회수돼야 한다. 진행 중인 정상 위탁은 뺏지 않는다.
        assertThat(candidates).contains(KEY_PREFIX + "dead");
        assertThat(candidates).doesNotContain(KEY_PREFIX + "fresh");
        assertThat(reclaimTxService.claim(KEY_PREFIX + "dead",
                LocalDateTime.now().minusMinutes(30))).isTrue();
    }

    @Test
    @DisplayName("★ACK창과_콜백창은_각각의_임계로_판정된다")
    void ACK창과_콜백창은_각각의_임계로_판정된다() {
        // given — 둘 다 40분 전에 마지막으로 갱신됐다. 성질만 다르다.
        LocalDateTime fortyMinutesAgo = LocalDateTime.now().minusMinutes(40);
        insertRow("ack", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ISSUED,
                NODE_ALIVE, fortyMinutesAgo);
        insertRow("cb", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ACCEPTED,
                NODE_ALIVE, fortyMinutesAgo);
        // 그리고 콜백 창까지 넘긴 수락 건.
        insertRow("cbold", LsWebhookIdempotency.CHANNEL_VLM, LsWebhookIdempotency.STATE_ACCEPTED,
                NODE_ALIVE, LocalDateTime.now().minusHours(7));

        LocalDateTime ackCutoff = LocalDateTime.now().minusMinutes(30);      // 기본 ACK 창
        LocalDateTime callbackCutoff = LocalDateTime.now().minusMinutes(360); // 기본 콜백 창

        // when / then — ACK 창은 수락 못 받은 건만 집는다.
        assertThat(staleIssuedKeys(ackCutoff))
                .contains(KEY_PREFIX + "ack")
                .doesNotContain(KEY_PREFIX + "cb", KEY_PREFIX + "cbold");

        // 수락된 건은 ACK 창(30분)을 넘겨도 회수 대상이 아니다 — 벤더 분석이 수십 분 걸린다.
        assertThat(staleAcceptedKeys(callbackCutoff))
                .as("40분 지난 수락 건은 아직 분석 중일 수 있다 — 뺏으면 같은 영상이 중복 위탁된다")
                .doesNotContain(KEY_PREFIX + "cb")
                .contains(KEY_PREFIX + "cbold");

        // 두 임계를 하나로 덮으면(콜백 창에 ACK 임계를 쓰면) 정상 진행 건이 회수된다.
        assertThat(staleAcceptedKeys(ackCutoff)).contains(KEY_PREFIX + "cb");
    }
}
