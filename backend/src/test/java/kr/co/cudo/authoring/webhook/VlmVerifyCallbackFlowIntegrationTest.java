package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VLM verify 위탁↔콜백 상관관계 통합 검증 — 결함1/2 폐쇄 실증 (Phase 2).
 *
 * <p>위탁부(VlmTimeseriesStep)가 {@code ledger.recordIssued(request_id, CHANNEL_VLM, null, rawSn)}
 * 로 매핑을 등록하는 배선을 그대로 재현한 뒤, 벤더 규격의 verify 콜백을 실제 엔드포인트
 * {@code POST /v1/vlm/callback}(무서명) 로 전송하여 필터 → 컨트롤러 → 서비스 경로가
 * request_id 로 rawSn 을 역조회해 {@code LS_DATA_META} 에 적재하는지 단언한다.
 *
 * <h3>검증(HIGH 폐쇄)</h3>
 * <ul>
 *   <li>등록됨 → 콜백 200 + applied=true + META 적재(결함1/2 닫힘: 401 아님).</li>
 *   <li>미등록 request_id → 401(무단 콜백 주입 차단) — 등록 배선이 없으면 100% 이 경로가 됨을 실증.</li>
 * </ul>
 *
 * <p>공유 Testcontainers PG 격리: 시드 영상은 {@code VLMCB-} 고유 clipId, 단언은 시드 rawSn 으로만 좁힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VlmVerifyCallbackFlowIntegrationTest {

    private static final String CALLBACK_PATH = HmacWebhookFilter.PATH_VLM;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private WebhookIdempotencyLedger ledger;

    private Long seedVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "VLMCB-" + UUID.randomUUID(), "CCTV-VLMCB", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/vlmcb.mp4", LocalDateTime.now(), 30));
        return raw.getRawSn();
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
    @DisplayName("위탁_등록_후_동일_request_id_verify_콜백이_rawSn_역조회로_LS_DATA_META_적재된다")
    void issuedThenCallbackResolvesRawSnAndPersistsMeta() throws Exception {
        // given — 위탁부 배선 재현: (request_id → CHANNEL_VLM, rawSn) 매핑 등록
        Long rawSn = seedVideo();
        String requestId = "REQ-" + UUID.randomUUID().toString().replace("-", "");
        ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);
        // 역조회 사전 확인
        assertThat(ledger.resolveRawSn(requestId)).contains(rawSn);

        // when — verify 콜백 전송(무서명 규격)
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedCallbackJson(requestId)))
                // then — 401 아님, applied=true
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.requestId").value(requestId));

        // META 적재 확인 — verify 규격 2키(description + accuracy)
        List<LsDataMeta> metas = metaRepository.findByRawSnAndMetaKeyIn(rawSn,
                Set.of(VlmResultService.META_KEY_DESCRIPTION, VlmResultService.META_KEY_ACCURACY));
        assertThat(metas)
                .extracting(LsDataMeta::getMetaKey)
                .containsExactlyInAnyOrder(
                        VlmResultService.META_KEY_DESCRIPTION, VlmResultService.META_KEY_ACCURACY);
    }

    @Test
    @DisplayName("미등록_request_id_콜백은_401로_거부된다_무단_주입_차단")
    void unissuedRequestIdRejected401() throws Exception {
        String unknown = "REQ-" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedCallbackJson(unknown)))
                .andExpect(status().isUnauthorized());
    }
}
