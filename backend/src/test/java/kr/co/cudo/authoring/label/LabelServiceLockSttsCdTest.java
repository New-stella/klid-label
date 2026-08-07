package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
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
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H-ISSUE-41 / H-ISSUE-42 — 라벨 조회 응답의 영상 잠금 코드(lockSttsCd) 계약 검증.
 *
 * <p>배경: {@code LabelResponse} javadoc 과 FE 판정 상수(LockSttsCd)는 정본을
 * {@code "LOCKED_FOR_REDEIDENT"} 로 선언하는데, {@code LabelService.getByFrame} 이
 * DB 락 <b>행</b>의 상태값({@code LS_AUTH_WORK_LOCK.LOCK_STTS_CD='LOCKED'})을 그대로 응답에 실어
 * 계약이 어긋났다. 그 결과 서버 단독 잠금(신고 없이 락만 걸린 상태)에서 FE 가 잠금을 인지하지 못하고
 * (배너 미표시), 저장 시점에 BE 최종 방어에 걸려서야 원인을 알 수 없는 안내를 받았다.
 *
 * <p>두 값은 서로 다른 축이다 — 락 행 상태(LOCKED/RELEASED)는 내부 저장 모델이고,
 * lockSttsCd 는 FE 와의 <b>응답 계약</b>이다. 값을 재사용하지 않고 각자의 상수를 쓴다.
 */
class LabelServiceLockSttsCdTest {

    private static final Long SRC_SN = 400L;
    private static final Long RAW_SN = 8201L;
    private static final String ACTOR_SUB = "1001";

    private WorkLockService workLockService;
    private LabelService service;

    private static void setSrcSn(LsDataSrc target, long value) {
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static LsDataSrc frame(long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, "/raw/f" + frameNo + ".jpg", null);
        setSrcSn(src, srcSn);
        return src;
    }

    @BeforeEach
    void setUp() {
        LsDataLblRepository labelRepository = mock(LsDataLblRepository.class);
        LsDataLblAiInfoRepository aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        workLockService = mock(WorkLockService.class);
        LabelAccessGuard accessGuard = mock(LabelAccessGuard.class);
        LsLabelRepository lsLabelRepository = mock(LsLabelRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ReviewApprovalGate approvalGate = mock(ReviewApprovalGate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        service = new LabelService(labelRepository, aiInfoRepository, srcRepository,
                videoRepository, workLockService, accessGuard, objectMapper,
                lsLabelRepository, eventPublisher, approvalGate,
                mock(LsDataLblHstryRepository.class), mock(LsDataLblAttrValRepository.class),
                mock(FrameBoundsResolver.class), mock(UserNameResolver.class));

        LsDataSrc current = frame(SRC_SN, 0);
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(current);
        when(srcRepository.lockAndReadLabelVersion(any())).thenReturn(Optional.of(0L));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(aiInfoRepository.findByDataLblSnIn(anyCollection())).thenReturn(List.of());
        when(labelRepository.findDistinctSrcSnsWithLabelIn(anyCollection())).thenReturn(List.of());
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(current));
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("서버_단독_잠금_상태에서_lockSttsCd_응답값이_LOCKED_FOR_REDEIDENT다")
    void lockedRawReturnsContractLockCode() {
        // given — 신고 없이 서버 측 작업락만 걸린 상태(타 세션 편집 등).
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        // when
        LabelResponse resp = service.getByFrame(SRC_SN, worker());

        // then — FE 판정 정본과 동일한 문자열이어야 한다(락 행 상태값 "LOCKED" 는 계약 위반).
        assertThat(resp.lockSttsCd()).isEqualTo(LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT);
        assertThat(resp.lockSttsCd()).isEqualTo("LOCKED_FOR_REDEIDENT");
    }

    @Test
    @DisplayName("잠금이_없으면_lockSttsCd_는_null이다")
    void unlockedRawReturnsNull() {
        // given
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);

        // when
        LabelResponse resp = service.getByFrame(SRC_SN, worker());

        // then
        assertThat(resp.lockSttsCd()).isNull();
    }

    @Test
    @DisplayName("신고구간에서는_락상태와_무관하게_412")
    void deidentReportGateWinsOverLockState() {
        // given — 락(작업락 LOCKED)과 비식별 누락 신고(DE_IDNTF_YN='F')가 <b>동시에</b> 걸린 영상.
        //   두 상태는 축이 다르다 — 락은 "지금 편집 불가"(응답 lockSttsCd 로 화면에 안내),
        //   신고는 "PII 가 남아 있어 라벨 좌표를 내려줄 수 없음"(412 거부, CLAUDE.md 차단 범위).
        //   게이트를 실제 구현체로 끼워(LabelAccessGuard + DeidentReportGate) 스텁된 예외가 아니라
        //   데이터 상태('F')로부터 412 가 산출되는 경로를 그대로 태운다.
        LsDataLblRepository labelRepository = mock(LsDataLblRepository.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        LsTaskAssignmentRepository assignmentRepository = mock(LsTaskAssignmentRepository.class);
        WorkLockService lockService = mock(WorkLockService.class);

        LsDataSrc current = frame(SRC_SN, 0);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(current));
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                Long.valueOf(ACTOR_SUB), LsTaskAssignment.TASK_LABELER, RAW_SN)).thenReturn(true);
        when(videoRepository.findDeIdntfYnByRawSn(RAW_SN)).thenReturn(Optional.of("F"));
        when(lockService.isRawLocked(RAW_SN)).thenReturn(true);

        LabelService gated = new LabelService(labelRepository, mock(LsDataLblAiInfoRepository.class),
                srcRepository, videoRepository, lockService,
                new LabelAccessGuard(srcRepository, assignmentRepository,
                        new DeidentReportGate(videoRepository)),
                new ObjectMapper(), mock(LsLabelRepository.class), mock(ApplicationEventPublisher.class),
                mock(ReviewApprovalGate.class), mock(LsDataLblHstryRepository.class),
                mock(LsDataLblAttrValRepository.class), mock(FrameBoundsResolver.class),
                mock(UserNameResolver.class));

        // when / then — 잠금 배너(lockSttsCd)를 담은 200 이 아니라 412 로 거부되어야 한다.
        assertThatThrownBy(() -> gated.getByFrame(SRC_SN, worker()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // then — 순서 고정(이 테스트의 본질): 신고 게이트는 락 상태 판정보다 <b>먼저</b> 평가된다.
        //   리팩토링으로 lockSttsCd 계산이 게이트 앞으로 올라가면 여기서 깨진다. 라벨 본문 조회도
        //   일어나지 않아야 한다 — 게이트가 막는 대상이 바로 그 라벨 좌표(PII 위치)다.
        verify(lockService, never()).isRawLocked(anyLong());
        verifyNoInteractions(labelRepository);
    }

    @Test
    @DisplayName("BE_응답_상수와_FE_판정_상수가_동일한_문자열이다")
    void beResponseConstantMatchesFrontendConstant() throws Exception {
        // 저장소 레이아웃: backend/ 와 frontend/ 가 형제. 접근 불가 환경(CI 분리 등)에선 스킵.
        // (선례: CocoClassesDriftTest — ai-server 정본 파일 대조)
        Path types = Path.of("..", "frontend", "src", "features", "label", "types.ts");
        assumeTrue(Files.exists(types), "frontend 소스 미접근 — BE/FE 계약 드리프트 검증 스킵");

        String src = Files.readString(types);
        Matcher m = Pattern.compile("LOCKED_FOR_REDEIDENT:\\s*'([^']+)'").matcher(src);
        assertThat(m.find()).as("FE LockSttsCd 상수 선언을 찾지 못함").isTrue();
        String feConstant = m.group(1);

        // BE 상수 + 실제로 내려보내는 값(서비스 산출) 둘 다 대조한다 — 주석/문서가 아니라 응답
        // 실값까지 고정해야 오탈자성 드리프트가 다시 통과하지 못한다.
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);
        String beEmitted = service.getByFrame(SRC_SN, worker()).lockSttsCd();

        assertThat(LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT)
                .as("FE 판정 상수 ↔ BE 응답 상수 동치")
                .isEqualTo(feConstant);
        assertThat(beEmitted)
                .as("FE 판정 상수 ↔ BE 응답 실값 동치(어긋나면 서버 잠금이 화면에 반영되지 않는다)")
                .isEqualTo(feConstant);
    }
}
