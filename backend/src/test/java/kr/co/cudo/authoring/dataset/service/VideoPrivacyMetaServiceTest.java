package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영상 단위 개인정보 메타 서비스 단위 테스트 (V163).
 *
 * <p>검증 축: 비식별 기본상수 프리필(DERIVED) · 수동값 우선(MANUAL) · 화이트리스트 검증(400·원문 미노출) ·
 * IDOR(403)/미존재(404) · PUT 전체교체 계약 · APPROVED 이후 수정 시 TASK_MODIFIED(exportRegenerated=true) ·
 * dirty checking(전체 save 금지).
 */
class VideoPrivacyMetaServiceTest {

    private static final Long RAW_SN = 88L;

    private VideoRepository videoRepository;
    private LabelAccessGuard accessGuard;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private LsDatasetVideoMetaRepository videoMetaRepository;
    private LsTaskEventLogRepository taskEventLogRepository;
    private ApplicationEventPublisher eventPublisher;
    private VideoPrivacyMetaService service;

    private final TokenClaims worker =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        videoMetaRepository = mock(LsDatasetVideoMetaRepository.class);
        taskEventLogRepository = mock(LsTaskEventLogRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new VideoPrivacyMetaService(videoRepository, accessGuard, rawDataStatusRepository,
                videoMetaRepository, taskEventLogRepository, eventPublisher);
        when(accessGuard.parseUserNo("100")).thenReturn(100L);
        // 승인 판정은 <잠금 없는 조회> + advisory 락 직렬화 조합이다(DEV_FIX 2차 — FOR SHARE 는 배치와 교착).
        when(rawDataStatusRepository.findByRawDataIdIn(any())).thenReturn(List.of());
    }

    /** 개인정보 수동값 미입력 상태의 영상. */
    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-PRV", "CCTV-1", "EVT01", "LG01",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/prv.mp4", LocalDateTime.of(2026, 3, 1, 10, 0), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        return raw;
    }

    /** 검수 완료(APPROVED) 상태로 세팅. */
    private void approved() {
        LsRawDataStatus status = mock(LsRawDataStatus.class);
        when(status.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    @Test
    @DisplayName("수동값_미입력이면_비식별_기본상수가_DERIVED_출처로_프리필된다")
    void 수동값_미입력이면_비식별_기본상수가_DERIVED_출처로_프리필된다() {
        // given — 수동 판정 없음
        raw();

        // when
        VideoPrivacyMetaResponse res = service.get(RAW_SN, worker);

        // then — 화면이 상수를 하드코딩하지 않도록 BE 가 프리필 + 출처를 내려준다
        assertThat(res.anonymity()).isEqualTo("Y");
        assertThat(res.pseudonymity()).isEqualTo("N");
        assertThat(res.privacyIncluded()).isEqualTo("N");
        assertThat(res.anonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
        assertThat(res.pseudonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
        assertThat(res.privacyIncludedSource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
    }

    @Test
    @DisplayName("수동값_저장후_조회하면_MANUAL_출처로_반환된다")
    void 수동값_저장후_조회하면_MANUAL_출처로_반환된다() {
        // given
        raw();

        // when — 사람이 "익명 아님 / 가명 포함 / 개인정보 포함"으로 판정
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "Y"), worker);
        VideoPrivacyMetaResponse res = service.get(RAW_SN, worker);

        // then
        assertThat(res.anonymity()).isEqualTo("N");
        assertThat(res.pseudonymity()).isEqualTo("Y");
        assertThat(res.privacyIncluded()).isEqualTo("Y");
        assertThat(res.anonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_MANUAL);
        assertThat(res.pseudonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_MANUAL);
        assertThat(res.privacyIncludedSource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_MANUAL);
    }

    @Test
    @DisplayName("PUT은_전체교체라_null_필드는_수동값이_삭제되고_프리필로_돌아간다")
    void PUT은_전체교체라_null_필드는_수동값이_삭제되고_프리필로_돌아간다() {
        // given — 이미 3필드 수동 저장된 영상
        raw();
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "Y"), worker);

        // when — 가명여부만 남기고 나머지는 null(삭제)로 전송
        VideoPrivacyMetaResponse res =
                service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest(null, "Y", null), worker);

        // then — 삭제된 필드는 기본상수 프리필(DERIVED)로 복귀
        assertThat(res.anonymity()).isEqualTo("Y");
        assertThat(res.anonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
        assertThat(res.privacyIncluded()).isEqualTo("N");
        assertThat(res.privacyIncludedSource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
        assertThat(res.pseudonymity()).isEqualTo("Y");
        assertThat(res.pseudonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_MANUAL);
    }

    @Test
    @DisplayName("허용값_외_입력은_400이고_입력원문을_응답에_싣지_않는다")
    void 허용값_외_입력은_400이고_입력원문을_응답에_싣지_않는다() {
        // given — 스크립트 삽입 시도(저장형 XSS 표면)
        raw();
        String malicious = "<script>alert(1)</script>";

        // when / then — 필드명만 알리고 원문은 미노출(CWE-117/209)
        assertThatThrownBy(() ->
                service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest(malicious, "N", "N"), worker))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getMessage()).doesNotContain(malicious).contains("anonymity");
                });
        // and — 소문자 y 등 유사값도 거부(화이트리스트)
        assertThatThrownBy(() ->
                service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("y", "N", "N"), worker))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("타인_배정_영상이면_403이고_저장이_수행되지_않는다")
    void 타인_배정_영상이면_403이고_저장이_수행되지_않는다() {
        // given — 인가 가드가 거부
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다."))
                .when(accessGuard).verifyRawAccess(anyLong(), any());

        // when / then
        assertThatThrownBy(() ->
                service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("Y", "N", "N"), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(videoRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("존재하지_않는_영상이면_404")
    void 존재하지_않는_영상이면_404() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.get(RAW_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("검수완료_영상_수정시_TASK_MODIFIED가_exportRegenerated_true로_발행된다")
    void 검수완료_영상_수정시_TASK_MODIFIED가_exportRegenerated_true로_발행된다() {
        // given
        raw();
        approved();

        // when
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "Y"), worker);

        // then — export 를 새 버전으로 전량 재생성한 뒤 통지가 나가야 한다
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent evt = captor.getValue();
        assertThat(evt.rawSn()).isEqualTo(RAW_SN);
        assertThat(evt.changeType()).isEqualTo(ChangeType.META_UPDATED);
        assertThat(evt.exportRegenerated()).isTrue();
        // 영상 단위 수정이므로 프레임 식별자는 없다
        assertThat(evt.srcSn()).isNull();
    }

    @Test
    @DisplayName("미검수_영상_수정시_통지를_발행하지_않는다")
    void 미검수_영상_수정시_통지를_발행하지_않는다() {
        // given — 상태 row 없음(미검수)
        raw();

        // when
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "N", "N"), worker);

        // then — 최초 승인 시점 export 가 최신 값을 산출하므로 통지 불필요
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("저장은_dirty_checking으로만_수행되고_전체_save를_호출하지_않는다")
    void 저장은_dirty_checking으로만_수행되고_전체_save를_호출하지_않는다() {
        // given
        LsDataRaw raw = raw();

        // when
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "N"), worker);

        // then — 배치가 쓰는 다른 컬럼을 stale 값으로 덮지 않도록 save/saveAndFlush 금지(CWE-362)
        verify(videoRepository, never()).save(any());
        verify(videoRepository, never()).saveAndFlush(any());
        assertThat(raw.getAnonyInclYn()).isEqualTo("N");
        assertThat(raw.getPsdoInclYn()).isEqualTo("Y");
        assertThat(raw.getPrvcInclYn()).isEqualTo("N");
    }

    // ─── DEV_FIX(2026-08-03) — 인가 회귀 / 신고 게이트 / 승인 판정 경합 ──────────────────

    /**
     * ★ GET 인가 회귀 가드 (적대검증 mutation 대응).
     *
     * <p>기존 테스트는 {@code update} 의 403 만 잡았고 <b>GET 의 {@code verifyRawAccess} 를 제거해도
     * RED 가 나지 않았다</b>(컨트롤러 테스트는 서비스를 mock 하므로 무력). 즉 GET IDOR(CWE-639)이
     * 회귀 무방비였다. 이 테스트가 그 seam 을 직접 덮는다 — {@code get()} 첫 줄의 가드 호출을 지우면
     * 반드시 실패해야 한다.
     */
    @Test
    @DisplayName("타인_배정_영상은_조회도_403이고_영상행을_읽지_않는다")
    void 타인_배정_영상은_조회도_403이다() {
        // given — 인가 가드가 거부(WORKER 가 본인 배정이 아닌 영상 조회 시도)
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다."))
                .when(accessGuard).verifyRawAccess(anyLong(), any());

        // when / then — 403 이고, 가드 이전에 데이터를 읽어 정보가 새지 않는다
        assertThatThrownBy(() -> service.get(RAW_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(videoRepository, never()).findById(any());
    }

    /**
     * 비식별 신고 구간(412) — 신고 접수가 이 3필드를 "재판정 대상"으로 리셋하는데 같은 구간에 PUT 으로
     * 옛 판정을 되돌릴 수 있으면 resolve 후 재산출 때 그 값이 그대로 관제로 나간다(라벨은 작업락으로
     * 409 차단되는데 개인정보 선언만 열려 있던 비대칭).
     */
    @Test
    @DisplayName("비식별_신고_구간_영상은_저장이_412로_차단되고_값이_바뀌지_않는다")
    void 비식별_신고_구간_영상은_저장이_412로_차단된다() {
        // given
        LsDataRaw raw = raw();
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        // when / then
        assertThatThrownBy(() ->
                service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "N", "N"), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(raw.getAnonyInclYn()).isNull();
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    /** GET 은 차단하지 않는다 — 값 자체가 PII 가 아니고, 막으면 신고 구간에 화면이 뜨지 않는다. */
    @Test
    @DisplayName("비식별_신고_구간이어도_조회는_차단하지_않는다")
    void 비식별_신고_구간이어도_조회는_차단하지_않는다() {
        // given
        raw();

        // when
        VideoPrivacyMetaResponse res = service.get(RAW_SN, worker);

        // then — 게이트는 저장 경로에만 걸린다(조회 경로에서 호출되지 않음)
        assertThat(res.rawSn()).isEqualTo(RAW_SN);
        verify(accessGuard, never()).requireNotUnderDeidentReport(any());
    }

    /**
     * 동시 승인 경합 창 차단 — <b>flush(raw 행락) → advisory 락 → 상태 판정</b> 순서
     * ({@code EnvironmentMetaService} 와 동일). 승인 경로의 {@code materialize} 가 같은 advisory 를
     * 잡으므로 어느 순서로 커밋되든 산출물이 stale 로 고착되지 않는다.
     *
     * <p><b>★ 상태 행을 FOR SHARE 로 잠그면 안 된다</b>(DEV_FIX 2차): 그러면 이 트랜잭션이
     * {@code raw → status} 순서가 되는데 {@code BatchTransitionService} 는 {@code status → raw} 라
     * 순환 대기(40P01)가 성립한다. 정적 회귀 가드는 {@code LockOrderGuardTest}.
     */
    @Test
    @DisplayName("승인_판정은_flush_후_advisory락을_잡고_수행된다 — 상태행_공유잠금_금지")
    void 승인_판정은_advisory락_직렬화로_수행된다() {
        // given
        raw();
        approved();

        // when
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "Y"), worker);

        // then — ① raw 행락(flush) → ② advisory → ③ 상태 판정 순서, 상태 행 잠금은 사용하지 않는다
        InOrder order = inOrder(videoRepository, videoMetaRepository, rawDataStatusRepository);
        order.verify(videoRepository).flush();
        order.verify(videoMetaRepository).acquireRawLock(RAW_SN);
        order.verify(rawDataStatusRepository).findByRawDataIdIn(List.of(RAW_SN));
        verify(rawDataStatusRepository, never()).findByRawDataIdForShare(anyLong());
        verify(eventPublisher).publishEvent(any(TaskModifiedEvent.class));
    }

    /**
     * 행 단위 감사(OWASP A09) — 누가·언제·어느 영상의 개인정보 선언을 바꿨는지 {@code LS_TASK_EVENT_LOG}
     * 에 1행 남긴다. 판단값(Y/N)은 남기지 않는다(CWE-359) — 사유는 "변경됨/변경 없음" 고정 문구뿐.
     */
    @Test
    @DisplayName("개인정보_선언_변경은_작업이벤트로그에_행단위로_감사된다")
    void 개인정보_선언_변경은_행단위로_감사된다() {
        // given
        raw();

        // when — 값이 실제로 바뀌는 저장
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "Y"), worker);

        // then
        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        LsTaskEventLog row = captor.getValue();
        assertThat(row.getRawDataId()).isEqualTo(RAW_SN);
        assertThat(row.getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_PRIVACY_META_UPDATE);
        assertThat(row.getActorUserNo()).isEqualTo(100L);
        assertThat(row.getRsn()).isEqualTo(LsTaskEventLog.RSN_PRIVACY_META_CHANGED);
        // 판단값(Y/N)은 어떤 필드에도 실리지 않는다.
        assertThat(row.getRsn()).doesNotContain("Y").doesNotContain("N");
    }

    @Test
    @DisplayName("값이_달라지지_않은_저장도_감사되지만_사유가_구분된다")
    void 무변경_저장도_감사된다() {
        // given — 이미 같은 값이 저장된 영상
        LsDataRaw raw = raw();
        raw.changePrivacyMeta("N", "Y", "Y");

        // when
        service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest("N", "Y", "Y"), worker);

        // then — 열어보고 그대로 저장한 것과 판정을 뒤집은 것을 사후에 구분할 수 있어야 한다
        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        assertThat(captor.getValue().getRsn()).isEqualTo(LsTaskEventLog.RSN_PRIVACY_META_UNCHANGED);
    }

    @Test
    @DisplayName("공백_문자열은_미입력으로_정규화되어_CHAR1_컬럼을_오염시키지_않는다")
    void 공백_문자열은_미입력으로_정규화되어_CHAR1_컬럼을_오염시키지_않는다() {
        // given
        LsDataRaw raw = raw();

        // when — 빈 문자열/공백 전송(정규화 대상)
        VideoPrivacyMetaResponse res =
                service.update(RAW_SN, new VideoPrivacyMetaUpdateRequest(" ", "", null), worker);

        // then — 컬럼은 null(미입력), 응답은 기본상수 프리필
        assertThat(raw.getAnonyInclYn()).isNull();
        assertThat(raw.getPsdoInclYn()).isNull();
        assertThat(raw.getPrvcInclYn()).isNull();
        assertThat(res.anonymitySource()).isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
    }
}
