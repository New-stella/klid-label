package kr.co.cudo.authoring.augment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.integration.AugmentExternalModePolicy;
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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 제약 위반(DataIntegrityViolationException) → <b>409 CONFLICT</b> 재분기 검증 (DEV_FIX LOW-6).
 *
 * <h3>왜 필요한가</h3>
 * <p>중복 증강 차단 정책 폐기와 함께 {@code DataIntegrityViolationException} 전용 catch 를 지웠는데,
 * {@code IDMP_KEY} UNIQUE 와 FK 제약은 <b>그대로 남아 있다</b>. 전용 분기가 없으면 그 위반이 generic
 * {@code catch (Exception)} 에 흡수돼 "생성 0건 → {@code INTERNAL_ERROR} 500" 이 되고, 호출자는 원인도
 * 재시도 가능 여부도 알 수 없다(관측성 회귀).
 *
 * <p>이 충돌은 서비스 내부에서 UUID 로 발급하는 멱등 키 충돌·정합 붕괴라 통합 테스트로 자연 재현할
 * 수단이 없다 — 그래서 적재만 실패하도록 저장소를 세운 <b>단위</b> 테스트로 고정한다.
 *
 * <p>⚠ 이 분기는 <b>중복 증강 차단이 아니다</b>. 그 정책(UK_LS_DATA_AUG_ACTVTN)은 2026-07-31 폐기됐고
 * 같은 (영상 × 종류) 재요청은 정상 동선이다({@code AugmentRequestControllerTest} 회귀 가드 참조).
 */
class AugmentRequestConflictTest {

    private static final long RAW_SN = 1L;
    private static final long SRC_SN = 11L;

    private LsDataAugRepository augRepository;
    private AugmentRequestService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        LsRawDataStatusRepository statusRepository = mock(LsRawDataStatusRepository.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        augRepository = mock(LsDataAugRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DeidentReportGate deidentReportGate = mock(DeidentReportGate.class);
        AugmentCallbackUrlResolver callbackUrlResolver = mock(AugmentCallbackUrlResolver.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        // 외부 연동 모드 — 기본 mock(false)은 "연동됨(http)" 이므로 종전 동작 그대로다.
        AugmentExternalModePolicy externalModePolicy = mock(AugmentExternalModePolicy.class);

        LsRawDataStatus approved = LsRawDataStatus.initial(RAW_SN);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        given(statusRepository.findByRawDataIdIn(any())).willReturn(List.of(approved));
        given(srcRepository.findFirstSrcSnGroupedByRawSn(any()))
                .willReturn(List.<Object[]>of(new Object[]{RAW_SN, SRC_SN}));
        given(deidentReportGate.isUnderDeidentReport(anyLong())).willReturn(false);
        given(callbackUrlResolver.resolve()).willReturn("https://authoring.example/v1/genai/callback");
        // 파생 영상 가드 — 정상 시드는 ORGNL_RAW_SN 이 null 인 원본이다.
        given(videoRepository.findById(anyLong())).willReturn(java.util.Optional.of(
                LsDataRaw.createFromIngest("CLIP-" + RAW_SN, "CCTV-001", "EVT", "11680",
                        LsDataRaw.PRVC_TYPE_ANONY, "/nas-storage/raw/x.mp4",
                        java.time.LocalDateTime.now(), 30)));

        service = new AugmentRequestService(statusRepository, srcRepository, augRepository,
                videoRepository, eventPublisher, deidentReportGate, callbackUrlResolver,
                new ObjectMapper(), externalModePolicy);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("잔존_제약_위반은_500이_아니라_409로_종결된다")
    void 제약위반은_409() {
        // given — 적재가 UNIQUE/FK 제약으로 거부되는 상황
        given(augRepository.save(any())).willThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_aug_idempotency_key\""));

        // when / then
        assertThatThrownBy(() -> service.request(request(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .as("의미 있는 4xx 로 마감되어야 한다 — generic 500 은 원인 은폐다")
                        .isEqualTo(ErrorCode.CONFLICT));
    }

    /** 제약 위반 <b>이외</b>의 적재 실패는 종전대로 500(생성 0건) — 과잉 4xx 회귀 방지. */
    @Test
    @DisplayName("제약_위반이_아닌_적재_실패는_종전대로_INTERNAL_ERROR")
    void 그밖의_실패는_500() {
        given(augRepository.save(any())).willThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.request(request(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    /** 응답·로그 어디에도 제약 이름·SQL 원문이 새지 않는다(CWE-209). */
    @Test
    @DisplayName("409_메시지에_제약명이나_SQL_원문이_노출되지_않는다")
    void 충돌_메시지는_내부정보를_숨긴다() {
        given(augRepository.save(any())).willThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_aug_idempotency_key\""));

        assertThatThrownBy(() -> service.request(request(), reviewer))
                .hasMessageNotContaining("uk_aug_idempotency_key")
                .hasMessageNotContaining("constraint");
    }

    private static AugmentRequestRequest request() {
        return new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("NIGHT", "WINTER", "RAIN", "ROAD", "HIGH"));
    }
}
