package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 원장 캐시 검증. [@design ADR-057]
 *
 * <p>노드 선택은 <b>배정마다</b> 원장을 본다. 캐시가 없으면 그 조회가 그대로 DB 왕복이 되므로,
 * 폴링 주기와 같은 짧은 TTL(5초)로 눌러 둔다. 5초 낡은 목록으로 배정해도 문제가 없는 이유는
 * 배정이 자주 일어나지 않고, 실제 호출 실패는 서킷브레이커가 즉시 잡기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class AiSrvrRegistryTest {

    @Mock
    private LsAiSrvrRepository repository;

    private AtomicLong nanos;
    private AiSrvrRegistry registry;

    @BeforeEach
    void setUp() {
        nanos = new AtomicLong(0L);
        registry = new AiSrvrRegistry(repository, nanos::get);
    }

    @Test
    @DisplayName("티티엘_안에서는_원장을_다시_읽지_않는다")
    void 티티엘_안에서는_원장을_다시_읽지_않는다() {
        // given
        given(repository.findAll()).willReturn(List.of(node("gpu01")));

        // when — TTL(5초) 이내에 두 번 조회
        registry.findAll();
        nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        registry.findAll();

        // then — DB 는 한 번만 읽는다
        verify(repository, times(1)).findAll();
    }

    @Test
    @DisplayName("티티엘이_지나면_원장을_다시_읽는다")
    void 티티엘이_지나면_원장을_다시_읽는다() {
        // given
        given(repository.findAll()).willReturn(List.of(node("gpu01")));

        // when
        registry.findAll();
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        registry.findAll();

        // then
        verify(repository, times(2)).findAll();
    }

    @Test
    @DisplayName("무효화하면_티티엘_안이라도_다시_읽는다")
    void 무효화하면_티티엘_안이라도_다시_읽는다() {
        // given — 관리자가 노드를 바꾸면 즉시 반영돼야 한다
        given(repository.findAll()).willReturn(List.of(node("gpu01")));

        // when
        registry.findAll();
        registry.invalidate();
        registry.findAll();

        // then
        verify(repository, times(2)).findAll();
    }

    @Test
    @DisplayName("가용_노드만_추리는_조회는_캐시를_공유한다")
    void 가용_노드만_추리는_조회는_캐시를_공유한다() {
        // given — 가용/정비중이 섞인 원장
        LsAiSrvr available = node("gpu01");
        LsAiSrvr draining = node("gpu02");
        ReflectionTestUtils.setField(draining, "srvrSttsCd", AiSrvrStatus.DRAINING);
        given(repository.findAll()).willReturn(List.of(available, draining));

        // when
        List<LsAiSrvr> found = registry.findAvailable();

        // then — 상태별 별도 쿼리를 쏘지 않고 같은 스냅샷에서 거른다
        assertThat(found).extracting(LsAiSrvr::getSrvrId).containsExactly("gpu01");
        verify(repository, times(1)).findAll();
    }

    @Test
    @DisplayName("캐시된_목록은_바깥에서_바꿀_수_없다")
    void 캐시된_목록은_바깥에서_바꿀_수_없다() {
        // given
        given(repository.findAll()).willReturn(List.of(node("gpu01")));

        // when · then — 호출측이 수정하면 다음 호출자가 오염된 목록을 본다
        List<LsAiSrvr> found = registry.findAll();
        assertThat(found).isUnmodifiable();
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }
}
