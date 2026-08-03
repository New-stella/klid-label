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

    /**
     * <b>명시적</b> mock 모드({@code AI_MOCK_MODE=true})를 뜻하는 {@code mock_reason} 값 (G-ISSUE-02).
     *
     * <p><b>⚠ 이 값은 배포 환경 차단의 면제 사유가 아니다 — 면제로 되돌리지 말 것.</b> 한때
     * "개발자가 의도적으로 켠 상태라 오탐" 이라는 이유로 면제였으나 실측은 정반대였다:
     * {@code weights_missing}/{@code load_failed} mock 은 <b>빈 detections</b> 를 내는 반면
     * {@code env_mock} mock 만 <b>합성 라벨</b>(person, score=0.9)을 실제로 만들고, ai-server 는
     * 가중치 존재 확인보다 {@code AI_MOCK_MODE} 를 먼저 보므로 <b>가중치 미배포 사고가 이 사유로
     * 위장</b>된다. 현재 차단 판정은 사유를 보지 않는다({@code YoloAutolabelStep.blocksUntrusted}).
     *
     * <p>남은 용도는 <b>진단·계약 고정</b>이다 — 로그·운영 안내가 가리키는 사유 문자열을 호출부마다
     * 리터럴로 적으면 오타로 어긋나므로 여기 한 곳에서만 정의한다.
     */
    public static final String REASON_ENV_MOCK = "env_mock";

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
