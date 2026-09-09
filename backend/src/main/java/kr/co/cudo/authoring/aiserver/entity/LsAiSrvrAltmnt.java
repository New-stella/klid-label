package kr.co.cudo.authoring.aiserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.common.client.VlmClient;
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

    /** 사유 컬럼의 폭. 값이 이보다 길면 INSERT/UPDATE 가 DB 오류로 터진다. */
    public static final int ALTMNT_RSN_MAX_LENGTH = 4000;

    /**
     * 사유 문장을 <b>실제로 담는 상한</b> — 컬럼 폭보다 훨씬 짧게 둔다.
     *
     * <p>이 값은 사람이 읽는 한 문장이고 길어질 축이 없다. 컬럼 폭까지 열어 두면 언젠가 스택트레이스나
     * 응답 본문을 그대로 담는 호출부가 생긴다.
     */
    static final int REASON_MAX_LENGTH = 200;

    /**
     * <b>재배정 사유</b> 문장 — 이 값이 「최초 배정 ↔ 재배정」을 원장만 보고 가르는 유일한 표식이다.
     * [@design ADR-057] [@design AC-1100]
     *
     * <p>최초 배정({@code insertIfAbsent})은 이 칸을 <b>비운 채</b> 넣는다. 따라서 <b>값이 있으면
     * 재배정</b>이고 없으면 최초 배정이다 — 클래스 설명 §재배정 사유는 사후 진단의 유일한 근거가 말하는
     * 대조가 이 규칙 위에 선다. 최초 배정이 이 칸을 채우기 시작하면 그 대조가 통째로 무너진다.
     *
     * <p>직전 장비 식별자를 함께 담는다 — 갱신이 그 값을 덮어쓰므로, 담지 않으면 <b>어디에서 옮겨왔는지가
     * 영구히 사라진다</b>(추적 불연속을 되짚을 때 필요한 것이 바로 그 값이다).
     *
     * <h3>담지 <b>않는</b> 것</h3>
     * <p>주소·포트·호스트 등 <b>내부 토폴로지</b>는 싣지 않는다(CWE-497). 식별자는 원장 컬럼으로 이미
     * 드러나 있는 값이라 성질이 다르다. 다만 그 식별자는 <b>DB 체크 제약을 우회해 들어왔을 수 있으므로</b>
     * <b>개행·탭</b>을 제거하고({@link VlmClient#safeForLog}) 길이를 눌러 담는다.
     *
     * <p>⚠ <b>정제 범위를 정확히 적는다 (2026-09-08 정정)</b> — 여기 「개행·<b>제어문자</b>를 제거하고」로
     * 적혀 있었으나 그 헬퍼가 실제로 지우는 것은 <b>CR·LF·TAB 셋뿐</b>이라 나머지 제어문자는 통과한다.
     * 이 값은 <b>원장에 영속된다</b>는 점에서 로그보다 오래 남으므로, 근거와 동작이 어긋난 채로 두면
     * 다음 사람이 「제어문자는 이미 걸러졌다」고 전제한다.
     *
     * <p>그럼에도 <b>헬퍼를 넓히지 않았다</b>. 그것은 공용 로그 정제기라 운영 코드 8파일 32곳이 함께
     * 쓰며, 그 범위를 바꾸는 것은 이 자리의 판단이 아니다. 실질 위험도 낮다 — 이 값이 담는 것은
     * 식별자 하나뿐이고 그 컬럼에는 <b>소문자·숫자·하이픈·밑줄만</b> 허용하는 체크 제약이 걸려 있어
     * ({@code AiSrvrIdPolicy#SRVR_ID_REGEX}) 제어문자가 들어오려면 그 제약을 우회해야 한다.
     * ⚠ 넓히는 것이 필요해지면 <b>공용 헬퍼가 아니라 이 자리 전용 정제</b>를 두는 편이 낫다.
     */
    public static String reassignReason(String fromSrvrId) {
        String text = "재배정: 고정 장비를 지금 고를 수 없어 옮김 (from=" + VlmClient.safeForLog(fromSrvrId) + ")";
        return text.length() <= REASON_MAX_LENGTH ? text : text.substring(0, REASON_MAX_LENGTH);
    }
}
