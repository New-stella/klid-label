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
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.json.VlmDescriptionPolicy;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
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
    @DisplayName("검수완료_APPROVED_후_메타update_시_TaskModifiedEvent_발행_changeType_META_UPDATED")
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
        // 계약 표준값 (이전 비표준 "META" → "META_UPDATED" 교정)
        assertThat(event.changeType()).isEqualTo(ChangeType.META_UPDATED);
        // 발행된 changeType 은 반드시 계약 표준 집합에 속해야 한다 (비표준 문자열 회귀 방어)
        assertThat(ChangeType.ALL).contains(event.changeType());
        assertThat(event.modifierNo()).isEqualTo(1001L);
        // "event" 키는 vd_description 조달에 참여하지 않으므로 디스크 산출물은 그대로다.
        assertThat(event.exportRegenerated()).isFalse();
    }

    // ---------- vd_description 조달 키 수정 시 export 재생성 (@req R10) ----------

    private void stubItem(String metaKey, String before) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        LsDataMeta meta = LsDataMeta.create(RAW_SN, metaKey, before);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, metaKey)).thenReturn(Optional.of(meta));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(meta));
    }

    private TaskModifiedEvent capturePublished() {
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("서술_전문_수정은_export_재생성을_동반한다")
    void 서술_전문_수정은_export_재생성을_동반한다() {
        // given — 승인 완료 영상의 vlm.description 을 REVIEWER 가 정정
        stubItem(VlmResultService.META_KEY_DESCRIPTION, "이전 서술");
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "정정 서술")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — export JSON 의 video.vd_description 이 바뀌므로 폴더를 새 버전으로 재생성해야 한다.
        assertThat(capturePublished().exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("레거시_구간_수정도_export_재생성을_동반한다")
    void 레거시_구간_수정도_export_재생성을_동반한다() {
        // given — 전문이 없는 영상은 레거시 구간을 이어붙여 vd_description 을 만든다.
        stubItem("0-8", "이전 구간");
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("0-8", "정정 구간")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then
        assertThat(capturePublished().exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("manual_timeseries_키_수정은_export를_재생성한다")
    void manual_timeseries_키_수정은_export를_재생성한다() {
        // given — manual-timeseries 는 사람이 직접 쓴 전문이며 vd_description 조달 우선순위 2다.
        stubItem(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "이전 전문");
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "정정 전문")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 재생성하지 않으면 저장은 됐는데 산출물은 옛 전문으로 고착된다.
        assertThat(capturePublished().exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("조달과_무관한_키는_값이_바뀌어도_재생성하지_않는다")
    void 조달과_무관한_키는_값이_바뀌어도_재생성하지_않는다() {
        // given — "event" 는 vd_description 조달 축이 아니다(기술메타도 읽기전용도 아니라 저장은 된다).
        stubItem("event", "이동");
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("event", "정차")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 디스크가 그대로인데 regen=true 로 새면 관제가 전 프레임을 헛 재픽업한다.
        assertThat(capturePublished().exportRegenerated()).isFalse();
    }

    // ---------- 값 무변경 저장은 재생성을 유발하지 않는다 (CWE-770) ----------

    @Test
    @DisplayName("값이_바뀌지_않으면_export를_재생성하지_않는다")
    void 값이_바뀌지_않으면_export를_재생성하지_않는다() {
        // given — REVIEWER 가 같은 값으로 저장 버튼을 다시 누른 상황(FE dirty 체크에 의존하지 않는다).
        stubItem(VlmResultService.META_KEY_DESCRIPTION, "동일 서술");
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "동일 서술")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 산출 내용이 같은데 재생성하면 v2·v3… 가 이미지 2벌과 함께 무한 적층된다.
        assertThat(capturePublished().exportRegenerated()).isFalse();
    }

    @Test
    @DisplayName("값이_바뀐_항목이_하나라도_있으면_재생성한다")
    void 값이_바뀐_항목이_하나라도_있으면_재생성한다() {
        // given — 무변경 항목과 실제 변경 항목이 한 요청에 섞여 있다.
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        LsDataMeta same = LsDataMeta.create(RAW_SN, VlmResultService.META_KEY_DESCRIPTION, "동일 서술");
        LsDataMeta changed = LsDataMeta.create(RAW_SN, "0-8", "이전 구간");
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.of(same));
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, "0-8")).thenReturn(Optional.of(changed));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(same, changed));
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "동일 서술"),
                new MetaUpdateRequest.Item("0-8", "정정 구간")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then
        assertThat(capturePublished().exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("조달_키의_신규_등록은_재생성한다")
    void 조달_키의_신규_등록은_재생성한다() {
        // given — 값이 없다가 생기는 것도 산출 내용 변경이다(신규 등록 → 검토행 생성 경로).
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        LsDataMeta saved = LsDataMeta.create(
                RAW_SN, VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "신규 전문");
        when(metaRepository.findByRawSnAndMetaKey(
                RAW_SN, VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(saved));
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(
                        VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "신규 전문")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then
        assertThat(capturePublished().exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("값_무변경_저장도_통지_자체는_발행한다")
    void 값_무변경_저장도_통지_자체는_발행한다() {
        // given — 재생성만 생략하고 저장·통지 계약은 그대로다(승인 후 수정 통지는 발행).
        stubItem(VlmResultService.META_KEY_DESCRIPTION, "동일 서술");
        seedStatus(LsRawDataStatus.STTS_APPROVED);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "동일 서술")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then
        TaskModifiedEvent event = capturePublished();
        assertThat(event.changeType()).isEqualTo(ChangeType.META_UPDATED);
        assertThat(event.exportRegenerated()).isFalse();
    }
}
