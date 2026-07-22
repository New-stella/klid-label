package kr.co.cudo.authoring.evntanno;

import jakarta.validation.Validator;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
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
    private LsRawDataStatusRepository rawDataStatusRepository;
    private ApplicationEventPublisher eventPublisher;
    private Validator validator;
    private EvntAnnoService service;

    @BeforeEach
    void setUp() {
        annoRepository = mock(LsEvntAnnoRepository.class);
        reviewRepository = mock(LsEvntAnnoReviewRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(accessGuard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);

        @SuppressWarnings("unchecked")
        ObjectProvider<EvntAnnoService> self = mock(ObjectProvider.class);
        service = new EvntAnnoService(annoRepository, reviewRepository, accessGuard,
                rawDataStatusRepository, eventPublisher, validator, self);
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
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(stts);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
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
    }
}
