package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelHistoryResponse;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 라벨 이력 응답의 작성자 <b>표시명</b> 노출 (GET /v1/frames/{srcSn}/label-history).
 *
 * <p>배경: 응답의 {@code actor} 가 사번({@code REG_ID}) 이라 화면에 "2001"·"1001" 같은 내부 번호가
 * 그대로 찍혔다. 표시명({@code LS_ACNT_USER.USER_NM})을 {@code actorName} 으로 함께 내려준다.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li><b>N+1 금지</b> — 페이지에 작성자가 몇 명 섞여도 사용자 마스터 조회는 <b>1회</b>.</li>
 *   <li>비숫자 사번·마스터 미존재·사번 null 은 <b>예외가 아니라 이름 {@code null}</b> — 조회는 계속 200.</li>
 *   <li><b>하위호환</b> — {@code actor} 는 여전히 <b>사번</b>을 담는다(이름으로 바꿔치기 금지).</li>
 * </ul>
 */
class LabelHistoryActorNameTest {

    private static final Long SRC_SN = 300L;
    private static final Long RAW_SN = 8101L;
    private static final Pageable PAGEABLE = PageRequest.of(0, 20);

    private LsDataLblHstryRepository labelHistoryRepository;
    private LabelAccessGuard accessGuard;
    private UserRepository userRepository;
    private LabelService service;

    private static final TokenClaims REVIEWER =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() {
        labelHistoryRepository = mock(LsDataLblHstryRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        userRepository = mock(UserRepository.class);

        // 이름 해석은 실제 헬퍼(UserNameResolver)를 쓴다 — N+1 단언이 "리포지토리 호출 횟수"에
        // 걸려야 회귀 가드로서 의미가 있다(헬퍼를 mock 하면 배치화 여부를 못 본다).
        service = new LabelService(
                mock(LsDataLblRepository.class), mock(LsDataLblAiInfoRepository.class),
                mock(LsDataSrcRepository.class), mock(VideoRepository.class),
                mock(WorkLockService.class), accessGuard, new ObjectMapper(),
                mock(LsLabelRepository.class), mock(ApplicationEventPublisher.class),
                mock(ReviewApprovalGate.class), labelHistoryRepository,
                mock(LsDataLblAttrValRepository.class), mock(FrameBoundsResolver.class),
                new UserNameResolver(userRepository),
                new kr.co.cudo.authoring.label.service.FrameDiscardApplier(
                        mock(kr.co.cudo.authoring.batch.repository.LsDataSrcRepository.class),
                        mock(kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository.class)));

        LsDataSrc frame = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(frame);
        lenient().when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
    }

    /** 저장 이벤트 이력 행 — 작성자 사번만 다르게(변경 1건은 팩토리 필수 조건). */
    private static LsDataLblHstry event(String regId) {
        return LsDataLblHstry.recordSaveEvent(SRC_SN, regId,
                List.of(LabelChange.added(1L, "person",
                        new LabelSnapshot("BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]"))));
    }

    private static LsAcntUser user(Long userNo, String userNm) {
        LsAcntUser u = mock(LsAcntUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserNm()).thenReturn(userNm);
        return u;
    }

    private void givenHistory(LsDataLblHstry... rows) {
        Page<LsDataLblHstry> page = new PageImpl<>(Arrays.asList(rows), PAGEABLE, rows.length);
        when(labelHistoryRepository.findBySrcSn(any(), any())).thenReturn(page);
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("이력_작성자_이름이_사용자마스터에서_매핑되고_사번은_그대로_유지된다")
    void actorNameIsResolvedAndActorStaysUserNo() {
        // given — 검수자1(사번 1) 이 저장한 이력 1건
        givenHistory(event("1"));
        // (mock 픽스처는 when(...) 밖에서 먼저 만든다 — 중첩 스터빙 금지)
        List<LsAcntUser> users = List.of(user(1L, "검수자1"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        Page<LabelHistoryResponse> page = service.getHistory(SRC_SN, REVIEWER, PAGEABLE);

        // then — 이름은 새 필드로, 사번은 기존 필드에 그대로(하위호환)
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).actorName()).isEqualTo("검수자1");
        assertThat(page.getContent().get(0).actor()).isEqualTo("1");
    }

    @Test
    @DisplayName("작성자가_여럿_섞여도_사용자_조회는_1회다")
    void userLookupIsBatchedIntoSingleQuery() {
        // given — 이력 5건(작성자 3명, 중복 포함)
        givenHistory(event("1"), event("100"), event("1"), event("101"), event("100"));
        List<LsAcntUser> users =
                List.of(user(1L, "검수자1"), user(100L, "작업자100"), user(101L, "작업자101"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        Page<LabelHistoryResponse> page = service.getHistory(SRC_SN, REVIEWER, PAGEABLE);

        // then — 조회 1회 · 중복 제거된 사번 3개만 전달 (행마다 조회하면 실패)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository, times(1)).findByUserNoIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 100L, 101L);
        verify(userRepository, never()).findByUserNo(any());
        assertThat(page.getContent()).extracting(LabelHistoryResponse::actorName)
                .containsExactly("검수자1", "작업자100", "검수자1", "작업자101", "작업자100");
    }

    @Test
    @DisplayName("비숫자_사번은_예외없이_이름이_null이다")
    void nonNumericUserNoYieldsNullName() {
        // given — 레거시/외부 채널 사번이 숫자가 아닌 경우 (REG_ID 는 VARCHAR)
        givenHistory(event("wkr01"));

        // when — NumberFormatException 으로 이력 조회 전체가 죽으면 안 된다
        Page<LabelHistoryResponse> page = service.getHistory(SRC_SN, REVIEWER, PAGEABLE);

        // then
        assertThat(page.getContent().get(0).actorName()).isNull();
        assertThat(page.getContent().get(0).actor()).isEqualTo("wkr01");
        // 파싱 가능한 사번이 하나도 없으면 사용자 조회 자체를 하지 않는다(불필요 쿼리 제거)
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("마스터에_없는_사번은_이름이_null이고_조회는_200이다")
    void unknownUserNoYieldsNullNameButStillReturns() {
        // given — 퇴사·삭제로 사용자 마스터에 999 가 없음
        givenHistory(event("999"));
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when / then — 예외 없이 응답(=200) + 이름만 null (FE 가 사번으로 폴백)
        Page<LabelHistoryResponse> page = service.getHistory(SRC_SN, REVIEWER, PAGEABLE);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).actorName()).isNull();
        assertThat(page.getContent().get(0).actor()).isEqualTo("999");
    }

    @Test
    @DisplayName("시스템_이력행은_사번도_이름도_null이다")
    void systemRowHasNullActorAndName() {
        // given — REG_ID 가 없는 시스템 이력 행
        givenHistory(event(null));

        // when
        Page<LabelHistoryResponse> page = service.getHistory(SRC_SN, REVIEWER, PAGEABLE);

        // then
        assertThat(page.getContent().get(0).actor()).isNull();
        assertThat(page.getContent().get(0).actorName()).isNull();
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("이력이_없으면_사용자조회도_하지_않는다")
    void emptyHistoryMeansNoUserLookup() {
        // given
        Page<LabelHistoryResponse> page;
        when(labelHistoryRepository.findBySrcSn(any(), any()))
                .thenReturn(new PageImpl<>(new ArrayList<>(), PAGEABLE, 0));

        // when
        page = service.getHistory(SRC_SN, REVIEWER, PAGEABLE);

        // then
        assertThat(page.getContent()).isEmpty();
        verify(userRepository, never()).findByUserNoIn(anyCollection());
    }
}
