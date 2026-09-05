package kr.co.cudo.authoring.portal.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.listener.MarkingBatchBridge;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingOutcome;
import kr.co.cudo.authoring.marking.service.MarkingPrecheckReader;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.event.PortalMarkingCompletedEvent;
import kr.co.cudo.authoring.portal.listener.PortalFrameExtractBridge;
import kr.co.cudo.authoring.portal.service.PortalUploadMarkingChannel;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★★ <b>포털 마킹이 관제 배치를 깨우지 않는다</b> — 되돌림 실증.
 *
 * <h3>왜 이 시험이 가장 중요한가</h3>
 * <p>관제 채널에서 마킹 완료는 잔여 배치(외부 시계열 위탁 → 프레임 추출 → 오토라벨링)를 깨운다.
 * 포털에는 위탁도 오토라벨링도 <b>없다</b>. 가드만 열고 이 배선을 보지 않으면 <b>포털 사용자의 개인
 * 영상이 외부 벤더로 나간다</b> — 그리고 그 실패는 오류로 드러나지 않는다.
 *
 * <h3>격리를 조건문이 아니라 <b>타입</b>이 보장한다</h3>
 * <p>같은 이벤트를 발행하고 소비자에서 채널로 거르는 안은 채택하지 않았다 — 거름망을 한 번
 * 빠뜨리면 그대로 새기 때문이다. 지금은 채널이 <b>서로 다른 타입</b>의 완료 이벤트를 내고, 각
 * 브리지가 자기 타입만 구독한다. 이 시험은 그 두 축을 함께 고정한다.
 */
class PortalMarkingBatchIsolationTest {

    private static final long ULD_SN = 501L;
    private static final String OWNER = "portal-user-1";

    private LsMarkingRepository markingRepository;
    private PortalUploadAssetRepository assetRepository;
    private ApplicationEventPublisher eventPublisher;
    private MarkingService markingService;
    private PortalUploadMarkingChannel channel;

    @BeforeEach
    void setUp() {
        markingRepository = mock(LsMarkingRepository.class);
        assetRepository = mock(PortalUploadAssetRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        MarkingPrecheckReader precheckReader = mock(MarkingPrecheckReader.class);

        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), "./storage/raw/portal",
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        channel = new PortalUploadMarkingChannel(assetRepository, props);

        markingService = new MarkingService(
                markingRepository, null, null, new ObjectMapper(), eventPublisher,
                null, precheckReader, null, null, null);

        PortalUploadAsset asset = new PortalUploadAsset(ULD_SN, OWNER,
                PortalUploadLedger.TYPE_VIDEO, "v.mp4", "/p/v.mp4", 1024L, "video/mp4",
                PortalUploadLedger.STATUS_UPLOADED, 60.0, 30.0, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.of(asset));
        when(assetRepository.findPortalAsset(ULD_SN)).thenReturn(Optional.of(asset));
        when(assetRepository.transitionToProcessing(ULD_SN)).thenReturn(1);
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TokenClaims owner() {
        return new TokenClaims(OWNER, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("★★포털_마킹_저장은_포털_전용_이벤트만_발행한다 — 관제_배치_이벤트_0건")
    void portalMarkingPublishesOnlyPortalEvent() {
        MarkingRequest req = new MarkingRequest("MANUAL", null, List.of(new MarkItem(300, "00:10")));

        MarkingOutcome outcome = markingService.createOn(ULD_SN, req, owner(), channel);

        assertThat(outcome.marks()).hasSize(1);
        verify(eventPublisher).publishEvent(any(PortalMarkingCompletedEvent.class));
        // 이 한 줄이 「개인 영상이 외부 위탁으로 나가지 않는다」의 실증이다.
        verify(eventPublisher, never()).publishEvent(any(MarkingCompletedEvent.class));
    }

    @Test
    @DisplayName("★★자동_마킹도_관제_배치_이벤트를_발행하지_않는다")
    void portalAutoMarkingPublishesOnlyPortalEvent() {
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        markingService.createOn(ULD_SN, req, owner(), channel);

        verify(eventPublisher).publishEvent(any(PortalMarkingCompletedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(MarkingCompletedEvent.class));
    }

    /**
     * ★★ 소비자 축 — 관제 배치 브리지가 포털 완료 이벤트를 <b>구독할 수 없다</b>.
     *
     * <p>거름망(조건문)이 아니라 구독 타입으로 막는 것이 이 설계의 핵심이므로, 그 타입이 넓어지면
     * 여기서 빨개진다.
     */
    @Test
    @DisplayName("★★관제_배치_브리지는_포털_완료_이벤트를_구독하지_않는다")
    void batchBridgeDoesNotSubscribePortalEvent() {
        assertThat(listenedTypes(MarkingBatchBridge.class))
                .containsExactly(MarkingCompletedEvent.class);
    }

    @Test
    @DisplayName("★포털_추출_브리지는_관제_완료_이벤트를_구독하지_않는다")
    void portalBridgeDoesNotSubscribeControlEvent() {
        assertThat(listenedTypes(PortalFrameExtractBridge.class))
                .containsExactly(PortalMarkingCompletedEvent.class);
    }

    /** 그 브리지가 실제로 구독하는 이벤트 타입 목록(선언 순서). */
    private static List<Class<?>> listenedTypes(Class<?> bridge) {
        return Arrays.stream(bridge.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(TransactionalEventListener.class))
                .map(Method::getParameterTypes)
                .map(types -> types.length == 1 ? types[0] : Object.class)
                .toList();
    }
}
