package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.VlmMetaRequest;
import kr.co.cudo.authoring.common.client.dto.VlmMetaResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VlmMetaStep 단위 테스트 (Phase 1 + Phase 5 갱신).
 * <p>
 * 본 step 은 V2 파이프라인의 첫 단계로, 영상 단위 VLM 메타를 LS_DATA_META 에 저장한다.
 *  - META_KEY 는 "VLM_META." prefix 로 통일.
 *  - 동일 (rawSn, key) 가 이미 존재하면 UPDATE (UK 충돌 방지).
 *  - Phase 5: 신규 메타에 한해 LS_DATA_META_REVIEW row 동시 INSERT.
 */
class VlmMetaStepTest {

    private AiServerClient aiServerClient;
    private LsDataMetaRepository metaRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private VideoRepository videoRepository;
    private VlmMetaStep step;

    @BeforeEach
    void setUp() {
        aiServerClient = mock(AiServerClient.class);
        metaRepository = mock(LsDataMetaRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        videoRepository = mock(VideoRepository.class);
        step = new VlmMetaStep(aiServerClient, metaRepository, metaReviewRepository, videoRepository);
        // 기본: 검토 row 미존재 (신규 INSERT 경로)
        when(metaReviewRepository.existsByDataMetaSn(anyLong())).thenReturn(false);
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
    }

    /** save(LsDataMeta) 호출 시 metaSn 채워진 영속 객체를 흉내내어 반환. */
    private void mockSaveAssignsMetaSn(long fakeMetaSn) {
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta arg = inv.getArgument(0);
            setField(arg, "metaSn", fakeMetaSn);
            return arg;
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    @DisplayName("정상_응답_시_LS_DATA_META_저장_META_KEY_VLM_META_prefix")
    void normalResponseSavesWithVlmMetaPrefix() {
        Long rawSn = 201L;
        newRaw(rawSn);
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("environment", "outdoor");
        pairs.put("activity", "walking");
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(pairs)));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        mockSaveAssignsMetaSn(1001L);

        step.run(rawSn);

        ArgumentCaptor<LsDataMeta> captor = ArgumentCaptor.forClass(LsDataMeta.class);
        verify(metaRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<LsDataMeta> saved = captor.getAllValues();
        assertThat(saved).extracting(LsDataMeta::getMetaKey)
                .containsExactlyInAnyOrder("VLM_META.environment", "VLM_META.activity");
        assertThat(saved).extracting(LsDataMeta::getMetaVal)
                .containsExactlyInAnyOrder("outdoor", "walking");
        assertThat(saved).allMatch(m -> m.getRawSn().equals(rawSn));
    }

    @Test
    @DisplayName("Phase5_정상_저장_시_LS_DATA_META_REVIEW_도_동시_INSERT")
    void newMetaTriggersReviewInsert() {
        Long rawSn = 210L;
        newRaw(rawSn);
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("scene_type", "intersection");
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(pairs)));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        mockSaveAssignsMetaSn(1010L);

        step.run(rawSn);

        ArgumentCaptor<LsDataMetaReview> reviewCaptor = ArgumentCaptor.forClass(LsDataMetaReview.class);
        verify(metaReviewRepository).save(reviewCaptor.capture());
        LsDataMetaReview r = reviewCaptor.getValue();
        assertThat(r.getDataMetaSn()).isEqualTo(1010L);
        assertThat(r.getDataRawSn()).isEqualTo(rawSn);
        assertThat(r.getDataSrcSn()).isNull();
        assertThat(r.getMetaTypeCd()).isEqualTo(LsDataMetaReview.META_TYPE_VLM);
        assertThat(r.getSrcSysCd()).isEqualTo(LsDataMetaReview.SRC_AI_SERVER);
        assertThat(r.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_AUTO_GENERATED);
    }

    @Test
    @DisplayName("외부_VLM_타임아웃_시_예외_전파")
    void externalTimeoutPropagates() {
        Long rawSn = 202L;
        newRaw(rawSn);
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("vlm timeout")));

        assertThatThrownBy(() -> step.run(rawSn))
                .isInstanceOf(RuntimeException.class);

        verify(metaRepository, never()).save(any());
        verify(metaReviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("mock_모드_응답_정상_저장")
    void mockResponseSavesNormally() {
        Long rawSn = 203L;
        newRaw(rawSn);
        Map<String, String> mockPairs = new LinkedHashMap<>();
        mockPairs.put("environment", "MOCK_ENV");
        mockPairs.put("activity", "MOCK_ACT");
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(mockPairs)));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        mockSaveAssignsMetaSn(2000L);

        step.run(rawSn);

        verify(metaRepository, org.mockito.Mockito.times(2)).save(any(LsDataMeta.class));
    }

    @Test
    @DisplayName("이미_존재하는_키는_UPDATE_새_키만_INSERT_UK_충돌_방지")
    void existingKeyUpdatesValue() {
        Long rawSn = 204L;
        newRaw(rawSn);
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("environment", "indoor");
        pairs.put("activity", "running");
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(pairs)));

        LsDataMeta existing = LsDataMeta.create(rawSn, "VLM_META.environment", "outdoor");
        setField(existing, "metaSn", 3001L);
        when(metaRepository.findByRawSnAndMetaKey(rawSn, "VLM_META.environment"))
                .thenReturn(Optional.of(existing));
        when(metaRepository.findByRawSnAndMetaKey(rawSn, "VLM_META.activity"))
                .thenReturn(Optional.empty());
        // activity 신규 INSERT 시 metaSn 3002L 으로 채워준다
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta arg = inv.getArgument(0);
            setField(arg, "metaSn", 3002L);
            return arg;
        });

        step.run(rawSn);

        // existing 은 updateValue 로 indoor 로 갱신됨
        assertThat(existing.getMetaVal()).isEqualTo("indoor");
        ArgumentCaptor<LsDataMeta> captor = ArgumentCaptor.forClass(LsDataMeta.class);
        verify(metaRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(m -> "VLM_META.activity".equals(m.getMetaKey()) && "running".equals(m.getMetaVal()));
    }

    @Test
    @DisplayName("이미_검토_row_있으면_LS_DATA_META_REVIEW_중복_INSERT_안함")
    void existingReviewSkipsInsert() {
        Long rawSn = 211L;
        newRaw(rawSn);
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("environment", "outdoor");
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(pairs)));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        mockSaveAssignsMetaSn(4001L);
        when(metaReviewRepository.existsByDataMetaSn(4001L)).thenReturn(true);

        step.run(rawSn);

        verify(metaReviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("응답_null_또는_metaPairs_null_이면_save_호출_안_함")
    void nullResponseSkipsSave() {
        Long rawSn = 205L;
        newRaw(rawSn);
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(null)));

        step.run(rawSn);

        verify(metaRepository, never()).save(any());
        verify(metaReviewRepository, never()).save(any());
    }
}
