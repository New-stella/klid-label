package kr.co.cudo.authoring.aiserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 노드 x 용도별 부하 관측값 ({@code LS_AI_SRVR_USG}). [@design ADR-057]
 *
 * <h3>왜 장비마다 값 하나가 아닌가</h3>
 * <p>추론 서버는 장비 안에서 <b>실행을 용도별로 갈라</b> 두었다. 노드를 고를 때 보는 값은 그 요청
 * 자신의 용도에 해당하는 부하뿐이며, 두 값을 하나로 합쳐 보면 일괄 처리가 밀린 장비를 화면 요청이
 * <b>피할 이유가 없는데도 피하게</b> 된다. 그래서 저장 자리도 장비와 용도의 조합이다.
 *
 * <h3>실효 부하 = 처리중 + 대기</h3>
 * <p>용도마다 동시에 처리하는 건수가 <b>하나</b>여서, 대기가 없더라도 이미 하나를 처리하는 중이면
 * 그 용도는 바쁘다. 대기만 보면 한가한 장비와 바쁜 장비가 똑같이 "없음"으로 보여 요청의 절반을
 * 바쁜 쪽으로 보내게 된다.
 *
 * <p>⚠ 여기 담기는 값은 <b>마지막으로 관측한 것</b>이고, 대기 건수는 상대가 잠그지 않고 세는
 * 근사값이다. 정확한 수로 등식을 세우지 말 것.
 */
@Entity
@Table(name = "LS_AI_SRVR_USG")
@IdClass(LsAiSrvrUsg.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsAiSrvrUsg {

    /** 복합 식별자 — 노드 하나가 용도마다 한 행을 갖는다. */
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String srvrId;
        private AiSrvrUsageType usgTypeCd;

        public Key(String srvrId, AiSrvrUsageType usgTypeCd) {
            this.srvrId = srvrId;
            this.usgTypeCd = usgTypeCd;
        }
    }

    @Id
    @Column(name = "SRVR_ID", length = 20)
    private String srvrId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "USG_TYPE_CD", length = 20)
    private AiSrvrUsageType usgTypeCd;

    /** 그 용도의 큐 길이 — 근사값이다. */
    @Column(name = "WTNG_NOCS", nullable = false)
    private Integer wtngNocs;

    /** 그 용도가 지금 처리 중인 건수(0 또는 1). */
    @Column(name = "PRCS_NOCS", nullable = false)
    private Integer prcsNocs;

    /**
     * <b>우리가</b> 관측한 시각.
     *
     * <p>상대 응답에 실려 오는 관측 시각은 <b>상대 장비의 시계</b>다. 우리 시계에서 빼면 시계 오차가
     * 그대로 지연으로 잡히므로 쓰지 않는다 — 신선도는 우리 폴링 주기로 판단한다.
     */
    @Column(name = "CHCK_DT")
    private LocalDateTime chckDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsAiSrvrUsg(String srvrId, AiSrvrUsageType usgTypeCd, LocalDateTime regDt) {
        this.srvrId = srvrId;
        this.usgTypeCd = usgTypeCd;
        this.wtngNocs = 0;
        this.prcsNocs = 0;
        this.regDt = regDt;
    }

    /**
     * 정적 팩토리 — 아직 관측 전이라 부하는 0이다.
     *
     * <p>0 은 "한가하다"가 아니라 "아직 모른다"에 가깝지만, 모름을 표현하는 값을 따로 두지 않는다 —
     * 첫 폴링이 곧 채우고, 그 사이 잘못 골라도 실제 호출 실패는 서킷브레이커가 잡는다.
     */
    public static LsAiSrvrUsg of(String srvrId, AiSrvrUsageType usgTypeCd, LocalDateTime regDt) {
        return new LsAiSrvrUsg(srvrId, usgTypeCd, regDt);
    }

    /**
     * 새 관측값을 반영한다.
     *
     * <p><b>음수는 0으로 누른다</b>(정규화 정책의 소유자는 {@link AiSrvrSlotLoad} 다). 대기 건수는 상대가 잠그지 않고 세는 근사값이라 순간적으로 음수가
     * 나올 수 있는데, 음수 부하는 <b>가장 한가한 노드</b>가 되어 요청을 빨아들이고 컬럼 제약(0 이상)에도
     * 걸려 갱신이 통째로 실패한다.
     *
     * @param running    처리 중 건수
     * @param queued     대기 건수
     * @param observedAt <b>우리가</b> 관측한 시각(상대 시계가 아니다)
     */
    public void observe(int running, int queued, LocalDateTime observedAt) {
        // ★정규화 규칙은 여기서 다시 쓰지 않고 AiSrvrSlotLoad 가 소유한 것을 부른다(단일 진실원).
        AiSrvrSlotLoad normalized = new AiSrvrSlotLoad(running, queued);
        this.prcsNocs = normalized.running();
        this.wtngNocs = normalized.queued();
        this.chckDt = observedAt;
        this.mdfcnDt = observedAt;
    }

    /**
     * 실효 부하 — 처리 중 + 대기.
     *
     * <p>대기만 세면 이미 하나를 잡고 있는 노드를 한가한 노드와 구분하지 못한다.
     *
     * <p>공식의 소유자는 {@link AiSrvrSlotLoad} 다 — 여기서는 저장된 값을 그 식에 넘길 뿐이다.
     */
    public int effectiveLoad() {
        // ★공식을 여기서 다시 쓰지 않는다 — 두 곳에 적으면 가중치를 바꿀 때 한쪽만 바뀐다.
        return AiSrvrSlotLoad.effectiveLoad(
                prcsNocs == null ? 0 : prcsNocs,
                wtngNocs == null ? 0 : wtngNocs);
    }
}
