package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletContext;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * <b>브라우저가 우리 API 를 부를 때 쓰는 경로 접두어</b>를 확정하는 자리 — 해석은 여기 한 곳이다.
 *
 * <h2>해석 순서</h2>
 * <ol>
 *   <li><b>설정값</b>({@link PublicApiPathDefaults#PROPERTY_KEY}) — 지정했으면 그것이 이긴다.</li>
 *   <li><b>WAR 웹 컨텍스트에서 도출</b> — {@code 컨텍스트 + /v1}.
 *       근거는 {@link PublicApiPathDefaults#deriveFromContextPath} javadoc.</li>
 *   <li><b>종전 기본값</b>({@code /api/v1}) — 컨텍스트가 비어 있을 때(루트 배포·MockMvc).</li>
 * </ol>
 *
 * <h2>왜 도출을 기본으로 두나</h2>
 * <p>이 값과 웹 컨텍스트는 <b>같은 사실의 두 표현</b>이다. 둘을 따로 적게 하면 배포마다 두 곳을
 * 맞춰야 하고, <b>한 곳만 바꿨을 때 아무 신호 없이 영상·이미지가 남의 서버로 날아간다.</b>
 * 웹 컨텍스트는 이미 빌드 인자로 향마다 정해지므로, 그것에서 도출하면 <b>어긋날 자리 하나가
 * 통째로 사라진다.</b>
 *
 * <p>⚠ 그럼에도 설정을 남겨 둔 이유는 도출 규칙이 <b>앞단이 접두어를 그대로 넘긴다</b>는 전제
 * 위에 서 있기 때문이다. 그 전제가 깨지는 향이 생기면 설정으로 덮는다.
 *
 * <h2>단위 시험에서는 빈이 없다</h2>
 * <p>서비스들이 생성자로 직접 만들어지는 단위 시험에는 이 빈이 주입되지 않는다. 그래서 각 서비스는
 * 필드 초기값으로 {@link #ofDefault()} 를 들고 있고, 스프링이 뜨면 그 자리가 이 빈으로 교체된다.
 * ⚠ 그 초기값을 지우면 단위 시험이 {@code NullPointerException} 으로 죽는다.
 */
@Slf4j
@Component
public class PublicApiPath {

    /** 확정된 접두어 — 앞 슬래시 있음, 뒤 슬래시 없음. */
    private final String prefix;

    /** 어디서 왔는지 — 기동 로그에만 쓴다(운영자가 「왜 이 값인가」를 물을 때의 답). */
    private final String origin;

    /**
     * ⚠ {@code @Autowired} 를 지우지 말 것 — 이 클래스는 생성자가 <b>둘</b>이라(아래 단위 시험용
     * private 생성자) 표식이 없으면 스프링이 어느 것을 쓸지 고르지 못하고
     * {@code No default constructor found} 로 <b>컨텍스트 전체가 기동에 실패</b>한다.
     * (2026-09-10 실측 — 시험이 잡았다.)
     */
    @Autowired
    public PublicApiPath(
            @Value(PublicApiPathDefaults.VALUE_EXPRESSION) String configured,
            ObjectProvider<ServletContext> servletContextProvider) {
        String explicit = configured == null ? "" : configured.trim();
        if (!explicit.isEmpty()) {
            this.prefix = PublicApiPathDefaults.normalize(explicit);
            this.origin = "설정(" + PublicApiPathDefaults.PROPERTY_KEY + ")";
            return;
        }
        // ⚠ ObjectProvider 인 이유: 웹이 아닌 컨텍스트(일부 시험)에는 ServletContext 빈이 없다.
        //   생성자에 직접 받으면 그런 컨텍스트가 통째로 기동에 실패한다.
        ServletContext ctx = servletContextProvider.getIfAvailable();
        String contextPath = ctx == null ? "" : ctx.getContextPath();
        this.prefix = PublicApiPathDefaults.deriveFromContextPath(contextPath);
        this.origin = (contextPath == null || contextPath.isEmpty())
                ? "기본값(웹 컨텍스트 없음)"
                : "웹 컨텍스트('" + contextPath + "') + " + PublicApiPathDefaults.CONTROLLER_ROOT;
    }

    private PublicApiPath(String prefix, String origin) {
        this.prefix = prefix;
        this.origin = origin;
    }

    /**
     * 스프링 없이 쓸 때의 기본 — <b>단위 시험 전용</b>.
     *
     * <p>서비스 필드의 초기값으로 둔다. 스프링이 뜨면 주입이 이 값을 덮으므로 운영 경로에서는
     * 쓰이지 않는다.
     */
    public static PublicApiPath ofDefault() {
        return new PublicApiPath(PublicApiPathDefaults.DEFAULT_BASE_PATH, "단위 시험 기본값");
    }

    @PostConstruct
    void announce() {
        // 운영자가 「지금 어떤 주소가 나가고 있나」를 로그 한 줄로 알 수 있게 한다.
        log.info("[PublicApiPath] 브라우저 호출 접두어 = {} (출처: {})", prefix, origin);
    }

    /** 확정된 접두어. */
    public String prefix() {
        return prefix;
    }

    /**
     * 브라우저가 부를 절대 경로를 만든다.
     *
     * @param suffix 접두어 뒤에 붙일 경로. 반드시 {@code '/'} 로 시작한다.
     */
    public String of(String suffix) {
        return PublicApiPathDefaults.join(prefix, suffix);
    }
}
