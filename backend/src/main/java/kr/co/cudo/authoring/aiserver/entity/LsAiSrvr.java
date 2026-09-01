package kr.co.cudo.authoring.aiserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 서버 원장 ({@code LS_AI_SRVR}). [@design ADR-057] [@req R10]
 *
 * <h3>목록의 진실원은 이 표 하나다</h3>
 * <p>추론 서버 주소는 원래 설정값 하나였다. 장비가 둘이 되면서 그 자리를 이 원장이 대신한다.
 * 기존 설정값은 <b>원장이 비었을 때 최초 1회 씨앗</b>으로만 쓰이며, 그 뒤로는 이 표가 이긴다 —
 * 설정과 원장을 둘 다 진실원으로 두면 "어느 쪽이 이기는가"가 코드 여기저기에 흩어진다.
 *
 * <h3>식별자와 이름을 가른 이유</h3>
 * <p>{@link #srvrId} 는 소문자·숫자만 허용하는 <b>기계용 식별자</b>다(서킷브레이커 이름과 메트릭
 * 라벨로 조립된다). 실제 장비 호스트명처럼 사람이 읽는 이름은 {@link #srvrNm} 이 따로 받는다.
 * 두 축을 하나로 합치면 라벨이 조용히 어긋난다 — 판정 규칙은
 * {@code AiSrvrIdPolicy} 가 단독으로 갖고 DB 체크 제약이 같은 규칙을 한 번 더 건다.
 *
 * <p>⚠ 상태를 바꾸는 통로를 이 클래스에 두지 않았다. 가용 노드가 0이 되는 것을 막으려면
 * <b>원장 전체</b>를 봐야 하는데, 엔티티 하나는 자기 행만 안다. 그 판정은 저장소의 조건부
 * UPDATE 가 원자적으로 수행한다.
 */
@Entity
@Table(name = "LS_AI_SRVR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsAiSrvr {

    /**
     * 서버가 담당하는 축 — 부하의 성질이 근본적으로 달라 같은 목록에서 섞어 고르면 안 된다.
     *
     * <p>{@link #INFERENCE} 는 동기 호출이라 큐 길이가 곧 부하지만, {@link #TIMESERIES} 는
     * 논블로킹 제출 + 콜백이라 요청 수·지연으로 부하가 읽히지 않는다.
     */
    public enum SrvrType {
        /** 추론 서버 — 탐지·분할·추적. */
        INFERENCE,
        /** 외부 시계열 분석 — 위탁 후 콜백. */
        TIMESERIES
    }

    @Id
    @Column(name = "SRVR_ID", length = 20)
    private String srvrId;

    /** 사람이 읽는 이름(실제 장비 호스트명 등) — 형식 제약이 없는 표시 축이다. */
    @Column(name = "SRVR_NM", length = 100)
    private String srvrNm;

    /** 호출 기준 주소 — ★내부 토폴로지라 로그·오류 응답에 전문을 싣지 않는다. */
    @Column(name = "SRVR_ADDR", nullable = false, length = 200)
    private String srvrAddr;

    @Enumerated(EnumType.STRING)
    @Column(name = "SRVR_TYPE_CD", nullable = false, length = 20)
    private SrvrType srvrTypeCd;

    @Enumerated(EnumType.STRING)
    @Column(name = "SRVR_STTS_CD", nullable = false, length = 20)
    private AiSrvrStatus srvrSttsCd;

    @Column(name = "CHCK_DT")
    private LocalDateTime chckDt;

    /** 상태점검 <b>연속</b> 실패 횟수 — 한 번의 네트워크 흔들림으로 노드를 내리지 않기 위한 축이다. */
    @Column(name = "CHCK_FAIL_NOCS", nullable = false)
    private Integer chckFailNocs;

    /**
     * 상태점검 <b>연속</b> 성공 횟수 — 이용불가 노드의 복귀 판정 축이다.
     *
     * <p>실패 카운터를 부호 있는 값으로 겸용하지 않고 컬럼을 따로 둔 이유는, 한 컬럼에 두 축을 담으면
     * 읽는 쪽마다 해석이 갈리기 때문이다. 폴링 노드의 메모리에 두지 않는 이유는 2노드가 틱을 나눠 갖고
     * (클러스터링이 틱마다 한 노드에서만 발화시킨다) 재기동으로도 사라지기 때문이다.
     */
    @Column(name = "CHCK_SCS_NOCS", nullable = false)
    private Integer chckScsNocs;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsAiSrvr(String srvrId, String srvrNm, String srvrAddr, SrvrType srvrTypeCd,
                     LocalDateTime regDt) {
        this.srvrId = srvrId;
        this.srvrNm = srvrNm;
        this.srvrAddr = srvrAddr;
        this.srvrTypeCd = srvrTypeCd;
        this.srvrSttsCd = AiSrvrStatus.AVAILABLE;
        this.chckFailNocs = 0;
        this.chckScsNocs = 0;
        this.regDt = regDt;
    }

    /**
     * 정적 팩토리 — 노드를 원장에 세운다. 상태는 항상 {@link AiSrvrStatus#AVAILABLE} 로 시작한다.
     *
     * <p>등록 시점에 상태를 고르게 하지 않는 이유: 아직 상태점검을 한 번도 하지 않아 <b>우리가
     * 아는 것이 없기</b> 때문이다. 실제 상태는 첫 점검이 정한다.
     *
     * @param srvrId 소문자·숫자 20자 이내({@code AiSrvrIdPolicy}). 위반 값은 DB 체크 제약이 막는다
     * @param srvrNm 사람이 읽는 이름. 모르면 {@code null}(추측해 채우지 않는다)
     */
    public static LsAiSrvr register(String srvrId, String srvrNm, String srvrAddr,
                                    SrvrType srvrTypeCd, LocalDateTime regDt) {
        return new LsAiSrvr(srvrId, srvrNm, srvrAddr, srvrTypeCd, regDt);
    }

    /**
     * 상태점검이 성공했다 — 연속 실패를 끊고 연속 성공을 쌓는다.
     *
     * <p><b>상태를 바꾸지 않는다.</b> 복귀 판정(연속 N회 성공)은 두 카운터만으로 결정되지 않고
     * 현재 상태에 따라 달라지므로, 그 전이는 저장소의 조건부 UPDATE 가 원자적으로 수행한다.
     *
     * @return 갱신된 연속 성공 횟수
     */
    public int recordCheckSuccess(LocalDateTime checkedAt) {
        this.chckDt = checkedAt;
        this.chckFailNocs = 0;
        this.chckScsNocs = safe(this.chckScsNocs) + 1;
        return this.chckScsNocs;
    }

    /**
     * 상태점검이 실패했다 — 연속 성공을 끊고 연속 실패를 쌓는다.
     *
     * <p>연속 성공을 <b>0으로 되돌리는</b> 것이 핵심이다. 이어서 세면 "연속"이 아니게 되어, 흔들리는
     * 노드가 성공을 띄엄띄엄 모아 복귀했다가 다시 내려가는 왕복을 반복한다.
     *
     * @return 갱신된 연속 실패 횟수
     */
    public int recordCheckFailure(LocalDateTime checkedAt) {
        this.chckDt = checkedAt;
        this.chckScsNocs = 0;
        this.chckFailNocs = safe(this.chckFailNocs) + 1;
        return this.chckFailNocs;
    }

    /**
     * 등록한 사람을 남긴다 — 표에 등록자 전용 컬럼이 없어 수정자 자리를 함께 쓴다. [@design API-227]
     *
     * <p><b>수정일시는 건드리지 않는다.</b> 등록 시각은 {@link #regDt} 가 이미 갖고 있고, 여기에 같은
     * 값을 한 번 더 적으면 「한 번도 손대지 않은 행」과 「등록 직후 수정된 행」이 구분되지 않는다.
     */
    public void assignRegistrar(String actorId) {
        this.mdfrId = actorId;
    }

    /**
     * 표시 이름·주소를 고친다 — <b>{@code null} 은 「그대로 둔다」</b>는 뜻이다. [@design API-228]
     *
     * <p>부분 수정 창구라 요청에 없는 항목을 지우지 않는다. 특히 주소는 {@code NOT NULL} 이라
     * 「비운다」가 애초에 성립하지 않고, 이름도 이 통로로는 지울 수 없다 — 빈 문자열로 지우는 우회를
     * 열면 표시 축에서 「빈 이름」과 「모름」이 구분되지 않는다.
     *
     * <p><b>주소 형식 판정을 여기서 하지 않는다.</b> 어떤 주소를 허용할지는 외부 연동 정책이 소유하며,
     * 그 규칙을 엔티티에 복제하면 두 번째 진실원이 된다.
     *
     * <p>⚠ 상태는 여기서 바꾸지 않는다 — 가용 노드가 0이 되는 것을 막으려면 <b>원장 전체</b>를 봐야
     * 하는데 엔티티 하나는 자기 행만 안다(클래스 주석 참조).
     */
    public void applyProfileChange(String srvrNm, String srvrAddr, String actorId, LocalDateTime now) {
        if (srvrNm != null) {
            this.srvrNm = srvrNm;
        }
        if (srvrAddr != null) {
            this.srvrAddr = srvrAddr;
        }
        this.mdfrId = actorId;
        this.mdfcnDt = now;
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
