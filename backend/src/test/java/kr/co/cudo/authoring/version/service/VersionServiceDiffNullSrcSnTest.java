package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-ISSUE-26 — {@code DATA_SRC_SN} 이 NULL 인 버전 해시로 diff 를 호출해도 <b>미처리 500 이 아니라 4xx</b>
 * 로 끝나는지 검증한다.
 *
 * <p>구 비식별 신고 스냅샷({@code SAVE_REASON_CD='DEIDENT_REPORT'})은 영상(rawSn) 스코프라
 * {@code DATA_SRC_SN} 이 NULL 이다. 가드가 없으면 {@code accessGuard.verifyAccess(null, actor)} →
 * {@code srcRepository.findById(null)} 에서 {@code InvalidDataAccessApiUsageException}(500)이 났다.
 * D-25 정책 반전으로 신규 적재는 중단됐지만 <b>운영 DB 에 기존 행이 남아 있어 가드는 계속 필요</b>하다.
 *
 * <p>같은 mock 골격을 재사용해 {@code diffWithWorking} 의 <b>게이트 평가 순서</b>(신고 게이트 →
 * 라벨 읽기)도 함께 고정한다 — 호출 여부는 실 DB 통합 테스트로는 기계적으로 단언할 수 없다.
 */
class VersionServiceDiffNullSrcSnTest {

    private static final String HASH_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private LsLabelVersionRepository labelVersionRepository;
    private LabelAccessGuard accessGuard;
    private LsDataLblRepository labelRepository;
    private VersionService versionService;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        labelVersionRepository = mock(LsLabelVersionRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        labelRepository = mock(LsDataLblRepository.class);
        versionService = new VersionService(
                labelVersionRepository, accessGuard, mock(VideoRepository.class),
                mock(WorkLockService.class), mock(LsDataSrcRepository.class),
                labelRepository, new ObjectMapper(),
                mock(ApplicationEventPublisher.class), mock(ReviewApprovalGate.class),
                mock(LsDataLblAiInfoRepository.class), mock(LsDataLblAttrValRepository.class),
                mock(LsDataLblHstryRepository.class),
                mock(kr.co.cudo.authoring.user.service.UserNameResolver.class));
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    /** DATA_SRC_SN 이 NULL 인 레거시(영상 스코프) 버전 행. */
    private LsLabelVersion rawScopedVersion(String hash) {
        return LsLabelVersion.create(8L, null, hash, "{\"items\":[]}", 1,
                LsLabelVersion.SAVE_REASON_DEIDENT_REPORT, "1");
    }

    /** 프레임 스코프 정상 버전 행. */
    private LsLabelVersion frameScopedVersion(String hash) {
        return LsLabelVersion.create(8L, 4L, hash, "{\"srcSn\":4,\"items\":[]}", 2,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");
    }

    @Test
    @DisplayName("DATA_SRC_SN_이_NULL_인_해시로_diff_호출시_500_이_아닌_4xx")
    void diffWithNullDataSrcSnReturns4xx() {
        // given — 같은(NULL 스코프) 해시로 자기 자신과 비교 요청 (ISSUES.md 재현 경로).
        when(labelVersionRepository.findByVersionHash(HASH_A))
                .thenReturn(List.of(rawScopedVersion(HASH_A)));

        // when / then — 400 INVALID_INPUT (미처리 500 금지).
        assertThatThrownBy(() -> versionService.diff(HASH_A, HASH_A, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 인가 가드에 null srcSn 이 전달되지 않았다(=findById(null) 미도달).
        verify(accessGuard, never()).verifyAndGet(any(), any());
    }

    @Test
    @DisplayName("to_버전만_DATA_SRC_SN_NULL_인_경우도_4xx")
    void diffWithNullOnToSideReturns4xx() {
        // given — from 은 정상 프레임 스코프, to 는 레거시 NULL 스코프.
        when(labelVersionRepository.findByVersionHash(HASH_A))
                .thenReturn(List.of(frameScopedVersion(HASH_A)));
        when(labelVersionRepository.findByVersionHash(HASH_B))
                .thenReturn(List.of(rawScopedVersion(HASH_B)));

        // when / then
        CustomException ex = (CustomException) org.assertj.core.api.Assertions
                .catchThrowable(() -> versionService.diff(HASH_A, HASH_B, reviewer));
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(accessGuard, never()).verifyAndGet(any(), any());
    }

    @Test
    @DisplayName("정상_프레임_스코프_버전_diff_는_영향받지_않는다")
    void normalFrameScopedDiffStillWorks() {
        // given — 양쪽 모두 프레임 스코프(정상 경로 회귀 방어).
        when(labelVersionRepository.findByVersionHash(HASH_A))
                .thenReturn(List.of(frameScopedVersion(HASH_A)));
        when(labelVersionRepository.findByVersionHash(HASH_B))
                .thenReturn(List.of(frameScopedVersion(HASH_B)));
        // DEV_FIX-A(S7/H4) — diff 는 신고 게이트 판정을 위해 rawSn 이 필요하므로 verifyAndGet 을 쓴다.
        when(accessGuard.verifyAndGet(4L, reviewer))
                .thenReturn(LsDataSrc.create(8L, 0L, "/f.jpg", null));

        // when
        var result = versionService.diff(HASH_A, HASH_B, reviewer);

        // then — 게이트에 걸리지 않고 인가 검사(from/to 각 1회)를 정상 통과한다.
        assertThat(result).isNotNull();
        verify(accessGuard, org.mockito.Mockito.times(2)).verifyAndGet(4L, reviewer);
        // 신고 게이트는 같은 영상이라 rawSn 단위 1회만 평가한다(N+1 금지).
        verify(accessGuard).requireNotUnderDeidentReport(8L);
    }

    @Test
    @DisplayName("작업본_diff_는_신고게이트_통과_전에_라벨을_읽지_않는다_게이트_순서_회귀가드")
    void diffWithWorkingEvaluatesGateBeforeReadingLabels() {
        // given — 프레임 스코프 정상 버전이지만 영상이 비식별 누락 신고 구간(DE_IDNTF_YN='F').
        when(labelVersionRepository.findByVersionHash(HASH_A))
                .thenReturn(List.of(frameScopedVersion(HASH_A)));
        when(accessGuard.verifyAndGet(4L, reviewer))
                .thenReturn(LsDataSrc.create(8L, 0L, "/f.jpg", null));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED,
                        "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."))
                .when(accessGuard).requireNotUnderDeidentReport(8L);

        // when / then — 412 로 거부된다.
        assertThatThrownBy(() -> versionService.diffWithWorking(HASH_A, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // ★ 결과(412)만 고정하면 게이트를 라벨 읽기 뒤로 옮겨도 테스트가 통과한다("가드를 되돌려도
        //   테스트가 통과한다" 사고 패턴). 라벨 좌표는 PII 위치 특정 정보이므로(CWE-359) 게이트
        //   통과 전에는 <b>읽지도 않았음</b>을 기계적으로 고정한다.
        verify(labelRepository, never()).findBySrcSn(any());
        verify(labelRepository, never()).findBySrcSnIn(any());
    }
}
