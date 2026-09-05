package kr.co.cudo.authoring.sysconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 검증 이벤트 유형별 질문 문구 (LS_VRFC_EVNT_QSTN, V17). [design: ERD-033]
 *
 * <p>이벤트 어노테이션의 <b>질문 칸을 채우는 값의 조달원</b>이다. 한 유형이 질문 여러 건을 갖고
 * <b>정렬순서가 곧 의미</b>다 — 가장 앞선 것이 그 유형의 <b>첫 번째 질문</b>, 곧 기본값이다.
 *
 * <h3>★ (유형코드, 정렬순서) 유일 — 「첫 번째 질문」의 결정성 근거</h3>
 * <p>마킹을 거치지 않는 경로는 <b>언제나 그 유형의 첫 번째 질문</b>을 쓴다. 정렬 키 없이 조회 순서에
 * 기대면 그 기본값이 <b>실행마다 달라진다</b>. DB 유일 인덱스({@code UK_LS_VRFC_EVNT_QSTN_TYPE_SORT})와
 * 조달 판정기({@code VerificationEventQuestionResolver})가 짝으로 그것을 고정한다.
 *
 * <h3>왜 문구를 우리가 보관하는가</h3>
 * <p>외부 시계열 분석의 추가 질문 창구는 <b>질문 문장을 사업자 서버가 이벤트별로 관리</b>하며 연동
 * 시스템이 지정할 수도, 응답으로 받을 수도 없다. 그런데 어노테이션의 질문 칸은 채워져야 하고, 이후
 * 규격이 질의를 받는 방향으로 바뀔 수 있다는 협의가 있었다. 그래서 문구를 우리가 보관하고 그 목록에서
 * 조달한다 — 값을 <b>지어내지 않는다</b>.
 *
 * <p>⚠ <b>인지·수용한 잔여 위험</b>: 현행 위탁 요청 본문에는 질문을 실을 자리가 없다. 첫 번째가 아닌
 * 질문을 고르면 기록된 질문과 사업자가 실제로 쓴 질문이 달라진다.
 *
 * <h3>질문 본문 검증의 단일 진실원</h3>
 * <p>{@link #QSTN_CN_MAX_LENGTH} · {@link #QSTN_CN_ALLOWED_REGEX} · {@link #isQstnCnValid(String)} 가
 * <b>이 클래스 한 곳</b>에 있고, 요청 DTO 의 Bean Validation 과 서비스 2차 방어선이 <b>같은 값·같은
 * 함수</b>를 본다. 두 곳이 갈라지면 한쪽만 통과하는 값이 조용히 생긴다({@code LsDataIngest} 가 겪은
 * 실사고와 동형).
 */
@Entity
@Table(name = "LS_VRFC_EVNT_QSTN")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsVrfcEvntQstn {

    /**
     * 질문 문구 물리 상한 — 컬럼 {@code QSTN_CN VARCHAR(4000)}(표준도메인 내용V4000).
     *
     * <p>입력단 검증이 이 값을 <b>반드시</b> 봐야 한다 — 안 보면 초과 입력이 검증을 통과해 INSERT
     * 시점에 DB 오류(500)로 터진다. 400 으로 되돌려 주는 것이 입구 검증의 일이다.
     */
    public static final int QSTN_CN_MAX_LENGTH = 4000;

    /**
     * 질문 문구 허용 문자 — <b>제어문자·개행 금지</b>(C0 {@code 0x00~0x1F} · DEL {@code 0x7F}).
     *
     * <p>이 값은 <b>외부 사업자 요청 바디와 로그에 그대로 실린다</b>. 개행이 섞이면 로그 한 줄에 여러
     * 줄이 들어가 기록을 위조할 수 있고(CWE-117), 사업자 쪽 파싱도 깨진다.
     *
     * <p>어노테이션 상수로 쓰이므로 컴파일 타임 상수여야 한다 — 리터럴을 복제하지 말고 이 상수를 참조할 것.
     */
    public static final String QSTN_CN_ALLOWED_REGEX = "^[^\\u0000-\\u001F\\u007F]+$";

    private static final Pattern QSTN_CN_ALLOWED = Pattern.compile(QSTN_CN_ALLOWED_REGEX);

    /**
     * 질문 문구 입력 정규화 — 앞뒤 공백을 걷어낸다.
     *
     * <p>빈 값(null·공백만)은 <b>질문이 아니므로</b> {@code null} 을 돌려준다. 판정은 반드시 정규화
     * <b>결과</b>에 대해 한다 — 그래야 표기 변형으로 검증을 우회할 수 없다.
     */
    public static String normalizeQstnCn(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 질문 문구 검증 — 빈 값·제어문자·길이 초과를 거른다.
     *
     * @param normalized {@link #normalizeQstnCn} 를 통과한 값(또는 {@code null})
     * @return 저장해도 되는 값이면 {@code true}. {@code null}(빈 질문)은 {@code false}
     */
    public static boolean isQstnCnValid(String normalized) {
        return normalized != null
                && normalized.length() <= QSTN_CN_MAX_LENGTH
                && QSTN_CN_ALLOWED.matcher(normalized).matches();
    }

    /** 검증이벤트질문일련번호 (PK) — 마킹이 고른 질문을 이 값으로 보관한다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "VRFC_EVNT_QSTN_SN")
    private Long vrfcEvntQstnSn;

    /**
     * 이 질문이 딸린 검증 이벤트 유형.
     *
     * <p>연관 <b>객체</b>가 아니라 코드 값으로 든다 — 이 저장소의 Aggregate 간 참조 관례이고, 유형은
     * 이 경로에서 수정 대상이 아니라 소유 키로만 쓰인다.
     */
    @Column(name = "VRFC_EVNT_TYPE_CD", length = 20, nullable = false)
    private String vrfcEvntTypeCd;

    /**
     * 같은 유형 안에서 이 질문이 놓이는 순서 — <b>1부터</b> 요청 배열 순서를 그대로 매긴다.
     * ★「첫 번째 질문」의 결정성 근거(클래스 javadoc 참조).
     */
    @Column(name = "SORT_SEQ", nullable = false)
    private Integer sortSeq;

    /** 질문 문구 전문 — 이벤트 어노테이션의 질문 칸에 이 값이 그대로 들어간다. */
    @Column(name = "QSTN_CN", length = QSTN_CN_MAX_LENGTH, nullable = false)
    private String qstnCn;

    /** 등록자아이디. */
    @Column(name = "REG_ID", length = 30)
    private String regId;

    /** 등록일시. */
    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    /**
     * 수정자아이디.
     *
     * <p>이 표의 행은 <b>갱신되지 않는다</b> — 편집 통로가 「목록 전체 교체」 하나뿐이라 행은 지워지고
     * 다시 생긴다. 따라서 "누가 이 문구를 넣었나"는 {@link #regId} 가 답하고 이 칸은 비어 있는 것이
     * 정상이다. 삽입 시점에 채우면 "고친 적 없음"과 "고쳤음"이 값으로 구분되지 않는다.
     */
    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    /** 수정일시 — {@link #mdfrId} 와 같은 이유로 비어 있는 것이 정상이다. */
    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsVrfcEvntQstn(String vrfcEvntTypeCd, Integer sortSeq, String qstnCn, String actor) {
        this.vrfcEvntTypeCd = vrfcEvntTypeCd;
        this.sortSeq = sortSeq;
        this.qstnCn = qstnCn;
        this.regId = actor;
        this.regDt = LocalDateTime.now();
    }

    /**
     * 질문 1건 생성 — <b>전체 교체 저장</b>({@code PUT .../questions})에서만 호출한다.
     *
     * @param vrfcEvntTypeCd 소유 유형 코드(실재가 이미 확인된 값)
     * @param sortSeq        요청 배열 순서(1부터)
     * @param qstnCn         {@link #normalizeQstnCn} · {@link #isQstnCnValid} 를 통과한 문구
     * @param actor          요청자 식별자(감사 — 토큰 subject). 알 수 없으면 {@code null}
     */
    public static LsVrfcEvntQstn create(String vrfcEvntTypeCd, Integer sortSeq,
                                        String qstnCn, String actor) {
        return new LsVrfcEvntQstn(vrfcEvntTypeCd, sortSeq, qstnCn, actor);
    }

    /** 이 질문이 주어진 유형에 속하는가 — 조달 판정기의 소속 검증축. */
    public boolean belongsTo(String typeCd) {
        return this.vrfcEvntTypeCd != null && this.vrfcEvntTypeCd.equals(typeCd);
    }
}
