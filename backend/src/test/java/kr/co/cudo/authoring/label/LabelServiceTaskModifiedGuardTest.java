package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
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
import static org.mockito.Mockito.times;
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
    private ReviewApprovalGate approvalGate;
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
        approvalGate = mock(ReviewApprovalGate.class);

        service = new LabelService(labelRepository, aiInfoRepository, srcRepository,
                videoRepository, workLockService, accessGuard, new ObjectMapper(),
                lsLabelRepository, eventPublisher, approvalGate,
                mock(LsDataLblHstryRepository.class), mock(LsDataLblAttrValRepository.class),
                mock(kr.co.cudo.authoring.label.service.FrameBoundsResolver.class),
                mock(kr.co.cudo.authoring.user.service.UserNameResolver.class));
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
        // C-ISSUE-21 — bulkUpsert 는 프레임 행 락과 동시에 <b>DB 현재</b> 라벨셋 버전을 스칼라로 읽는다
        //   (1차 캐시 우회 — 엔티티 조회로는 락 획득 前 값이 반환돼 CAS 가 무력화된다).
        when(srcRepository.lockAndReadLabelVersion(any())).thenReturn(java.util.Optional.of(0L));
        when(accessGuard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);
        when(accessGuard.parseUserNo(any())).thenReturn(1001L);
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of());
        when(aiInfoRepository.findByDataLblSnIn(anyCollection())).thenReturn(List.of());
    }

    private void seedStatus(String stts) {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(LsRawDataStatus.STTS_APPROVED.equals(stts));
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
        when(approvalGate.isApproved(RAW_SN)).thenReturn(false);

        // when
        service.bulkUpsert(SRC_SN, oneManualLabel(), worker());

        // then
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("검수완료_APPROVED_후_라벨_추가시_LABEL_ADDED_가_실제로_발행된다")
    void 검수완료_발행() {
        // given — 영상이 검수 완료(APPROVED) 상태 + 기존 라벨 없음 → 이번 저장은 순수 '추가'
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
        // D-ISSUE-44 — 구 구현은 전부 LABEL_UPDATED 로 뭉개 LABEL_ADDED 가 계약에만 존재하는
        // dead 값이었다. 추가 저장은 반드시 LABEL_ADDED 로 발행되어야 한다.
        assertThat(event.changeType()).isEqualTo(ChangeType.LABEL_ADDED);
        // 발행된 changeType 은 반드시 계약 표준 집합에 속해야 한다 (비표준 문자열 회귀 방어)
        assertThat(ChangeType.ALL).contains(event.changeType());
        assertThat(event.modifierNo()).isEqualTo(1001L);
        // C-4(Phase 5C) — 승인 후 라벨 수정은 export 를 새 버전으로 전량 재생성하므로 exportRegenerated=true.
        //   디바운스 flush 가 export(force=true)→전 프레임 통지 순서로 데이터마트 파일을 동기화한다.
        assertThat(event.exportRegenerated()).isTrue();
        // Phase 7a-1 — 사람이 콘텐츠를 고치는 경로라 재검토 표시 축도 true 로 실린다.
        assertThat(event.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("검수완료_후_기존라벨이_전부_빠지면_LABEL_DELETED_가_발행된다")
    void 검수완료_삭제_발행() {
        // given — 기존 라벨 1건 + full-replace 로 빈 목록 저장 = 삭제
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        LsDataLbl existing = LsDataLbl.createManual(SRC_SN, "BBOX", null, "person",
                "[[1.0,1.0],[2.0,2.0]]", 1001L);
        setField(existing, "lblSn", 7001L);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(existing));

        // when
        service.bulkUpsert(SRC_SN, new LabelBulkUpsertRequest(List.of()), worker());

        // then
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(ChangeType.LABEL_DELETED);
    }

    @Test
    @DisplayName("검수완료_후_기존라벨_좌표만_수정하면_LABEL_UPDATED_만_발행된다")
    void 검수완료_수정_발행() {
        // given — 기존 라벨 1건을 같은 lblSn 으로 좌표만 바꿔 저장한다(추가·삭제 없음).
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        LsDataLbl existing = LsDataLbl.createManual(SRC_SN, "BBOX", null, "person",
                "[[1.0,1.0],[2.0,2.0]]", 1001L);
        setField(existing, "lblSn", 7003L);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(existing));
        LabelItemDto moved = new LabelItemDto(7003L, "BBOX", null, "person",
                List.of(List.of(9.0, 9.0), List.of(10.0, 10.0)), null);

        // when
        service.bulkUpsert(SRC_SN, new LabelBulkUpsertRequest(List.of(moved)), worker());

        // then — UPDATED 단독 발행(ADDED/DELETED 가 섞이면 관제가 잘못된 변경종류를 받는다).
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(ChangeType.LABEL_UPDATED);
    }

    @Test
    @DisplayName("추가와_삭제가_한_저장에_섞이면_두_종류가_모두_발행된다")
    void 검수완료_추가삭제_혼재_발행() {
        // given — 기존 라벨은 요청에서 빠지고(삭제) 새 라벨 1건이 추가된다.
        stubCommon();
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        LsDataLbl existing = LsDataLbl.createManual(SRC_SN, "BBOX", null, "car",
                "[[3.0,3.0],[4.0,4.0]]", 1001L);
        setField(existing, "lblSn", 7002L);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(existing));

        // when
        service.bulkUpsert(SRC_SN, oneManualLabel(), worker());

        // then — 디바운서가 (srcSn ↔ 변경종류) 페어로 축적하므로 종류별 발행이 안전하다.
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TaskModifiedEvent::changeType)
                .containsExactlyInAnyOrder(ChangeType.LABEL_ADDED, ChangeType.LABEL_DELETED);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
