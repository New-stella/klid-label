package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「위탁하지 않는다」 단언 — <b>오버로드 양쪽·창구 양쪽</b>을 한 번에 덮는다.
 *
 * <h3>왜 필요한가 (약한 단언이 실제로 있었다)</h3>
 * <p>{@code verify(vlmClient, never()).submitDescribe(any(), any())} 는 <b>2-인자 오버로드만</b> 본다.
 * 스텝이 1-인자 오버로드로 바뀌거나 새 경로가 그것을 쓰면, 위탁이 <b>실제로 나가는데</b> 이 단언은
 * 그대로 초록이다 — 게이트(신고·미결·중복 위탁 차단)가 통째로 뚫려도 시험이 알려 주지 않는다.
 * 추가 질문 창구({@code submitDescribeSub})에 대한 {@code never()} 는 아예 없었다.
 *
 * <p>같은 함정이 원장 발급 기록에도 있다 — 5-인자 {@code never()} 는 4-인자 호출을 잡지 못한다.
 */
public final class VlmSubmitAssertions {

    private VlmSubmitAssertions() {
    }

    /** 어떤 창구로도, 어떤 오버로드로도 위탁이 나가지 않았다. */
    public static void neverSubmitted(VlmClient vlmClient) {
        verify(vlmClient, never()).submitDescribe(any(VlmTimeseriesRequest.class));
        verify(vlmClient, never()).submitDescribe(any(VlmTimeseriesRequest.class), any());
        verify(vlmClient, never()).submitDescribeSub(any(VlmTimeseriesRequest.class));
        verify(vlmClient, never()).submitDescribeSub(any(VlmTimeseriesRequest.class), any());
    }

    /** 어떤 오버로드로도 상관키가 선커밋되지 않았다(고아 미결 방지). */
    public static void neverIssued(WebhookIdempotencyLedger ledger) {
        verify(ledger, never()).recordIssued(any(), any());
        verify(ledger, never()).recordIssued(any(), any(), any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any(), any());
    }
}
