package kr.co.cudo.authoring.common.config;

/**
 * <b>브라우저가 우리 API 를 부를 때 쓰는 경로 접두어</b>의 단일 출처.
 *
 * <h2>왜 필요한가 (되돌리기 전에 반드시 읽을 것)</h2>
 *
 * <p>백엔드가 응답 <b>본문에</b> 절대 경로를 담아 내려주는 자리 중 이 값을 쓰는 곳은 둘이다 —
 * 포털 업로드 재생 서명 주소, 검수 프레임 이미지 주소. 이 값들은 브라우저가 그대로
 * {@code <video src>} · {@code <img src>} 에 물리는 주소라, <b>컨트롤러 매핑이 아니라 「앞단
 * 웹서버가 우리에게 넘겨주는 접두어」</b>를 알아야 만들 수 있다.
 *
 * <p>그런데 그 접두어는 <b>배포 향마다 다르다</b>:
 * <ul>
 *   <li>포털 향 — 앞단이 {@code /authoring-api/} 로 우리에게 넘긴다(포털 자신의 {@code /api/v1/}
 *       은 <b>포털 WAS</b> 로 간다). 그래서 {@code /api/v1/...} 을 내려주면 그 재생 요청이
 *       <b>남의 서버로</b> 가고 영상이 전부 깨진다.</li>
 *   <li>관제 향 — 브라우저가 보는 접두어가 {@code /label-studio/api/v1} 이다.</li>
 * </ul>
 *
 * <p>즉 이 값은 <b>코드가 알 수 없는 배포 사실</b>이므로 설정으로 받는다. 기본값은 종전 리터럴
 * 그대로라, 지정하지 않으면 <b>지금 동작이 한 글자도 바뀌지 않는다.</b>
 *
 * <p>⚠ <b>내부 영상 재생 주소({@code /v1/videos/{rawSn}/stream-url})는 이 값을 쓰지 않는다</b>
 * ({@code @design API-114}). 그 주소는 배포 접두를 뺀 API 기준 경로({@link #DEFAULT_BASE_PATH})이고
 * 화면이 자기 배포 접두를 붙인다. 이 값을 쓰면 관제향에서 접두가 두 번 붙어 재생되지 않았다
 * (2026-09-16 온프렘 실측). 반대로 포털 업로드 재생 주소는 포털 화면이 접두를 계산할 수 없어
 * (API 기준 주소가 {@code /api/v1} 로 끝나지 않는다) 이 값을 그대로 써야 한다 — 두 주소를 한쪽으로
 * 맞추려 들지 말 것.
 *
 * <p>⚠ <b>서명에는 영향이 없다.</b> 서명 입력은 {@code rawSn}·{@code exp}·{@code userNo}·nonce 이고
 * 경로가 아니다 — 접두어를 바꿔도 기존 서명 검증이 깨지지 않는다.
 *
 * <p>⚠ <b>컨트롤러 매핑을 이 값으로 바꾸려 들지 말 것.</b> 컨트롤러가 어디에 붙는지는 WAR 컨텍스트
 * (WEB-INF/jboss-web.xml)가 정하고, 이것은 <b>브라우저가 부를 주소</b>를 만드는 별개 축이다.
 * 둘이 같은 값일 수도 있고 아닐 수도 있다(앞단이 접두어를 떼느냐에 따라 갈린다).
 *
 * <p>{@link #VALUE_EXPRESSION} 은 리터럴 연결이라 애노테이션 속성으로 쓸 수 있다.
 * 형식 검증은 기동 시 {@link PublicApiPathGuard} 가 한 번에 한다 — 요청마다 던지면 설정 오타가
 * 500 폭주가 되므로, 잘못된 값은 <b>기동을 막는 쪽</b>이 옳다.
 */
public final class PublicApiPathDefaults {

    private PublicApiPathDefaults() {
    }

    /** 프로퍼티 키. */
    public static final String PROPERTY_KEY = "authoring.public-api-base-path";

    /**
     * 미설정 시 폴백 — <b>종전 하드코딩 리터럴과 같은 값</b>이다.
     *
     * <p>다른 값으로 바꾸지 말 것. 이 상수의 존재 이유는 「설정을 안 하면 지금 그대로」를
     * 보장하는 것이고, 값을 옮기는 순간 그 보장이 사라진다.
     */
    public static final String DEFAULT_BASE_PATH = "/api/v1";

    /**
     * {@code @Value} 표현식 — 프로퍼티 키 + <b>빈 문자열</b> 기본값.
     *
     * <p>★ 기본값을 {@link #DEFAULT_BASE_PATH} 가 아니라 <b>비워 두는 것이 핵심</b>이다.
     * 여기에 값을 채우면 「운영자가 지정했는가」와 「기본값이 채워졌는가」를 구분할 수 없어,
     * 아래 {@link #deriveFromContextPath} 자동 도출이 <b>영원히 발동하지 않는다.</b>
     */
    public static final String VALUE_EXPRESSION = "${" + PROPERTY_KEY + ":}";

    /** 컨트롤러가 컨텍스트 바로 아래에서 시작하는 세그먼트. */
    public static final String CONTROLLER_ROOT = "/v1";

    /**
     * <b>WAR 웹 컨텍스트에서 공개 접두어를 도출한다</b> — 설정을 지정하지 않았을 때의 기본.
     *
     * <h2>왜 도출이 가능한가</h2>
     * <p>두 사실이 겹쳐서 성립한다:
     * <ol>
     *   <li><b>컨트롤러는 예외 없이 컨텍스트 바로 아래 {@code /v1} 에서 시작한다</b>
     *       ({@code ServletInitializer} javadoc). 이건 이 저장소의 불변식이다.</li>
     *   <li><b>앞단 웹서버가 접두어를 그대로 넘긴다</b>(passthrough). 알려진 두 향이 모두 그렇다 —
     *       포털향 {@code /authoring-api} · 관제향 {@code /label-studio/api}.</li>
     * </ol>
     * <p>따라서 <b>브라우저가 부르는 주소 = 웹 컨텍스트 + {@code /v1}</b> 이다.
     *
     * <h2>덕분에 얻는 것</h2>
     * <p>배포마다 설정을 따로 넣지 않아도 맞는다. 웹 컨텍스트는 이미 빌드 인자
     * ({@code KLID_WEB_CONTEXT})로 향마다 정해지므로, <b>값을 두 곳에 적을 필요가 없다</b> —
     * 한 곳만 바꾸면 두 축이 함께 움직인다(어긋날 자리를 하나 없앤다).
     *
     * <h2>⚠ 언제 틀리나 — 그때는 설정으로 덮는다</h2>
     * <p>앞단이 접두어를 <b>떼고</b> 넘기는 향(strip)에서는 성립하지 않는다. 그런 향은 현재
     * 어디에도 없지만, 생기면 {@link #PROPERTY_KEY} 로 명시하면 된다(설정이 언제나 이긴다).
     *
     * @param contextPath 서블릿 컨텍스트 경로. 비어 있으면(내장 서버 루트·MockMvc) 기본값으로 떨어진다.
     */
    public static String deriveFromContextPath(String contextPath) {
        if (contextPath == null) return DEFAULT_BASE_PATH;
        String ctx = contextPath.trim();
        // 루트 배포·MockMvc 는 컨텍스트가 빈 문자열이다. 그때 "/v1" 로 도출하면 기존 동작이
        // 바뀌므로(시험·로컬이 /api/v1 을 기대한다) 종전 기본값을 그대로 쓴다.
        if (ctx.isEmpty() || "/".equals(ctx)) return DEFAULT_BASE_PATH;
        return normalize(ctx) + CONTROLLER_ROOT;
    }

    /**
     * 설정값을 정규화한다 — 앞 슬래시 보장, 뒤 슬래시 제거.
     *
     * <p>비었거나 {@code null} 이면 기본값으로 떨어진다. {@code null} 이 오는 경우는 <b>단위
     * 시험이 서비스를 생성자로 직접 만들 때</b>뿐이다(그때는 {@code @Value} 가 주입되지 않는다).
     * 운영 경로에서는 Spring 이 언제나 기본값이라도 채워 준다.
     */
    public static String normalize(String configured) {
        if (configured == null) return DEFAULT_BASE_PATH;
        String trimmed = configured.trim();
        if (trimmed.isEmpty()) return DEFAULT_BASE_PATH;
        String withLead = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        // 뒤 슬래시는 붙는 쪽(suffix)이 이미 '/' 로 시작하므로 여기서 떼어 '//' 를 막는다.
        while (withLead.length() > 1 && withLead.endsWith("/")) {
            withLead = withLead.substring(0, withLead.length() - 1);
        }
        return withLead;
    }

    /**
     * 브라우저가 부를 절대 경로를 만든다.
     *
     * @param configured 설정 원문(정규화 전). {@code null} 이면 기본값.
     * @param suffix     접두어 뒤에 붙일 경로. 반드시 {@code '/'} 로 시작한다.
     */
    public static String join(String configured, String suffix) {
        if (suffix == null || !suffix.startsWith("/")) {
            throw new IllegalArgumentException("suffix 는 '/' 로 시작해야 합니다: " + suffix);
        }
        return normalize(configured) + suffix;
    }
}
