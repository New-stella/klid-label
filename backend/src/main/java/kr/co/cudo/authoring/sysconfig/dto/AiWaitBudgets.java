package kr.co.cudo.authoring.sysconfig.dto;

/**
 * 라벨링 화면이 부르는 <b>온디맨드 AI 추론 4종</b>의 대기 예산 묶음.
 *
 * <p>네 종류를 한 덩어리로 내려보내는 이유는 화면이 종류마다 다른 상수를 들고 있지 않게 하기
 * 위해서다. 종류별 값이 왜 다른지는 각 항목의 도출 근거({@code AiWaitBudgetPolicy})에 적혀 있다.
 *
 * @param autolabel 오토라벨 — {@code POST /v1/frames/{srcSn}/autolabel}
 * @param segment   분할 — {@code POST /v1/frames/{srcSn}/sam2-segment}
 * @param sam2Track SAM2 추적 — {@code POST /v1/frames/{srcSn}/sam2-track}
 * @param autoTrack AI 자동 추적 — {@code POST /v1/frames/{srcSn}/yolo-track}
 */
public record AiWaitBudgets(
        AiWaitBudget autolabel,
        AiWaitBudget segment,
        AiWaitBudget sam2Track,
        AiWaitBudget autoTrack
) {
}
