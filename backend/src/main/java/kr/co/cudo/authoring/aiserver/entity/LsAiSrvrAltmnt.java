package kr.co.cudo.authoring.aiserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 영상별 노드 배정 ({@code LS_AI_SRVR_ALTMNT}). [@design ADR-057] [@req R3]
 *
 * <h3>배정이 영상당 한 건인 것이 보장의 전부다</h3>
 * <p>추적기는 노드 <b>프로세스의 로컬 메모리</b>에 있다. 한 영상의 프레임이 두 노드로 흩어지면
 * 그 지점에서 추적이 끊긴다. 그래서 {@code RAW_SN} 에 유니크 제약을 걸고, 배정은 조회가 아니라
 * <b>기록</b>으로 고정한다 — 노드 목록이 흔들려도 이미 배정된 영상은 움직이지 않는다.
 *
 * <h3>왜 상호작용 요청은 여기 남기지 않는가</h3>
 * <p>상호작용 경로의 식별자는 요청 1건짜리라 <b>다음 요청과 절대 매칭되지 않는다</b>. 기록하면
 * 재사용되지 않는 행만 무한히 쌓인다. 대신 한 요청의 모든 프레임이 같은 호출 안에서 나가므로,
 * 요청 하나를 한 노드로 보내는 것만으로 그 안의 연속성이 지켜진다.
 *
 * <h3>재배정 사유는 사후 진단의 유일한 근거다</h3>
 * <p>추적 불연속의 원인은 넷인데(재배정 · 추적기 만료 · 노드 재기동 · 캐시 축출) 뒤의 셋은
 * 우리가 감지조차 못 해 기록이 남지 않는다. 따라서 {@link #altmntRsn} 이 <b>있으면</b> 재배정이고
 * <b>없는데 끊겼으면</b> 나머지 셋이다. 이 대조가 오진을 막는 유일한 수단이라, 기록은 재배정과
 * 같은 트랜잭션에서 남긴다 — 비동기로 빼면 그 창 동안 "기록이 없다"와 "아직 기록이 안 됐다"가
 * 구분되지 않는다.
 */
@Entity
@Table(name = "LS_AI_SRVR_ALTMNT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsAiSrvrAltmnt {

    /**
     * ⚠ 채번 폭이 1인 것은 의도다 — 멱등 배정이 네이티브 upsert 로 같은 시퀀스를 직접 당긴다.
     * 폭을 늘리면 JPA 의 묶음 채번과 그 호출이 서로 다른 구간을 쓰게 되어 값이 크게 튄다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "lsAiSrvrAltmntSeq")
    @SequenceGenerator(name = "lsAiSrvrAltmntSeq",
            sequenceName = "LS_AI_SRVR_ALTMNT_SEQ", allocationSize = 1)
    @Column(name = "ALTMNT_SN")
    private Long altmntSn;

    /** 배정 대상 영상 — ★유니크. 물리 외래키는 없다(이 행이 영상보다 오래 남을 수 있다). */
    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "SRVR_ID", nullable = false, length = 20)
    private String srvrId;

    @Column(name = "ALTMNT_DT", nullable = false)
    private LocalDateTime altmntDt;

    /** 재배정 사유 — 노드 이탈로 다시 배정했을 때만 채운다(클래스 설명 참조). */
    @Column(name = "ALTMNT_RSN", length = 4000)
    private String altmntRsn;
}
