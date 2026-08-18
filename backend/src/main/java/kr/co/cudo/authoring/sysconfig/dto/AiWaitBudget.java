package kr.co.cudo.authoring.sysconfig.dto;

/**
 * AI 추론 <b>한 종류</b>의 대기 예산 — 화면이 요청 제한시간을 계산하는 재료.
 *
 * <h3>화면이 쓰는 방법 (확정 계약)</h3>
 * <pre>
 *   제한시간 = min(baseSec + perFrameSec × 프레임수, ceilingSec)
 * </pre>
 * 계산값이 {@code ceilingSec} 를 넘으면 <b>기다리지 말고 나눠 보낸다</b>(나눌 수 없는 종류는 상한에서
 * 자른다 — 그 이상은 어차피 앞단이 끊는다).
 *
 * <h3>왜 서버가 이 값을 소유하는가</h3>
 * <p>이 값은 전부 <b>서버의 재시도 예산</b>에서 도출된다. 화면이 같은 숫자를 자기 상수로 들고 있으면
 * 서버 예산이 바뀐 순간 조용히 어긋나, "정상 동작인데 AI 실패로 보이는" 결함이 그대로 되살아난다.
 * 도출은 {@code AiWaitBudgetPolicy} 한 곳에서만 한다.
 *
 * @param baseSec     프레임 수와 무관한 고정 몫(초)
 * @param perFrameSec 프레임 1건마다 더해지는 몫(초). 요청 안에서 프레임을 훑지 않는 종류는 0
 * @param ceilingSec  한 요청이 넘지 말아야 할 절대 상한(초)
 */
public record AiWaitBudget(int baseSec, int perFrameSec, int ceilingSec) {
}
