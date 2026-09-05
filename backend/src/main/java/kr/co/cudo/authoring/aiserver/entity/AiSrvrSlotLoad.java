package kr.co.cudo.authoring.aiserver.entity;

/**
 * 한 용도(슬롯)의 부하 관측값. [@design ADR-057]
 *
 * <p><b>실효 부하는 둘의 합</b>이다. 용도마다 동시에 처리하는 건수가 하나여서, 대기가 없더라도
 * 이미 하나를 처리하는 중이면 그 용도는 바쁘다 — 대기만 보면 한가한 장비와 바쁜 장비가 똑같이
 * "없음"으로 보여 요청의 절반을 바쁜 쪽으로 보내게 된다.
 *
 * <h3>★ 이 record 가 부하 산술의 <b>단일 진실원</b>이다</h3>
 * <p>실효 부하 공식({@link #effectiveLoad()})과 음수 정규화({@link #clampToZero(int)})는 여기서만
 * 결정한다. 저장 자리({@link LsAiSrvrUsg})도 평활 창도 <b>자기 식을 따로 갖지 않고</b> 여기를 부른다 —
 * 가중치나 클램핑 정책을 바꿀 때 한쪽만 고치면 나머지가 <b>두 번째 진실원</b>으로 남기 때문이다.
 *
 * <p>이 값 객체가 {@code entity} 패키지에 있는 이유도 그것이다. {@code service} 에 두면 저장 자리인
 * 엔티티가 이 식을 부를 수 없어(계층 역전) 같은 산술을 복제하게 된다.
 *
 * <p>⚠ {@code queued} 는 상대가 <b>잠그지 않고</b> 세는 근사값이다. 정확한 수로 등식을 세우지 말 것.
 *
 * @param running 처리 중 건수(0 또는 1)
 * @param queued  대기 건수(근사값)
 */
public record AiSrvrSlotLoad(int running, int queued) {

    /**
     * 음수는 0으로 눌러 담는다.
     *
     * <p>근사값이라 순간적으로 음수가 나올 수 있는데, 음수 부하는 <b>가장 한가한 노드</b>가 되어
     * 요청을 빨아들이고 컬럼 제약(0 이상)에도 걸려 갱신이 통째로 실패한다. 값을 버리는 대신 누른다 —
     * 음수라는 사실이 "부하가 없다"는 뜻은 아니지만, 0 이 그 순간의 가장 가까운 진술이다.
     */
    public AiSrvrSlotLoad {
        running = clampToZero(running);
        queued = clampToZero(queued);
    }

    /**
     * 부하 값의 음수 정규화 — <b>이 한 곳이 정책의 소유자다</b>.
     *
     * <p>부하로 쓰이는 정수를 누르는 자리(관측값 저장·평활 창 입력)는 전부 이 메서드를 부른다.
     * 각자 {@code Math.max(0, ...)} 를 적어 두면 정책이 갈릴 때 한쪽만 바뀐다.
     */
    public static int clampToZero(int value) {
        return Math.max(0, value);
    }

    /**
     * 실효 부하 공식 — 처리 중 + 대기. <b>이 한 곳이 공식의 소유자다</b>.
     *
     * <p>저장된 행에서 계산할 때도 이 식을 부른다({@link LsAiSrvrUsg#effectiveLoad()}).
     */
    public static int effectiveLoad(int running, int queued) {
        return clampToZero(running) + clampToZero(queued);
    }

    /** 실효 부하 — 처리 중 + 대기. */
    public int effectiveLoad() {
        return effectiveLoad(running, queued);
    }
}
