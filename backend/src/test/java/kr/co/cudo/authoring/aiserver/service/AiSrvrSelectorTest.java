package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrAltmntRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.PinnedTarget;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 선택기 — <b>무엇을 보고 고르는가</b>를 고정한다. [@design ADR-057]
 *
 * <p>여기서 고정하는 것은 "고르기는 한다"가 아니라 잘못 고르면 실제로 손해가 나는 네 가지다:
 * <ol>
 *   <li>보는 부하는 <b>그 요청 자신의 용도</b>의 것뿐이다(합쳐 보면 일괄 처리가 밀린 장비를 화면
 *       요청이 피할 이유 없이 피한다).</li>
 *   <li>평활 표본이 없으면 <b>원장의 마지막 관측값</b>으로 떨어진다(0으로 지어내면 재기동 직후마다
 *       바쁜 장비가 가장 한가한 장비로 보여 요청을 통째로 빨아들인다).</li>
 *   <li>시계열 축의 부하 원천은 <b>우리 위탁 원장</b>이다(그 축은 폴러가 관측하지 않는다).</li>
 *   <li>영상 고정은 <b>이 축의 성질이 아니다</b>(고정하면 죽은 장비에 묶인 영상이 영영 못 옮겨간다).</li>
 *   <li><b>원장 식별자 형식</b>을 어긴 장비는 후보에서 빠지고, 그 판정은 <b>고를 때마다</b> 새로 한다 —
 *       원장은 런타임에 바뀐다. [@design ADR-062]</li>
 *   <li>★★<b>쓸 수 있는 후보가 0이면 사유를 가리지 않고 거부</b>다 — 형식 위반이든, 보낼 수 없는
 *       주소든, 주소 정책 탈락이든, <b>상태점검 실패로 그 유형이 전부 이용불가</b>든, <b>그 유형의 행이
 *       0건</b>이든 같다. 어느 주소로도 폴백하지 않는다. ⚠ 구 동작은 「형식 위반이 하나라도 섞였을 때만
 *       거부」였고, 그 좁힘이 <b>두 장비가 함께 죽은 실제 장애를 폴백으로 감췄다</b>(2026-09-07 현장).
 *       [@design AC-1093] [@design AC-1092]</li>
 *   <li>{@code empty} 는 <b>「원장을 못 읽었다」</b>일 때만 남는다(조회 실패 — 분산만 포기). 「후보가 0」과
 *       「못 읽었다」를 섞으면 DB 순단이 전 위탁을 죽이거나 fail-closed 가 조용히 열린다.</li>
 * </ol>
 */
class AiSrvrSelectorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 10, 0);

    private AiSrvrRegistry registry;
    private AiSrvrLoadSmoother smoother;
    private LsAiSrvrUsgRepository usgRepository;
    private LsWebhookIdempotencyRepository submitLedger;
    private AiSrvrSelector selector;

    @BeforeEach
    void setUp() {
        registry = mock(AiSrvrRegistry.class);
        // 평활기는 진짜를 쓴다 — peek/smooth 의 계약(표본 없으면 empty)까지 함께 고정된다.
        smoother = new AiSrvrLoadSmoother(3);
        usgRepository = mock(LsAiSrvrUsgRepository.class);
        submitLedger = mock(LsWebhookIdempotencyRepository.class);
        // 정책은 <진짜>를 쓴다 — 목으로 대체하면 「같은 객체를 부른다」는 계약이 시험 밖으로 빠진다.
        selector = new AiSrvrSelector(registry, smoother, usgRepository, submitLedger,
                new VlmUrlPolicy());
        lenient().when(usgRepository.findBySrvrIdInAndUsgTypeCd(anyCollection(), any()))
                .thenReturn(List.of());
        lenient().when(submitLedger.countAcceptedBySrvrId(anyCollection())).thenReturn(List.of());
    }

    private LsAiSrvr node(String id, LsAiSrvr.SrvrType type) {
        return LsAiSrvr.register(id, null, "http://" + id + ":9300", type, NOW);
    }

    private void available(LsAiSrvr... nodes) {
        when(registry.findAvailable()).thenReturn(Arrays.asList(nodes));
    }

    private LsAiSrvrUsg usg(String srvrId, AiSrvrUsageType usage, int running, int queued) {
        LsAiSrvrUsg row = LsAiSrvrUsg.of(srvrId, usage, NOW);
        row.observe(running, queued, NOW);
        return row;
    }

    private void acceptedCounts(Map<String, Long> counts) {
        when(submitLedger.countAcceptedBySrvrId(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return counts.entrySet().stream()
                    .filter(e -> ids.contains(e.getKey()))
                    .map(e -> loadRow(e.getKey(), e.getValue()))
                    .toList();
        });
    }

    private LsWebhookIdempotencyRepository.ServerLoadCount loadRow(String srvrId, long count) {
        return new LsWebhookIdempotencyRepository.ServerLoadCount() {
            @Override
            public String getSrvrId() {
                return srvrId;
            }

            @Override
            public long getLoadCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("장비를_고를_때_그_용도의_부하만_본다")
    void 장비를_고를_때_그_용도의_부하만_본다() {
        // given — a 는 일괄 처리가 밀렸고 b 는 화면 요청이 밀렸다. 합쳐 보면 둘 다 5로 같아진다.
        available(node("a", LsAiSrvr.SrvrType.INFERENCE), node("b", LsAiSrvr.SrvrType.INFERENCE));
        smoother.smooth("a", AiSrvrUsageType.BATCH, 5);
        smoother.smooth("a", AiSrvrUsageType.INTERACTIVE, 0);
        smoother.smooth("b", AiSrvrUsageType.BATCH, 0);
        smoother.smooth("b", AiSrvrUsageType.INTERACTIVE, 5);

        // when / then — 화면 요청은 a 로(그 용도는 비어 있다), 일괄 처리는 b 로 간다.
        assertThat(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.INTERACTIVE))
                .as("화면 요청은 그 용도가 비어 있는 장비로 가야 한다")
                .map(LsAiSrvr::getSrvrId).contains("a");
        assertThat(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .as("일괄 처리는 그 용도가 비어 있는 장비로 가야 한다")
                .map(LsAiSrvr::getSrvrId).contains("b");
    }

    @Test
    @DisplayName("평활_표본이_없으면_원장의_마지막_관측값으로_고른다")
    void 평활_표본이_없으면_원장의_마지막_관측값으로_고른다() {
        // given — 방금 기동한 노드처럼 평활 창이 통째로 비어 있다(틱이 2노드로 갈리는 상황).
        available(node("a", LsAiSrvr.SrvrType.INFERENCE), node("b", LsAiSrvr.SrvrType.INFERENCE));
        when(usgRepository.findBySrvrIdInAndUsgTypeCd(anyCollection(), any()))
                .thenReturn(List.of(
                        usg("a", AiSrvrUsageType.BATCH, 1, 2),   // 실효 3
                        usg("b", AiSrvrUsageType.BATCH, 0, 1))); // 실효 1

        // when
        Optional<LsAiSrvr> chosen = selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);

        // then — 표본이 없다고 0으로 지어내면 둘 다 0이라 식별자 순으로 a 가 뽑힌다(= 바쁜 쪽).
        assertThat(chosen).map(LsAiSrvr::getSrvrId).contains("b");
    }

    @Test
    @DisplayName("평활_표본이_있으면_원장을_다시_묻지_않는다")
    void 평활_표본이_있으면_원장을_다시_묻지_않는다() {
        // given
        available(node("a", LsAiSrvr.SrvrType.INFERENCE), node("b", LsAiSrvr.SrvrType.INFERENCE));
        smoother.smooth("a", AiSrvrUsageType.BATCH, 4);
        smoother.smooth("b", AiSrvrUsageType.BATCH, 1);

        // when
        Optional<LsAiSrvr> chosen = selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);

        // then — 폴백은 표본이 없는 노드에만 쓰인다(선택마다 DB 를 때리지 않는다).
        assertThat(chosen).map(LsAiSrvr::getSrvrId).contains("b");
        verify(usgRepository, never()).findBySrvrIdInAndUsgTypeCd(anyCollection(), any());
    }

    @Test
    @DisplayName("시계열_축은_수락된_위탁이_적은_장비를_고른다")
    void 시계열_축은_수락된_위탁이_적은_장비를_고른다() {
        // given — 이 축은 폴러가 관측하지 않으므로 부하를 아는 자리가 우리 위탁 원장뿐이다.
        available(node("a", LsAiSrvr.SrvrType.TIMESERIES), node("b", LsAiSrvr.SrvrType.TIMESERIES));
        acceptedCounts(Map.of("a", 3L, "b", 1L));

        // when
        Optional<LsAiSrvr> chosen = selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH);

        // then
        assertThat(chosen).map(LsAiSrvr::getSrvrId).contains("b");
        // 시계열 축에서 용도별 관측 원장을 읽으면 그건 언제나 0이라 배분이 첫 장비로 고정된다.
        verify(usgRepository, never()).findBySrvrIdInAndUsgTypeCd(anyCollection(), any());
    }

    @Test
    @DisplayName("축이_다른_장비는_후보에_들지_않는다")
    void 축이_다른_장비는_후보에_들지_않는다() {
        // given — 추론 장비가 아무리 한가해도 시계열 위탁을 받을 수 없다(부하의 성질이 다르다).
        available(node("infer1", LsAiSrvr.SrvrType.INFERENCE), node("ts1", LsAiSrvr.SrvrType.TIMESERIES));
        acceptedCounts(Map.of("ts1", 9L));

        // when / then
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("ts1");
        assertThat(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("infer1");
    }

    /**
     * ★★ <b>그 유형의 행이 0건이어도 거부다</b> — 배포 기본 주소로 폴백하지 않는다.
     * [@design AC-1093] [@design AC-1092]
     *
     * <p>⚠ <b>구 동작 폐기(2026-09-07)</b> — 여기 「가용 장비가 없으면 예외가 아니라 고르지 못했다를
     * 돌려준다」가 있었고 호출자가 <b>배포 기본 주소로 그대로</b> 나갔다. 배포 설정값은 그 유형의 첫 행을
     * 심는 <b>씨앗</b>일 뿐이며, 원장에 행이 없을 때 그 값으로 대신 호출하면 <b>진실원이 둘</b>이 된다.
     * 씨앗값이 없어 아무것도 심기지 않은 상태는 <b>미연동이 정상인 배포</b>라 사용 시점 거부가 맞다.
     *
     * <p>⚠⚠ 「그 유형의 행이 0건이면 폴백」 예외를 만들지 말 것 — 그 구분은 설계에 없다.
     */
    @Test
    @DisplayName("★★그_유형의_행이_0건이어도_폴백하지_않고_거부한다")
    void 그_유형의_행이_0건이어도_거부한다() {
        // given — 시계열 노드를 등록하기 전의 형상(부트스트랩은 추론 노드만 세운다). 형식 위반은 0건이다.
        available(node("infer1", LsAiSrvr.SrvrType.INFERENCE));

        // when / then
        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("empty 를 돌려주면 호출자가 배포 기본 주소로 폴백해 진실원이 둘이 된다")
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * ★★ <b>상태점검 실패로 그 유형이 전부 이용불가가 되어도 같은 거부다</b> — 형식 위반은 0건이다.
     * [@design AC-1093] [@design AC-1094]
     *
     * <p>이것이 <b>2026-09-07 현장 결함의 사용 측면</b>이다. 두 장비가 함께 죽어 전부 이용불가가 되면
     * 형식 위반은 하나도 없으므로, 「형식 위반이 하나라도 걸러졌을 때만 거부」로 좁혀 두면 위탁이
     * <b>조용히 배포 기본 주소로 나가 실제 장애를 감춘다</b>. 거부의 주어는 사유가 아니라
     * <b>「쓸 수 있는 후보가 0」</b>이다.
     *
     * <p>선택기는 가용 장비만 본다({@code registry.findAvailable}) — 전부 이용불가면 그 유형이 목록에서
     * 통째로 사라지므로, 여기서는 그 상태를 「그 유형의 가용 행이 없음」으로 재현한다.
     */
    @Test
    @DisplayName("★★상태점검_실패로_전부_이용불가여도_거부한다 — 형식 위반이 0건이어도 같다")
    void 상태점검_실패로_전부_이용불가여도_거부한다() {
        // given — 식별자는 전부 형식 규약을 지킨다. 다만 그 유형이 가용 목록에 하나도 없다.
        available(node("ts01", LsAiSrvr.SrvrType.INFERENCE), node("ts02", LsAiSrvr.SrvrType.INFERENCE));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("형식 위반을 게이트로 삼으면 이 경로가 폴백으로 새어 실제 장애가 감춰진다")
                .isInstanceOf(NonRetryableExternalException.class);
    }

    @Test
    @DisplayName("부하가_같으면_식별자_순으로_고른다 — 같은 상황에서 같은 답")
    void 부하가_같으면_식별자_순으로_고른다() {
        // given — 조회 순서를 뒤집어도 결과가 흔들리면 안 된다.
        available(node("b", LsAiSrvr.SrvrType.TIMESERIES), node("a", LsAiSrvr.SrvrType.TIMESERIES));
        acceptedCounts(Map.of("a", 2L, "b", 2L));

        // when / then
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("a");
    }

    /**
     * ★ 영상 고정(같은 영상은 늘 같은 장비로)은 <b>축 A(추론)의 성질</b>이다.
     *
     * <p>시계열은 영상 하나에 위탁 한 번이라 고정할 대상이 없고, 고정하면 <b>죽은 장비에 묶인 영상이
     * 영영 다른 장비로 가지 못한다</b>. 두 가지로 고정한다 — (1)선택은 <b>기억을 갖지 않는다</b>
     * (부하가 뒤집히면 곧바로 다른 장비를 고른다) (2)선택 경로 어디에도 <b>배정 표가 배선돼 있지 않다</b>.
     */
    @Test
    @DisplayName("영상_고정은_축B에_적용되지_않는다")
    void 영상_고정은_축B에_적용되지_않는다() throws Exception {
        // given — 첫 선택 뒤 부하가 뒤집힌다.
        available(node("a", LsAiSrvr.SrvrType.TIMESERIES), node("b", LsAiSrvr.SrvrType.TIMESERIES));
        acceptedCounts(Map.of("a", 0L, "b", 5L));
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("a");

        // when — 부하가 뒤집혔다.
        acceptedCounts(Map.of("a", 5L, "b", 0L));

        // then — 직전 선택을 기억하지 않는다.
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("직전 선택을 기억하면 죽은 장비에 묶인 영상이 영영 옮겨가지 못한다")
                .map(LsAiSrvr::getSrvrId).contains("b");

        // and — 배정 표(영상 고정의 저장 자리)가 선택기·위탁 스텝 어디에도 배선돼 있지 않다.
        assertThat(dependsOnAssignmentLedger(AiSrvrSelector.class)).isFalse();
        assertThat(dependsOnAssignmentLedger(VlmTimeseriesStep.class)).isFalse();
    }

    /**
     * ★ <b>보낼 수 없는 장비를 고르면 원장이 거짓말을 한다</b> — 그래서 후보 단계에서 뺀다.
     *
     * <p>원장 CHECK 제약은 주소에 {@code NOT NULL} 만 걸어 <b>빈 문자열이 통과</b>한다. 그 장비가
     * 후보로 나가면 호출자는 그 장비를 위탁 원장에 적은 뒤 <b>배포 기본 주소로</b> 요청을 보낸다 —
     * 오류가 아니라 조용한 어긋남이고, 그 원장은 다음 배분의 입력이라 요청을 받지 않은 장비의 부하가
     * 올라간다(자기강화).
     */
    @Test
    @DisplayName("★보낼_수_없는_주소의_장비는_후보에서_빠진다")
    void 보낼_수_없는_주소의_장비는_후보에서_빠진다() {
        // given — 빈 주소(공백)와 스킴 없는 주소. 둘 다 절대 목적지를 만들 수 없다.
        available(nodeWithAddr("blank", "   "), nodeWithAddr("noscheme", "ts02:9500"),
                node("ok", LsAiSrvr.SrvrType.TIMESERIES));
        // 식별자 순으로는 blank 가 가장 앞이라, 걸러 내지 않으면 그 장비가 뽑힌다.
        acceptedCounts(Map.of("blank", 0L, "noscheme", 0L, "ok", 0L));

        // when
        Optional<LsAiSrvr> chosen = selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH);

        // then
        assertThat(chosen).map(LsAiSrvr::getSrvrId)
                .as("보낼 수 없는 장비를 고르면 원장과 실제 목적지가 갈린다")
                .contains("ok");
    }

    /**
     * ★ 보낼 수 없는 주소만 남아도 <b>거부</b>다 — 형식 위반이 0건이어도 같다. [@design AC-1093]
     *
     * <p>⚠ 구 동작은 여기서 {@code empty} 를 돌려주었고 호출자가 배포 기본 주소로 나갔다. 그러면
     * 「원장에 쓸 수 없는 장비만 있다」는 사실이 아무 데도 드러나지 않는다.
     */
    @Test
    @DisplayName("★보낼_수_있는_장비가_하나도_없으면_폴백하지_않고_거부한다")
    void 보낼_수_있는_장비가_없으면_거부한다() {
        available(nodeWithAddr("blank", ""), nodeWithAddr("noscheme", "ts02:9500"));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * ★ <b>조회 실패로 위탁을 막지 않는다</b> — 분산만 포기한다.
     *
     * <p>노드 분산을 도입하기 전에는 DB 가 흔들려도 위탁은 나갔다. 부하 조회 예외를 그대로 올리면
     * <b>이 기능이 없던 때보다 나빠지는</b> 새 실패 모드(배치 전체 FAILED)가 생긴다. 같은 스텝의 상태
     * 관측이 이미 "조회 실패로 정상 위탁을 막지 않는다"를 명시 보장하는 것과 같은 원칙이다.
     */
    @Test
    @DisplayName("★부하_조회가_실패해도_예외가_아니라_고르지_못했다로_낮춘다")
    void 부하_조회가_실패해도_예외가_아니다() {
        available(node("a", LsAiSrvr.SrvrType.TIMESERIES), node("b", LsAiSrvr.SrvrType.TIMESERIES));
        when(submitLedger.countAcceptedBySrvrId(anyCollection()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("pool exhausted"));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH)).isEmpty();
    }

    @Test
    @DisplayName("★원장_조회가_실패해도_예외가_아니라_고르지_못했다로_낮춘다")
    void 원장_조회가_실패해도_예외가_아니다() {
        when(registry.findAvailable())
                .thenThrow(new org.springframework.dao.QueryTimeoutException("pool exhausted"));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH)).isEmpty();
        assertThat(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.INTERACTIVE)).isEmpty();
    }

    private LsAiSrvr nodeWithAddr(String id, String addr) {
        return LsAiSrvr.register(id, null, addr, LsAiSrvr.SrvrType.TIMESERIES, NOW);
    }

    /**
     * ★ <b>원장에서 고른 목적지도 연동 주소 정책을 거친다</b> — 배포 기본 주소만 거치던 비대칭을 닫는다.
     *
     * <p>여기 쓰인 주소들은 전부 {@code PinnedTarget.canPin} 을 <b>통과</b>한다(절대 URI + host 있음).
     * 즉 앞 단계 필터로는 걸리지 않으며, 정책을 부르지 않으면 그대로 위탁 목적지가 된다 — 그 목적지로
     * 나가는 요청에는 표식이 붙어 자격증명 가드의 호스트 비교까지 면제되므로 <b>배포 기본값보다 느슨한
     * 경로</b>가 열린다.
     *
     * <p>⚠ <b>식별자에 하이픈을 쓰지 말 것</b> — 고정값이 형식({@code ^[a-z0-9]{1,20}$})을 어기면
     * <b>앞선 형식 필터에서 먼저 빠져</b> 이 시험이 검증하려던 주소 정책 축에 도달하지 못한다(그리고
     * 후보가 전부 비어 위탁 거부로 끝난다). 하이픈은 <b>주소</b> 쪽에만 남겨 둔다. [@design ADR-062]
     */
    @Test
    @DisplayName("★연동_주소_정책을_통과하지_못하는_장비는_후보에서_빠진다")
    void 연동_주소_정책을_통과하지_못하는_장비는_후보에서_빠진다() {
        // given — 비허용 스킴 · placeholder 호스트 · 클라우드 메타데이터 대역. 셋 다 canPin 은 통과한다.
        assertThat(PinnedTarget.canPin("ftp://ts-ftp:9500")).isTrue();
        assertThat(PinnedTarget.canPin("http://your-vlm-service:9500")).isTrue();
        assertThat(PinnedTarget.canPin("http://169.254.169.254")).isTrue();
        available(nodeWithAddr("aftp", "ftp://ts-ftp:9500"),
                nodeWithAddr("bplaceholder", "http://your-vlm-service:9500"),
                nodeWithAddr("cimds", "http://169.254.169.254"),
                nodeWithAddr("dok", "http://ts-ok:9500"));
        // 식별자 순으로는 정책 위반 장비들이 앞이라, 거르지 않으면 그중 하나가 뽑힌다.
        acceptedCounts(Map.of("aftp", 0L, "bplaceholder", 0L, "cimds", 0L, "dok", 0L));

        // when / then
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId)
                .as("정책이 거부하는 주소로는 위탁이 나가면 안 된다")
                .contains("dok");
    }

    /**
     * ★ <b>평문 http·사설 대역 장비는 계속 고른다</b> — 이 축이 깨지면 분산이 조용히 멈춘다.
     *
     * <p>실 연동은 <b>노드도 평문 http</b> 다. 정책을 좁히면(구 HTTPS 강제) 여기서 정상 장비가 전부
     * 후보에서 빠져 위탁이 항상 배포 기본 주소 한 곳으로만 나간다 — 오류 없이 이중화만 사라진다.
     */
    @Test
    @DisplayName("★평문_http_와_사설대역_장비는_계속_후보다 — 좁히면 분산이 조용히 멈춘다")
    void 평문_http_와_사설대역_장비는_계속_후보다() {
        available(nodeWithAddr("tsa", "http://10.0.0.11:9500"),
                nodeWithAddr("tsb", "http://192.168.0.12:9500"));
        acceptedCounts(Map.of("tsa", 3L, "tsb", 1L));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("tsb");
    }

    /**
     * ★ <b>정책 판정이 던지는 예외는 「그 장비 제외」이지 「그 자리에서 배치를 죽이는 것」이 아니다</b>.
     *
     * <p>장비 하나의 주소 오타가 정책 예외 그대로 올라가면 <b>후보가 남아 있어도</b> 배치 전체가
     * FAILED 로 마감된다 — 그 성질은 그대로 유지된다(바로 위 시험이 그것을 고정한다).
     *
     * <p>다만 그렇게 걸러 낸 결과 <b>후보가 0이 되면</b> 결과는 「고르지 못했다」가 아니라 <b>거부</b>다.
     * ⚠ 구 동작은 여기서 {@code empty} 를 돌려주어 호출자가 배포 기본 주소로 나갔다. [@design AC-1093]
     */
    @Test
    @DisplayName("★정책이_전부_거부하면_폴백이_아니라_거부다")
    void 정책이_전부_거부하면_거부다() {
        available(nodeWithAddr("x", "ftp://ts-ftp:9500"), nodeWithAddr("y", "http://changeme:9500"));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  원장 식별자 형식 — 기동에서 <선택 시점>으로 옮겨 온 판정 [@design ADR-062] [@design AC-1074]
    // ─────────────────────────────────────────────────────────────────────────────

    /** 형식({@code ^[a-z0-9]{1,20}$})을 어긴 식별자의 장비 — 체크 제약을 우회해 들어온 행을 재현한다. */
    private LsAiSrvr malformed(String srvrId, LsAiSrvr.SrvrType type) {
        return LsAiSrvr.register(srvrId, null, "http://ts-ok:9500", type, NOW);
    }

    /**
     * ★ <b>위반 행만 빠지고 나머지는 정상 사용된다</b> — 「하나가 잘못되면 전부 못 쓴다」가 아니다.
     *
     * <p>이 값은 서킷브레이커 이름·메트릭 라벨로 <b>조립</b>되므로 그 장비를 고르는 순간부터 라벨이
     * 조용히 어긋난다. 고르는 자리에서 빼면 잘못된 식별자가 나갈 길이 구조적으로 사라진다.
     */
    @Test
    @DisplayName("★식별자_형식을_위반한_장비는_후보에서_빠지고_정상_장비로_간다")
    void 식별자_형식을_위반한_장비는_후보에서_빠진다() {
        // given — 부하로는 위반 장비가 더 한가하고 식별자 순으로도 앞이라, 거르지 않으면 그쪽이 뽑힌다.
        available(malformed("KLID-AI-01", LsAiSrvr.SrvrType.TIMESERIES),
                malformed("ts-02", LsAiSrvr.SrvrType.TIMESERIES),
                node("ok", LsAiSrvr.SrvrType.TIMESERIES));
        acceptedCounts(Map.of("KLID-AI-01", 0L, "ts-02", 0L, "ok", 7L));

        // when / then
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("형식을 어긴 식별자로 위탁이 나가면 서킷·메트릭 라벨이 조용히 어긋난다")
                .map(LsAiSrvr::getSrvrId).contains("ok");
    }

    /**
     * ★★ <b>전부 위반이면 폴백이 아니라 거부다</b> — 이 시험이 이 라운드의 핵심이다.
     *
     * <p>여기서 {@code empty} 를 돌려주면 호출자는 「분산을 못 했다」로 읽고 <b>배포 기본 주소로 그대로
     * 위탁</b>한다. 즉 「쓰면 안 되는 장비만 있는 원장」을 만난 요청이 아무 일도 없었다는 듯 나가고,
     * 이 판정이 막으려던 바로 그 일이 벌어진다.
     *
     * <p>이 시험은 <b>거부를 try 블록 안에서 던지는 회귀도 함께 잡는다</b> — 안에서 던지면 조회 실패
     * catch 가 그것을 삼켜 {@code empty} 가 되고, 이 단언이 죽는다.
     */
    @Test
    @DisplayName("★★쓸_수_있는_장비가_하나도_없으면_폴백하지_않고_위탁을_거부한다")
    void 쓸_수_있는_장비가_없으면_위탁을_거부한다() {
        available(malformed("KLID-AI-01", LsAiSrvr.SrvrType.TIMESERIES),
                malformed("ts-02", LsAiSrvr.SrvrType.TIMESERIES));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("empty 를 돌려주면 호출자가 배포 기본 주소로 폴백해 fail-closed 가 조용히 열린다")
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * ★ 사유가 섞여 있어도 <b>후보가 0이면</b> 거부다 — 사유를 세지 않는다. [@design AC-1093]
     *
     * <p>⚠ 구 서술 폐기: 「위반이 하나라도 있었으면 거부」. 그 게이트가 상태점검으로 전부 이용불가가 된
     * 원장을 폴백으로 흘려보냈다. 좁히려면 「그 걸러짐이 없었다면 후보가 남았을까」라는 반사실 판정이
     * 필요한데, 그 답은 필터 순서에 따라 달라져 같은 원장이 경로마다 다르게 판정된다.
     */
    @Test
    @DisplayName("★형식_위반과_보낼_수_없는_주소가_섞여_전부_빠져도_거부한다")
    void 형식_위반이_섞여_전부_빠지면_거부한다() {
        available(malformed("KLID-AI-01", LsAiSrvr.SrvrType.TIMESERIES),
                nodeWithAddr("blank", "   "));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * ★ <b>거부 사유에는 어느 연동인지와 사유 분류만 싣는다</b>(AC-1074).
     *
     * <p>식별자 원문·주소·형식 규칙은 <b>서버 기록에만</b> 남긴다 — 거부 응답이 내부망을 더듬는 수단이
     * 되면 안 된다(CWE-209/497).
     */
    @Test
    @DisplayName("★거부_사유에_식별자_원문과_주소가_실리지_않는다")
    void 거부_사유에_식별자와_주소가_실리지_않는다() {
        available(LsAiSrvr.register("KLID-AI-01", null, "http://10.9.9.9:9500",
                LsAiSrvr.SrvrType.TIMESERIES, NOW));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .isInstanceOf(NonRetryableExternalException.class)
                .hasMessageContaining("외부 시계열 분석 벤더")
                .hasMessageNotContaining("KLID-AI-01")
                .hasMessageNotContaining("10.9.9.9")
                .hasMessageNotContaining("[a-z0-9]");
    }

    /** ★ 추론 축도 같은 규칙이며, 거부 문구는 <b>그 축의 연동 이름</b>을 쓴다. */
    @Test
    @DisplayName("★추론_축도_같은_규칙이고_거부_문구는_그_축의_연동_이름을_쓴다")
    void 추론_축도_같은_규칙이다() {
        available(malformed("KLID-AI-01", LsAiSrvr.SrvrType.INFERENCE));

        assertThatThrownBy(() ->
                selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.INTERACTIVE))
                .isInstanceOf(NonRetryableExternalException.class)
                .hasMessageContaining("AI 추론 서버");
    }

    /**
     * ★★ <b>고를 때마다 다시 판정한다</b> — 기동 시 1회 판정을 캐시하면 안 되는 이유.
     *
     * <p>원장은 운영 화면에서 <b>런타임에</b> 바뀐다(장비 등록·수정). 판정을 들고 있으면 뒤에 등록한
     * 정상 장비가 <b>영영 제외</b>되거나, 이미 고친 장비가 계속 제외된다. 재기동 없이 반영돼야 한다.
     */
    @Test
    @DisplayName("★★기동_뒤_정상_장비를_추가하면_재기동_없이_선택된다 — 판정을 캐시하지 않는다")
    void 나중에_추가한_정상_장비가_재기동_없이_선택된다() {
        // given — 처음에는 위반 행뿐이라 거부된다.
        available(malformed("KLID-AI-01", LsAiSrvr.SrvrType.TIMESERIES));
        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .isInstanceOf(NonRetryableExternalException.class);

        // when — 운영자가 관리 화면에서 정상 장비를 등록했다(원장 스냅샷이 바뀐다).
        available(malformed("KLID-AI-01", LsAiSrvr.SrvrType.TIMESERIES),
                node("ok", LsAiSrvr.SrvrType.TIMESERIES));

        // then — 재기동 없이 곧바로 후보가 된다.
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("판정을 캐시하면 뒤에 등록한 정상 장비가 영영 제외된다")
                .map(LsAiSrvr::getSrvrId).contains("ok");
    }

    /**
     * ★ 반대 방향도 재기동 없이 반영된다 — 정상이던 장비가 <b>어긋난 값으로 수정</b>되면 곧 빠진다.
     *
     * <p>캐시가 있으면 이미 통과한 장비는 계속 통과한다. 그 방향의 누수가 더 위험하다.
     */
    @Test
    @DisplayName("★정상이던_장비가_어긋난_값으로_바뀌면_다음_선택부터_빠진다")
    void 정상이던_장비가_바뀌면_다음_선택부터_빠진다() {
        available(node("ok", LsAiSrvr.SrvrType.TIMESERIES));
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("ok");

        // when — 같은 장비가 형식을 어긴 식별자로 다시 등록됐다.
        available(malformed("OK", LsAiSrvr.SrvrType.TIMESERIES));

        assertThatThrownBy(() -> selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * ★ <b>조회 실패는 거부로 격상되지 않는다</b> — DB 가 흔들렸을 뿐 원장이 잘못된 것이 아니다.
     *
     * <p>이 둘을 같은 실패로 다루면, 커넥션 풀이 마르는 순간 시계열 위탁이 <b>전량 확정 실패</b>로
     * 마감된다(노드 분산 도입 전에는 없던 실패 모드다).
     */
    @Test
    @DisplayName("★조회_실패는_거부가_아니라_고르지_못했다로_남는다")
    void 조회_실패는_거부로_격상되지_않는다() {
        when(registry.findAvailable())
                .thenThrow(new org.springframework.dao.QueryTimeoutException("pool exhausted"));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("DB 순단을 원장 오류로 격상시키면 위탁이 전량 확정 실패로 마감된다")
                .isEmpty();
    }

    private boolean dependsOnAssignmentLedger(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (LsAiSrvrAltmntRepository.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }
}
