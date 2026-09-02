package kr.co.cudo.authoring.aiserver.entity;

import java.util.Locale;
import java.util.Optional;

/**
 * 추론 서버가 실행을 갈라 둔 <b>용도</b>. [@design ADR-057]
 *
 * <h3>왜 두 용도를 하나로 합쳐 보지 않는가</h3>
 * <p>두 용도는 장비 안에서 <b>실행이 격리</b>돼 있다 — 일괄 처리가 밀려 있다는 사실이 화면에서
 * 쓰는 요청의 응답을 늦추지 않는다. 두 부하를 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서
 * 다시 붙이는 셈이 되어, 일괄 처리가 밀린 장비를 화면 요청이 <b>피할 이유가 없는데도 피하게</b>
 * 된다. 그래서 노드를 고를 때 보는 값은 <b>그 요청 자신의 용도에 해당하는 부하뿐</b>이다.
 *
 * <h3>슬롯 이름은 상대의 어휘, 용도 코드는 우리 어휘다</h3>
 * <p>외부 부하 경로가 돌려주는 키({@code batch}/{@code interactive})와 우리 컬럼값
 * ({@code BATCH}/{@code INTERACTIVE})을 {@link #fromSlotKey(String)} 한 곳에서 잇는다. 두 어휘를
 * 여기저기서 각자 변환하면 상대가 키를 바꿨을 때 고칠 자리를 놓친다.
 */
public enum AiSrvrUsageType {

    /** 일괄 처리 — 배치 파이프라인의 추론. 밀려도 사람이 기다리고 있지 않다. */
    BATCH("batch"),

    /** 화면에서 쓰는 요청 — 사람이 응답을 기다린다. */
    INTERACTIVE("interactive");

    private final String slotKey;

    AiSrvrUsageType(String slotKey) {
        this.slotKey = slotKey;
    }

    /** 외부 부하 응답이 쓰는 슬롯 키. */
    public String slotKey() {
        return slotKey;
    }

    /**
     * 슬롯 키를 용도로 해석한다 — <b>모르면 비어 있음</b>이다.
     *
     * <p>예외로 만들지 않는 이유: 상대가 슬롯을 하나 늘리면 <b>조회 전체가 실패</b>해 아는 두 용도의
     * 부하까지 잃는다. 셋째가 생기면 상대가 알려주기로 했고, 그 전까지는 모르는 것을 조용히 넘긴다.
     *
     * <p>대소문자·앞뒤 공백은 가리지 않는다(전송 계층에서 흔한 차이라 그것으로 값을 잃을 이유가 없다).
     * 반대로 <b>추측해서 맞추지는 않는다</b> — 부분 일치·접두 매칭을 하지 않는다.
     */
    public static Optional<AiSrvrUsageType> fromSlotKey(String slotKey) {
        if (slotKey == null) {
            return Optional.empty();
        }
        String normalized = slotKey.trim().toLowerCase(Locale.ROOT);
        for (AiSrvrUsageType type : values()) {
            if (type.slotKey.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
