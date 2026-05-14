package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.VlmMetaRequest;
import kr.co.cudo.authoring.common.client.dto.VlmMetaResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VlmMetaStep 단위 테스트 (Phase 1 신규).
 * <p>
 * 본 step 은 V2 파이프라인의 첫 단계로, 영상 단위 VLM 메타를 LS_DATA_META 에 저장한다.
 *  - META_KEY 는 "VLM_META." prefix 로 통일 (Phase 2 META_TYPE_CD 컬럼 도입 전 임시 분기).
 *  - 동일 (rawSn, key) 가 이미 존재하면 UPDATE (UK 충돌 방지).
 */
class VlmMetaStepTest {

    private AiServerClient aiServerClient;
    private LsDataMetaRepository metaRepository;
    private VideoRepository videoRepository;
    private VlmMetaStep step;

    @BeforeEach
    void setUp() {
        aiServerClient = mock(AiServerClient.class);
        metaRepository = mock(LsDataMetaRepository.class);
        videoRepository = mock(VideoRepository.class);
        step = new VlmMetaStep(aiServerClient, metaRepository, videoRepository);
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
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
        when(metaRepository.findByRawSnAndMetaKeyAndMetaTypeCd(any(), any(), any())).thenReturn(Optional.empty());

        step.run(rawSn);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LsDataMeta> captor = ArgumentCaptor.forClass(LsDataMeta.class);
        verify(metaRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<LsDataMeta> saved = captor.getAllValues();
        assertThat(saved).extracting(LsDataMeta::getMetaKey)
                .containsExactlyInAnyOrder("VLM_META.environment", "VLM_META.activity");
        assertThat(saved).extracting(LsDataMeta::getMetaVal)
                .containsExactlyInAnyOrder("outdoor", "walking");
        assertThat(saved).allMatch(m -> m.getRawSn().equals(rawSn));
        // Phase 2: 모든 신규 저장 메타는 metaTypeCd='RAW' (원본 영상 기준)
        assertThat(saved).allMatch(m -> LsDataMeta.META_TYPE_RAW.equals(m.getMetaTypeCd()));
    }

    @Test
    @DisplayName("Phase2_정상_저장_시_LS_DATA_META_METATYPECD_RAW")
    void newlySavedMetaHasRawTypeCode() {
        Long rawSn = 210L;
        newRaw(rawSn);
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("scene_type", "intersection");
        when(aiServerClient.extractVideoMeta(any(VlmMetaRequest.class)))
                .thenReturn(Mono.just(new VlmMetaResponse(pairs)));
        when(metaRepository.findByRawSnAndMetaKeyAndMetaTypeCd(any(), any(), any())).thenReturn(Optional.empty());

        step.run(rawSn);

        ArgumentCaptor<LsDataMeta> captor = ArgumentCaptor.forClass(LsDataMeta.class);
        verify(metaRepository).save(captor.capture());
        // Phase 2: META_TYPE_CD 컬럼 도입 — 신규 저장 시 'RAW' 디폴트
        assertThat(captor.getValue().getMetaTypeCd()).isEqualTo(LsDataMeta.META_TYPE_RAW);
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
        when(metaRepository.findByRawSnAndMetaKeyAndMetaTypeCd(any(), any(), any())).thenReturn(Optional.empty());

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
        when(metaRepository.findByRawSnAndMetaKeyAndMetaTypeCd(rawSn, "VLM_META.environment", LsDataMeta.META_TYPE_RAW))
                .thenReturn(Optional.of(existing));
        when(metaRepository.findByRawSnAndMetaKeyAndMetaTypeCd(rawSn, "VLM_META.activity", LsDataMeta.META_TYPE_RAW))
                .thenReturn(Optional.empty());

        step.run(rawSn);

        // existing 은 updateValue 로 indoor 로 갱신됨
        assertThat(existing.getMetaVal()).isEqualTo("indoor");
        // 새 키(activity) 는 save 1회 호출
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LsDataMeta> captor = ArgumentCaptor.forClass(LsDataMeta.class);
        verify(metaRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        // 신규 저장된 metaKey 에 VLM_META.activity 가 포함되어 있어야 함
        assertThat(captor.getAllValues())
                .anyMatch(m -> "VLM_META.activity".equals(m.getMetaKey()) && "running".equals(m.getMetaVal()));
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
    }
}
