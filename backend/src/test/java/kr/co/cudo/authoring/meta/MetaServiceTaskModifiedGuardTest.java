package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetaService.update 의 TASK_MODIFIED 통지 발행 가드 단위 테스트 (Mockito).
 *
 * <p>라벨 경로와 동일하게, 검수 완료(APPROVED) 후 메타 수정 시에만 TASK_MODIFIED 를 발행한다.
 */
class MetaServiceTaskModifiedGuardTest {

    private static final Long SRC_SN = 7001L;
    private static final Long RAW_SN = 8001L;
    private static final String ACTOR_SUB = "1001";

    private LsDataMetaRepository metaRepository;
    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private ApplicationEventPublisher eventPublisher;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private MetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(LsDataMetaRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);

        service = new MetaService(metaRepository, srcRepository, authrtRepository,
                metaReviewRepository, eventPublisher, rawDataStatusRepository);
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private MetaUpdateRequest oneItem() {
        return new MetaUpdateRequest(List.of(new MetaUpdateRequest.Item("event", "정차")));
    }

    private void stubCommon() {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        LsDataMeta meta = LsDataMeta.create(RAW_SN, "event", "이동");
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, "event")).thenReturn(Optional.of(meta));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(meta));
    }

    private void seedStatus(String stts) {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(stts);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    @Test
    @DisplayName("검수전_IN_REVIEW_상태_메타update_시_TaskModifiedEvent_미발행")
    void 검수전_미발행() {
        // given — 검수 전(IN_REVIEW) 상태
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        // when
        service.update(SRC_SN, oneItem(), reviewer());

        // then
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("검수완료_APPROVED_후_메타update_시_TaskModifiedEvent_발행_changeType_META")
    void 검수완료_발행() {
        // given — 검수 완료(APPROVED) 상태
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        // when
        service.update(SRC_SN, oneItem(), reviewer());

        // then
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.rawSn()).isEqualTo(RAW_SN);
        assertThat(event.srcSn()).isEqualTo(SRC_SN);
        assertThat(event.changeType()).isEqualTo("META");
        assertThat(event.modifierNo()).isEqualTo(1001L);
    }
}
