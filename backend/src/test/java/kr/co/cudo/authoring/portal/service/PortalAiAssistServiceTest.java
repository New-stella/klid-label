package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiCallCancellationRegistry;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelRequest;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.AiFrameAccess;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import kr.co.cudo.authoring.sysconfig.service.AiDefaultsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 포털 AI 보조 서비스 — 추론 본체 재사용(내부 인가 경로 미사용) · 자동 추적 교차 영상 선판정 · 취소 소유자 키.
 *
 * @design API-254, API-255, API-257, API-258
 */
class PortalAiAssistServiceTest {

    private static final TokenClaims ALICE =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    private PortalWorkTargetResolver resolver;
    private AutolabelOnlineService autolabel;
    private Sam2SegmentService segment;
    private YoloTrackService track;
    private AiCallCancellationRegistry registry;
    private PortalAiAssistService service;

    @BeforeEach
    void setUp() {
        resolver = mock(PortalWorkTargetResolver.class);
        autolabel = mock(AutolabelOnlineService.class);
        segment = mock(Sam2SegmentService.class);
        track = mock(YoloTrackService.class);
        registry = mock(AiCallCancellationRegistry.class);
        service = new PortalAiAssistService(
                new PortalAiFrameAccessFactory(resolver, mock(PortalLabelService.class), mock(PortalUploadService.class)),
                autolabel, segment, track, mock(AiDefaultsService.class), registry);
    }

    private void judged(long srcSn, long rawSn) {
        LsDataSrc f = LsDataSrc.create(rawSn, 0L, "/x/0.png", LocalDateTime.now());
        setField(f, "srcSn", srcSn);
        when(resolver.resolveFrame(eq(srcSn), eq("alice"))).thenReturn(new PortalWorkTargetResolver.FrameTarget(
                new PortalWorkTargetResolver.Target(rawSn, srcSn, PortalWorkTargetResolver.Origin.PORTAL_UPLOAD), f));
    }

    @Test
    @DisplayName("★AI_탐지는_포털_입력_경계로_본체를_부르고_내부_인가_진입점은_부르지_않는다")
    void autolabelUsesPortalAccess() {
        judged(1L, 100L);
        when(autolabel.autolabelWithAccess(any(), eq(1L), eq(ALICE), any(), any(), any(), any()))
                .thenReturn(new AutolabelOnlineService.AutolabelOutcome(
                        new AutolabelResponse(1L, 0, List.of()), false, null));

        service.autolabel(1L, new AutolabelRequest(List.of("person"), AutolabelShape.POLYGON, 0.4, 2.0), ALICE);

        ArgumentCaptor<AiFrameAccess> cap = ArgumentCaptor.forClass(AiFrameAccess.class);
        verify(autolabel).autolabelWithAccess(cap.capture(), eq(1L), eq(ALICE),
                eq(List.of("person")), eq(AutolabelShape.POLYGON), eq(0.4), eq(2.0));
        assertThat(cap.getValue()).isInstanceOf(PortalAiFrameAccess.class);
        verify(autolabel, never()).autolabel(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AI_분할은_포털_입력_경계로_본체를_부르고_내부_진입점은_부르지_않는다")
    void segmentUsesPortalAccess() {
        Sam2SegmentRequest req = new Sam2SegmentRequest(1L, List.of(List.of(1.0, 1.0)), null, null);
        when(segment.segmentWithAccess(any(), eq(req))).thenReturn(Sam2SegmentResponse.empty());

        service.segment(req, ALICE);

        ArgumentCaptor<AiFrameAccess> cap = ArgumentCaptor.forClass(AiFrameAccess.class);
        verify(segment).segmentWithAccess(cap.capture(), eq(req));
        assertThat(cap.getValue()).isInstanceOf(PortalAiFrameAccess.class);
        verify(segment, never()).segment(any(), any());
    }

    @Test
    @DisplayName("★자동_추적_후속_프레임이_다른_영상이면_본체_호출_전에_400")
    void crossVideoTrackRejectedBeforeInference() {
        judged(1L, 100L);
        judged(2L, 100L);
        judged(3L, 200L);

        assertThatThrownBy(() -> service.track(new YoloTrackRequest(1L, List.of(2L, 3L)), ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verifyNoInteractions(track);
    }

    @Test
    @DisplayName("자동_추적_후속_프레임_인가_실패는_교차_영상_판정보다_먼저다_403")
    void nextFrameForbiddenBeforeCrossVideo() {
        judged(1L, 100L);
        when(resolver.resolveFrame(eq(9L), eq("alice")))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인 작업 대상이 아니거나 존재하지 않습니다."));

        assertThatThrownBy(() -> service.track(new YoloTrackRequest(1L, List.of(9L)), ALICE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(track);
    }

    @Test
    @DisplayName("같은_영상이면_같은_입력_경계로_본체에_넘기고_인가_조회는_프레임당_한_번이다")
    void sameVideoTrackDelegatesWithMemoizedAccess() {
        judged(1L, 100L);
        judged(2L, 100L);
        YoloTrackRequest req = new YoloTrackRequest(1L, List.of(2L));
        when(track.trackWithAccess(any(), eq(req))).thenAnswer(inv -> {
            AiFrameAccess access = inv.getArgument(0);
            // 본체가 하는 재인가를 흉내 낸다 — 입력 경계가 메모하므로 조회가 늘지 않아야 한다.
            access.authorize(1L);
            access.authorize(2L);
            return new YoloTrackResponseDto(List.of(), false, null);
        });

        service.track(req, ALICE);

        verify(resolver, times(1)).resolveFrame(1L, "alice");
        verify(resolver, times(1)).resolveFrame(2L, "alice");
    }

    @Test
    @DisplayName("★취소는_채널을_포함한_소유자_키로_등록소에_묻는다")
    void cancelUsesChannelQualifiedOwnerKey() {
        when(registry.cancel(anyString(), anyString())).thenReturn(true);

        assertThat(service.cancel("req-1", ALICE).cancelled()).isTrue();
        verify(registry).cancel("req-1", AiCallCancellationRegistry.ownerKey(ALICE));
        assertThat(AiCallCancellationRegistry.ownerKey(ALICE)).isEqualTo("PORTAL:alice");
    }

    @Test
    @DisplayName("★인터셉터가_포털_토큰으로_등록한_요청을_포털_취소가_실제로_끊는다_키_배선_일치")
    void interceptorAndPortalCancelAgreeOnKey() throws Exception {
        AiCallCancellationRegistry real = new AiCallCancellationRegistry();
        PortalAiAssistService wired = new PortalAiAssistService(
                new PortalAiFrameAccessFactory(resolver, mock(PortalLabelService.class), mock(PortalUploadService.class)),
                autolabel, segment, track, mock(AiDefaultsService.class), real);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(ALICE, null, List.of()));
        var interceptor = new kr.co.cudo.authoring.common.client.AiCallCancellationInterceptor(real);
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/v1/portal/frames/1/autolabel");
        request.addHeader(kr.co.cudo.authoring.common.client.AiCallCancellationInterceptor.REQUEST_ID_HEADER, "req-p");
        interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), new Object());
        try {
            TokenClaims internalSameSub =
                    new TokenClaims("alice", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
            assertThat(wired.cancel("req-p", internalSameSub).cancelled())
                    .as("같은 subject 라도 내부 채널은 포털 요청을 끊지 못한다").isFalse();
            assertThat(wired.cancel("req-p", ALICE).cancelled()).isTrue();
        } finally {
            interceptor.afterCompletion(request, new org.springframework.mock.web.MockHttpServletResponse(), new Object(), null);
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException(name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
