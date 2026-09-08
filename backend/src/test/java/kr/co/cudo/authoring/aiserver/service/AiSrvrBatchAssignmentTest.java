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
        given(altmntRepository.findByRawSn(7L)).willReturn(Optional.of(altmnt(7L, "gpu-03")));
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

    @Test
    @DisplayName("조회_실패로_고르지_못하면_배정하지_않고_장비_미상으로_진행한다")
    void 조회_실패로_고르지_못하면_장비_미상으로_진행한다() {
        given(selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH))
                .willReturn(Optional.empty());

        assertThat(assignment().resolveAddress(7L)).isNull();
        verify(altmntRepository, never()).assignIfAbsent(any(), anyString(), any());
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
