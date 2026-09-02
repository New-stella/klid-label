package kr.co.cudo.authoring.aiserver.service;

import java.util.regex.Pattern;

/**
 * AI 서버 식별자({@code SRVR_ID}) 형식 판정의 <b>단일 진실원</b>. [@design ADR-057] [@req R12]
 *
 * <h3>왜 하이픈과 대문자를 막는가</h3>
 * <p>이 값은 서킷브레이커 이름({@code ai-batch-<식별자>} 꼴)과 메트릭 라벨로 <b>조립</b>된다.
 * 실제 장비 호스트명({@code klid-ai-gpu-01})을 그대로 쓰면 조립 결과에서 <b>어디서 갈리는지
 * 파싱으로 복원되지 않아</b> 라벨이 조용히 어긋난다 — 그리고 그 어긋남은 한참 뒤 대시보드에서야
 * 드러난다. 실제 호스트명은 {@code SRVR_NM} 에 따로 둔다.
 *
 * <h3>형식 제한이 곧 방어선이다</h3>
 * <p>이 값은 관리 화면에서 들어오는 <b>외부 입력</b>이고, 그대로 로그와 메트릭 라벨에 실린다.
 * 개행·제어문자가 섞이면 로그 한 줄에 여러 줄이 들어가 기록을 위조할 수 있다(CWE-117).
 * 소문자·숫자만 허용하는 것이 그 경로를 통째로 닫는다. 길이 상한은 컬럼 폭과 같아서, 입구에서
 * 걸러 주지 않으면 INSERT 시점 DB 오류(500)가 된다.
 *
 * <h3>3중 강제의 가운데 자리</h3>
 * <p>같은 규칙이 세 곳에 있다 — (1)DB 체크 제약 (2)기동 가드 (3)입력 DTO. 규칙 <b>문자열</b>은
 * 여기 하나뿐이고 나머지가 이것을 참조한다(체크 제약과의 일치는 통합 시험이 대조한다).
 * 정규식을 복제하면 그 사본이 두 번째 진실원이 되어, 한쪽만 통과하는 값이 조용히 생긴다.
 */
public final class AiSrvrIdPolicy {

    /** 식별자 최대 길이 — 컬럼 {@code SRVR_ID VARCHAR(20)}(표준도메인 명V20)과 같다. */
    public static final int SRVR_ID_MAX_LENGTH = 20;

    /**
     * 식별자 허용 형식 — 소문자·숫자만, 1자 이상 {@link #SRVR_ID_MAX_LENGTH} 자 이하.
     *
     * <p>어노테이션 상수로도 쓰이므로 컴파일 타임 상수여야 한다(리터럴을 복제하지 말고 이 상수를
     * 참조할 것). DB 체크 제약 {@code ck_ls_ai_srvr_srvr_id_format} 이 같은 문자열을 쓴다.
     */
    public static final String SRVR_ID_REGEX = "^[a-z0-9]{1,20}$";

    private static final Pattern SRVR_ID_PATTERN = Pattern.compile(SRVR_ID_REGEX);

    private AiSrvrIdPolicy() {
    }

    /**
     * 형식 판정 — {@code null}·빈 값·공백은 식별자가 아니므로 거짓이다.
     *
     * <p>여기서 <b>다듬지 않는다</b>(trim 하지 않는다). 다듬어서 통과시키면 저장된 값과 입력한
     * 값이 달라지고, 그 차이가 서킷 이름·메트릭 라벨까지 따라간다.
     */
    public static boolean isValid(String srvrId) {
        return srvrId != null && SRVR_ID_PATTERN.matcher(srvrId).matches();
    }
}
