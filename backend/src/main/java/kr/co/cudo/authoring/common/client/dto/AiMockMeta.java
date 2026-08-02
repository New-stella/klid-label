package kr.co.cudo.authoring.common.client.dto;

/**
 * ai-server 추론 응답의 <b>신뢰 판정 단일 원천</b> (C-ISSUE-81, CWE-345 / CWE-1287).
 *
 * <p><b>왜 필요한가 — 부정 신호만 보면 fail-open 이다</b>: ai-server 응답 DTO 의 {@code mock} 은
 * primitive {@code boolean} 이라 <b>필드가 통째로 생략된 JSON</b>({@code {"polygon":[...],"score":0.9}})을
 * 받으면 Jackson 이 기본값 {@code false} 로 채운다. 즉 {@code mock()} 만 확인하는 코드는
 * "mock 이 아니라고 <i>말하지 않은</i>" 응답을 "정상 응답"으로 오인한다 — mock 좌표(시드 폴리곤 복사본,
 * score 0.9)가 안내 없이 자동 적용돼 학습데이터가 오염된다(C-ISSUE-81 의 필드-생략 변종).
 *
 * <p><b>규약 — 긍정 증명(positive proof) 요구</b>: 실모델 결과로 신뢰하려면 ai-server 가
 * {@code source="model"} 을 <b>명시</b>해야 한다. 필드 생략({@code source=null})·오타·미래 값·
 * {@code "mock"} 은 모두 신뢰하지 않는다(fail-closed). {@code mock=true} 는 그 자체로 부정 신호이므로
 * source 와 무관하게 신뢰하지 않는다.
 *
 * <p>{@code "model"} 은 ai-server 스키마({@code ai-server/app/schemas.py})의 {@code source} 기본값과
 * 1:1 이며, 드리프트는 {@code Sam2MockMetaDriftTest} 가 스냅샷 계약으로 고정한다.
 */
public final class AiMockMeta {

    /** ai-server 가 실모델 추론 결과임을 명시하는 {@code source} 값. */
    public static final String SOURCE_MODEL = "model";

    private AiMockMeta() {
    }

    /**
     * 이 응답을 자동 적용 대상으로 신뢰할 수 없는지 판정한다.
     *
     * @param mock   ai-server 가 내려준 mock 플래그(미전송 시 primitive 기본값 false — 신뢰 근거 아님)
     * @param source ai-server 가 내려준 출처("model" 만 신뢰, null/그 외 전부 불신)
     * @return 신뢰 불가면 true
     */
    public static boolean untrusted(boolean mock, String source) {
        return mock || !SOURCE_MODEL.equals(source);
    }
}
