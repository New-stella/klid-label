package kr.co.cudo.authoring.common.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.dto.AiMockMeta;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-server 응답 신뢰 판정의 <b>fail-open 회귀 가드</b> (C-ISSUE-81 필드-생략 변종, CWE-345/1287).
 *
 * <p><b>PoC</b>: ai-server 가 {@code mock}/{@code source}/{@code mock_reason} 를 <b>모두 생략</b>한
 * 응답({@code {"track_id":"t","polygon":[...],"score":0.9}})을 보내면 Jackson 이 primitive
 * {@code boolean mock} 을 기본값 {@code false} 로 채운다. {@code mock()} 만 확인하던 구 판정은
 * 이를 "정상 응답"으로 오인해 mock 좌표를 전 프레임 자동 적용했다(안내 메시지도 없음).
 * 이제 실모델 출처({@code source="model"})의 <b>긍정 증명</b>을 요구해 차단한다.
 */
class AiMockMetaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("판정규약_source가_model로_명시된_비mock_응답만_신뢰한다")
    void onlyExplicitModelSourceIsTrusted() {
        // given/when/then — 긍정 증명이 있는 경우만 신뢰.
        assertThat(AiMockMeta.untrusted(false, "model")).isFalse();

        // 부정 신호 / 증명 부재는 전부 불신(fail-closed).
        assertThat(AiMockMeta.untrusted(true, "mock")).isTrue();
        assertThat(AiMockMeta.untrusted(true, "model")).isTrue();   // mock=true 우선
        assertThat(AiMockMeta.untrusted(false, null)).isTrue();     // 필드 생략
        assertThat(AiMockMeta.untrusted(false, "")).isTrue();
        assertThat(AiMockMeta.untrusted(false, "Model")).isTrue();  // 대소문자 변형
        assertThat(AiMockMeta.untrusted(false, "unknown")).isTrue();
    }

    @Test
    @DisplayName("PoC_track응답이_mock필드를_생략하면_신뢰하지_않는다")
    void trackResponseWithOmittedMockMetaIsUntrusted() throws Exception {
        // given — mock 관련 키를 전부 생략한 ai-server 응답(원 결함 재현 형태).
        String json = "{\"track_id\":\"t\",\"polygon\":[[10.0,10.0],[30.0,10.0],[30.0,30.0]],\"score\":0.9}";

        // when
        Sam2TrackResponse res = objectMapper.readValue(json, Sam2TrackResponse.class);

        // then — primitive 기본값 false 로 채워지지만 신뢰 판정은 차단된다.
        assertThat(res.mock()).isFalse();
        assertThat(res.source()).isNull();
        assertThat(res.untrusted()).isTrue();
    }

    @Test
    @DisplayName("PoC_segment응답이_mock필드를_생략하면_신뢰하지_않는다")
    void segmentResponseWithOmittedMockMetaIsUntrusted() throws Exception {
        String json = "{\"polygon\":[[1.0,1.0],[2.0,1.0],[2.0,2.0]],\"score\":0.95}";

        Sam2Response res = objectMapper.readValue(json, Sam2Response.class);

        assertThat(res.mock()).isFalse();
        assertThat(res.untrusted()).isTrue();
    }

    @Test
    @DisplayName("PoC_yolo응답이_mock필드를_생략하면_신뢰하지_않는다")
    void yoloResponseWithOmittedMockMetaIsUntrusted() throws Exception {
        String json = "{\"detections\":[{\"label\":\"person\",\"points\":[1.0,2.0,3.0,4.0],\"score\":0.9}]}";

        YoloResponse res = objectMapper.readValue(json, YoloResponse.class);

        assertThat(res.mock()).isFalse();
        assertThat(res.untrusted()).isTrue();
    }

    @Test
    @DisplayName("회귀_실모델_응답은_그대로_신뢰된다")
    void explicitModelResponsesRemainTrusted() throws Exception {
        String track = "{\"track_id\":\"t\",\"polygon\":[[1.0,1.0]],\"score\":0.9,"
                + "\"mock\":false,\"source\":\"model\",\"mock_reason\":null}";
        String segment = "{\"polygon\":[[1.0,1.0]],\"score\":0.9,"
                + "\"mock\":false,\"source\":\"model\",\"mock_reason\":null}";

        assertThat(objectMapper.readValue(track, Sam2TrackResponse.class).untrusted()).isFalse();
        assertThat(objectMapper.readValue(segment, Sam2Response.class).untrusted()).isFalse();
        // 구버전 호출부 호환 생성자(배치 스텝 등)도 신뢰 유지 — 회귀 가드.
        assertThat(new Sam2TrackResponse("t", List.of(List.of(1.0, 1.0)), 0.9).untrusted()).isFalse();
        assertThat(new Sam2Response(List.of(List.of(1.0, 1.0)), 0.9).untrusted()).isFalse();
        assertThat(new YoloResponse(List.of()).untrusted()).isFalse();
    }
}
