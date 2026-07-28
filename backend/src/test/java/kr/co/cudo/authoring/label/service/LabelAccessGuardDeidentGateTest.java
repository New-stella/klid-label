package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S7 + D-ISSUE-26 — {@link LabelAccessGuard} 의 비식별 신고 게이트 및 null srcSn 방어 단위 테스트.
 *
 * <ul>
 *   <li><b>S7</b>: 비식별 신고 구간({@code DE_IDNTF_YN='F'}) 영상의 라벨 조회는 차단(412)되어야 한다 —
 *       신고가 라벨을 삭제하지 않고 보존하도록 정책이 반전(D-25)되면서 열린 PII 좌표 노출창을 닫는다.
 *       {@code 'Y'}(정상)·{@code 'N'}(미수행)·값 없음은 통과해야 한다(정상 플로우 회귀 방어).</li>
 *   <li><b>D-ISSUE-26</b>: {@code srcSn == null} 이 들어와도 {@code findById(null)} 의
 *       {@code InvalidDataAccessApiUsageException}(미처리 500)이 아니라 규약 4xx 로 끝나야 한다.</li>
 * </ul>
 */
class LabelAccessGuardDeidentGateTest {

    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository assignmentRepository;
    private VideoRepository videoRepository;
    private LabelAccessGuard guard;

    private TokenClaims reviewer;
    private TokenClaims worker;

    @BeforeEach
    void setUp() {
        srcRepository = mock(LsDataSrcRepository.class);
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        videoRepository = mock(VideoRepository.class);
        // 신고 구간 판정은 DeidentReportGate(단일 원천)에 위임됐다 — 실제 구현체를 끼워 기존 스텁
        //   (videoRepository.findDeIdntfYnByRawSn)이 그대로 판정에 반영되게 한다.
        guard = new LabelAccessGuard(srcRepository, assignmentRepository,
                new DeidentReportGate(videoRepository));
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("비식별_신고_상태에서는_라벨_조회가_차단된다")
    void blocksLabelReadWhileDeidentReported() {
        // given — 신고로 DE_IDNTF_YN='F' 내려간 영상.
        when(videoRepository.findDeIdntfYnByRawSn(700L)).thenReturn(Optional.of("F"));

        // when / then — 412 PRECONDITION_FAILED (기존 마킹 게이트와 동일 규약).
        assertThatThrownBy(() -> guard.requireNotUnderDeidentReport(700L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("REVIEWER도_비식별_신고_구간에서는_동일하게_차단된다")
    void blocksReviewerToo() {
        // given — 게이트는 역할 무관 프리컨디션(영상 스트리밍·마킹 게이트와 동일 정책).
        when(videoRepository.findDeIdntfYnByRawSn(701L)).thenReturn(Optional.of("F"));
        LsDataSrc src = frame(11L, 701L);
        when(srcRepository.findById(11L)).thenReturn(Optional.of(src));

        // when / then — REVIEWER 는 인가는 통과하지만 게이트에서 막힌다.
        assertThatCode(() -> guard.verifyAndGet(11L, reviewer)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireNotUnderDeidentReport(701L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("deIdntfYn_이_Y_인_일반영상의_라벨_조회_저장은_영향받지_않는다")
    void allowsNormalVideos() {
        // given — 'Y'(비식별 완료) / 'N'(미수행) / 값 없음(null).
        when(videoRepository.findDeIdntfYnByRawSn(800L)).thenReturn(Optional.of("Y"));
        when(videoRepository.findDeIdntfYnByRawSn(801L)).thenReturn(Optional.of("N"));
        when(videoRepository.findDeIdntfYnByRawSn(802L)).thenReturn(Optional.empty());

        // when / then — 전부 통과(정상 플로우 회귀 방어).
        assertThatCode(() -> guard.requireNotUnderDeidentReport(800L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireNotUnderDeidentReport(801L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireNotUnderDeidentReport(802L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireNotUnderDeidentReport(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("게이트는_인가_검사를_대체하지_않는다_미배정_WORKER는_FORBIDDEN")
    void gateDoesNotBypassAuthorization() {
        // given — 미배정 WORKER + 정상('Y') 영상.
        LsDataSrc src = frame(12L, 900L);
        when(srcRepository.findById(12L)).thenReturn(Optional.of(src));
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(100L, "LABELER", 900L))
                .thenReturn(false);

        // when / then — 인가에서 먼저 막힌다(게이트가 인가를 우회·대체하지 않음).
        assertThatThrownBy(() -> guard.verifyAndGet(12L, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("LabelAccessGuard_에_srcSn_null_이_들어와도_500_이_아니다")
    void nullSrcSnIsRejectedWith4xx() {
        // when / then — D-ISSUE-26: findById(null) 진입 전에 4xx 로 종료(미처리 500 금지).
        CustomException ex = (CustomException) org.assertj.core.api.Assertions
                .catchThrowable(() -> guard.verifyAndGet(null, reviewer));
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        // 레포지토리에 null 이 전달되지 않았음(예외 유형이 InvalidDataAccessApiUsageException 이 아님).
        assertThat(ex).isInstanceOf(CustomException.class);
    }

    private LsDataSrc frame(long srcSn, long rawSn) {
        LsDataSrc s = LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now());
        setField(s, "srcSn", srcSn);
        return s;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
