package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.FrameDescriptionResponse;
import kr.co.cudo.authoring.label.service.FrameDescriptionService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * FrameDescriptionService 단위 테스트 (Mockito).
 *
 * <p>저장·조회 로직과 TASK_MODIFIED(검수 완료 후에만) 발행 가드를 검증한다.
 * 인가는 LabelAccessGuard 재사용을 mock 으로 확인하고, 실제 IDOR 403 은 컨트롤러 통합 테스트에서 검증한다.
 */
class FrameDescriptionServiceTest {

    private static final Long SRC_SN = 7101L;
    private static final Long RAW_SN = 8101L;
    private static final String ACTOR_SUB = "1001";

    private LabelAccessGuard guard;
    private LsDataSrcRepository srcRepository;
    private ApplicationEventPublisher eventPublisher;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private FrameDescriptionService service;

    @BeforeEach
    void setUp() {
        guard = mock(LabelAccessGuard.class);
        srcRepository = mock(LsDataSrcRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        service = new FrameDescriptionService(guard, srcRepository, eventPublisher, rawDataStatusRepository);
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataSrc stubSrc() {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(guard.verifyAndGet(SRC_SN, reviewer())).thenReturn(src);
        when(guard.verifyAndGet(any(Long.class), any(TokenClaims.class))).thenReturn(src);
        when(guard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);
        return src;
    }

    private void seedStatus(String stts) {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(stts);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    @Test
    @DisplayName("설명_저장후_조회시_동일값_반환")
    void 저장후_조회_동일값() {
        // given
        stubSrc();
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        // when
        service.update(SRC_SN, "교차로에서 보행자가 무단횡단", reviewer());
        FrameDescriptionResponse res = service.get(SRC_SN, reviewer());

        // then — srcSn 라운드트립(영속 PK)은 컨트롤러 통합 테스트에서 검증. 여기선 저장→조회 값 일치 확인.
        assertThat(res.description()).isEqualTo("교차로에서 보행자가 무단횡단");
    }

    @Test
    @DisplayName("빈값_저장시_설명_삭제_null허용")
    void 빈값_삭제_null() {
        // given
        LsDataSrc src = stubSrc();
        src.updateDescription("기존 설명");
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        // when — 빈 문자열 저장
        FrameDescriptionResponse res = service.update(SRC_SN, "   ", reviewer());

        // then — blank 는 null 로 정규화(삭제)
        assertThat(res.description()).isNull();
    }

    @Test
    @DisplayName("검수전_상태_설명수정시_TaskModifiedEvent_미발행")
    void 검수전_미발행() {
        // given
        stubSrc();
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        // when
        service.update(SRC_SN, "설명", reviewer());

        // then
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("APPROVED_영상_설명수정시_TASK_MODIFIED_발행_META_UPDATED")
    void 검수완료_발행() {
        // given
        stubSrc();
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        // when
        service.update(SRC_SN, "설명", reviewer());

        // then
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.rawSn()).isEqualTo(RAW_SN);
        assertThat(event.srcSn()).isEqualTo(SRC_SN);
        assertThat(event.changeType()).isEqualTo(ChangeType.META_UPDATED);
        assertThat(ChangeType.ALL).contains(event.changeType());
        assertThat(event.modifierNo()).isEqualTo(1001L);
        // HIGH-B(Phase 5C) — 프레임 설명은 export JSON 의 image.description 으로 나가므로 승인 후 수정 시
        //   export 폴더를 새 버전으로 재생성해야 데이터마트가 동기화된다. exportRegenerated=true 회귀 방어
        //   (4-arg=false 로 되돌리면 실패).
        assertThat(event.exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("guard_403_예외_전파_및_통지_미발행")
    void guard_403_전파() {
        // given — guard 가 IDOR 차단(403)
        TokenClaims actor = reviewer();
        when(guard.verifyAndGet(SRC_SN, actor))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."));

        // when / then
        assertThatThrownBy(() -> service.update(SRC_SN, "설명", actor))
                .isInstanceOf(CustomException.class);
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }
}
