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
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R5 프레임 4색 SAVED(연두) — 형제 프레임별 라벨 존재 플래그(hasLabel) 응답 검증.
 *
 * <p>배경: FE 프레임 strip 은 형제 프레임의 저장 여부를 알 방법이 없어 SAVED(연두)가 표시되지
 * 않았다. 이를 위해 LabelResponse.siblings[].hasLabel 을 추가하고, 라벨 보유 프레임 집합을
 * 프레임 수와 무관하게 단일 IN 쿼리(findDistinctSrcSnsWithLabelIn)로 조회한다(N+1 금지).
 */
class LabelServiceSiblingHasLabelTest {

    private static final Long SRC_SN = 300L;
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

    private static void setSrcSn(LsDataSrc target, long value) {
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static LsDataSrc frame(long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, "/raw/f" + frameNo + ".jpg", null);
        setSrcSn(src, srcSn);
        return src;
    }

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

        LsDataSrc current = frame(SRC_SN, 0);
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(current);
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(aiInfoRepository.findByDataLblSnIn(anyCollection())).thenReturn(List.of());
        // 형제 프레임 300(현재)/301/302.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(current, frame(301L, 1), frame(302L, 2)));
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("프레임목록_응답에_hasLabel_라벨있는프레임만_true")
    void siblingsHasLabelTrueOnlyForLabeled() {
        // given — 300, 302 에 라벨 존재. 301 은 라벨 없음.
        when(labelRepository.findDistinctSrcSnsWithLabelIn(anyCollection()))
                .thenReturn(List.of(300L, 302L));

        // when
        LabelResponse resp = service.getByFrame(SRC_SN, worker());

        // then
        assertThat(resp.siblings()).hasSize(3);
        assertThat(resp.siblings()).extracting(LabelResponse.SiblingFrame::srcSn, LabelResponse.SiblingFrame::hasLabel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(300L, true),
                        org.assertj.core.groups.Tuple.tuple(301L, false),
                        org.assertj.core.groups.Tuple.tuple(302L, true));
    }

    @Test
    @DisplayName("라벨없는_프레임_hasLabel_false")
    void siblingsAllFalseWhenNoLabels() {
        // given — 어느 프레임에도 라벨 없음.
        when(labelRepository.findDistinctSrcSnsWithLabelIn(anyCollection()))
                .thenReturn(List.of());

        LabelResponse resp = service.getByFrame(SRC_SN, worker());

        assertThat(resp.siblings()).extracting(LabelResponse.SiblingFrame::hasLabel)
                .containsExactly(false, false, false);
    }

    @Test
    @DisplayName("hasLabel_조회_N플러스1없이_단일쿼리")
    void hasLabelResolvedInSingleQuery() {
        when(labelRepository.findDistinctSrcSnsWithLabelIn(anyCollection()))
                .thenReturn(List.of(300L));

        service.getByFrame(SRC_SN, worker());

        // 프레임(형제) 수와 무관하게 IN 절 1회만 호출돼야 한다.
        verify(labelRepository, times(1)).findDistinctSrcSnsWithLabelIn(anyCollection());
    }

    @Test
    @DisplayName("DTO_of_는_labeledSrcSns_집합으로_hasLabel_매핑")
    void dtoOfMapsHasLabelFromSet() {
        LsDataSrc current = frame(SRC_SN, 0);
        List<LsDataSrc> siblings = List.of(current, frame(301L, 1));
        LabelResponse resp = LabelResponse.of(
                current, siblings, List.<LsDataLbl>of(), "DEID", null,
                java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(301L), objectMapper);

        assertThat(resp.siblings()).extracting(
                        LabelResponse.SiblingFrame::srcSn, LabelResponse.SiblingFrame::hasLabel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(300L, false),
                        org.assertj.core.groups.Tuple.tuple(301L, true));
    }

    @Test
    @DisplayName("SiblingFrame_직렬화_JSON_에_hasLabel_필드_포함")
    void siblingSerializesHasLabel() throws Exception {
        LsDataSrc current = frame(SRC_SN, 0);
        LabelResponse resp = LabelResponse.of(
                current, List.of(current), List.<LsDataLbl>of(), "DEID", null,
                java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(300L), objectMapper);
        String json = objectMapper.writeValueAsString(resp.siblings().get(0));
        assertThat(json).contains("hasLabel");
        // BigDecimal import 사용 회피용 no-op (컴파일 경고 방지) — 실제 검증은 위 assert.
        assertThat(BigDecimal.ZERO).isNotNull();
    }
}
