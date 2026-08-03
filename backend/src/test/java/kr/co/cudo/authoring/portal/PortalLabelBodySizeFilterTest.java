package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.config.PortalLabelBodySizeFilter;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.controller.PortalUploadLabelController;
import kr.co.cudo.authoring.portal.service.PortalUploadLabelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 4 (DEV_FIX M-1) — 라벨 PUT 본문 크기 상한 필터 단위 검증.
 * 경로 한정(라벨 PUT 만)·413(초과)·411(chunked)·정상 통과·타 경로/메서드 미적용을 확인한다.
 *
 * <p>2차 QA(CWE-436/770) — 필터가 원시 {@code getRequestURI()} 문자열 정규식으로 판정하던 시절
 * {@code /labels} 의 한 글자를 퍼센트 인코딩({@code %6Cabels})하면 <b>필터만 스킵되고 컨트롤러는
 * 정상 라우팅</b>되어 상한·chunked 차단이 통째로 무력화됐다. 아래 인코딩/matrix/context-path 케이스와
 * {@link #encodedVariantsNeverBypassTheCap()} 파리티 테스트가 그 재발을 막는다.
 */
class PortalLabelBodySizeFilterTest {

    private static final long MAX = 1024L;
    // D2 — 본문 한도는 PortalUploadProperties(record) 로 통합됐다(구 @Value 주입 제거).
    private final PortalLabelBodySizeFilter filter =
            new PortalLabelBodySizeFilter(new ObjectMapper(), new PortalUploadProperties(
                    5_368_709_120L, java.util.List.of("mp4"), "./storage/raw/portal",
                    java.util.List.of("jpg"), 20_971_520L, 50, 2000,
                    16_777_216L, MAX, 30L, 30L));
    private static final String LABEL_PUT = "/v1/portal/uploads/frames/500/labels";
    /** 데이터마트 사용자 라벨 저장(POST) — 좌표 JSON 을 본문으로 받는다. */
    private static final String USER_LABELS = "/v1/portal/user-labels";

    /** MockHttpServletRequest.getContentLengthLong() 는 실제 content 길이(없으면 -1)를 반환한다. */
    private MockHttpServletRequest req(String method, String path, byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        if (body != null) {
            r.setContent(body);
        }
        return r;
    }

    /**
     * 원시(퍼센트 인코딩된) URI 요청. {@code servletPath} 를 <b>일부러 설정하지 않는다</b> — 필터는
     * 서블릿 컨테이너가 채워주는 값에 의존하지 않고 MVC 라우팅과 동일한 경로 표현으로 판정해야 한다.
     */
    private MockHttpServletRequest rawReq(String method, String rawUri, byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, rawUri);
        if (body != null) {
            r.setContent(body);
        }
        return r;
    }

    private static byte[] oversized() {
        return new byte[(int) (MAX + 1)];
    }

    @Test
    @DisplayName("본문_상한초과_413")
    void tooLarge() throws Exception {
        MockHttpServletRequest r = req("PUT", LABEL_PUT, oversized());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull(); // 체인 미진행(파싱 전 차단)
    }

    @Test
    @DisplayName("Content_Length_부재_chunked_411")
    void chunkedRejected() throws Exception {
        // MockHttpServletRequest 는 setContent 없이 Content-Length 미설정 시 getContentLengthLong()=-1.
        MockHttpServletRequest r = new MockHttpServletRequest("PUT", LABEL_PUT);
        r.setServletPath(LABEL_PUT);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(411);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("정상_크기_본문은_통과")
    void withinLimitPasses() throws Exception {
        byte[] body = new byte[]{'[', ']'};
        MockHttpServletRequest r = req("PUT", LABEL_PUT, body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull(); // 체인 진행
    }

    @Test
    @DisplayName("GET_라벨_경로는_필터_미적용")
    void getNotFiltered() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", LABEL_PUT);
        r.setServletPath(LABEL_PUT);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(chain.getRequest()).isNotNull(); // Content-Length 미설정이어도 통과(GET 미적용)
    }

    @Test
    @DisplayName("타_경로_PUT은_필터_미적용")
    void otherPathNotFiltered() throws Exception {
        String other = "/v1/portal/uploads/images";
        MockHttpServletRequest r = new MockHttpServletRequest("PUT", other);
        r.setServletPath(other);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    // ─── 2차 QA (CWE-436) — 경로 판정 우회 회귀 방어 ───

    @Test
    @DisplayName("%6Cabels_같은_단순_퍼센트인코딩_경로에서도_본문상한이_적용된다")
    void percentEncodedPathStillCapped() throws Exception {
        MockHttpServletRequest r =
                rawReq("PUT", "/v1/portal/uploads/frames/500/%6Cabels", oversized());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("chunked_전송이_인코딩_경로에서도_411로_차단된다")
    void chunkedRejectedOnEncodedPath() throws Exception {
        MockHttpServletRequest r =
                rawReq("PUT", "/v1/portal/uploads/frames/500/%6Cabels", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(411);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("matrix파라미터_경로에서도_본문상한이_적용된다")
    void matrixParameterPathStillCapped() throws Exception {
        // PathPattern 은 path parameter(;a=b) 를 제거한 값으로 매칭하므로 컨트롤러에 그대로 라우팅된다.
        MockHttpServletRequest r =
                rawReq("PUT", "/v1/portal/uploads/frames/500/labels;v=1", oversized());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("trailing_slash_중복슬래시_matrix파라미터_경로에서도_정상_판정된다")
    void separatorVariantsStillCapped() throws Exception {
        // trailing-slash 매칭 허용 여부는 MVC handler mapping 설정에 좌우된다(실측: MVC 는 /labels/ 를
        // 컨트롤러로 라우팅했다). 필터가 "덜 매칭"하면 그 자체로 우회이므로 상한 대상으로 잡는다.
        // matrix parameter(;a=b) 는 valueToMatch 단계에서 제거되므로 정상 경로와 동일 판정이다.
        List<String> capped = List.of(
                "/v1/portal/uploads/frames/500/labels/",     // trailing slash
                "/v1/portal/uploads/frames/500/labels;v=1",  // matrix parameter
                "/v1/portal/uploads/frames;a=b/500/labels"); // matrix parameter(중간 세그먼트)

        for (String uri : capped) {
            MockHttpServletRequest r = rawReq("PUT", uri, oversized());
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(r, res, chain);

            assertThat(res.getStatus()).as("상한 적용: %s", uri).isEqualTo(413);
            assertThat(chain.getRequest()).as("체인 미진행: %s", uri).isNull();
        }

        // 중복 슬래시는 컨트롤러도 라우팅하지 않는다(운영에서는 Security StrictHttpFirewall 이 선차단).
        // 필터가 함께 스킵해도 우회가 아니며, 이 등가성은 파리티 테스트가 실제 라우팅으로 재확인한다.
        MockHttpServletRequest dup =
                rawReq("PUT", "/v1/portal/uploads/frames/500//labels", oversized());
        MockFilterChain dupChain = new MockFilterChain();
        filter.doFilter(dup, new MockHttpServletResponse(), dupChain);
        assertThat(dupChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("context_path_설정_여부와_무관하게_동일하게_동작한다")
    void contextPathAgnostic() throws Exception {
        // (1) context-path 미설정
        MockHttpServletRequest plain = rawReq("PUT", LABEL_PUT, oversized());
        MockHttpServletResponse plainRes = new MockHttpServletResponse();
        filter.doFilter(plain, plainRes, new MockFilterChain());
        assertThat(plainRes.getStatus()).isEqualTo(413);

        // (2) server.servlet.context-path=/api 설정 — requestURI 는 컨텍스트 경로를 포함한다.
        MockHttpServletRequest ctx = rawReq("PUT", "/api" + LABEL_PUT, oversized());
        ctx.setContextPath("/api");
        MockHttpServletResponse ctxRes = new MockHttpServletResponse();
        MockFilterChain ctxChain = new MockFilterChain();
        filter.doFilter(ctx, ctxRes, ctxChain);
        assertThat(ctxRes.getStatus()).isEqualTo(413);
        assertThat(ctxChain.getRequest()).isNull();

        // (3) context-path 하위 + 인코딩 변형도 동일
        MockHttpServletRequest ctxEncoded =
                rawReq("PUT", "/api/v1/portal/uploads/frames/500/%6Cabels", oversized());
        ctxEncoded.setContextPath("/api");
        MockHttpServletResponse ctxEncodedRes = new MockHttpServletResponse();
        filter.doFilter(ctxEncoded, ctxEncodedRes, new MockFilterChain());
        assertThat(ctxEncodedRes.getStatus()).isEqualTo(413);
    }

    @Test
    @DisplayName("타_포털_엔드포인트_이미지조회_등은_영향받지_않는다")
    void otherPortalEndpointsUnaffected() throws Exception {
        // 라벨 본문을 받지 않는 경로만 남긴다. (구 목록에 있던 /v1/portal/user-labels 는
        //  실제로는 라벨 좌표 JSON 을 받는 저장 엔드포인트라 "미적용이 정상"이 아니었다 —
        //  3차 QA CWE-770. 아래 userLabelSaveIsCapped() 로 이관.)
        List<String> untouched = List.of(
                "/v1/portal/uploads/frames/500/image",
                "/v1/portal/frames/500/image",
                "/v1/portal/uploads/500/file",
                "/v1/portal/uploads/500/export");

        for (String path : untouched) {
            MockHttpServletRequest r = rawReq("PUT", path, oversized());
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(r, res, chain);

            assertThat(chain.getRequest()).as("필터 미적용 경로: %s", path).isNotNull();
            assertThat(res.getStatus()).as("필터 미적용 경로: %s", path).isEqualTo(200);
        }
    }

    // ─── 3차 QA (CWE-770) — 데이터마트 사용자 라벨 저장(POST) 본문 상한 ───
    //   POST /v1/portal/user-labels 는 좌표 JSON 문자열(points)을 그대로 받는 저장 엔드포인트인데
    //   필터 대상이 아니어서 원 결함(라벨 PUT pre-parse DoS)과 동일 클래스가 그대로 남아 있었다.

    @Test
    @DisplayName("본인라벨_저장_요청도_본문상한이_적용된다")
    void userLabelSaveIsCapped() throws Exception {
        MockHttpServletRequest r = rawReq("POST", USER_LABELS, oversized());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("본인라벨_저장_chunked_전송은_411로_차단된다")
    void userLabelSaveChunkedRejected() throws Exception {
        MockHttpServletRequest r = rawReq("POST", USER_LABELS, null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(411);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("본인라벨_저장_인코딩_trailing_slash_변형에서도_상한이_적용된다")
    void userLabelSaveVariantsCapped() throws Exception {
        List<String> capped = List.of(
                "/v1/portal/user-labels",
                "/v1/portal/user-%6Cabels",   // 퍼센트 인코딩
                "/v1/portal/user-labels/",    // trailing slash
                "/v1/portal/user-labels;v=1");// matrix parameter

        for (String uri : capped) {
            MockHttpServletRequest r = rawReq("POST", uri, oversized());
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(r, res, chain);

            assertThat(res.getStatus()).as("상한 적용: %s", uri).isEqualTo(413);
            assertThat(chain.getRequest()).as("체인 미진행: %s", uri).isNull();
        }
    }

    @Test
    @DisplayName("본인라벨_저장_정상_크기_본문은_통과한다")
    void userLabelSaveWithinLimitPasses() throws Exception {
        MockHttpServletRequest r = rawReq("POST", USER_LABELS, "{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("본인라벨_조회_GET은_필터_미적용")
    void userLabelGetNotFiltered() throws Exception {
        MockHttpServletRequest r = rawReq("GET", USER_LABELS, null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, res, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("라벨_본문을_받지_않는_POST_엔드포인트는_영향받지_않는다")
    void otherPostEndpointsUnaffected() throws Exception {
        // 업로드(멀티파트·TUS)처럼 본문이 큰 것이 정상인 경로가 상한에 걸리면 회귀다.
        List<String> untouched = List.of(
                "/v1/portal/uploads/images",
                "/v1/portal/uploads/videos",
                "/v1/portal/tus/uploads");

        for (String path : untouched) {
            MockHttpServletRequest r = rawReq("POST", path, oversized());
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(r, res, chain);

            assertThat(chain.getRequest()).as("필터 미적용 경로: %s", path).isNotNull();
            assertThat(res.getStatus()).as("필터 미적용 경로: %s", path).isEqualTo(200);
        }
    }

    // ─── 3차 QA (CWE-436 잔여) — servlet path prefix 정합 ───

    /**
     * {@code spring.mvc.servlet.path=/api2} 형상에서 DispatcherServlet 은 {@code /api2} 하위에 매핑되고
     * MVC 는 <b>servletPathPrefix 를 제외한</b> 경로({@code /v1/portal/...})로 라우팅한다. 필터가
     * contextPath 만 반영하는 자체 파싱({@code RequestPath.parse(uri, ctx)})을 쓰면 필터가 보는 경로는
     * {@code /api2/v1/...} 이라 패턴에 걸리지 않고 <b>MVC 만 라우팅</b>하는 fail-open 이 된다 —
     * 원 결함(CWE-436)과 동일 클래스의 재발이다.
     */
    @Test
    @DisplayName("servlet_path_prefix_설정_환경에서도_MVC와_동일_경로로_판정한다")
    void servletPathPrefixAware() throws Exception {
        MockHttpServletRequest labelPut = rawReq("PUT", "/api2" + LABEL_PUT, oversized());
        labelPut.setServletPath("/api2");
        MockHttpServletResponse labelRes = new MockHttpServletResponse();
        MockFilterChain labelChain = new MockFilterChain();
        filter.doFilter(labelPut, labelRes, labelChain);
        assertThat(labelRes.getStatus()).isEqualTo(413);
        assertThat(labelChain.getRequest()).isNull();

        MockHttpServletRequest userLabelPost = rawReq("POST", "/api2" + USER_LABELS, oversized());
        userLabelPost.setServletPath("/api2");
        MockHttpServletResponse userRes = new MockHttpServletResponse();
        MockFilterChain userChain = new MockFilterChain();
        filter.doFilter(userLabelPost, userRes, userChain);
        assertThat(userRes.getStatus()).isEqualTo(413);
        assertThat(userChain.getRequest()).isNull();
    }

    // ─── 파리티 — "필터가 보는 경로"와 "MVC 가 라우팅하는 경로"가 어긋나지 않는다 ───

    /**
     * 인코딩 변형 경로가 <b>컨트롤러에 도달하면서 상한만 빠져나가는</b> 조합이 없음을 실제 DispatcherServlet
     * 라우팅으로 확인한다(CWE-436 재발 방어).
     *
     * <p>본문은 <b>상한 초과 + 파싱 가능</b>({@code "[]"} + 공백 패딩)이라 3분기로 명확히 갈린다:
     * <ul>
     *   <li>413 — 필터가 파싱 전에 차단(정상)</li>
     *   <li>404 — 핸들러 미매칭(컨트롤러 미도달 → 우회 아님)</li>
     *   <li><b>200 — 필터 스킵 + 컨트롤러 실행 = 우회(FAIL)</b></li>
     * </ul>
     *
     * <p>이중 인코딩({@code %256Cabels})은 <b>단 한 번만 디코딩</b>하는 Spring 규약상 {@code %6Cabels}
     * 라는 리터럴 세그먼트가 되어 <b>컨트롤러도 라우팅하지 않는다</b>(404). 즉 자체 디코딩 루프를
     * 재구현하지 않는 한 자동으로 방어된다 — 이 테스트가 그 성질을 고정한다.
     */
    @Test
    @DisplayName("이중인코딩_포함_인코딩변형_경로는_컨트롤러에_도달하는_한_반드시_상한이_적용된다_우회불가")
    void encodedVariantsNeverBypassTheCap() {
        MockMvc mvc = standaloneMvc();
        byte[] body = oversizedJsonArray();

        List<String> variants = List.of(
                "/v1/portal/uploads/frames/500/labels",        // 정상 경로
                "/v1/portal/uploads/frames/500/%6Cabels",      // 단순 퍼센트 인코딩 (실측 우회 벡터)
                "/v1/portal/uploads/frames/500/%6cabels",      // 소문자 hex
                "/v1/portal/uploads/frames/500/label%73",      // 마지막 글자 인코딩
                "/v1/portal/uploads/frames/500/%6C%61%62%65%6C%73", // 전 글자 인코딩
                "/v1/portal/uploads/frames/%35%30%30/labels",  // 경로변수 인코딩
                "/v1/portal/uploads/frames/500/labels;v=1",    // matrix parameter
                "/v1/portal/uploads/frames/500/%256Cabels",    // 이중 인코딩
                "/v1/portal/uploads/frames/500/labels/",       // trailing slash
                "/v1/portal/uploads/frames/500//labels");      // 중복 슬래시

        for (String uri : variants) {
            assertThat(statusOf(mvc, uri, body))
                    .as("우회 없음(413 차단 또는 404 미라우팅): %s", uri)
                    .isIn(413, 404);
        }

        // 이중 인코딩은 Spring 이 <b>한 번만</b> 디코딩하므로 리터럴 "%6Cabels" 세그먼트가 되어
        // 컨트롤러도 라우팅하지 않는다 — 필터와 MVC 가 함께 무시하므로 우회가 성립하지 않는다.
        assertThat(statusOf(mvc, "/v1/portal/uploads/frames/500/%256Cabels", body)).isEqualTo(404);

        // 실제 라우팅되는(=우회 시 200 이 나오는) 변형들은 반드시 413 이어야 한다.
        List<String> routable = List.of(
                "/v1/portal/uploads/frames/500/labels",
                "/v1/portal/uploads/frames/500/%6Cabels",
                "/v1/portal/uploads/frames/500/%6cabels",
                "/v1/portal/uploads/frames/500/label%73",
                "/v1/portal/uploads/frames/500/%6C%61%62%65%6C%73",
                "/v1/portal/uploads/frames/%35%30%30/labels",
                "/v1/portal/uploads/frames/500/labels;v=1",
                "/v1/portal/uploads/frames/500/labels/");
        for (String uri : routable) {
            assertThat(statusOf(mvc, uri, body)).as("상한 적용: %s", uri).isEqualTo(413);
        }
    }

    /** 상한을 넘되 <b>파싱 가능한</b> JSON 본문 — 필터를 통과하면 200 이 나므로 우회가 즉시 드러난다. */
    private static byte[] oversizedJsonArray() {
        return ("[]" + " ".repeat((int) MAX)).getBytes(StandardCharsets.UTF_8);
    }

    private MockMvc standaloneMvc() {
        PortalUploadLabelService service = mock(PortalUploadLabelService.class);
        when(service.replaceLabels(anyLong(), anyString(), any())).thenReturn(List.of());
        return MockMvcBuilders.standaloneSetup(new PortalUploadLabelController(service))
                .addFilters(filter)
                .setCustomArgumentResolvers(new TokenClaimsResolver())
                .build();
    }

    /**
     * @return HTTP 상태. 핸들러 미매칭 등으로 예외가 나면 "컨트롤러 미도달"이므로 404 로 간주한다
     *         (판정 대상은 <b>우회 성공(200)</b> 여부뿐이라 안전한 축약이다).
     */
    private static int statusOf(MockMvc mvc, String rawUri, byte[] body) {
        try {
            return mvc.perform(put(URI.create(rawUri))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 404;
        }
    }

    /** standalone MockMvc 에는 Security 가 없으므로 {@code @AuthenticationPrincipal} 을 직접 채운다. */
    private static final class TokenClaimsResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return TokenClaims.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return new TokenClaims("500", Role.PORTAL_USER, Channel.PORTAL,
                    Instant.now().plusSeconds(3600));
        }
    }
}
