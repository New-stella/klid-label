package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrAltmntRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
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

    @Test
    @DisplayName("★가용_장비가_없으면_예외가_아니라_고르지_못했다를_돌려준다")
    void 가용_장비가_없으면_고르지_못했다를_돌려준다() {
        // given — 시계열 노드를 등록하기 전의 현재 형상(부트스트랩은 추론 노드만 세운다).
        available(node("infer1", LsAiSrvr.SrvrType.INFERENCE));

        // when / then — 여기서 던지면 「분산을 못 한다」가 「연동이 끊긴다」로 격상된다.
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH)).isEmpty();
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

    @Test
    @DisplayName("★보낼_수_있는_장비가_하나도_없으면_고르지_못했다로_답한다 — 예외가 아니다")
    void 보낼_수_있는_장비가_없으면_고르지_못했다() {
        available(nodeWithAddr("blank", ""), nodeWithAddr("noscheme", "ts02:9500"));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .as("여기서 예외로 만들면 「분산을 못 한다」가 「연동이 끊긴다」로 격상된다")
                .isEmpty();
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
     */
    @Test
    @DisplayName("★연동_주소_정책을_통과하지_못하는_장비는_후보에서_빠진다")
    void 연동_주소_정책을_통과하지_못하는_장비는_후보에서_빠진다() {
        // given — 비허용 스킴 · placeholder 호스트 · 클라우드 메타데이터 대역. 셋 다 canPin 은 통과한다.
        assertThat(PinnedTarget.canPin("ftp://ts-ftp:9500")).isTrue();
        assertThat(PinnedTarget.canPin("http://your-vlm-service:9500")).isTrue();
        assertThat(PinnedTarget.canPin("http://169.254.169.254")).isTrue();
        available(nodeWithAddr("a-ftp", "ftp://ts-ftp:9500"),
                nodeWithAddr("b-placeholder", "http://your-vlm-service:9500"),
                nodeWithAddr("c-imds", "http://169.254.169.254"),
                nodeWithAddr("d-ok", "http://ts-ok:9500"));
        // 식별자 순으로는 정책 위반 장비들이 앞이라, 거르지 않으면 그중 하나가 뽑힌다.
        acceptedCounts(Map.of("a-ftp", 0L, "b-placeholder", 0L, "c-imds", 0L, "d-ok", 0L));

        // when / then
        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId)
                .as("정책이 거부하는 주소로는 위탁이 나가면 안 된다")
                .contains("d-ok");
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
        available(nodeWithAddr("ts-a", "http://10.0.0.11:9500"),
                nodeWithAddr("ts-b", "http://192.168.0.12:9500"));
        acceptedCounts(Map.of("ts-a", 3L, "ts-b", 1L));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .map(LsAiSrvr::getSrvrId).contains("ts-b");
    }

    /**
     * ★ <b>정책 판정이 던지는 예외는 「그 장비 제외」이지 「위탁 실패」가 아니다</b>.
     *
     * <p>장비 하나의 주소 오타가 예외로 올라가면 배치 전체가 FAILED 로 마감된다 — 노드 분산 도입 전에는
     * 없던 실패 모드다. 후보가 하나도 안 남는 극단에서도 결과는 <b>「고르지 못했다」</b> 여야 하고,
     * 그때 호출자는 배포 기본 주소로 그대로 위탁한다.
     */
    @Test
    @DisplayName("★정책이_전부_거부해도_예외가_아니라_고르지_못했다로_답한다")
    void 정책이_전부_거부해도_예외가_아니다() {
        available(nodeWithAddr("x", "ftp://ts-ftp:9500"), nodeWithAddr("y", "http://changeme:9500"));

        assertThat(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH)).isEmpty();
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
