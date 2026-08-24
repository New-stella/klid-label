package kr.co.cudo.authoring.batch.vlm;

import kr.co.cudo.authoring.batch.step.VlmSubmitOutcomeRecorder;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H1 — VLM 미결 회수의 <b>ACK 창 / 콜백 창 분리</b> 회귀 가드 (Testcontainers PostgreSQL).
 *
 * <h3>막는 회귀 (원래 결함)</h3>
 * <p>구 구현은 ACK 수신 사실을 원장에 남기지 않아 스위퍼가 "수락 응답조차 못 받은 건"과 "수락됐고
 * 결과 콜백만 남은 건"을 구분하지 못했다. VLM describe 콜백은 영상 길이에 따라 수십 분이 걸리므로,
 * 정상 진행 중인 위탁이 ACK 임계(기본 30분)에 걸려 회수·재개되고 <b>같은 비식별 영상이 외부 VLM
 * 벤더로 최대 3회(회수 예산) 재전송</b>됐다.
 *
 * <h3>왜 IT 인가</h3>
 * <p>고정 대상이 <b>DB 술어</b>({@code STTS_CD}) 와 그 술어를 만드는 <b>조건부 원자 UPDATE</b>
 * ({@code claimAckReceived}) 다 — mock 으로는 재현되지 않는다. 또한 이 레포에는 {@code @Transactional}
 * 자기호출로 경계가 사라졌는데 ambient tx 때문에 테스트가 GREEN 이던 실사고 이력이 있어, 테스트
 * 클래스에 {@code @Transactional} 을 <b>붙이지 않고</b> 매 단언을 DB 재조회로 수행한다.
 *
 * <h3>시간 의존 제거</h3>
 * <p>"30분이 지나도"는 시계를 기다려 만들지 않는다. 회수 후보 조회는 {@code cutoff}(경과 임계)를
 * 인자로 받으므로, <b>미래 cutoff</b>({@link #ANY_ELAPSED})를 주면 "임의로 오래 경과한 상태"가 즉시
 * 성립한다. 따라서 판별자는 오직 원장 <b>상태</b>가 되고, 테스트에 실시간 대기가 없다.
 *
 * <h3>RED 실증</h3>
 * <ul>
 *   <li>{@code VlmSubmitOutcomeRecorder.onAccepted} 의 {@code ledger.recordAckReceived(requestId)} 를
 *       제거하면 → {@link #ackReceivedRowIsNotAnAckWindowCandidate} 가 RED (행이 ISSUED 로 남아
 *       ACK 창 후보로 다시 잡힌다 = 중복 위탁 재발).</li>
 *   <li>스위퍼를 구 형태(ISSUED 단일 패스)로 되돌리면 → 콜백 창 후보 조회
 *       ({@link #ackReceivedRowIsReclaimedOnlyByCallbackWindow})의 근거가 사라진다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class VlmSubmitAckWindowIT {

    /**
     * "임의의 시간이 경과했다"를 대신하는 미래 cutoff — 실시간 대기 없이 임계 경과를 성립시킨다.
     * 이 값을 쓰면 후보 여부를 가르는 것은 경과 시간이 아니라 <b>원장 상태</b> 하나가 된다.
     */
    private static final LocalDateTime ANY_ELAPSED = LocalDateTime.now().plusYears(1);
    /** 공유 Testcontainers PG 에 다른 테스트가 남긴 VLM 행이 섞여도 우리 키가 잘리지 않도록 넉넉히 잡는다. */
    private static final int LARGE_LIMIT = 1000;

    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private LsWebhookIdempotencyRepository idempotencyRepository;
    @Autowired private VlmSubmitReclaimTxService reclaimTxService;
    @Autowired private VlmSubmitOutcomeRecorder outcomeRecorder;
    @Autowired private VideoRepository videoRepository;

    private Long seedVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "VLMACK-" + UUID.randomUUID(), "CCTV-VLMACK", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/vlmack.mp4", LocalDateTime.now(), 30));
        return raw.getRawSn();
    }

    /** 위탁 개시 — 상관키를 ISSUED 로 선커밋한다({@code VlmTimeseriesStep} 과 동일 호출). */
    private String issue(Long rawSn) {
        String requestId = "VLMACK-" + UUID.randomUUID();
        ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);
        return requestId;
    }

    private List<String> ackWindowCandidateKeys() {
        return reclaimTxService.findCandidates(ANY_ELAPSED, LARGE_LIMIT).stream()
                .map(VlmSubmitReclaimTxService.Candidate::idmpKey)
                .toList();
    }

    private List<String> callbackWindowCandidateKeys() {
        return reclaimTxService.findAcceptedCandidates(ANY_ELAPSED, LARGE_LIMIT).stream()
                .map(VlmSubmitReclaimTxService.Candidate::idmpKey)
                .toList();
    }

    /** 추가 질문 창구 위탁 개시 — 채널만 다르고 나머지는 같다. */
    private String issueSub(Long rawSn) {
        String requestId = "VLMACK-SUB-" + UUID.randomUUID();
        ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM_SUB, null, rawSn);
        return requestId;
    }

    // ───────────────────────── ⓪ 두 창구 모두 회수 대상이다 (★CO-005 회귀 가드) ─────────────────────────

    @Test
    @DisplayName("★추가질문_채널의_미결도_ACK창_회수후보에_포함된다")
    void subChannelIssuedRowIsAlsoAnAckWindowCandidate() {
        // given — 위탁 창구가 둘로 늘었다. 스위퍼가 한 채널만 훑으면 나머지 채널의 미결 행이
        //   영구히 남고, 그 영상은 미결 판정에 걸려 <b>재위탁 자체가 막힌다</b>.
        //   서비스 스텁이 아니라 실제 리포지토리 질의(`chnlCd in :channels`)로 확인한다.
        Long rawSn = seedVideo();
        String descKey = issue(rawSn);
        String subKey = issueSub(rawSn);

        // when
        List<String> candidates = ackWindowCandidateKeys();

        // then
        assertThat(candidates).contains(descKey, subKey);
    }

    @Test
    @DisplayName("★추가질문_채널의_미결도_콜백창_회수후보에_포함된다")
    void subChannelAcceptedRowIsAlsoACallbackWindowCandidate() {
        // given — ACK 는 받았는데 결과 콜백이 오지 않는 축도 두 창구 대칭이어야 한다.
        Long rawSn = seedVideo();
        String descKey = issue(rawSn);
        String subKey = issueSub(rawSn);
        ledger.recordAckReceived(descKey);
        ledger.recordAckReceived(subKey);

        // when
        List<String> candidates = callbackWindowCandidateKeys();

        // then
        assertThat(candidates).contains(descKey, subKey);
    }

    // ───────────────────────── ① ACK 를 받은 행은 ACK 창이 건드리지 않는다 (★HIGH 회귀 가드) ─────────────────────────

    @Test
    @DisplayName("ACK를_받은_위탁은_ACK창_임계가_아무리_지나도_회수후보가_아니다_중복위탁_차단")
    void ackReceivedRowIsNotAnAckWindowCandidate() {
        // given — 위탁 개시(ISSUED). 이 시점에는 ACK 창의 정당한 후보다(대조군).
        Long rawSn = seedVideo();
        String requestId = issue(rawSn);
        assertThat(ackWindowCandidateKeys())
                .as("ACK 를 못 받은 행은 ACK 창 후보여야 한다(대조군 — 쿼리 자체가 도는지 확인)")
                .contains(requestId);

        // when — 벤더가 요청을 수락했고 완료 핸들러가 ACK 를 관측한다(전용 풀 스레드와 동일 경로).
        outcomeRecorder.onAccepted(rawSn, requestId, new VlmTimeseriesResponse(requestId, "accepted"));

        // then — 원장이 ACCEPTED 로 전이돼 ACK 창 후보에서 빠진다.
        //   ★ recordAckReceived 배선을 제거하면 여기서 RED — 진행 중인 정상 위탁을 30분 스윕이 뺏어
        //     같은 비식별 영상을 외부 VLM 벤더로 최대 3회 재전송하던 H1 이 그대로 재발한다.
        assertThat(idempotencyRepository.findById(requestId).orElseThrow().getSttsCd())
                .isEqualTo(LsWebhookIdempotency.STATE_ACCEPTED);
        assertThat(ackWindowCandidateKeys())
                .as("ACK 수신 행이 ACK 창 후보로 남으면 중복 위탁이 재발한다")
                .doesNotContain(requestId);
    }

    @Test
    @DisplayName("ACK를_받은_행은_ACK창_원자클레임에도_걸리지_않는다_fail_safe_재판정")
    void ackReceivedRowIsNotClaimableByAckWindow() {
        // given
        Long rawSn = seedVideo();
        String requestId = issue(rawSn);
        outcomeRecorder.onAccepted(rawSn, requestId, new VlmTimeseriesResponse(requestId, "accepted"));

        // when — 후보 조회와 클레임 사이에 ACK 가 도착한 경우와 동형(stale 후보로 클레임 시도).
        boolean claimed = reclaimTxService.claim(requestId, ANY_ELAPSED);

        // then — 클레임 술어에도 STTS_CD='ISSUED' 가 실려 있어 0행 no-op 이다(2중 방어).
        assertThat(claimed).isFalse();
        assertThat(idempotencyRepository.findById(requestId).orElseThrow().getSttsCd())
                .isEqualTo(LsWebhookIdempotency.STATE_ACCEPTED);
    }

    // ───────────────────────── ② 콜백 창은 ACK 수신 건을 회수한다 (무한 대기 금지) ─────────────────────────

    @Test
    @DisplayName("ACK후_콜백이_끝내_오지_않으면_콜백창에서_회수된다_무한대기_금지")
    void ackReceivedRowIsReclaimedOnlyByCallbackWindow() {
        // given — ACK 는 받았고 결과 콜백만 남은 상태.
        Long rawSn = seedVideo();
        String requestId = issue(rawSn);
        outcomeRecorder.onAccepted(rawSn, requestId, new VlmTimeseriesResponse(requestId, "accepted"));

        // then — 콜백 창(별도 패스)의 후보다. 이 패스가 없으면 "수락됐는데 결과가 영영 안 오는" 건이
        //        어느 창에도 잡히지 않아 시계열 메타가 무증상 영구 결손된다.
        assertThat(callbackWindowCandidateKeys()).contains(requestId);

        // when — 콜백 창 원자 클레임.
        boolean claimed = reclaimTxService.claimAccepted(requestId, ANY_ELAPSED);

        // then — 회수 표식은 ACK 창과 동일한 FAILED 라 회수 예산이 두 창을 합산해 무한 재위탁을 막는다.
        assertThat(claimed).isTrue();
        assertThat(idempotencyRepository.findById(requestId).orElseThrow().getSttsCd())
                .isEqualTo(LsWebhookIdempotency.STATE_FAILED);
        assertThat(callbackWindowCandidateKeys()).doesNotContain(requestId);
    }

    @Test
    @DisplayName("동시_콜백창_클레임은_한쪽만_성공한다_2노드_ActiveActive_이중회수_차단")
    void concurrentCallbackWindowClaimSucceedsOnce() {
        // given
        Long rawSn = seedVideo();
        String requestId = issue(rawSn);
        outcomeRecorder.onAccepted(rawSn, requestId, new VlmTimeseriesResponse(requestId, "accepted"));

        // when — 두 노드가 같은 후보를 집는 상황(조건부 UPDATE 의 원자성 검증).
        boolean first = reclaimTxService.claimAccepted(requestId, ANY_ELAPSED);
        boolean second = reclaimTxService.claimAccepted(requestId, ANY_ELAPSED);

        // then — 정확히 1회만 성립한다.
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    // ───────────────────────── ③ ACK 조차 못 받은 행은 기존대로 ACK 창이 회수 ─────────────────────────

    @Test
    @DisplayName("ACK를_못받은_행은_ACK창에서_회수된다_기존동작_유지")
    void noAckRowIsReclaimedByAckWindow() {
        // given — 노드 사망·벤더 무응답 등으로 어떤 신호도 오지 않은 건.
        Long rawSn = seedVideo();
        String requestId = issue(rawSn);

        // then — ACK 창 후보이고 콜백 창 후보는 아니다(창이 서로 침범하지 않는다).
        assertThat(ackWindowCandidateKeys()).contains(requestId);
        assertThat(callbackWindowCandidateKeys()).doesNotContain(requestId);

        // when
        boolean claimed = reclaimTxService.claim(requestId, ANY_ELAPSED);

        // then
        assertThat(claimed).isTrue();
        assertThat(idempotencyRepository.findById(requestId).orElseThrow().getSttsCd())
                .isEqualTo(LsWebhookIdempotency.STATE_FAILED);
    }

    @Test
    @DisplayName("ACK_전이는_이미_종결된_원장을_되살리지_않는다_부활금지")
    void ackDoesNotResurrectSettledLedger() {
        // given — ACK 미수신으로 이미 회수(FAILED)된 원장.
        Long rawSn = seedVideo();
        String requestId = issue(rawSn);
        assertThat(reclaimTxService.claim(requestId, ANY_ELAPSED)).isTrue();

        // when — 뒤늦게 ACK 가 도착한다.
        outcomeRecorder.onAccepted(rawSn, requestId, new VlmTimeseriesResponse(requestId, "accepted"));

        // then — ISSUED 조건부 UPDATE 라 0행 no-op. 회수 예산 판정(FAILED 행 수)이 무너지지 않는다.
        assertThat(idempotencyRepository.findById(requestId).orElseThrow().getSttsCd())
                .isEqualTo(LsWebhookIdempotency.STATE_FAILED);
        assertThat(callbackWindowCandidateKeys()).doesNotContain(requestId);
        assertThat(ackWindowCandidateKeys()).doesNotContain(requestId);
    }
}
