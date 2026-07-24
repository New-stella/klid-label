package kr.co.cudo.authoring.dataset.service;

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
import kr.co.cudo.authoring.dataset.dto.FramePrivacyBulkItem;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FramePrivacyMetaService 단위 테스트 (Mockito).
 *
 * <p>파생 프리필/수동값 우선, 저장 후 유지, TASK_MODIFIED(검수 완료 후) 발행, guard IDOR 전파,
 * 벌크 디바운스 그룹핑(동일 rawSn)을 검증한다. 실제 403/404 는 컨트롤러 통합 테스트에서 검증한다.
 */
class FramePrivacyMetaServiceTest {

    private static final Long SRC_SN = 7201L;
    private static final Long RAW_SN = 8201L;
    private static final String ACTOR_SUB = "1001";

    private LabelAccessGuard guard;
    private LsDataSrcRepository srcRepository;
    private VideoRepository videoRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private ApplicationEventPublisher eventPublisher;
    private FramePrivacyMetaService service;

    @BeforeEach
    void setUp() {
        guard = mock(LabelAccessGuard.class);
        srcRepository = mock(LsDataSrcRepository.class);
        videoRepository = mock(VideoRepository.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new FramePrivacyMetaService(
                guard, srcRepository, videoRepository, rawDataStatusRepository, eventPublisher);
        when(guard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataSrc stubSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        // TokenClaims 는 매 reviewer() 호출마다 exp(Instant.now())가 달라 equals 불일치 → any() 매처로 바인딩.
        when(guard.verifyAndGet(eq(srcSn), any(TokenClaims.class))).thenReturn(src);
        return src;
    }

    /** raw 개인정보 유형별 파생 프리필 소스 스텁. */
    private void stubRaw(String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-P-001", "CCTV-1", "EVT", "11680", prvcTypeCd, "/var/raw/clip.mp4",
                java.time.LocalDateTime.now(), 30);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
    }

    private void seedStatus(String stts) {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(stts);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    @Test
    @DisplayName("저장값_없으면_파생값_프리필")
    void 저장값없으면_파생프리필() {
        // given — 프레임 수동 미저장, 영상 개인정보 유형=PSDO(가명)
        stubSrc(SRC_SN);
        stubRaw(LsDataRaw.PRVC_TYPE_PSDO);

        // when
        FramePrivacyMetaResponse res = service.get(SRC_SN, reviewer());

        // then — pseudonymity=Y(PSDO), anonymity=N(ANONY 아님), privacyIncluded=Y(PSDO→prvcYn Y)
        assertThat(res.pseudonymity()).isEqualTo("Y");
        assertThat(res.anonymity()).isEqualTo("N");
        assertThat(res.privacyIncluded()).isEqualTo("Y");
    }

    @Test
    @DisplayName("프레임_수동저장후_재조회_유지")
    void 수동저장후_재조회유지() {
        // given
        stubSrc(SRC_SN);
        stubRaw(LsDataRaw.PRVC_TYPE_ANONY);
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        // when — 파생과 다른 수동값 저장 후 재조회
        service.update(SRC_SN, new FramePrivacyMetaUpdateRequest(SRC_SN, "N", "Y", "N"), reviewer());
        FramePrivacyMetaResponse res = service.get(SRC_SN, reviewer());

        // then — 수동값이 파생을 덮어 유지
        assertThat(res.anonymity()).isEqualTo("N");
        assertThat(res.pseudonymity()).isEqualTo("Y");
        assertThat(res.privacyIncluded()).isEqualTo("N");
    }

    @Test
    @DisplayName("검수전_상태_수정시_TaskModifiedEvent_미발행")
    void 검수전_미발행() {
        stubSrc(SRC_SN);
        stubRaw(LsDataRaw.PRVC_TYPE_PRVC);
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);

        service.update(SRC_SN, new FramePrivacyMetaUpdateRequest(SRC_SN, "N", "N", "Y"), reviewer());

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("APPROVED_영상_수정시_TASK_MODIFIED_발행_META_UPDATED")
    void 검수완료_발행() {
        stubSrc(SRC_SN);
        stubRaw(LsDataRaw.PRVC_TYPE_PRVC);
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        service.update(SRC_SN, new FramePrivacyMetaUpdateRequest(SRC_SN, "N", "N", "Y"), reviewer());

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().rawSn()).isEqualTo(RAW_SN);
        assertThat(captor.getValue().changeType()).isEqualTo(ChangeType.META_UPDATED);
    }

    @Test
    @DisplayName("guard_403_예외_전파_및_통지_미발행")
    void guard_403_전파() {
        TokenClaims actor = reviewer();
        when(guard.verifyAndGet(SRC_SN, actor))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."));

        assertThatThrownBy(() -> service.update(SRC_SN,
                new FramePrivacyMetaUpdateRequest(SRC_SN, "Y", "Y", "Y"), actor))
                .isInstanceOf(CustomException.class);
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    /** 벌크 조회(findAllById) 스텁 — 동일 rawSn 프레임 3건. */
    private java.util.List<LsDataSrc> stubBulkSrcs() {
        LsDataSrc s1 = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        LsDataSrc s2 = LsDataSrc.create(RAW_SN, 1, "/raw/f1.jpg", null);
        LsDataSrc s3 = LsDataSrc.create(RAW_SN, 2, "/raw/f2.jpg", null);
        ReflectionTestUtils.setField(s1, "srcSn", 7201L);
        ReflectionTestUtils.setField(s2, "srcSn", 7202L);
        ReflectionTestUtils.setField(s3, "srcSn", 7203L);
        java.util.List<LsDataSrc> all = List.of(s1, s2, s3);
        when(srcRepository.findAllById(any())).thenReturn(all);
        return all;
    }

    @Test
    @DisplayName("벌크_PUT시_동일rawSn_다건_저장되고_통지는_디바운스")
    void 벌크_동일rawSn_디바운스그룹() {
        // given — 같은 rawSn 의 3개 프레임, APPROVED (통지 대상). 벌크 조회 + rawSn 단위 인가.
        stubBulkSrcs();
        stubRaw(LsDataRaw.PRVC_TYPE_PRVC);
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        // when
        List<FramePrivacyMetaResponse> res = service.updateBulk(List.of(
                new FramePrivacyBulkItem(7201L, "N", "N", "Y"),
                new FramePrivacyBulkItem(7202L, "N", "Y", "Y"),
                new FramePrivacyBulkItem(7203L, "Y", "N", "N")), reviewer());

        // then — 3건 저장, 발행된 이벤트는 모두 동일 rawSn(디바운서가 60초 윈도우에서 1회로 코얼레스)
        assertThat(res).hasSize(3);
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(3)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).allMatch(e -> RAW_SN.equals(e.rawSn()));
        assertThat(captor.getAllValues()).extracting(TaskModifiedEvent::srcSn)
                .containsExactlyInAnyOrder(7201L, 7202L, 7203L);
    }

    @Test
    @DisplayName("대량_벌크는_프레임수만큼_findById하지_않고_벌크조회_인가는_rawSn당_1회_saveAll_1회")
    void 벌크_N플러스1_제거_및_인가1회() {
        // given — 동일 rawSn 프레임 3건(대량 벌크의 대표). 미승인이라 통지는 없음(성능 축만 검증).
        stubBulkSrcs();
        stubRaw(LsDataRaw.PRVC_TYPE_PRVC);
        seedStatus(LsRawDataStatus.STTS_IN_REVIEW);
        TokenClaims actor = reviewer();

        // when
        service.updateBulk(List.of(
                new FramePrivacyBulkItem(7201L, "N", "N", "Y"),
                new FramePrivacyBulkItem(7202L, "N", "Y", "Y"),
                new FramePrivacyBulkItem(7203L, "Y", "N", "N")), actor);

        // then — 프레임별 findById(verifyAndGet) 미사용, 벌크 조회 1회, rawSn 단위 인가 1회, saveAll 1회.
        verify(srcRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(guard, never()).verifyAndGet(any(), any(TokenClaims.class));
        verify(guard, org.mockito.Mockito.times(1)).verifyRawAccess(eq(RAW_SN), eq(actor));
        verify(srcRepository, org.mockito.Mockito.times(1)).saveAll(any());
        verify(srcRepository, never()).save(any());
    }

    @Test
    @DisplayName("벌크_미존재_srcSn_포함시_404_전파_및_통지_미발행")
    void 벌크_미존재_404() {
        // given — 조회 결과에 없는 srcSn(7299) 포함 (findAllById 가 누락). IDOR/404 방어 보존.
        stubBulkSrcs();
        stubRaw(LsDataRaw.PRVC_TYPE_PRVC);
        seedStatus(LsRawDataStatus.STTS_APPROVED);

        // when / then — 미존재 프레임 항목에서 404 로 거부(전체 롤백). 미존재 항목이 앞서므로 통지 미발행.
        assertThatThrownBy(() -> service.updateBulk(List.of(
                new FramePrivacyBulkItem(7299L, "N", "Y", "Y"),
                new FramePrivacyBulkItem(7201L, "N", "N", "Y")), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }
}
