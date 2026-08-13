package kr.co.cudo.authoring.evntanno;

import jakarta.validation.Validator;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvntAnnoService.upsert 의 TASK_MODIFIED 통지 발행 가드 단위 테스트 (Mockito).
 *
 * <p>MetaService 와 동일하게, 검수 완료(APPROVED) 후 수정 시에만 TASK_MODIFIED(META_UPDATED)를 발행한다.
 */
class EvntAnnoServiceTaskModifiedGuardTest {

    private static final Long RAW_SN = 8001L;
    private static final String ACTOR_SUB = "1001";

    private LsEvntAnnoRepository annoRepository;
    private LsEvntAnnoReviewRepository reviewRepository;
    private LabelAccessGuard accessGuard;
    private ReviewApprovalGate approvalGate;
    private ApplicationEventPublisher eventPublisher;
    private Validator validator;
    private EvntAnnoService service;

    @BeforeEach
    void setUp() {
        annoRepository = mock(LsEvntAnnoRepository.class);
        reviewRepository = mock(LsEvntAnnoReviewRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        approvalGate = mock(ReviewApprovalGate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(accessGuard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);

        @SuppressWarnings("unchecked")
        ObjectProvider<EvntAnnoService> self = mock(ObjectProvider.class);
        service = new EvntAnnoService(annoRepository, reviewRepository, accessGuard,
                approvalGate, eventPublisher, validator, self);
        // 재시도 래퍼가 self.getObject().upsertOnce(...) 를 호출하므로 자기 자신을 반환하게 한다.
        when(self.getObject()).thenReturn(service);

        // 기존 event_annotation 존재 → update 경로
        LsEvntAnno existing = LsEvntAnno.create(RAW_SN, "{\"event_class\":\"이동\"}", ACTOR_SUB);
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(existing));
        when(reviewRepository.findByEvntAnnoSn(any())).thenReturn(Collections.emptyList());
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private EventAnnotationPayload payload() {
        return new EventAnnotationPayload("정차", "무슨 이벤트?", null, null, null);
    }

    private void seedStatus(String stts) {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(LsRawDataStatus.STTS_APPROVED.equals(stts));
    }

    @Test
    @DisplayName("검수전_IN_REVIEW_상태_event_annotation_수정시_TaskModifiedEvent_미발행")
    void 검수전_미발행() {
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        service.upsert(RAW_SN, payload(), reviewer());

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("검수완료_APPROVED_후_수정시_TaskModifiedEvent_발행_changeType_META_UPDATED")
    void 검수완료_발행() {
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        service.upsert(RAW_SN, payload(), reviewer());

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.rawSn()).isEqualTo(RAW_SN);
        assertThat(event.changeType()).isEqualTo(ChangeType.META_UPDATED);
        assertThat(ChangeType.ALL).contains(event.changeType());
        assertThat(event.modifierNo()).isEqualTo(1001L);
        // ★단언 반전(구 기대값 isFalse 폐기) — 이 경로는 export 폴더를 <재생성해야> 한다.
        //   구 기대값의 근거는 "이 서비스가 재동결(materialize)을 하지 않아 재export 만 붙이면 승인 시점
        //   동결본이 그대로 나간다" 였는데, <재승인 경로에서는 성립하지 않는다>: 승인
        //   (ReviewService.approve)이 <같은 승인 트랜잭션에서> materialize 를 먼저 호출해 동결본을
        //   갱신하고, 재생성은 그 이후 디바운스 flush tick 에 일어나므로 export 는 이미 새 동결본을 읽는다.
        //   구 기대값을 유지하면 event_annotation <단독> 수정 시 윈도우의 재생성 축이 false 로 남아
        //   재승인해도 새 버전 폴더가 생기지 않고, 관제가 픽업하는 JSON 에 이전 어노테이션이 남는다.
        assertThat(event.exportRegenerated()).isTrue();
        // Phase 7a-1 — 사람이 콘텐츠를 고치는 경로라 재검토 표시 축은 true 로 실린다
        //   (exportRegenerated 와 독립 축 — 두 축을 하나로 합치지 말 것).
        assertThat(event.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("이벤트어노테이션_단독수정도_exportRegenerated_true_로_실려_재승인시_산출물이_재생성된다")
    void 단독수정_재생성축_true() {
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        // given: 이 저장 <하나만> 일어난다(다른 수정과 함께 저장되지 않는 단독 경로).
        // when
        service.upsert(RAW_SN, payload(), reviewer());

        // then: 재승인 시 재생성 여부는 디바운스 윈도우에 축적된 exportRegenerated 의 OR 누적이 정한다.
        //   따라서 이 경로가 단독으로 발생하면 <이 이벤트 하나가> true 여야만 export 가 실행된다.
        //   (다른 수정과 함께 저장된 경우는 OR 누적으로 이미 true 라 이 결함이 드러나지 않았다.)
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.exportRegenerated()).isTrue();
        // 재검수 강제 축은 그대로 유지된다(이번 변경이 건드리는 축이 아니다).
        assertThat(event.needsRecheck()).isTrue();
        // 영상 단위 변경이라 프레임 식별자는 싣지 않는다.
        assertThat(event.srcSn()).isNull();
    }
}
