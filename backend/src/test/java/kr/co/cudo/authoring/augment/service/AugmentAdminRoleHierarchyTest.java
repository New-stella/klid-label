package kr.co.cudo.authoring.augment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentCancelResponse;
import kr.co.cudo.authoring.augment.dto.AugmentProgressResponse;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentExternalModePolicy;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.integration.dto.GenAiContract;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 역할 계층(관리자 → 검수자)이 증강 도메인의 <b>서비스 안 역할 판정</b>에도 적용되는지 고정한다.
 *
 * <h3>왜 필요한가</h3>
 * <p>Spring 의 {@code RoleHierarchy} 는 권한(authority) 축에만 걸린다. 서비스가 역할을 그대로 동등
 * 비교하면 컨트롤러 {@code @PreAuthorize} 는 통과한 관리자가 <b>서비스 안에서만</b> 403 이 되어,
 * 관리자는 관리 기능은 쓰되 증강 요청·취소·폐기 복구·채택/반려·진행 조회에서 거부된다(계층이 반쪽만
 * 성립). 판정의 단일 원천은 {@link TokenClaims#hasRole(Role)} 이며 이 테스트가 그 자리를 지킨다.
 *
 * <h3>왜 「거부됐다」를 상태코드로만 단언하지 않는가</h3>
 * <p>증강 경로는 역할 게이트 <b>뒤</b>에 부모 비식별·파생 깊이·검수 완료·상태 전이 게이트가 줄줄이
 * 붙어 있다. 뒤 게이트에 걸리는 픽스처로 시험을 짜면 「관리자는 못 한다」류의 단언이 <b>다른 사유로</b>
 * 항상 참이 되어 역할 판정을 되돌려도 초록이다. 그래서 ①뒤 게이트를 <b>전부 통과시키는</b> 목을 주고
 * ②통과·거부를 상태코드가 아니라 <b>부수효과(적재·이벤트·외부 호출)의 유무</b>로도 함께 단언한다.
 *
 * <p>순수 단위 테스트인 것도 의도다 — 관리자 판정에 {@code LS_USER_ROLE} 행이 필요 없어야
 * 「시스템에 관리자가 항상 있는 셈」이 되어 부트스트랩 창구 시험을 깨뜨리는 일이 없다.
 *
 * @design ADR-055
 * @design ROLE-004
 * @design AC-125
 */
class AugmentAdminRoleHierarchyTest {

    private static final long RAW_SN = 1L;
    private static final long SRC_SN = 11L;
    private static final long DATA_AUG_SN = 77L;

    private static final TokenClaims ADMIN = claims("9", Role.ADMIN);
    private static final TokenClaims REVIEWER = claims("1", Role.REVIEWER);
    private static final TokenClaims WORKER = claims("100", Role.WORKER);
    private static final TokenClaims PORTAL = new TokenClaims(
            "200", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(3600));

    private static TokenClaims claims(String sub, Role role) {
        return new TokenClaims(sub, role, Channel.INTERNAL, Instant.now().plusSeconds(3600));
    }

    // ============================================================
    // 증강 요청 — AugmentRequestService.request
    // ============================================================

    @Nested
    @DisplayName("증강 요청")
    class RequestGate {

        private LsDataAugRepository augRepository;
        private ApplicationEventPublisher eventPublisher;
        private AugmentRequestService service;

        /** 역할 게이트 뒤의 모든 가드(파생·연동·검수완료·신고구간·대표프레임·적재)를 통과시키는 목. */
        @BeforeEach
        void setUp() {
            LsRawDataStatusRepository statusRepository = mock(LsRawDataStatusRepository.class);
            LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
            augRepository = mock(LsDataAugRepository.class);
            eventPublisher = mock(ApplicationEventPublisher.class);
            DeidentReportGate deidentReportGate = mock(DeidentReportGate.class);
            AugmentCallbackUrlResolver callbackUrlResolver = mock(AugmentCallbackUrlResolver.class);
            VideoRepository videoRepository = mock(VideoRepository.class);
            AugmentExternalModePolicy externalModePolicy = mock(AugmentExternalModePolicy.class);

            LsRawDataStatus approved = LsRawDataStatus.initial(RAW_SN);
            approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
            given(statusRepository.findByRawDataIdIn(any())).willReturn(List.of(approved));
            given(srcRepository.findFirstSrcSnGroupedByRawSn(any()))
                    .willReturn(List.<Object[]>of(new Object[]{RAW_SN, SRC_SN}));
            given(deidentReportGate.isUnderDeidentReport(anyLong())).willReturn(false);
            given(callbackUrlResolver.resolve()).willReturn("https://authoring.example/v1/genai/callback");
            given(videoRepository.findById(anyLong())).willReturn(Optional.of(
                    LsDataRaw.createFromIngest("CLIP-" + RAW_SN, "CCTV-001", "EVT", "11680",
                            LsDataRaw.PRVC_TYPE_ANONY, "/nas-storage/raw/x.mp4",
                            LocalDateTime.now(), 30)));
            // 적재는 성공한다 — 실패로 두면 「관리자도 못 한다」가 적재 실패로 항상 참이 된다.
            given(augRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service = new AugmentRequestService(statusRepository, srcRepository, augRepository,
                    videoRepository, eventPublisher, deidentReportGate, callbackUrlResolver,
                    new ObjectMapper(), externalModePolicy);
        }

        @Test
        @DisplayName("관리자는_증강을_요청할_수_있다_계층으로_검수자_자리를_물려받는다")
        void 관리자는_요청한다() {
            AugmentRequestResponse response = service.request(request(), ADMIN);

            assertThat(response.createdCount())
                    .as("관리자 요청이 실제로 증강 1건을 만들어야 한다")
                    .isEqualTo(1);
            verify(augRepository).save(any(LsDataAug.class));
            verify(eventPublisher).publishEvent(any(AugmentRequestedItemEvent.class));
        }

        /** 대조군 — 이 단언이 없으면 위 단언이 「그저 통과했다」는 사실만 말한다. */
        @Test
        @DisplayName("검수자도_종전대로_증강을_요청할_수_있다")
        void 검수자는_종전대로_요청한다() {
            assertThat(service.request(request(), REVIEWER).createdCount()).isEqualTo(1);
            verify(augRepository).save(any(LsDataAug.class));
        }

        @Test
        @DisplayName("작업자는_증강을_요청할_수_없고_적재도_일어나지_않는다")
        void 작업자는_거부된다() {
            assertThatThrownBy(() -> service.request(request(), WORKER))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verify(augRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
        }

        @Test
        @DisplayName("포털_회원은_계층으로도_증강_요청_자리에_들어오지_못한다")
        void 포털회원은_거부된다() {
            assertThatThrownBy(() -> service.request(request(), PORTAL))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verify(augRepository, never()).save(any());
        }

        @Test
        @DisplayName("인증_토큰이_없으면_401_이며_적재도_일어나지_않는다")
        void 미인증은_401() {
            assertThatThrownBy(() -> service.request(request(), null))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
            verify(augRepository, never()).save(any());
        }

        private AugmentRequestRequest request() {
            return new AugmentRequestRequest(
                    List.of(RAW_SN), List.of(AugmentTypeCode.AUGMENT),
                    new AugmentRequestRequest.Mtdt(
                            AugmentPrompts.Time.NIGHT, AugmentPrompts.Season.WINTER,
                            AugmentPrompts.Weather.RAIN, AugmentPrompts.Terrain.ROAD,
                            AugmentPrompts.Severity.HIGH),
                    null);
        }
    }

    // ============================================================
    // 증강 취소 — AugmentCancelService.cancel
    // ============================================================

    @Nested
    @DisplayName("증강 취소")
    class CancelGate {

        private AugmentCancelTxService cancelTxService;
        private AugmentCancelService service;

        @BeforeEach
        void setUp() {
            cancelTxService = mock(AugmentCancelTxService.class);
            ExternalAugmentClient externalClient = mock(ExternalAugmentClient.class);
            AugmentExternalProbe probe = mock(AugmentExternalProbe.class);

            // 취소를 선점하고 청크 1건을 넘긴다 — externalJobId 가 없어 외부 왕복 없이 로컬 종결된다.
            given(cancelTxService.claim(anyLong())).willReturn(new AugmentCancelTxService.CancelClaim(
                    true, LsDataAug.AUG_WINTER, LsDataAug.STTS_CANCELED,
                    List.of(new AugmentCancelTxService.CancelTarget(5L, 1, null))));
            given(cancelTxService.markJobsCanceled(anyLong(), any())).willReturn(1);

            service = new AugmentCancelService(cancelTxService, externalClient, probe, 20L);
        }

        @Test
        @DisplayName("관리자는_증강_요청을_취소할_수_있다")
        void 관리자는_취소한다() {
            AugmentCancelResponse response = service.cancel(DATA_AUG_SN, "오요청", ADMIN);

            assertThat(response.canceled()).as("관리자의 취소가 실제로 수행돼야 한다").isTrue();
            assertThat(response.fullyCanceled()).isTrue();
            verify(cancelTxService).claim(DATA_AUG_SN);
            verify(cancelTxService).markJobsCanceled(anyLong(), any());
        }

        @Test
        @DisplayName("검수자도_종전대로_증강_요청을_취소할_수_있다")
        void 검수자는_종전대로_취소한다() {
            assertThat(service.cancel(DATA_AUG_SN, "오요청", REVIEWER).canceled()).isTrue();
        }

        @Test
        @DisplayName("작업자의_취소는_거부되고_취소_선점조차_일어나지_않는다")
        void 작업자는_거부된다() {
            assertThatThrownBy(() -> service.cancel(DATA_AUG_SN, "오요청", WORKER))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verify(cancelTxService, never()).claim(anyLong());
        }

        @Test
        @DisplayName("포털_회원의_취소도_거부된다")
        void 포털회원은_거부된다() {
            assertThatThrownBy(() -> service.cancel(DATA_AUG_SN, "오요청", PORTAL))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verify(cancelTxService, never()).claim(anyLong());
        }
    }

    // ============================================================
    // 진행상태 조회 권한 — AugmentProgressService.requireViewer
    // ============================================================

    @Nested
    @DisplayName("진행상태 조회 권한")
    class ProgressViewGate {

        private AugmentSnapshotLoader snapshotLoader;
        private ExternalAugmentClient externalClient;
        private AugmentProgressService service;

        @BeforeEach
        void setUp() {
            snapshotLoader = mock(AugmentSnapshotLoader.class);
            externalClient = mock(ExternalAugmentClient.class);
            AugmentExternalProbe probe = mock(AugmentExternalProbe.class);
            AugmentResultRecoveryService recoveryService = mock(AugmentResultRecoveryService.class);
            AugmentStatusWindowRotator windowRotator = mock(AugmentStatusWindowRotator.class);

            // 청크가 없는 종결 증강 — 역할 게이트만 남기고 뒤 경로(외부 왕복·회수)를 전부 비운다.
            given(snapshotLoader.load(anyLong())).willReturn(new AugmentSnapshotLoader.Snapshot(
                    DATA_AUG_SN, LsDataAug.AUG_WINTER, LsDataAug.STTS_ACCEPTED, List.of()));

            service = new AugmentProgressService(snapshotLoader, externalClient, probe,
                    recoveryService, windowRotator, 20L);
        }

        @Test
        @DisplayName("관리자는_증강_진행상태를_조회할_수_있다")
        void 관리자는_조회한다() {
            AugmentProgressResponse response = service.progress(DATA_AUG_SN, ADMIN);

            assertThat(response.id()).isEqualTo(DATA_AUG_SN);
            verify(snapshotLoader).load(DATA_AUG_SN);
        }

        @Test
        @DisplayName("검수자와_작업자도_종전대로_진행상태를_조회한다")
        void 검수자와_작업자는_종전대로_조회한다() {
            assertThat(service.progress(DATA_AUG_SN, REVIEWER).id()).isEqualTo(DATA_AUG_SN);
            assertThat(service.progress(DATA_AUG_SN, WORKER).id()).isEqualTo(DATA_AUG_SN);
        }

        @Test
        @DisplayName("포털_회원은_진행상태를_조회할_수_없고_스냅샷도_읽히지_않는다")
        void 포털회원은_거부된다() {
            assertThatThrownBy(() -> service.progress(DATA_AUG_SN, PORTAL))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verify(snapshotLoader, never()).load(anyLong());
        }

        @Test
        @DisplayName("인증_토큰이_없으면_401_이며_스냅샷도_읽히지_않는다")
        void 미인증은_401() {
            assertThatThrownBy(() -> service.progress(DATA_AUG_SN, null))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
            verify(snapshotLoader, never()).load(anyLong());
        }
    }
}
