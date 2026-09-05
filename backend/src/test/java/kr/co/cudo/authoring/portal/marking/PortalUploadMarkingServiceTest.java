package kr.co.cudo.authoring.portal.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingPrecheckReader;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalMarkingListResponse;
import kr.co.cudo.authoring.portal.dto.PortalMarkingRequest;
import kr.co.cudo.authoring.portal.dto.PortalMarkingSaveResponse;
import kr.co.cudo.authoring.portal.service.PortalUploadMarkingChannel;
import kr.co.cudo.authoring.portal.service.PortalUploadMarkingService;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포털 업로드 마킹 저장·조회 — 창구가 실제로 쓰는 경로(채널 공통 마킹 로직 + 포털 판정기).
 */
class PortalUploadMarkingServiceTest {

    private static final long ULD_SN = 501L;
    private static final String OWNER = "portal-user-1";
    /** 상한을 작게 두어 절단 규약을 실제로 밟는다. */
    private static final int MAX_FRAMES = 3;

    private PortalUploadAssetRepository assetRepository;
    private LsMarkingRepository markingRepository;
    private PortalUploadMarkingService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        MarkingPrecheckReader precheckReader = mock(MarkingPrecheckReader.class);
        ObjectMapper mapper = new ObjectMapper();

        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), "./storage/raw/portal",
                List.of("jpg"), 20_971_520L, 50, MAX_FRAMES,
                16_777_216L, 2_097_152L, 30L, 30L);
        PortalUploadMarkingChannel channel = new PortalUploadMarkingChannel(assetRepository, props);
        MarkingService markingService = new MarkingService(
                markingRepository, null, null, mapper, eventPublisher,
                null, precheckReader, null, null, null);
        service = new PortalUploadMarkingService(
                markingService, channel, assetRepository, markingRepository, mapper);

        when(assetRepository.transitionToProcessing(ULD_SN)).thenReturn(1);
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));
        givenAsset(PortalUploadLedger.STATUS_UPLOADED);
    }

    private void givenAsset(String status) {
        PortalUploadAsset asset = new PortalUploadAsset(ULD_SN, OWNER,
                PortalUploadLedger.TYPE_VIDEO, "v.mp4", "/p/v.mp4", 1024L, "video/mp4",
                status, 60.0, 30.0, 0, null, LocalDateTime.now(), LocalDateTime.now());
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.of(asset));
        when(assetRepository.findPortalAsset(ULD_SN)).thenReturn(Optional.of(asset));
    }

    private TokenClaims owner() {
        return new TokenClaims(OWNER, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(60));
    }

    // ======================== 저장 ========================

    @Test
    @DisplayName("자동_간격을_비우면_기본_300프레임으로_저장된다")
    void autoDefaultsTo300Frames() {
        PortalMarkingSaveResponse res =
                service.save(ULD_SN, new PortalMarkingRequest("AUTO", null, null), owner());

        assertThat(res.mode()).isEqualTo("AUTO");
        assertThat(res.interval()).isEqualTo(PortalMarkingRequest.DEFAULT_INTERVAL_FRAMES);
        assertThat(res.uldSttsCd()).isEqualTo(PortalUploadLedger.STATUS_PROCESSING);
    }

    /**
     * ★ 절단이 조용히 일어나지 않는다 — 저장된 수만 돌려주면 소비자는 그 수가 자기가 보낸 수인지
     * 잘린 수인지 구분할 수 없다.
     */
    @Test
    @DisplayName("★상한_초과는_거부가_아니라_절단이고_잘린_사실이_응답에_드러난다")
    void truncationIsReportedNotRejected() {
        // 60초 × 30fps = 1800프레임, 간격 100 → 18지점 > 상한 3.
        PortalMarkingSaveResponse res =
                service.save(ULD_SN, new PortalMarkingRequest("AUTO", 100, null), owner());

        assertThat(res.markCount()).isEqualTo(MAX_FRAMES);
        assertThat(res.requestedMarkCount()).isEqualTo(18);
        assertThat(res.truncated()).isTrue();
        assertThat(res.marks()).hasSize(MAX_FRAMES);
    }

    @Test
    @DisplayName("수동_지점이_하나도_없으면_400 — 서버가_간격으로_대신_채우지_않는다")
    void manualWithoutMarksIsRejected() {
        assertThatThrownBy(() ->
                service.save(ULD_SN, new PortalMarkingRequest("MANUAL", null, List.of()), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ======================== 조회 ========================

    @Test
    @DisplayName("저장된_마킹이_없으면_빈_목록이다 — 오류가_아니다")
    void listReturnsEmptyWhenNoMarking() {
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(ULD_SN)).thenReturn(List.of());

        PortalMarkingListResponse res = service.list(ULD_SN, owner());

        assertThat(res.uldSn()).isEqualTo(ULD_SN);
        assertThat(res.markings()).isEmpty();
    }

    /**
     * ★ 조회는 상태를 묻지 않는다 — 두 축을 같게 두면 <b>다시 저장할 수 없는 자산에서 무엇이
     * 저장돼 있는지조차 볼 수 없게</b> 된다.
     */
    @Test
    @DisplayName("★저장이_막힌_상태에서도_조회는_열려_있다")
    void listOpenEvenWhenSaveBlocked() {
        givenAsset(PortalUploadLedger.STATUS_READY);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(ULD_SN))
                .thenReturn(List.of(LsMarking.createManual(ULD_SN,
                        "[{\"frameIndex\":300,\"timestamp\":\"00:10\"}]", OWNER, 30.0, null)));

        PortalMarkingListResponse res = service.list(ULD_SN, owner());

        assertThat(res.markings()).hasSize(1);
        assertThat(res.markings().get(0).marks()).hasSize(1);
        assertThat(res.markings().get(0).markCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("남의_자산과_없는_자산은_조회도_같은_코드로_거절된다")
    void listRejectsForeignAsset() {
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(ULD_SN, owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
