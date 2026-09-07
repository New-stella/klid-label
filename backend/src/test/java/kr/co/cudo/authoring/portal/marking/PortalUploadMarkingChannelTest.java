package kr.co.cudo.authoring.portal.marking;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.service.MarkingEventType;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.event.PortalMarkingCompletedEvent;
import kr.co.cudo.authoring.portal.service.PortalUploadMarkingChannel;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포털 업로드 채널의 마킹 판정기 — 두 물음(접근·단계)과 채널 조달값.
 *
 * <p>관제 가드가 요구하는 세 조건(비식별 완료·배치 단계 표식·관제 이벤트 유형)은 이 경로에 없다.
 * 이 시험은 그 자리를 <b>본인 자산 + 포털 업로드 상태</b>가 대신한다는 것을 고정한다.
 */
class PortalUploadMarkingChannelTest {

    private static final long ULD_SN = 501L;
    private static final String OWNER = "portal-user-1";

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadMarkingChannel channel;

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), "./storage/raw/portal",
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        channel = new PortalUploadMarkingChannel(assetRepository, props);
    }

    private TokenClaims owner() {
        return new TokenClaims(OWNER, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(60));
    }

    private PortalUploadAsset asset(String type, String status, Double duration, Double fps) {
        return new PortalUploadAsset(ULD_SN, OWNER, type, "v.mp4", "/p/v.mp4", 1024L,
                "video/mp4", status, duration, fps, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void given(PortalUploadAsset asset) {
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.of(asset));
        when(assetRepository.findPortalAsset(ULD_SN)).thenReturn(Optional.of(asset));
    }

    // ======================== ① 접근 ========================

    @Test
    @DisplayName("토큰_주체가_없으면_401")
    void unauthenticatedIsUnauthorized() {
        assertThatThrownBy(() -> channel.requireAccess(ULD_SN, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /**
     * ★ 남의 자산과 없는 자산이 <b>같은 코드</b>로 거절된다 — 상태코드로 가르면 남의 자산의 실재
     * 여부가 응답으로 드러난다.
     */
    @Test
    @DisplayName("★남의_자산과_없는_자산이_같은_코드로_거절된다")
    void foreignAndMissingShareTheSameCode() {
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.empty());

        ErrorCode fromAccess = codeOf(() -> channel.requireAccess(ULD_SN, owner()));
        ErrorCode fromStage = codeOf(() -> channel.requireMarkable(ULD_SN, owner()));

        assertThat(fromAccess).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(fromStage).isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ======================== ② 단계 ========================

    @Test
    @DisplayName("마킹_대기_자산은_통과하고_조달값은_비어_있다")
    void awaitingMarkingPasses() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, PortalUploadLedger.STATUS_UPLOADED, 60.0, 30.0));

        var target = channel.requireMarkable(ULD_SN, owner());

        // 관제 인입 이벤트 유형이 이 경로에 오지 않고, 저장 경로는 외부 채널 응답에 싣지 않는다.
        assertThat(target.eventTypeCd()).isNull();
        assertThat(target.videoPath()).isNull();
    }

    @Test
    @DisplayName("영상이_아닌_자산은_400 — 기다려도_달라지지_않는_영구_조건이다")
    void nonVideoIsInvalidInput() {
        given(asset(PortalUploadLedger.TYPE_IMAGE, PortalUploadLedger.STATUS_UPLOADED, null, null));

        assertThat(codeOf(() -> channel.requireMarkable(ULD_SN, owner())))
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /**
     * ★ 재마킹을 제공하지 않는다. 잘못된 것은 요청 본문이 아니라 대상의 상태이므로 입력 오류가
     * 아니라 충돌이며, 메시지가 회복 경로를 함께 알린다.
     */
    @Test
    @DisplayName("★이미_저장한_자산의_재마킹은_409이고_회복_경로를_알린다")
    void reMarkingIsConflict() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, PortalUploadLedger.STATUS_PROCESSING, 60.0, 30.0));

        assertThatThrownBy(() -> channel.requireMarkable(ULD_SN, owner()))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("지우고 다시 올려")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("처리에_실패한_자산도_409")
    void failedAssetIsConflict() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, PortalUploadLedger.STATUS_FAILED, 60.0, 30.0));

        assertThat(codeOf(() -> channel.requireMarkable(ULD_SN, owner())))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ======================== 조달 ========================

    @Test
    @DisplayName("★자동인데_길이를_모르면_409 — 기다리면_풀리는_조건이라_입력_오류가_아니다")
    void autoWithoutDurationIsConflict() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, PortalUploadLedger.STATUS_UPLOADED, null, 30.0));

        assertThat(codeOf(() -> channel.resolveDurationSec(ULD_SN, "AUTO")))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("수동은_길이를_몰라도_저장할_수_있다 — 상한_검증만_건너뛴다")
    void manualWithoutDurationIsAllowed() {
        given(asset(PortalUploadLedger.TYPE_VIDEO, PortalUploadLedger.STATUS_UPLOADED, null, 30.0));

        assertThat(channel.resolveDurationSec(ULD_SN, "MANUAL")).isNull();
    }

    @Test
    @DisplayName("검증_이벤트_질문은_고를_축이_없어_비운다 — 지어내지_않는다")
    void questionIsAlwaysEmpty() {
        assertThat(channel.resolveQuestionSn(ULD_SN, 99L, MarkingEventType.NONE)).isNull();
    }

    // ======================== 저장 후속 ========================

    @Test
    @DisplayName("저장이_마킹_대기에서_추출_중으로_원자_전이한다")
    void onSavedTransitionsToProcessing() {
        when(assetRepository.transitionToProcessing(ULD_SN)).thenReturn(1);

        channel.onSaved(ULD_SN, 7001L);
    }

    @Test
    @DisplayName("★전이가_0행이면_409 — 판정과_저장_사이의_창은_조건부_UPDATE만_닫는다")
    void onSavedLostTransitionIsConflict() {
        when(assetRepository.transitionToProcessing(ULD_SN)).thenReturn(0);

        assertThat(codeOf(() -> channel.onSaved(ULD_SN, 7001L))).isEqualTo(ErrorCode.CONFLICT);
    }

    /**
     * ★★ 되돌림 실증 — 포털 마킹의 완료 이벤트는 <b>관제 배치를 깨우는 이벤트가 아니다</b>.
     *
     * <p>이 반환 타입을 관제 이벤트로 되돌리면 포털 사용자의 개인 영상이 외부 시계열 위탁으로 나간다.
     */
    @Test
    @DisplayName("★★완료_이벤트는_포털_전용_타입이며_관제_배치_이벤트가_아니다")
    void completionEventIsPortalOnly() {
        Object event = channel.completionEvent(ULD_SN, 7001L);

        assertThat(event).isInstanceOf(PortalMarkingCompletedEvent.class);
        assertThat(event).isNotInstanceOf(MarkingCompletedEvent.class);
    }

    private ErrorCode codeOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("예외가 발생해야 한다");
        } catch (CustomException e) {
            return e.getErrorCode();
        }
    }
}
