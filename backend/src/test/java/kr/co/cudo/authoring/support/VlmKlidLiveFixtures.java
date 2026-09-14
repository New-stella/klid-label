package kr.co.cudo.authoring.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 외부 시계열 분석 사업자 <b>실응답 원문</b>(2026-09-14) 픽스처. [design: INTSPEC-002]
 *
 * <p>{@code src/test/resources/fixtures/vlm-klid-live-20260914/} 의 콜백 본문 4건을 읽는다. 원문은
 * 사업자 실서버가 돌려준 그대로이며 출처는 같은 폴더 {@code README.md} 에 있다. <b>원문을 고치지 말 것</b> —
 * 이 픽스처의 목적은 사업자 형식이 바뀌었을 때 시험이 먼저 알려 주는 것이다.
 *
 * <p>기대값은 파일에서 뽑지 않고 <b>리터럴</b>로 적는다. 파일에서 뽑으면 파서가 틀려도 기대값이 같이
 * 틀려 시험이 초록으로 남는다. 리터럴은 원문과 바이트 대조를 거쳐 적었다.
 */
public final class VlmKlidLiveFixtures {

    private static final String DIR = "fixtures/vlm-klid-live-20260914/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 묘사 창구 · 교통사고 — 라벨 줄에 {@code - } 접두가 <b>없다</b>. */
    public static final String DESCRIBE_CAR_ACCIDENT = "describe-car_accident.json";
    /** 묘사 창구 · 화재 — 라벨 줄에 {@code - } 접두가 <b>있다</b>. */
    public static final String DESCRIBE_FIRE = "describe-fire.json";
    /** 추가 질문 창구 · 교통사고 — 굵게 · 소제목 · 번호 목록 · 줄끝 공백 두 칸 · 가로줄. */
    public static final String CUSTOM_CAR_ACCIDENT = "custom-car_accident.json";
    /** 추가 질문 창구 · 화재 — 글머리표 목록. */
    public static final String CUSTOM_FIRE = "custom-fire.json";

    public static final List<String> ALL =
            List.of(DESCRIBE_CAR_ACCIDENT, DESCRIBE_FIRE, CUSTOM_CAR_ACCIDENT, CUSTOM_FIRE);

    /** {@link #DESCRIBE_CAR_ACCIDENT} 의 「상황」 줄 콜론 뒤 값. */
    public static final String DESCRIBE_CAR_ACCIDENT_SITUATION =
            "교통사고는 확인되지 않음. 모든 차량들이 정상적으로 신호 및 표지판을 따르며 도로를 운행하고 있으며, "
                    + "차량 간 충돌, 사람 또는 구조물과의 충돌, 급격한 방향 변경 등 교통사고 특징이 전혀 관찰되지 않았습니다. "
                    + "일부 차량이 브레이크를 밟아 정지하거나 느린 속도로 통과하긴 하지만, "
                    + "이는 정상적인 교차로 통행 행위이며 사고 요소가 없습니다.";

    /** {@link #DESCRIBE_FIRE} 의 「상황」 줄 콜론 뒤 값. */
    public static final String DESCRIBE_FIRE_SITUATION =
            "일반적인 시내 교통 상황. 다양한 차종(승용차, 버스, 트럭)이 정상적으로 이동하고 있다. "
                    + "일부 차량은 신호 대기 중이며, 다른 차량은 통행 중이다. "
                    + "전체적으로 혼잡함은 없으며, 평온한 교통 흐름 유지.";

    /** {@link #CUSTOM_CAR_ACCIDENT} 위탁에 실제로 보낸 질문 문구. */
    public static final String CUSTOM_CAR_ACCIDENT_SENT_QUESTION =
            "영상에서 '차량 충돌을 동반한 교통사고' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?";

    /** {@link #CUSTOM_FIRE} 위탁에 실제로 보낸 질문 문구. */
    public static final String CUSTOM_FIRE_SENT_QUESTION =
            "영상에서 '화염이 보이는 불' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?";

    private VlmKlidLiveFixtures() {
    }

    /** 콜백 본문 원문(UTF-8). */
    public static String body(String file) {
        try (InputStream in = VlmKlidLiveFixtures.class.getClassLoader().getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("테스트 픽스처를 찾을 수 없습니다: " + DIR + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 콜백 본문의 {@code results.description} 원문. DTO 를 거치지 않고 JSON 트리에서 바로 읽는다. */
    public static String description(String file) {
        try {
            JsonNode node = MAPPER.readTree(body(file)).path("results").path("description");
            if (!node.isTextual()) {
                throw new IllegalStateException("results.description 이 문자열이 아닙니다: " + file);
            }
            return node.textValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
