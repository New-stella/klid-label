package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LabelService.bulkUpsert 의 TASK_MODIFIED 통지 발행 가드 단위 테스트 (Mockito).
 *
 * <p>핵심 정책(CLAUDE.md): TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다.
 * 검수 전(PENDING/ASSIGNED/IN_REVIEW 등) 저장은 일반 작업이므로 통지 미발행.
 */
class LabelServiceTaskModifiedGuardTest {

    private static final Long SRC_SN = 5001L;
    private static final Long RAW_SN = 9001L;
    private static final String ACTOR_SUB = "1001";

    private LsDataLblRepository labelRepository;
    private LsDataLblAiInfoRepository aiInfoRepository;
    private LsDataSrcRepository srcRepository;
    private VideoRepository videoRepository;
    private WorkLockService workLockService;
    private LabelAccessGuard accessGuard;
    private LsLabelRepository lsLabelRepository;
    private ApplicationEventPublisher eventPublisher;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private LabelService service;

    @BeforeEach
    void setUp() {
        labelRepository = mock(LsDataLblRepository.class);
        aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        videoRepository = mock(VideoRepository.class);
        workLockService = mock(WorkLockService.class);
        accessGuard = mock(LabelAccessGuard.class);
        lsLabelRepository = mock(LsLabelRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);

        service = new LabelService(labelRepository, aiInfoRepository, srcRepository,
                videoRepository, workLockService, accessGuard, new ObjectMapper(),
                lsLabelRepository, eventPublisher, rawDataStatusRepository,
                mock(LsDataLblHstryRepository.class));
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LabelBulkUpsertRequest oneManualLabel() {
        LabelItemDto item = new LabelItemDto(null, "BBOX", null, "person",
                List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null);
        return new LabelBulkUpsertRequest(List.of(item));
    }

    private void stubCommon() {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(accessGuard.verifyAndGet(SRC_SN, worker())).thenReturn(src);
        // 호출별 동일 객체 매칭이 어렵기 때문에 any() 로 안정화
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(src);
        when(accessGuard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);
        when(accessGuard.parseUserNo(any())).thenReturn(1001L);
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of());
        when(aiInfoRepository.findByDataLblSnIn(anyCollection())).thenReturn(List.of());
    }

    private void seedStatus(String stts) {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(stts);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    @Test
    @DisplayName("검수전_ASSIGNED_상태_bulkUpsert_시_TaskModifiedEvent_미발행")
    void 검수전_미발행() {
        // given — 영상이 검수 전(ASSIGNED) 상태
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_ASSIGNED);

        // when
        service.bulkUpsert(SRC_SN, oneManualLabel(), worker());

        // then — 통지 미발행
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("상태row_없음_미검수_간주_bulkUpsert_시_TaskModifiedEvent_미발행")
    void 상태없음_미발행() {
        // given — 상태 row 자체가 없음 → 미검수로 간주
        stubCommon();
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of());

        // when
        service.bulkUpsert(SRC_SN, oneManualLabel(), worker());

        // then
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("검수완료_APPROVED_후_bulkUpsert_시_TaskModifiedEvent_발행_rawSn_srcSn_changeType_검증")
    void 검수완료_발행() {
        // given — 영상이 검수 완료(APPROVED) 상태
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        // when
        service.bulkUpsert(SRC_SN, oneManualLabel(), worker());

        // then — 통지 1회 발행, 페이로드 검증
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.rawSn()).isEqualTo(RAW_SN);
        assertThat(event.srcSn()).isEqualTo(SRC_SN);
        // 계약 표준값 — bulkUpsert 는 add/update 혼재이므로 LABEL_UPDATED 로 통일
        assertThat(event.changeType()).isEqualTo(ChangeType.LABEL_UPDATED);
        // 발행된 changeType 은 반드시 계약 표준 집합에 속해야 한다 (비표준 문자열 회귀 방어)
        assertThat(ChangeType.ALL).contains(event.changeType());
        assertThat(event.modifierNo()).isEqualTo(1001L);
    }
}
