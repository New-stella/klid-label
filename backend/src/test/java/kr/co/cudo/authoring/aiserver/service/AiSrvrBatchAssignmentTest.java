package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrAltmnt;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrAltmntRepository;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 배치 경로의 <b>영상 고정</b>. [@design ADR-057]
 *
 * <p>고정이 빠지면 같은 영상의 프레임이 두 장비로 흩어져 <b>객체 식별자가 어긋난다</b> — 앞단 중계
 * 방식이 기각된 사유가 그것이므로, 분산만 배선하고 고정을 빼면 기각 사유였던 결함을 그대로 들여온다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiSrvrBatchAssignmentTest {

    @Mock private AiSrvrSelector selector;
    @Mock private LsAiSrvrAltmntRepository altmntRepository;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 10, 0);

    private AiSrvrBatchAssignment assignment() {
        return new AiSrvrBatchAssignment(selector, altmntRepository);
    }

    @Test
    @DisplayName("★배치는_영상을_장비에_고정하고_그_사실을_기록한다")
    void 배치는_영상을_장비에_고정하고_그_사실을_기록한다() {
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-01", "http://gpu1:9300")));
        given(altmntRepository.assignIfAbsent(eq(7L), eq("gpu-01"), any()))
                .willReturn(altmnt(7L, "gpu-01"));

        String addr = assignment().resolveAddress(7L);

        assertThat(addr).isEqualTo("http://gpu1:9300");
        verify(altmntRepository).assignIfAbsent(eq(7L), eq("gpu-01"), any());
    }

    @Test
    @DisplayName("★이미_배정된_영상은_방금_고른_장비가_아니라_배정된_장비로_간다")
    void 이미_배정된_영상은_배정된_장비로_간다() {
        // 부하가 기울어 다른 장비가 뽑혀도 고정이 이긴다 — 그러지 않으면 프레임이 흩어진다.
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-02", "http://gpu2:9300")));
        given(altmntRepository.assignIfAbsent(eq(7L), eq("gpu-02"), any()))
                .willReturn(altmnt(7L, "gpu-01"));
        given(selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, "gpu-01"))
                .willReturn(Optional.of(node("gpu-01", "http://gpu1:9300")));

        assertThat(assignment().resolveAddress(7L)).isEqualTo("http://gpu1:9300");
        verify(altmntRepository, never())
                .reassignIfCurrent(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("★고정된_장비를_지금_쓸_수_없으면_재배정하고_기록도_옮긴다")
    void 고정된_장비를_지금_쓸_수_없으면_재배정한다() {
        // 재배정하지 않으면 그 영상은 <영구히> 죽은 장비에 묶인다(영상당 배정 한 건).
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-02", "http://gpu2:9300")));
        given(altmntRepository.assignIfAbsent(eq(7L), eq("gpu-02"), any()))
                .willReturn(altmnt(7L, "gpu-01"));
        given(selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, "gpu-01"))
                .willReturn(Optional.empty());
        given(altmntRepository.reassignIfCurrent(eq(7L), eq("gpu-01"), eq("gpu-02"), any(), anyString()))
                .willReturn(1);

        assertThat(assignment().resolveAddress(7L)).isEqualTo("http://gpu2:9300");

        // ★사유·새 장비·시각 셋이 <원장에> 남아야 한다 — 장비와 시각만 갈아 끼우면 원장만 보고
        //   최초 배정과 재배정을 구분할 수 없다(응용 로그는 노드마다 흩어지고 곧 지워진다).
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(altmntRepository).reassignIfCurrent(
                eq(7L), eq("gpu-01"), eq("gpu-02"), any(), reason.capture());
        assertThat(reason.getValue())
                .as("사유가 비면 원장만 보고 최초 배정과 재배정을 구분할 수 없다")
                .isNotBlank()
                // 어디에서 옮겨왔는지는 갱신이 덮어쓰므로 사유에 담지 않으면 영구히 사라진다.
                .contains("gpu-01")
                // ⚠ 주소·포트는 내부 토폴로지다(CWE-497) — 사유에 실리면 원장을 읽는 모든 창구로 샌다.
                .doesNotContain("http").doesNotContain("9300")
                .hasSizeLessThanOrEqualTo(LsAiSrvrAltmnt.ALTMNT_RSN_MAX_LENGTH);
    }

    @Test
    @DisplayName("재배정_경합에서_지면_이긴_쪽의_결정을_다시_읽는다")
    void 재배정_경합에서_지면_이긴_쪽의_결정을_다시_읽는다() {
        // 두 노드가 같은 영상을 서로 다른 장비로 갈라 보내지 않게 하는 지점이다.
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-02", "http://gpu2:9300")));
        given(altmntRepository.assignIfAbsent(eq(7L), eq("gpu-02"), any()))
                .willReturn(altmnt(7L, "gpu-01"));
        given(selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, "gpu-01"))
                .willReturn(Optional.empty());
        given(altmntRepository.reassignIfCurrent(eq(7L), eq("gpu-01"), eq("gpu-02"), any(), anyString()))
                .willReturn(0);
        // ★첫 조회는 <사전 확인>이라 아직 배정이 없고, 재배정에 진 뒤의 조회에서 이긴 쪽이 보인다.
        //   두 호출을 한 값으로 뭉치면 사전 확인이 곧바로 돌려줘 이 경합 경로를 <아예 타지 않는다>.
        given(altmntRepository.findByRawSn(7L))
                .willReturn(Optional.empty(), Optional.of(altmnt(7L, "gpu-03")));
        given(selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, "gpu-03"))
                .willReturn(Optional.of(node("gpu-03", "http://gpu3:9300")));

        assertThat(assignment().resolveAddress(7L)).isEqualTo("http://gpu3:9300");
    }

    @Test
    @DisplayName("★후보가_0이면_배정도_하지_않고_거부한다_폴백_없음")
    void 후보가_0이면_배정도_하지_않고_거부한다() {
        willThrow(new NonRetryableExternalException("쓸 수 있는 장비가 없습니다."))
                .given(selector).select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);

        assertThatThrownBy(() -> assignment().resolveAddress(7L))
                .isInstanceOf(NonRetryableExternalException.class);
        verify(altmntRepository, never()).assignIfAbsent(any(), anyString(), any());
    }

    /**
     * ★★ 원장을 읽지 못하면 <b>표식</b>을 돌려준다 — {@code null} 이 아니다. [@design ADR-057] [@design AC-1100]
     *
     * <p>⚠ <b>구 동작 폐기(2026-09-08)</b> — {@code null} 을 돌려주고 있었다. 클라이언트는 그것을
     * 「아직 안 정했으니 내가 고르라」로 읽어 <b>프레임마다 다시 고른다</b>. 그러면 한 영상의 프레임이
     * 여러 장비로 흩어져 추적이 <b>프레임 경계에서 조용히 끊긴 채 적재</b>되고, 그 구간에는 배정
     * 기록도 없어 사후에 알아낼 수도 없다. 「정하지 못했다」도 <b>영상 단위로 한 번</b> 정한 답이어야
     * 한다 — 분산만 포기하고 고정은 지킨다.
     */
    @Test
    @DisplayName("★★조회_실패로_고르지_못하면_배포_기본주소_표식을_돌려준다_프레임마다_재선택_금지")
    void 조회_실패로_고르지_못하면_표식을_돌려준다() {
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.empty());

        assertThat(assignment().resolveAddress(7L))
                .as("null 을 돌려주면 클라이언트가 호출마다 다시 골라 프레임이 흩어진다")
                .isEqualTo(kr.co.cudo.authoring.common.client.PinnedTarget.DEPLOY_DEFAULT_TARGET);
        verify(altmntRepository, never()).assignIfAbsent(any(), anyString(), any());
    }

    /**
     * ★★ <b>고정된 배정을 먼저 확인한다</b> — 그 순서가 곧 사양이다. [@design AC-1100]
     *
     * <p>구 동작은 새 장비 선택을 <b>무조건 먼저</b> 불렀다. 그러면 후보가 0일 때 나는 거부가
     * <b>이미 고정돼 있고 그 장비가 살아 있는 영상까지</b> 함께 막는다 — 살아 있는 정비중 장비에
     * 고정된 영상이 <b>같은 계통의 다른 장비가 내려간 순간</b> 거부되는 것이 그 조합이며, 그때 그
     * 영상은 쓸 수 있는 장비를 갖고 있는데도 처리되지 못한다.
     *
     * <p>「신규 배정만 막고 진행 중인 작업은 끝까지」는 <b>술어를 가르는 것만으로는 지켜지지 않고
     * 순서로도 지켜져야 한다</b>. ⚠ 술어를 다시 합치지 말 것 — 깨진 것은 구조가 아니라 호출 순서였다.
     */
    @Test
    @DisplayName("★★후보가_0이어도_고정된_장비가_살아_있으면_그_장비로_계속_처리된다")
    void 후보가_0이어도_고정된_장비가_살아_있으면_계속_처리된다() {
        // given — 그 계통의 신규 배정 후보가 하나도 없다(정비중은 후보에서 빠진다).
        willThrow(new NonRetryableExternalException("쓸 수 있는 장비가 없습니다."))
                .given(selector).select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);
        // 그런데 이 영상은 <살아 있는> 정비중 장비에 이미 고정돼 있다.
        given(altmntRepository.findByRawSn(7L)).willReturn(Optional.of(altmnt(7L, "gpu-01")));
        given(selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, "gpu-01"))
                .willReturn(Optional.of(node("gpu-01", "http://gpu1:9300")));

        assertThat(assignment().resolveAddress(7L)).isEqualTo("http://gpu1:9300");
        verify(altmntRepository, never()).assignIfAbsent(any(), anyString(), any());
        verify(altmntRepository, never())
                .reassignIfCurrent(any(), anyString(), anyString(), any(), anyString());
    }

    /**
     * ★ 대조 — <b>고정이 없으면</b> 후보 0 거부는 종전대로 난다.
     *
     * <p>「후보 0 거부」와 「고정 유지」는 <b>다른 축</b>이다. 사전 확인이 그 거부를 통째로 삼키면
     * 폴백 없는 거부라는 계약이 조용히 사라진다.
     */
    @Test
    @DisplayName("★대조_고정된_배정이_없으면_후보_0은_종전대로_거부된다")
    void 고정된_배정이_없으면_후보_0은_거부된다() {
        willThrow(new NonRetryableExternalException("쓸 수 있는 장비가 없습니다."))
                .given(selector).select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);
        given(altmntRepository.findByRawSn(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> assignment().resolveAddress(7L))
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * ★ 고정된 장비가 <b>죽어 있으면</b> 사전 확인이 넘어가고 종전 경로가 그대로 돈다.
     *
     * <p>사전 확인이 「죽은 장비도 유지」로 넓어지면 그 영상이 영영 죽은 주소에 묶인다 — 유지 판정은
     * {@code AiSrvrSelector#selectPinned} 가 단독으로 갖고 여기서 다시 쓰지 않는다.
     */
    @Test
    @DisplayName("고정된_장비가_죽어_있으면_사전_확인이_잡지_않고_재배정_경로로_간다")
    void 고정된_장비가_죽어_있으면_재배정_경로로_간다() {
        given(altmntRepository.findByRawSn(7L)).willReturn(Optional.of(altmnt(7L, "gpu-01")));
        given(selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, "gpu-01"))
                .willReturn(Optional.empty());
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-02", "http://gpu2:9300")));
        given(altmntRepository.assignIfAbsent(eq(7L), eq("gpu-02"), any()))
                .willReturn(altmnt(7L, "gpu-01"));
        given(altmntRepository.reassignIfCurrent(eq(7L), eq("gpu-01"), eq("gpu-02"), any(), anyString()))
                .willReturn(1);

        assertThat(assignment().resolveAddress(7L)).isEqualTo("http://gpu2:9300");
    }

    /**
     * ⚠ 사전 확인이 <b>막지 않는다</b> — 원장을 읽지 못한 것을 여기서 예외로 올리면, 그 경우
     * 「분산만 포기하고 배포 기본 주소로」라는 규약이 <b>지름길 때문에</b> 깨진다.
     */
    @Test
    @DisplayName("고정_배정_조회가_실패해도_막지_않고_선택_단계로_넘어간다")
    void 고정_배정_조회가_실패해도_막지_않는다() {
        willThrow(new org.springframework.dao.QueryTimeoutException("pool exhausted"))
                .given(altmntRepository).findByRawSn(7L);
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.empty());

        assertThat(assignment().resolveAddress(7L))
                .isEqualTo(kr.co.cudo.authoring.common.client.PinnedTarget.DEPLOY_DEFAULT_TARGET);
    }

    @Test
    @DisplayName("영상을_모르면_고르기만_하고_기록하지_않는다")
    void 영상을_모르면_고르기만_하고_기록하지_않는다() {
        // 화면 요청처럼 고정할 축이 없는 호출 — 배정 표에 남기면 그 영상이 한 장비에 영구히 묶인다.
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.of(node("gpu-01", "http://gpu1:9300")));

        assertThat(assignment().resolveAddress(null)).isEqualTo("http://gpu1:9300");
        verify(altmntRepository, never()).assignIfAbsent(any(), anyString(), any());
    }

    private static LsAiSrvr node(String srvrId, String addr) {
        return LsAiSrvr.register(srvrId, null, addr, LsAiSrvr.SrvrType.INFERENCE, NOW);
    }

    private static LsAiSrvrAltmnt altmnt(Long rawSn, String srvrId) {
        // 생성자가 protected 라(JPA 전용) 리플렉션으로 만든다 — 배정 행을 만드는 통로는
        // 저장소의 조건부 INSERT 뿐이고, 시험 편의를 위해 공개 생성자를 새로 열지 않는다.
        LsAiSrvrAltmnt row = org.springframework.beans.BeanUtils.instantiateClass(LsAiSrvrAltmnt.class);
        ReflectionTestUtils.setField(row, "rawSn", rawSn);
        ReflectionTestUtils.setField(row, "srvrId", srvrId);
        ReflectionTestUtils.setField(row, "altmntDt", NOW);
        return row;
    }
}
