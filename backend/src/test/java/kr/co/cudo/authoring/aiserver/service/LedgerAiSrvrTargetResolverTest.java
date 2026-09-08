package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.client.AiWorkload;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 추론 목적지를 <b>장비 원장</b>에서 고른다 — 구 단일 설정값 축의 대체. [@design ADR-057]
 *
 * <p>여기서 고정하는 것은 셋이다 — ①원장에서 고른 장비의 주소를 돌려준다 ②용도 축이 그대로
 * 옮겨간다 ③<b>후보 0 거부</b>가 {@code empty} 로 낮춰지지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class LedgerAiSrvrTargetResolverTest {

    @Mock private AiSrvrSelector selector;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 10, 0);

    @Test
    @DisplayName("★추론_목적지를_원장에서_고른_장비_주소로_돌려준다")
    void 추론_목적지를_원장에서_고른_장비_주소로_돌려준다() {
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-02", "http://gpu2.internal:9300")));

        Optional<String> addr = new LedgerAiSrvrTargetResolver(selector)
                .resolveAddress(AiWorkload.BATCH);

        assertThat(addr).contains("http://gpu2.internal:9300");
    }

    @Test
    @DisplayName("★용도_축이_그대로_옮겨간다_화면_요청은_화면_슬롯의_부하만_본다")
    void 용도_축이_그대로_옮겨간다() {
        // 두 용도는 장비 안에서 실행이 격리돼 있어, 합쳐 보면 배치가 밀린 장비를 화면 요청이
        // 피할 이유가 없는데도 피하게 된다.
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.INTERACTIVE))
                .willReturn(Optional.of(node("gpu-01", "http://gpu1.internal:9300")));

        assertThat(new LedgerAiSrvrTargetResolver(selector).resolveAddress(AiWorkload.INTERACTIVE))
                .contains("http://gpu1.internal:9300");
    }

    @Test
    @DisplayName("★후보가_0이면_거부가_그대로_올라온다_empty로_낮추지_않는다")
    void 후보가_0이면_거부가_그대로_올라온다() {
        // empty 로 낮추면 호출이 배포 기본 주소로 조용히 나가 실제 장애가 감춰진다.
        willThrow(new NonRetryableExternalException("쓸 수 있는 장비가 없습니다."))
                .given(selector).select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);

        assertThatThrownBy(() -> new LedgerAiSrvrTargetResolver(selector)
                .resolveAddress(AiWorkload.BATCH))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    @Test
    @DisplayName("조회_실패로_고르지_못하면_막지_않고_비워_돌려준다")
    void 조회_실패로_고르지_못하면_막지_않고_비워_돌려준다() {
        // 저장소 순단이 정상 추론을 전량 죽이지 않게 하는 축 — 분산만 포기한다.
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.empty());

        assertThat(new LedgerAiSrvrTargetResolver(selector).resolveAddress(AiWorkload.BATCH))
                .isEmpty();
    }

    private static LsAiSrvr node(String srvrId, String addr) {
        return LsAiSrvr.register(srvrId, null, addr, LsAiSrvr.SrvrType.INFERENCE, NOW);
    }
}
