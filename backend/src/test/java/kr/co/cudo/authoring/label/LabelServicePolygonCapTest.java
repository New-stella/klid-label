package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ISSUE-1 — SAM2 적재 폴리곤(>1000점)으로 인한 라벨 저장 전면 차단 회귀 방지 검증.
 *
 * <p>적재 경로(무제한)와 라벨 저장 검증(1000점 cap)의 정합성 불일치로, 작업자가 본인이 만들지
 * 않은 초과 폴리곤이 포함된 프레임을 수정 없이 저장만 해도 400 으로 차단되던 회귀를 막기 위해
 * 저장 직전 Douglas-Peucker simplify 로 상한 이하로 줄인다.
 */
class LabelServicePolygonCapTest {

    private static final Long SRC_SN = 7001L;
    private static final Long RAW_SN = 8001L;
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
    private ObjectMapper objectMapper;
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
        objectMapper = new ObjectMapper();

        service = new LabelService(labelRepository, aiInfoRepository, srcRepository,
                videoRepository, workLockService, accessGuard, objectMapper,
                lsLabelRepository, eventPublisher, approvalGate,
                mock(LsDataLblHstryRepository.class), mock(LsDataLblAttrValRepository.class),
                mock(kr.co.cudo.authoring.label.service.FrameBoundsResolver.class),
                mock(kr.co.cudo.authoring.user.service.UserNameResolver.class),
                new kr.co.cudo.authoring.label.service.FrameDiscardApplier(
                        mock(kr.co.cudo.authoring.batch.repository.LsDataSrcRepository.class),
                        mock(kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository.class)));

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(src);
        // C-ISSUE-21 — bulkUpsert 는 프레임 행 락과 동시에 <b>DB 현재</b> 라벨셋 버전을 스칼라로 읽는다
        //   (1차 캐시 우회 — 엔티티 조회로는 락 획득 前 값이 반환돼 CAS 가 무력화된다).
        when(srcRepository.lockAndReadLabelVersion(any())).thenReturn(java.util.Optional.of(0L));
        when(accessGuard.parseUserNo(any())).thenReturn(1001L);
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aiInfoRepository.findByDataLblSnIn(anyCollection())).thenReturn(List.of());
        when(approvalGate.isApproved(RAW_SN)).thenReturn(false);
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    /** SAM2 분할 결과처럼 형태를 가진 조밀 폐곡선(원 근사) — 단순화 후에도 3점 이상 유지된다. */
    private List<List<Double>> densePolygon(int n) {
        List<List<Double>> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            pts.add(List.of(1000 + Math.cos(t) * 500, 1000 + Math.sin(t) * 500));
        }
        return pts;
    }

    @Test
    @DisplayName("초과_폴리곤_포함_프레임_저장이_400없이_성공하고_좌표가_1000점_이하로_simplify된다")
    void oversizedPolygonIsSimplifiedAndSavedWithout400() throws Exception {
        // given — SAM2 적재로 DB 에 이미 존재하는 4192점 폴리곤(id 보유, 작업자 본인이 만들지 않음)을
        // FE 가 자신의 id 그대로 다시 제출(수정 없이 저장). 적재 라벨은 id 가 있으므로 enforceMaxPoints=false.
        Long existingId = 9100L;
        LsDataLbl existing = setLblSn(LsDataLbl.createAutoPolygon(SRC_SN, null, "person",
                LabelPointSerializer.toJson(toPoints(densePolygon(4192)), objectMapper), null), existingId);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(existing));

        LabelItemDto oversized = new LabelItemDto(existingId, "POLYGON", null, "person",
                densePolygon(4192), null);
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(oversized));

        // when/then — 더 이상 400 으로 거부되지 않는다.
        assertThatCode(() -> service.bulkUpsert(SRC_SN, req, worker())).doesNotThrowAnyException();

        // 기존 라벨 in-place 갱신 경로 — 갱신된 row 의 좌표가 상한 이하로 simplify 되었는지 확인.
        List<Point> saved = LabelPointSerializer.fromJson(existing.getPointCn(), objectMapper);
        assertThat(saved.size()).isLessThanOrEqualTo(LabelService.MAX_POINTS_PER_LABEL);
        assertThat(saved.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("신규_초과_폴리곤은_상한초과로_400_거부된다_CWE770_DoS방어")
    void oversizedNewPolygonRejectedWith400() {
        // given — id == null 신규 라벨에 4192점 입력. SAM2/수동 정상 입력은 상한 이하이므로 비정상.
        LabelItemDto oversizedNew = new LabelItemDto(null, "POLYGON", null, "wall",
                densePolygon(4192), null);
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(oversizedNew));

        // when/then — CWE-770 방어로 400(INVALID_INPUT) 거부.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.bulkUpsert(SRC_SN, req, worker()))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class);
    }

    /** 테스트 전용 — @Id lblSn 을 reflection 으로 주입(영속 시뮬레이션). */
    private static LsDataLbl setLblSn(LsDataLbl entity, Long id) throws Exception {
        java.lang.reflect.Field f = LsDataLbl.class.getDeclaredField("lblSn");
        f.setAccessible(true);
        f.set(entity, id);
        return entity;
    }

    private static List<Point> toPoints(List<List<Double>> nested) {
        List<Point> out = new ArrayList<>(nested.size());
        for (List<Double> pair : nested) {
            out.add(new Point(pair.get(0), pair.get(1)));
        }
        return out;
    }

    @Test
    @DisplayName("상한_이하_폴리곤은_점이_그대로_보존되어_저장된다")
    void smallPolygonPreserved() {
        // given — 5점 폴리곤
        LabelItemDto small = new LabelItemDto(null, "POLYGON", null, "car",
                densePolygon(5), null);
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(small));

        // when
        service.bulkUpsert(SRC_SN, req, worker());

        // then — 점 수 보존.
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(labelRepository).save(captor.capture());
        List<Point> saved = LabelPointSerializer.fromJson(captor.getValue().getPointCn(), objectMapper);
        assertThat(saved).hasSize(5);
    }
}
