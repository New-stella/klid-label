package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (키포인트) — SKELETON(17-keypoint COCO 포즈) 삼중값 저장/검증 + 기존 4타입 회귀 격리 검증.
 */
class LabelServiceKeypointTest {

    private static final Long SRC_SN = 7101L;
    private static final Long RAW_SN = 8101L;
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
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        objectMapper = new ObjectMapper();

        service = new LabelService(labelRepository, aiInfoRepository, srcRepository,
                videoRepository, workLockService, accessGuard, objectMapper,
                lsLabelRepository, eventPublisher, rawDataStatusRepository,
                mock(LsDataLblHstryRepository.class));

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(src);
        when(accessGuard.parseUserNo(any())).thenReturn(1001L);
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aiInfoRepository.findByDataLblSnIn(anyCollection())).thenReturn(List.of());
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(src));
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of());
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    /** v 를 지정한 17개 삼중값 키포인트 생성. */
    private static List<List<Double>> skeleton17(int fixedV) {
        List<List<Double>> pts = new ArrayList<>(17);
        for (int i = 0; i < 17; i++) {
            pts.add(List.of(i * 5.0, i * 7.0, (double) fixedV));
        }
        return pts;
    }

    /** v 를 고정한 17개 삼중값의 저장용 JSON ([[x,y,v],x17]) — UPDATE 경로 기존 라벨 시드용. */
    private static String skeleton17Json(int fixedV) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i * 5.0).append(',').append(i * 7.0).append(',').append(fixedV).append(']');
        }
        return sb.append(']').toString();
    }

    private static void setLblSn(LsDataLbl target, long value) {
        try {
            Field f = LsDataLbl.class.getDeclaredField("lblSn");
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LabelResponse upsert(LabelItemDto item) {
        return service.bulkUpsert(SRC_SN, new LabelBulkUpsertRequest(List.of(item)), worker());
    }

    @Test
    @DisplayName("SKELETON_17개_삼중값_저장후_조회시_동일값_반환")
    void skeletonRoundTrip() {
        List<List<Double>> input = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            input.add(List.of(i * 3.5, i * 4.25, (double) (i % 3))); // v 0/1/2 순환
        }
        LabelItemDto item = new LabelItemDto(null, "SKELETON", null, "person", input, null);

        LabelResponse resp = upsert(item);

        List<List<Double>> returned = resp.items().get(0).points();
        assertThat(returned).hasSize(17);
        for (int i = 0; i < 17; i++) {
            assertThat(returned.get(i)).containsExactly(i * 3.5, i * 4.25, (double) (i % 3));
        }
        assertThat(resp.items().get(0).lblTypeCd()).isEqualTo("SKELETON");
    }

    @Test
    @DisplayName("SKELETON_포인트_16개면_400")
    void skeleton16Rejected() {
        List<List<Double>> sixteen = new ArrayList<>(skeleton17(2));
        sixteen.remove(0);
        LabelItemDto item = new LabelItemDto(null, "SKELETON", null, "person", sixteen, null);

        assertThatThrownBy(() -> upsert(item)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("SKELETON_가시성_v가_3이면_400")
    void skeletonVisibility3Rejected() {
        LabelItemDto item = new LabelItemDto(null, "SKELETON", null, "person", skeleton17(3), null);

        assertThatThrownBy(() -> upsert(item)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("SKELETON_원소가_2튜플이면_400")
    void skeletonTwoTupleElementRejected() {
        List<List<Double>> twoTuple = new ArrayList<>(17);
        for (int i = 0; i < 17; i++) {
            twoTuple.add(List.of(i * 1.0, i * 2.0)); // v 누락 (size=2)
        }
        LabelItemDto item = new LabelItemDto(null, "SKELETON", null, "person", twoTuple, null);

        assertThatThrownBy(() -> upsert(item)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("SKELETON_원소에_null이면_400")
    void skeletonNullElementRejected() {
        // v(가시성) 자리에 null — JSON [[10,20,null],...] 가 역직렬화된 형태.
        List<List<Double>> vNull = new ArrayList<>(skeleton17(1));
        vNull.set(0, Arrays.asList(10.0, 20.0, (Double) null));
        LabelItemDto vNullItem = new LabelItemDto(null, "SKELETON", null, "person", vNull, null);
        assertThatThrownBy(() -> upsert(vNullItem))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // y 좌표 자리에 null — [[10,null,2],...].
        List<List<Double>> yNull = new ArrayList<>(skeleton17(1));
        yNull.set(3, Arrays.asList(10.0, (Double) null, 2.0));
        LabelItemDto yNullItem = new LabelItemDto(null, "SKELETON", null, "person", yNull, null);
        assertThatThrownBy(() -> upsert(yNullItem))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("SKELETON_기존라벨_업데이트시_삼중값_갱신")
    void skeletonUpdateTripletChanged() {
        // given — 기존 SKELETON 라벨(v 전부 0) 이 DB 에 존재. UPDATE 경로(updateUserContent) 를 탄다.
        LsDataLbl existing = LsDataLbl.createManual(
                SRC_SN, "SKELETON", null, "person", skeleton17Json(0), 1001L);
        setLblSn(existing, 555L);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(existing));

        // when — 같은 라벨 id 로 v 를 2 로 바꿔 bulkUpsert (INSERT 아님).
        LabelItemDto item = new LabelItemDto(555L, "SKELETON", null, "person", skeleton17(2), null);
        LabelResponse resp = upsert(item);

        // then — 응답 삼중값 v 가 2 로 갱신 (UPDATE 경로 round-trip). 좌표도 새 값 반영.
        List<List<Double>> returned = resp.items().get(0).points();
        assertThat(returned).hasSize(17);
        for (int i = 0; i < 17; i++) {
            assertThat(returned.get(i)).containsExactly(i * 5.0, i * 7.0, 2.0);
        }
        // 저장 대상 엔티티의 POINT_CN 도 삼중값 v 를 담고 있어야 한다.
        assertThat(existing.getPointCn()).contains(",2]");
    }

    @Test
    @DisplayName("SKELETON_v0_미표기_점_x_y_0_허용")
    void skeletonV0ZeroCoordsAllowed() {
        List<List<Double>> pts = new ArrayList<>(17);
        for (int i = 0; i < 17; i++) {
            pts.add(List.of(0.0, 0.0, 0.0)); // 모두 미표기(v=0), x,y=0
        }
        LabelItemDto item = new LabelItemDto(null, "SKELETON", null, "person", pts, null);

        assertThatCode(() -> upsert(item)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기존_BBOX_저장조회_회귀없음")
    void bboxRegression() {
        List<List<Double>> box = List.of(List.of(10.0, 20.0), List.of(110.0, 220.0));
        LabelItemDto item = new LabelItemDto(null, "BBOX", null, "car", box, null);

        LabelResponse resp = upsert(item);

        assertThat(resp.items().get(0).points()).containsExactly(
                List.of(10.0, 20.0), List.of(110.0, 220.0));
        assertThat(resp.items().get(0).lblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("기존_POLYGON_저장조회_회귀없음")
    void polygonRegression() {
        List<List<Double>> poly = List.of(
                List.of(0.0, 0.0), List.of(50.0, 0.0), List.of(50.0, 50.0), List.of(0.0, 50.0));
        LabelItemDto item = new LabelItemDto(null, "POLYGON", null, "wall", poly, null);

        LabelResponse resp = upsert(item);

        assertThat(resp.items().get(0).points()).isEqualTo(poly);
    }

    @Test
    @DisplayName("기존_SEGMENT_TRACK_저장조회_회귀없음")
    void segmentAndTrackRegression() {
        List<List<Double>> seg = List.of(List.of(1.0, 2.0), List.of(3.0, 4.0), List.of(5.0, 6.0));
        LabelResponse segResp = upsert(new LabelItemDto(null, "SEGMENT", null, "road", seg, null));
        assertThat(segResp.items().get(0).points()).isEqualTo(seg);

        List<List<Double>> track = List.of(List.of(7.0, 8.0), List.of(9.0, 10.0));
        LabelResponse trackResp = upsert(new LabelItemDto(null, "TRACK", null, "bus", track, null));
        assertThat(trackResp.items().get(0).points()).isEqualTo(track);
    }
}
