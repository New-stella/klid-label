package kr.co.cudo.authoring.common.config;

/**
 * <b>이 배포본이 어느 채널용인가</b> — 설치 시점에 정해지는 배포 향. [@design ADR-012]
 *
 * <p>저작도구는 채널마다 <b>별도로 배포</b>되므로(관제 채널 배포본 · 포털 채널 배포본) 각 배포본은
 * 자기 채널을 <b>이미 알고 있다</b>. 그 사실을 담는 값이다.
 *
 * <h2>값역이 프론트와 같은 낱말인 이유</h2>
 * <p>프론트의 빌드 채널({@code frontend/src/lib/buildChannel.ts} 의 {@code BUILD_CHANNELS})이
 * {@code control}·{@code portal} 이며 <b>같은 사실</b>을 가리킨다. 두 값이 갈리면 같은 배포에서
 * 화면과 서버가 서로 다른 채널로 동작한다.
 *
 * <p>⚠ <b>{@code internal} 은 쓰지 않는다.</b> 그 낱말은 이 저장소에서 <b>인증 채널</b> 축
 * ({@link kr.co.cudo.authoring.common.security.Channel#INTERNAL})을 가리키며, 한 낱말이 두 축을
 * 가리키면 한쪽 값을 다른 쪽 판정에 쓰는 사고가 난다(프론트가 같은 이유로 {@code internal} 을
 * 폐기하고 {@code control} 로 바꾼 전례가 있다).
 *
 * <p>⚠ <b>키 이름은 둘로 유지한다</b> — 빌드타임 {@code VITE_BUILD_CHANNEL}(산출물에 굳는 값)과
 * 설치시점 {@code KLID_DEPLOY_FLAVOR}(설치 스크립트가 정하는 값). <b>값만 같게 맞추고 키를 합치지
 * 않는다.</b>
 */
public enum DeployFlavor {

    /** 관제 채널 배포본 — 인계 토큰의 채널 값을 종전대로 해석한다(값 있으면 그 값, 없으면 INTERNAL). */
    CONTROL,

    /** 포털 채널 배포본 — 인계 토큰의 채널 값을 <b>읽지 않고</b> 포털 채널로 확정한다. */
    PORTAL;

    /**
     * 설정 속성 이름.
     *
     * <p>⚠ <b>인증 전용 설정을 새로 두지 않는다</b>({@code ADR-012}) — 같은 사실을 두 곳에서
     * 선언하면 둘이 어긋나는 순간 어느 쪽이 참인지 판정할 수단이 없다. 그래서 이미 규정된
     * 배포 향 선언({@code KLID_DEPLOY_FLAVOR})을 그대로 재사용한다.
     */
    public static final String PROPERTY_KEY = "authoring.deploy.flavor";

    /** {@code @Value} 표현식 — <b>빈 기본값</b>이 핵심이다(미선언과 값역 밖을 같은 자리에서 다룬다). */
    public static final String VALUE_EXPRESSION = "${" + PROPERTY_KEY + ":}";

    /**
     * 선언되지 않았거나 값역 밖일 때의 향 — <b>관제 채널</b>(fail-closed).
     *
     * <p>근거({@code ADR-012}): <i>"배포 향이 선언되지 않았거나 값역 밖이면 관제 채널로 해석한다"</i>.
     * ⚠ <b>기동을 막지 않는다</b> — 그 결정이 「해석한다」이지 「거부한다」가 아니다.
     */
    public static final DeployFlavor DEFAULT = CONTROL;

    /**
     * 선언값을 향으로 해석한다 — <b>모르는 값은 {@link #DEFAULT}</b>.
     *
     * <p>공백을 다듬고 대소문자를 무시한다. 설치 스크립트가 소문자로 쓰지만, 설정을 손으로 넣는
     * 자리에서 {@code Portal} 로 적었다고 반대 채널로 동작하면 원인을 찾기 어렵다.
     *
     * @param declared 선언값. {@code null}·공백·값역 밖이면 {@link #DEFAULT}
     */
    public static DeployFlavor parseOrDefault(String declared) {
        if (declared == null) {
            return DEFAULT;
        }
        String v = declared.trim();
        if (v.isEmpty()) {
            return DEFAULT;
        }
        for (DeployFlavor flavor : values()) {
            if (flavor.name().equalsIgnoreCase(v)) {
                return flavor;
            }
        }
        return DEFAULT;
    }

    /**
     * 선언값이 <b>값역 밖</b>인가 — 「미선언」과 구분한다.
     *
     * <p>둘 다 {@link #DEFAULT} 로 떨어지지만 <b>운영자에게는 다른 사실</b>이다. 미선언은 의도일 수
     * 있고 값역 밖은 <b>오기</b>다. 조용히 같게 다루면 오기를 영영 못 찾는다 — {@code ADR-012} 가
     * 위험으로 적어 둔 자리다(<i>"배포 향 선언 누락·오기는 기동 시점이 아니라 인계 진입 시점에야
     * 드러난다"</i>).
     */
    public static boolean isOutOfRange(String declared) {
        if (declared == null) {
            return false;
        }
        String v = declared.trim();
        if (v.isEmpty()) {
            return false;
        }
        for (DeployFlavor flavor : values()) {
            if (flavor.name().equalsIgnoreCase(v)) {
                return false;
            }
        }
        return true;
    }
}
