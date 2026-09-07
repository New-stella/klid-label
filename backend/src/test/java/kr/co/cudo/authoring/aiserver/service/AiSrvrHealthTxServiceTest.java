package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 상태점검 결과의 <b>상태 전이</b> 검증. [@design ADR-057]
 *
 * <h3>연속 판정을 DB 에 두는 이유</h3>
 * <p>연속 실패·연속 성공을 폴링 노드의 메모리에 두면 <b>2노드 Active-Active 에서 표본이 갈린다</b> —
 * Quartz 클러스터링은 틱마다 <b>어느 한 노드</b>에서만 발화시키므로, 어느 쪽이 발화할지 모르는 채
 * 각자 반쪽 카운터를 들게 된다. 재기동으로도 사라진다. 그래서 두 카운터는 원장 컬럼이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiSrvrHealthTxServiceTest {

    @Mock private LsAiSrvrRepository repository;
    @Mock private LsAiSrvrUsgRepository usgRepository;

    private AiSrvrHealthTxService service;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 4, 12, 33);

    @BeforeEach
    void setUp() {
        service = new AiSrvrHealthTxService(repository, usgRepository, 3, 3);
    }

    @Test
    @DisplayName("연속_두번_실패로는_노드를_내리지_않는다")
    void 연속_두번_실패로는_노드를_내리지_않는다() {
        // given — 한 번의 네트워크 흔들림으로 노드를 내리지 않는다
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));

        // when
        service.applyHealth("gpu01", false, NOW);
        service.applyHealth("gpu01", false, NOW);

        // then
        assertThat(node.getChckFailNocs()).isEqualTo(2);
        verify(repository, never()).demoteByHealthCheck(anyString(), anyString());
    }

    @Test
    @DisplayName("연속_세번_실패하면_노드가_이용불가로_바뀐다")
    void 연속_세번_실패하면_노드가_이용불가로_바뀐다() {
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));
        given(repository.demoteByHealthCheck(anyString(), anyString())).willReturn(1);

        for (int i = 0; i < 3; i++) {
            service.applyHealth("gpu01", false, NOW);
        }

        verify(repository).demoteByHealthCheck("gpu01", AiSrvrStatus.UNAVAILABLE.name());
    }

    /**
     * ★★ <b>이 라운드의 핵심</b> — 상태점검 강등은 <b>마지막 가용 노드 보호를 타지 않는다</b>.
     * [@design AC-1093] [@design AC-1091]
     *
     * <p>구 코드는 {@code demoteIfNotLastAvailable} 을 불렀고, 그 쿼리는 가용이 하나뿐이면 강등을
     * 거부한다. 그래서 두 장비가 <b>같은 시각에 둘 다 죽으면</b> 나중 쪽이 「가용」으로 남아
     * <b>화면이 「한 대는 살아 있다」고 거짓을 말한다</b>(현장 실측: gpu01 연속 실패 3회인데 가용).
     *
     * <p>그 보호는 <b>사람의 조작</b>({@code demoteIfNotLastAvailableOfType})에 거는 것이고, 상태점검이
     * 내리는 이용불가는 <b>관측</b>이다. 그래서 두 보호 쿼리 어느 쪽도 부르지 않는 것까지 못 박는다 —
     * 「일관성」을 이유로 다시 배선하면 이 단언이 죽는다.
     */
    @Test
    @DisplayName("★★상태점검_강등은_마지막_가용노드_보호_쿼리를_타지_않는다")
    void 상태점검_강등은_마지막_가용노드_보호_쿼리를_타지_않는다() {
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));
        given(repository.demoteByHealthCheck(anyString(), anyString())).willReturn(1);

        for (int i = 0; i < 3; i++) {
            service.applyHealth("gpu01", false, NOW);
        }

        verify(repository).demoteByHealthCheck("gpu01", AiSrvrStatus.UNAVAILABLE.name());
        verify(repository, never()).demoteIfNotLastAvailable(anyString(), anyString());
        verify(repository, never())
                .demoteIfNotLastAvailableOfType(anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * ★ 강등이 실제로 일어났을 때만 <b>그 유형의 가용이 0이 되었는지</b> 확인한다.
     *
     * <p>운영자가 알아야 할 것은 「한 대가 내려갔다」가 아니라 <b>「이 유형으로 나갈 길이 없어졌다」</b>
     * 다 — 그 순간부터 그 유형의 위탁은 폴백 없이 거부되기 때문이다. [@design AC-1093]
     */
    @Test
    @DisplayName("★강등이_일어나면_그_유형의_가용이_0인지_확인한다")
    void 강등이_일어나면_그_유형의_가용이_0인지_확인한다() {
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));
        given(repository.demoteByHealthCheck(anyString(), anyString())).willReturn(1);
        given(repository.countBySrvrTypeCdAndSrvrSttsCd(any(), any())).willReturn(0L);

        for (int i = 0; i < 3; i++) {
            service.applyHealth("gpu01", false, NOW);
        }

        verify(repository).countBySrvrTypeCdAndSrvrSttsCd(
                LsAiSrvr.SrvrType.INFERENCE, AiSrvrStatus.AVAILABLE);
    }

    /**
     * ★ <b>알림이 실패해도 강등은 유지된다</b> — 알림은 부수 효과이고, 여기서 예외가 올라가면 이미
     * 옳게 내린 상태 전이가 트랜잭션과 함께 롤백된다.
     */
    @Test
    @DisplayName("★가용_수_확인이_실패해도_강등이_롤백되지_않는다")
    void 가용_수_확인이_실패해도_강등이_롤백되지_않는다() {
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));
        given(repository.demoteByHealthCheck(anyString(), anyString())).willReturn(1);
        given(repository.countBySrvrTypeCdAndSrvrSttsCd(any(), any()))
                .willThrow(new org.springframework.dao.QueryTimeoutException("pool exhausted"));

        for (int i = 0; i < 3; i++) {
            service.applyHealth("gpu01", false, NOW);
        }

        verify(repository).demoteByHealthCheck("gpu01", AiSrvrStatus.UNAVAILABLE.name());
    }

    /**
     * 조건부 UPDATE 가 0행을 돌려주는 것은 <b>그 사이 누가 상태를 바꿨다</b>는 뜻이다(정상).
     * 상태를 직접 UPDATE 해 그것을 덮어쓰지 않는다.
     */
    @Test
    @DisplayName("강등이_0행이면_상태를_직접_고쳐_덮어쓰지_않는다")
    void 강등이_0행이면_상태를_직접_고쳐_덮어쓰지_않는다() {
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));
        given(repository.demoteByHealthCheck(anyString(), anyString())).willReturn(0);

        for (int i = 0; i < 3; i++) {
            service.applyHealth("gpu01", false, NOW);
        }

        verify(repository).demoteByHealthCheck("gpu01", AiSrvrStatus.UNAVAILABLE.name());
        verify(repository, never()).promoteIfUnavailable(anyString());
        verify(repository, never()).countBySrvrTypeCdAndSrvrSttsCd(any(), any());
        assertThat(node.getSrvrSttsCd()).isEqualTo(AiSrvrStatus.AVAILABLE);
    }

    @Test
    @DisplayName("실패_사이에_성공이_끼면_연속이_끊긴다")
    void 실패_사이에_성공이_끼면_연속이_끊긴다() {
        LsAiSrvr node = available("gpu01");
        given(repository.findById("gpu01")).willReturn(Optional.of(node));

        service.applyHealth("gpu01", false, NOW);
        service.applyHealth("gpu01", false, NOW);
        service.applyHealth("gpu01", true, NOW);
        service.applyHealth("gpu01", false, NOW);

        assertThat(node.getChckFailNocs()).isEqualTo(1);
        verify(repository, never()).demoteByHealthCheck(anyString(), anyString());
    }

    @Test
    @DisplayName("이용불가_노드가_연속_세번_성공하면_복귀한다")
    void 이용불가_노드가_연속_세번_성공하면_복귀한다() {
        LsAiSrvr node = withStatus("gpu01", AiSrvrStatus.UNAVAILABLE);
        given(repository.findById("gpu01")).willReturn(Optional.of(node));
        given(repository.promoteIfUnavailable(anyString())).willReturn(1);

        for (int i = 0; i < 3; i++) {
            service.applyHealth("gpu01", true, NOW);
        }

        verify(repository).promoteIfUnavailable("gpu01");
    }

    @Test
    @DisplayName("복귀_직전_한번_실패하면_연속_성공이_처음부터_다시_쌓인다")
    void 복귀_직전_한번_실패하면_연속_성공이_처음부터_다시_쌓인다() {
        LsAiSrvr node = withStatus("gpu01", AiSrvrStatus.UNAVAILABLE);
        given(repository.findById("gpu01")).willReturn(Optional.of(node));

        service.applyHealth("gpu01", true, NOW);
        service.applyHealth("gpu01", true, NOW);
        service.applyHealth("gpu01", false, NOW);
        service.applyHealth("gpu01", true, NOW);
        service.applyHealth("gpu01", true, NOW);

        // 연속 2회뿐이라 아직 복귀하지 않는다 — 흔들리는 노드를 되살려 다시 내리는 왕복을 막는다
        verify(repository, never()).promoteIfUnavailable(anyString());
    }

    @Test
    @DisplayName("정비중_노드는_헬스가_실패해도_상태가_바뀌지_않는다")
    void 정비중_노드는_헬스가_실패해도_상태가_바뀌지_않는다() {
        // given — 사람이 의도적으로 세운 상태를 배치가 덮어쓰지 않는다
        LsAiSrvr node = withStatus("gpu01", AiSrvrStatus.DRAINING);
        given(repository.findById("gpu01")).willReturn(Optional.of(node));

        // when
        for (int i = 0; i < 5; i++) {
            service.applyHealth("gpu01", false, NOW);
        }

        // then — 상태는 그대로, 기록(연속 실패·점검 시각)만 남는다
        assertThat(node.getSrvrSttsCd()).isEqualTo(AiSrvrStatus.DRAINING);
        assertThat(node.getChckFailNocs()).isEqualTo(5);
        assertThat(node.getChckDt()).isEqualTo(NOW);
        verify(repository, never()).demoteByHealthCheck(anyString(), anyString());
    }

    @Test
    @DisplayName("정비중_노드는_헬스가_성공해도_자동으로_가용이_되지_않는다")
    void 정비중_노드는_헬스가_성공해도_자동으로_가용이_되지_않는다() {
        LsAiSrvr node = withStatus("gpu01", AiSrvrStatus.DRAINING);
        given(repository.findById("gpu01")).willReturn(Optional.of(node));

        for (int i = 0; i < 5; i++) {
            service.applyHealth("gpu01", true, NOW);
        }

        assertThat(node.getSrvrSttsCd()).isEqualTo(AiSrvrStatus.DRAINING);
        verify(repository, never()).promoteIfUnavailable(anyString());
    }

    @Test
    @DisplayName("원장에서_사라진_노드의_점검_결과는_조용히_버린다")
    void 원장에서_사라진_노드의_점검_결과는_조용히_버린다() {
        given(repository.findById("gone")).willReturn(Optional.empty());

        service.applyHealth("gone", false, NOW);

        verify(repository, never()).demoteByHealthCheck(anyString(), anyString());
    }

    @Test
    @DisplayName("부하는_용도별로_각각_한_행에_담긴다")
    void 부하는_용도별로_각각_한_행에_담긴다() {
        // given — 저장소에 아직 행이 없다
        given(usgRepository.findById(any())).willReturn(Optional.empty());

        // when
        service.applyLoad("gpu01", Map.of(
                AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 12),
                AiSrvrUsageType.INTERACTIVE, new AiSrvrSlotLoad(0, 0)), NOW);

        // then — ★장비마다 값 하나가 아니라 장비 x 용도 조합으로 둔다
        verify(usgRepository, org.mockito.Mockito.times(2)).save(any(LsAiSrvrUsg.class));
    }

    @Test
    @DisplayName("이미_있는_부하행은_새_관측으로_갱신한다")
    void 이미_있는_부하행은_새_관측으로_갱신한다() {
        LsAiSrvrUsg existing = LsAiSrvrUsg.of("gpu01", AiSrvrUsageType.BATCH, NOW.minusMinutes(1));
        given(usgRepository.findById(any())).willReturn(Optional.of(existing));

        service.applyLoad("gpu01", Map.of(AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 12)), NOW);

        assertThat(existing.getPrcsNocs()).isEqualTo(1);
        assertThat(existing.getWtngNocs()).isEqualTo(12);
        assertThat(existing.effectiveLoad()).isEqualTo(13);
        assertThat(existing.getChckDt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("부하를_알_수_없으면_행을_건드리지_않는다")
    void 부하를_알_수_없으면_행을_건드리지_않는다() {
        service.applyLoad("gpu01", Map.of(), NOW);

        verify(usgRepository, never()).save(any(LsAiSrvrUsg.class));
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static LsAiSrvr available(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai-1:9300",
                LsAiSrvr.SrvrType.INFERENCE, NOW);
    }

    private static LsAiSrvr withStatus(String srvrId, AiSrvrStatus status) {
        LsAiSrvr node = available(srvrId);
        ReflectionTestUtils.setField(node, "srvrSttsCd", status);
        return node;
    }
}
