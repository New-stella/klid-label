package kr.co.cudo.authoring.batch.reclaim;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 「처리 중」 고착 영상 <b>회수 스윕</b> 설정 ({@code authoring.batch.stuck-reclaim.*}).
 *
 * <h2>fail-closed 검증 — 오설정이면 기동을 거부한다</h2>
 * <p>이 설정이 무너지는 방향은 하나다. <b>임계가 짧아지는 쪽</b>이면 회수가 <b>정상 실행 중인
 * 파이프라인</b>을 되돌린다 — 그 파이프라인은 나중에 끝나면서 상태를 다시 덮어쓰고, 그사이 다른
 * 진입이 같은 영상을 선점해 파이프라인이 2벌 돌며 외부 위탁이 중복으로 나간다. 회수는 상태를
 * <b>되돌리는 조작</b>이라 증강 파생 폐기 유예({@code AugmentDiscardProperties})와 같은 부류다.
 *
 * <p>이 리포에는 {@code .env.example} 의 <b>빈 값</b>이 {@code ${KEY:default}} 를 무력화해 운영에서만
 * 조용히 다르게 동작한 실사고가 있다 — 그래서 경고가 아니라 {@code @PostConstruct} 기동 차단이다
 * ({@code QuartzClusteringGuard}·{@code AugmentDiscardProperties} 와 동형). 하한 clamp(조용한 보정)를
 * 쓰지 않는 이유도 같다. 보정하면 운영자가 지정한 값과 실제 값이 갈린 채로 뜬다.
 *
 * <h2>기본 임계 {@value #DEFAULT_STALE_TIMEOUT_MINUTES}분(24시간)의 근거</h2>
 * <p>회수 판정의 기준 시각은 <b>선점 시각과 진행 로그 마지막 갱신 시각 중 더 나중</b>이다. 임계를 잡을 때
 * 덮어야 하는 구간은 다음 둘이다.
 * <ol>
 *   <li><b>선점 후 실행이 시작될 때까지의 대기</b> — 전용 풀은 core 2 / 큐 4 / max 4 라 한 번에 최대
 *       8건이 접수되고, 뒤 순번은 앞 순번의 파이프라인이 끝나야 시작한다. 대기 중에는 진행 로그가
 *       한 번도 갱신되지 않으므로 이 구간이 통째로 임계 안에 들어와야 한다. 프레임 수가 많은 영상이
 *       연달아 접수되면 이 대기가 길어질 수 있다.</li>
 *   <li><b>가장 긴 단일 단계</b> — 단계가 넘어갈 때마다 진행 로그가 갱신되므로(오케스트레이터가
 *       단계마다 {@code markStage} 를 호출한다) 파이프라인 전체가 아니라 <b>한 단계</b>만 덮으면 된다.
 *       현재 가장 긴 단계는 프레임마다 ai 추론을 호출하는 오토라벨이다.
 *       <p>⚠ <b>이 단계의 소요는 「호출당 상한」이 아니라 「프레임 수 × 호출 시간」이며, 프레임 수의
 *       상한은 코드에 없다</b>(추출 간격과 영상 길이가 정한다). 호출당 타임아웃(수십 초 규모)만 보고
 *       "가장 긴 단계가 1분 남짓"이라고 읽으면 임계를 크게 과소평가하게 된다 — 프레임이 수천 장이면
 *       이 단계 하나가 <b>몇 시간</b>이 될 수 있다. 임계는 그 값을 덮어야 한다.</p></li>
 * </ol>
 * <p><b>기본값을 크게 잡는 이유</b> — <b>살아 있는 선점을 뺏지 않는 것이 회수 지연보다 중요하다.</b>
 * 회수는 상태를 되돌리는 조작이고, 잘못 회수하면 완주 영상이 실패로 강등돼 사람이 손댄 보간 라벨이
 * 파괴되는 경로가 열린다(클래스 상단 설명 참조). 반면 늦게 회수해도 결과는 「복구가 늦어질 뿐」이다.
 * 그래서 위 두 구간(큐 대기·최장 단계)을 넉넉히 덮는 쪽으로 크게 잡는다. 이 값은 운영에서 설정으로
 * 조정할 수 있다. 하한은 {@value #MIN_STALE_TIMEOUT_MINUTES}분으로, 실행 중 파이프라인을 뺏지 않기
 * 위한 바닥이다.
 *
 * <p>⚠ <b>대가(인지·수용)</b>: 실제로 고착된 영상도 최대 {@value #DEFAULT_STALE_TIMEOUT_MINUTES}분
 * (24시간)이 지나야 회수된다 — 그 사이 그 영상은 재기동이 막힌 채로 방치된다.
 *
 * <p>⚠ <b>임계를 낮추면 무엇이 나빠지나</b>(운영자용): 얻는 것은 <b>회수가 빨라지는 것뿐</b>이고, 잃는
 * 것은 <b>살아 있는 선점을 뺏지 않는다는 보장</b>이다. 임계가 「큐 대기 + 최장 단계」보다 짧아지면 정상
 * 실행 중인 파이프라인의 선점이 회수되고, 그러면 ①같은 영상에 다른 진입이 들어와 파이프라인이 2벌 돌며
 * 외부 위탁이 중복으로 나가고 ②먼저 돌던 작업이 나중에 끝나며 상태를 덮어써 화면·산출물이 어긋난다.
 * 하한 {@value #MIN_STALE_TIMEOUT_MINUTES}분은 그 바닥일 뿐 <b>안전한 값이라는 뜻이 아니다</b> —
 * 실제 안전선은 그 배포의 영상 프레임 수와 큐 적체가 정한다.
 *
 * <p>⚠ <b>노드 간 시계 동기(NTP) 전제</b>: 판정은 <b>서로 다른 노드가 각각 기록한 시각</b>(선점 표식의
 * {@code REG_DT}·진행 로그의 {@code MDFCN_DT})을 이 스윕이 도는 노드의 현재 시각과 비교한다. 노드 시계가
 * 어긋나면 임계가 그만큼 밀린다 — 앞선 시계의 노드가 스윕을 돌면 살아 있는 선점을 일찍 뺏고, 뒤처지면
 * 회수가 늦어진다. 운영 체크리스트는 배포 문서의 클러스터 시계 동기 항목을 따른다.
 *
 * <p>⚠ <b>남는 위험(인지·수용 — 이 스윕이 만든 결함이 아니라 기존 진입 가드의 한계)</b>
 * <ol>
 *   <li>프레임 수가 매우 많은 영상이 여러 건 연달아 접수되면 뒷줄의 대기가 임계를 넘을 수 있다. 그 경우
 *       아직 살아 있는 선점이 회수되는데, 뒤늦게 실행이 시작되면 진입 가드가 배치 단계를 다시 원자
 *       클레임하므로 <b>파이프라인 자체는 정상 수행</b>된다(상호배제가 그 구간에만 느슨해진다).</li>
 *   <li>회수 <b>직후</b>에 다른 주체가 같은 영상을 선점하면, 큐에서 뒤늦게 깨어난 옛 작업이 <b>소유권을
 *       확인하지 않고</b> 병행 실행될 수 있다. 진입 가드는 "지금 {@code PROCESSING} 인가"만 보고 "그
 *       {@code PROCESSING} 이 <b>내 것</b>인가"를 구분하지 못하기 때문이다.</li>
 *   <li>판정과 조건부 UPDATE 사이의 좁은 창에 <b>갓 접수된 정상 선점</b>이 끼어들면 그 선점을 뺏을 수 있다
 *       (판정은 무잠금이다).</li>
 * </ol>
 * <p>2·3 은 <b>이 회수 기능이 새로 만든 결함이 아니다</b> — 닫으려면 선점에 소유권 토큰을 부여하는 별도
 * 설계가 필요하고, 기본 임계가 24시간이라 도달 가능성이 매우 낮아 <b>인지·수용</b>한다. 운영에서 그런
 * 형상이 관측되면 임계를 올린다(내리지 않는다).
 *
 * @param enabled        스윕 활성화. 자기 토글이며 {@code authoring.batch.enabled} 등 남의 스위치에 얹지
 *                       않는다(무관 토글 종속 사고 방지)
 * @param intervalMs     스윕 주기(ms). 하한 {@value #MIN_INTERVAL_MS}
 * @param initialDelayMs 기동 후 첫 스윕까지 지연(ms). 기동 직후 아직 살아 있는 선점을 건드리지 않도록 둔다
 * @param staleTimeoutMinutes 선점·진행 무갱신 경과 임계(분). 하한 {@value #MIN_STALE_TIMEOUT_MINUTES}
 * @param batchSize      tick 당 회수 상한(자원 소모 방어, CWE-770)
 */
@ConfigurationProperties(prefix = "authoring.batch.stuck-reclaim")
public record ProcessingStaleReclaimProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("900000") long intervalMs,
        @DefaultValue("600000") long initialDelayMs,
        @DefaultValue("1440") int staleTimeoutMinutes,
        @DefaultValue("50") int batchSize
) {

    /** 오설정 시에도 도달할 수 없어야 하는 임계 하한(분) — 실행 중 파이프라인 회수 방지. */
    public static final int MIN_STALE_TIMEOUT_MINUTES = 60;
    /** 기본 임계(분) — 위 Javadoc 「기본 임계의 근거」 참조. */
    public static final int DEFAULT_STALE_TIMEOUT_MINUTES = 1440;
    /** 스윕 주기 하한(ms) — 과도한 폴링으로 DB 를 두드리지 않게 한다. */
    public static final long MIN_INTERVAL_MS = 60_000L;

    @PostConstruct
    public void validate() {
        if (staleTimeoutMinutes < MIN_STALE_TIMEOUT_MINUTES) {
            throw new IllegalStateException(
                    "authoring.batch.stuck-reclaim.stale-timeout-minutes=" + staleTimeoutMinutes
                            + " 는 허용되지 않습니다 (최소 " + MIN_STALE_TIMEOUT_MINUTES + "분)."
                            + " 임계가 짧으면 정상 실행 중인 배치 파이프라인의 선점을 회수해 같은 영상이"
                            + " 두 번 실행되고 외부 위탁이 중복으로 나갑니다."
                            + " 값을 비우면(빈 문자열) 기본값이 적용되지 않으니"
                            + " .env / application-{profile}.yml 에 값을 명시하세요.");
        }
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalStateException(
                    "authoring.batch.stuck-reclaim.interval-ms=" + intervalMs
                            + " 는 허용되지 않습니다 (최소 " + MIN_INTERVAL_MS + "ms).");
        }
        if (initialDelayMs < 0) {
            throw new IllegalStateException(
                    "authoring.batch.stuck-reclaim.initial-delay-ms=" + initialDelayMs
                            + " 는 허용되지 않습니다 (0 이상).");
        }
        if (batchSize < 1) {
            throw new IllegalStateException(
                    "authoring.batch.stuck-reclaim.batch-size=" + batchSize
                            + " 는 허용되지 않습니다 (최소 1).");
        }
    }
}
