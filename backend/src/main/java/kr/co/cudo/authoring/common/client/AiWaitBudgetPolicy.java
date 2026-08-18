package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudget;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudgets;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 온디맨드 AI 추론의 <b>대기 예산 도출 — 단일 진실원</b>.
 *
 * <p>화면이 "얼마나 기다려야 하는가"를 자기 상수로 들고 있으면 서버의 재시도 예산이 바뀌는 순간
 * 조용히 어긋나고, 그러면 <b>정상 동작이 「AI 실패」로 보이는</b> 결함이 그대로 되살아난다. 그래서
 * 값의 소유를 서버로 옮기고, 도출은 여기 한 곳에서만 한다.
 *
 * <h2>도출 재료 (전부 코드에서 읽는다 — 손으로 적은 숫자가 아니다)</h2>
 * <ul>
 *   <li><b>호출당 상한</b> {@link #PER_CALL_TIMEOUT} — {@link AiServerClient} 가 모든 추론 호출에 건다.</li>
 *   <li><b>재시도 횟수·백오프</b> — {@code resilience4j.retry.instances.ai} 를 {@link RetryRegistry} 에서
 *       읽는다. yml 을 고치면 이 클래스의 산출값이 <b>자동으로</b> 따라 움직인다(그것을 테스트가 고정한다).</li>
 *   <li><b>경로별 블로킹 상한</b> {@link #ONLINE_BLOCK_TIMEOUT} · {@link #POLYGON_BATCH_BUDGET} —
 *       일부 경로는 재시도 체인보다 <b>짧은 자기 상한</b>을 갖는다. 이 상수를 서비스가 참조하므로
 *       숫자가 두 곳에 적히지 않는다.</li>
 * </ul>
 *
 * <h2>호출 1회 최악 = 호출당 상한 × 시도 횟수 + 백오프 합</h2>
 * <p>운영 형상(3회 · 1s · ×2)에서 {@code 60×3 + (1+2) = 183초}다. ⚠ 이것은 "실패해서 오래 걸리는
 * 경우"만이 아니다 — <b>마지막 시도에서 성공한 요청</b>도 여기까지 쓴다.
 *
 * <h2>종류별 도출 (코드 실측)</h2>
 * <table border="1">
 *   <caption>온디맨드 4종</caption>
 *   <tr><th>종류</th><th>서버가 실제로 하는 일</th><th>고정분</th><th>프레임당</th></tr>
 *   <tr><td>오토라벨</td>
 *       <td>{@code AutolabelOnlineService.callYolo} 가 {@link #ONLINE_BLOCK_TIMEOUT}(70s)로 잘라
 *           block → 폴리곤 경로면 {@link #POLYGON_BATCH_BUDGET}(60s) wall-clock 예산 안에서 SAM 반복</td>
 *       <td>min(183,70)+60 = 130</td><td>0 (프레임을 훑지 않는다)</td></tr>
 *   <tr><td>분할</td>
 *       <td>{@code Sam2SegmentService} 가 {@code block()} 을 <b>인자 없이</b> 부른다 → 재시도 체인 전체</td>
 *       <td>183</td><td>0</td></tr>
 *   <tr><td>SAM2 추적</td>
 *       <td>{@code Sam2TrackService} 가 {@code nextSrcSns} 를 순회하되 루프 전체가
 *           {@link #TRACK_BATCH_BUDGET}(240s) wall-clock 예산 안에서 돈다 — 예산이 다하면 남은
 *           프레임을 처리하지 않고 <b>어디까지 했는지</b>를 응답에 담아 돌려준다</td>
 *       <td>10+240+2 = 252</td><td>0 (예산이 프레임 수를 대신 흡수한다)</td></tr>
 *   <tr><td>AI 자동 추적</td>
 *       <td>{@code YoloTrackService} 가 [시작 + 후속] 시퀀스를 <b>같은 예산</b> 안에서 순회하며
 *           프레임마다 {@link #ONLINE_BLOCK_TIMEOUT}(70s)와 잔여 예산 중 작은 값으로 잘라 block</td>
 *       <td>10+240+2 = 252</td><td>0</td></tr>
 * </table>
 * <p>여기에 추론이 아닌 서버측 부대 작업을 더한다 — {@link #FIXED_OVERHEAD_SEC}(권한·게이트 조회,
 * 요청 대기, 응답 직렬화·네트워크) 와 {@link #PER_FRAME_OVERHEAD_SEC}(프레임 이미지 디스크 읽기 +
 * base64 인코딩 + 전송). 이 둘은 계산이 아니라 <b>판단값</b>이라 상수로 두고 근거를 적어 둔다.
 *
 * <h2>절대 상한(ceiling)의 근거 — 앞단이 끊는 지점</h2>
 * <p>온프렘 배포의 앞단(nginx {@code proxy_read_timeout} / Caddy {@code read_timeout})이 300초에서
 * 끊는다. <b>그보다 긴 계획은 세워도 이룰 수 없다</b>. 그래서 기본 절대 상한은 그 값과 같게 두고,
 * 두 값이 갈리지 않도록 {@code AiWaitBudgetEdgeAlignmentGuardTest} 가 배포 템플릿과 대조한다.
 *
 * <h2>루프 경로의 가산분이 0 인 이유 — 요청 단위 예산이 프레임 수를 흡수한다</h2>
 * <p>예전에는 추적 두 경로의 프레임당 몫이 <b>재시도 체인 전체</b>였다. 그러면 절대 상한 안에
 * SAM2 추적은 1프레임, AI 자동 추적은 3프레임밖에 못 넣어, 화면이 50프레임을 <b>50번·17번</b>으로
 * 쪼개 보내고 조각마다 트래커가 리셋돼 추적 품질까지 떨어졌다.
 * <p>이제 두 루프에 {@link #TRACK_BATCH_BUDGET} 이 붙어(오토라벨 폴리곤 경로의
 * {@link #POLYGON_BATCH_BUDGET} 과 같은 장치) <b>요청 하나가 언제 끝나는지가 프레임 수와 무관</b>
 * 해졌다. 그래서 가산분이 0 이고, 화면은 서버 상한(요청당 50프레임)까지 한 번에 실어 보낸다.
 * 예산이 다하면 서버는 그때까지의 결과를 살려 두고 <b>이어 보낼 지점</b>을 응답에 담아 돌려준다 —
 * 조용히 버리지 않는다.
 *
 * <p><b>가산분 0 이 성립하려면 «프레임 수에 비례하는 일» 이 하나도 예산 밖에 없어야 한다</b>.
 * 추론만이 아니라 프레임마다 도는 부대 작업(접근 인가 검사·프레임 조회·이미지 인코딩)도 포함이다.
 * 하나라도 예산 시계 밖에 있으면 요청 소요가 프레임 수를 따라 늘어, 여기서 산출해 화면에 내려준
 * 대기 상한을 실제 소요가 넘어선다 — 그러면 <b>정상 요청이 화면에서 끊기는</b>, 이 클래스가 없애려던
 * 바로 그 결함이 돌아온다. 그래서 두 추적 경로 모두 프레임별 인가 검사를 예산 시계 <b>안</b>에 둔다
 * (SAM2 추적은 루프 안에서 프레임마다, AI 자동 추적은 루프 앞에서 한 번에 — 위치는 다르지만
 * <b>둘 다 시계가 시작된 뒤</b>다). 검사가 예산을 다 써 버리면 진행 0 인 부분 결과가 나올 뿐이고,
 * 그 상태도 계약이 이미 규정한 정상 응답이다.
 *
 * <p>예산 <b>밖</b>에 남는 것은 프레임 수와 무관한 고정 작업뿐이며 그 몫이
 * {@link #FIXED_OVERHEAD_SEC} 다.
 */
@Component
public class AiWaitBudgetPolicy {

    /**
     * 추론 호출 1회의 상한 — {@link AiServerClient} 의 모든 {@code .timeout(...)} 이 이 값을 쓴다.
     * <p>여기에 두는 이유: 이 값이 곧 아래 예산 계산의 입력이라, 클라이언트와 정책이 각자 숫자를
     * 들고 있으면 한쪽만 바뀌어 예산이 조용히 틀려진다.
     */
    public static final Duration PER_CALL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 온라인(사람이 기다리는) 경로가 블로킹 대기에 거는 상한 — 재시도 체인 전체보다 <b>짧다</b>.
     * <p>{@code AutolabelOnlineService.callYolo} 와 {@code YoloTrackService} 가 쓴다. 호출당 상한(60s)
     * 보다 살짝 크게 잡혀 있어, 첫 시도가 상한까지 간 뒤 두 번째 시도가 시작되는 지점에서 잘린다.
     */
    public static final Duration ONLINE_BLOCK_TIMEOUT = Duration.ofSeconds(70);

    /**
     * 오토라벨 폴리곤 경로가 <b>모든 검출 박스의 SAM 분할</b>에 쓰는 wall-clock 예산.
     * <p>{@code AutolabelOnlineService.polygonTotalBudget} 의 기본값이며, 개별 블로킹 호출을 N 회
     * 무한 반복하지 않도록 요청 전체에 단일 데드라인을 준다(CWE-770/400).
     */
    public static final Duration POLYGON_BATCH_BUDGET = Duration.ofSeconds(60);

    /**
     * 프레임을 훑는 추적 두 경로({@code Sam2TrackService} · {@code YoloTrackService})가
     * <b>요청 하나 전체</b>에 쓰는 wall-clock 예산.
     *
     * <h3>왜 필요한가</h3>
     * <p>이 예산이 없으면 «프레임당 최악»이 그대로 요청의 가산분이 되어, 절대 상한 안에 한두
     * 프레임밖에 넣지 못한다. 그러면 화면은 프레임 수만큼 요청을 쪼개게 되고, 추적은 조각마다
     * 트래커가 리셋되어 <b>결과 품질까지</b> 떨어진다. 예산을 두면 요청 하나가 언제 끝나는지가
     * 프레임 수와 무관하게 정해지므로, 화면은 한 번에 다 실어 보내고 서버는 할 수 있는 만큼 한 뒤
     * <b>어디까지 했는지</b>를 응답에 담아 돌려준다.
     *
     * <h3>값의 근거</h3>
     * <ul>
     *   <li><b>하한</b> — 한 프레임의 <b>최악 호출</b>({@link #worstCallSeconds()}, 운영 형상 183초)이
     *       통째로 들어가야 한다. 그보다 작으면 첫 프레임조차 재시도 체인을 다 못 쓰고 잘린다.</li>
     *   <li><b>상한</b> — 기본 절대 상한(300)에서 부대 몫({@link #FIXED_OVERHEAD_SEC} +
     *       {@link #PER_FRAME_OVERHEAD_SEC})을 뺀 288 이 이론상 최대다. 그것을 다 쓰면
     *       «도출 하한 = 기본 상한» 이 되어 운영자가 상한을 조금도 낮출 수 없고, 부대 몫이 조금만
     *       늘어도 계획이 깨진다.</li>
     *   <li>그래서 그 사이에서 <b>호출당 상한({@link #PER_CALL_TIMEOUT})의 배수</b>로 잡는다 —
     *       최악 호출 1건이 들어가고도 남고, 정상 속도(호출당 60초)라면 4프레임이 들어간다.</li>
     * </ul>
     */
    public static final Duration TRACK_BATCH_BUDGET = Duration.ofSeconds(240);

    /**
     * 기본 절대 상한(초) — <b>앞단 프록시가 끊는 지점</b>과 같은 값이다.
     * <p>이보다 큰 계획은 세워도 앞단에서 끊기므로, 늘리려면 배포 템플릿을 함께 늘려야 한다.
     */
    public static final int DEFAULT_CEILING_SEC = 300;

    /**
     * 운영자가 지정할 수 있는 절대 상한의 <b>상한</b>(초).
     * <p>비동기 응답 제한시간({@code spring.mvc.async.request-timeout}, 30분)과 같은 자릿수로 둔다 —
     * 그보다 큰 값은 어느 계층에서든 의미가 없다.
     */
    public static final int MAX_CEILING_SEC = 1800;

    /**
     * 추론이 아닌 <b>고정</b> 서버 작업 몫(초) — 판단값이다.
     * <p>덮는 것: 권한·신고 게이트 DB 조회, 요청 대기(bulkhead·스레드 큐), 시작 프레임 이미지 읽기,
     * 응답 직렬화와 왕복 네트워크. 계산으로 뽑을 수 없어 상수로 두되, 값을 키우면 예산만 넉넉해질 뿐
     * 안전 쪽으로 틀린다(줄이면 정상 요청을 끊는 쪽으로 틀린다).
     */
    static final int FIXED_OVERHEAD_SEC = 10;

    /**
     * 프레임 1건마다 더해지는 <b>비추론</b> 몫(초) — 판단값이다.
     * <p>덮는 것: 프레임 이미지 디스크 읽기(상한 20MB) + base64 인코딩 + 본문 전송.
     */
    static final int PER_FRAME_OVERHEAD_SEC = 2;

    /** {@code resilience4j.retry.instances.ai} — 이름을 여기서만 적는다. */
    static final String AI_RETRY_NAME = "ai";

    private final RetryConfig aiRetryConfig;

    public AiWaitBudgetPolicy(RetryRegistry retryRegistry) {
        this.aiRetryConfig = retryRegistry.retry(AI_RETRY_NAME).getRetryConfig();
    }

    /**
     * 추론 호출 <b>1회</b>의 최악 소요(초) = 호출당 상한 × 시도 횟수 + 시도 사이 백오프 합.
     *
     * <p>백오프는 상수로 적지 않고 {@link RetryConfig#getIntervalBiFunction()} 을 <b>실제로 적용해</b>
     * 합산한다. 그래야 yml 의 {@code wait-duration}/{@code exponential-backoff-multiplier} 를 고쳤을 때
     * 예산이 함께 움직인다(적어 두면 그 순간부터 두 번째 진실원이 된다).
     */
    public int worstCallSeconds() {
        int attempts = aiRetryConfig.getMaxAttempts();
        long backoffMillis = 0;
        IntervalBiFunction<Object> interval = aiRetryConfig.getIntervalBiFunction();
        // 시도 사이의 대기는 (시도 횟수 - 1) 번 발생한다. 첫 시도 앞에는 대기가 없다.
        for (int attempt = 1; attempt < attempts; attempt++) {
            backoffMillis += interval.apply(attempt, Either.left(RETRY_PROBE));
        }
        long totalMillis = PER_CALL_TIMEOUT.toMillis() * attempts + backoffMillis;
        return ceilToSeconds(totalMillis);
    }

    /**
     * 네 종류의 예산을 한 번에 산출한다.
     *
     * @param ceilingSec 적용할 절대 상한(초) — 운영자 설정이 반영된 <b>이미 검증된</b> 값이어야 한다
     *                   ({@code AiWaitBudgetProvider} 가 하한 미만을 끌어올려 넘긴다).
     */
    public AiWaitBudgets budgets(int ceilingSec) {
        int worstCall = worstCallSeconds();
        int onlineBlock = (int) Math.min(worstCall, ONLINE_BLOCK_TIMEOUT.getSeconds());

        // 오토라벨 — YOLO 1회(자기 상한으로 잘림) + 폴리곤 배치 예산. 프레임을 훑지 않는다.
        AiWaitBudget autolabel = new AiWaitBudget(
                onlineBlock + (int) POLYGON_BATCH_BUDGET.getSeconds() + FIXED_OVERHEAD_SEC, 0, ceilingSec);

        // 분할 — 호출당 상한이 없어(block() 인자 없음) 재시도 체인 전체.
        AiWaitBudget segment = new AiWaitBudget(worstCall + FIXED_OVERHEAD_SEC, 0, ceilingSec);

        // 추적 2종 — 프레임 수에 비례하는 일(인가 검사·조회·인코딩·추론)이 전부 요청 단위 wall-clock
        // 예산 안에서 돈다. 루프는 데드라인을 「반복 진입 직전」에 보므로, 마지막 반복의 부대 작업
        // (이미지 읽기·인코딩)만큼 예산을 넘길 수 있다 — 그 초과분이 PER_FRAME_OVERHEAD_SEC 이다.
        // 프레임을 더 실어도 서버는 예산 안에서 할 수 있는 만큼만 하고 「어디까지 했는지」를 응답에
        // 담아 돌려주므로 가산분이 0 이다.
        int trackBase = FIXED_OVERHEAD_SEC + (int) TRACK_BATCH_BUDGET.getSeconds() + PER_FRAME_OVERHEAD_SEC;
        AiWaitBudget sam2Track = new AiWaitBudget(trackBase, 0, ceilingSec);
        // AI 자동 추적 — 시퀀스 = [시작] + 후속이며 시작 프레임도 같은 루프·같은 예산 안에 있다.
        AiWaitBudget autoTrack = new AiWaitBudget(trackBase, 0, ceilingSec);

        return new AiWaitBudgets(autolabel, segment, sam2Track, autoTrack);
    }

    /**
     * 절대 상한이 내려갈 수 있는 <b>하한</b>(초) — 종류별 «한 프레임짜리 요청»이 전부 들어가는 최솟값.
     *
     * <p>이 값보다 작은 상한을 허용하면 <b>한 프레임도 완주할 수 없는</b> 예산이 되고, 그건 지금 고친
     * 결함("정상 동작이 AI 실패로 보인다")을 그대로 되돌리는 것이다. 그래서 숫자로 박지 않고 위
     * 도출에서 파생시킨다 — 재시도 예산을 늘리면 이 하한도 <b>함께</b> 올라간다.
     */
    public int minimumCeilingSeconds() {
        // 상한 자체는 아직 모르므로 임의값으로 산출한 뒤 고정분+가산분만 본다(ceiling 은 결과에 영향 없음).
        AiWaitBudgets b = budgets(DEFAULT_CEILING_SEC);
        return Math.max(
                Math.max(singleFrame(b.autolabel()), singleFrame(b.segment())),
                Math.max(singleFrame(b.sam2Track()), singleFrame(b.autoTrack())));
    }

    /** 그 종류의 «프레임 1건짜리 요청» 이 필요로 하는 시간(초). */
    private static int singleFrame(AiWaitBudget budget) {
        return budget.baseSec() + budget.perFrameSec();
    }

    /** 올림 — 내림하면 예산이 실제 최악보다 작아져(=정상 요청을 끊는 쪽으로) 틀린다. */
    private static int ceilToSeconds(long millis) {
        return (int) ((millis + 999) / 1000);
    }

    /**
     * 백오프 함수에 넘길 자리표 — 값이 아니라 «몇 번째 시도인가» 만 쓰이므로 내용은 무의미하다.
     * 매 호출 새로 만들지 않도록 상수로 둔다.
     */
    private static final RuntimeException RETRY_PROBE = new RuntimeException("interval probe");
}
