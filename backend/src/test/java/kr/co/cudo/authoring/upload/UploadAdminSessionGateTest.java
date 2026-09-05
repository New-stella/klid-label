package kr.co.cudo.authoring.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.service.AdminPasswordVerifier;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.exception.GlobalExceptionHandler;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionInterceptor;
import kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession;
import kr.co.cudo.authoring.dev.controller.DevAutolabelTestController;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import kr.co.cudo.authoring.portal.controller.PortalTusUploadController;
import kr.co.cudo.authoring.portal.controller.PortalUploadController;
import kr.co.cudo.authoring.portal.controller.PortalUploadLabelController;
import kr.co.cudo.authoring.upload.controller.TusUploadController;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.service.InternalUploadWiringGuard;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.crypto.SecretKey;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 업로드 창구의 <b>관리자 단기 유효창 요구 범위</b> 회귀 가드. [@design ADR-046 · API-152 · API-158]
 *
 * <h3>이 파일이 고정하는 것은 「걸린 곳」이 아니라 「걸리지 않은 곳」이다</h3>
 * <p>유효창을 넓게 걸면 <b>대용량 영상 업로드가 유효창 만료 시점에 끊긴다</b> — 큰 파일은 유효창
 * (기본 10분)보다 오래 걸리므로, 조각마다 요구하면 구조적으로 올릴 수 없게 된다. 그래서 이 가드의
 * 무게 중심은 {@code PATCH}·{@code DELETE}·{@code HEAD}·{@code OPTIONS} 가 <b>여전히 유효창 없이
 * 통과하는지</b>에 있다. 「POST 가 막힌다」만 확인하는 시험은 그 회귀를 한 건도 잡지 못한다.
 *
 * <h3>상태코드만 보지 않고 <b>핸들러 도달 여부</b>를 함께 단언한다</h3>
 * <p>인터셉터는 바디 역직렬화 이전에 돌므로, 거부와 「입력 검증 실패」가 상태코드만으로는 구분되지
 * 않는 구간이 생긴다. 그래서 통과 케이스는 <b>서비스가 실제로 호출됐는지</b>, 거부 케이스는
 * <b>호출되지 않았는지</b>를 함께 본다 — 상태코드 단언만 두면 게이트를 떼도 초록으로 남는다.
 *
 * <h3>포털 축은 대상이 아니다</h3>
 * <p>포털 이용자의 자기 자산 업로드는 관리 행위가 아니라 그 이용자 본인의 데이터라, 함께 묶으면 그
 * 기능이 통째로 죽는다. 반사(reflection)로 <b>표식 0건</b>을 고정한다.
 */
class UploadAdminSessionGateTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "upload-admin-session-gate-test-signing-key-0123456789".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    /** 유효창을 연 사람 = 업로드 세션 소유자. 두 판정이 같은 자격 주체(sub)를 기준으로 한다. */
    private static final String SUBJECT = "1001";
    /** 서명 키 파생 입력 — 형식만 맞으면 되는 시험 고정값이다(실제 자격이 아니다). */
    private static final String CREDENTIAL_HASH =
            "$2a$12$0123456789012345678901uPfPZaW6zVdlWLzWJ0m0Q5oBaGqfW8Bq";
    private static final UUID UPLOAD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TUS_VERSION = "1.0.0";

    private AdminSessionTokenService tokenService;
    private AdminSessionGate gate;

    @BeforeEach
    void setUp() {
        // 서명 키가 현재 관리자 자격에서 파생되므로 자격 조회를 고정값으로 스텁한다. 이 시험의 축은
        // 「어느 창구가 유효창을 요구하는가」이지 자격 저장소가 아니다.
        AdminPasswordVerifier passwordVerifier = mock(AdminPasswordVerifier.class);
        when(passwordVerifier.currentHash()).thenReturn(CREDENTIAL_HASH);

        tokenService = new AdminSessionTokenService(RESOLVER, passwordVerifier, 10);
        gate = new AdminSessionGate(tokenService);
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String validToken() {
        return tokenService.issue(SUBJECT, Instant.now()).token();
    }

    private static void authenticate() {
        // 유효창을 연 사람 = 관리자. 발급 창구의 판정이 역할 계층을 타지 않는 enum 동등 비교라
        // 검수자는 유효창 자체를 얻을 수 없다(AdminSessionService). 관리자는 계층으로 검수자
        // 전용 창구(PATCH·DELETE·HEAD·OPTIONS)도 그대로 통과한다.
        TokenClaims claims = new TokenClaims(SUBJECT, Role.ADMIN, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, List.of()));
    }

    private MockMvc mvcFor(Object... controllers) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return MockMvcBuilders.standaloneSetup(controllers)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setCustomArgumentResolvers(new TokenClaimsResolver())
                .addInterceptors(new AdminSessionInterceptor(gate))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ================================================================ TUS

    @Nested
    @DisplayName("TUS 업로드")
    class Tus {

        private TusUploadService service;
        private InternalUploadWiringGuard wiringGuard;
        private MockMvc mvc;

        @BeforeEach
        void setUpMvc() {
            service = mock(TusUploadService.class);
            wiringGuard = mock(InternalUploadWiringGuard.class);
            mvc = mvcFor(new TusUploadController(service, wiringGuard));
        }

        private MockHttpServletRequestBuilder createRequest() {
            return post("/v1/uploads")
                    .header("Tus-Resumable", TUS_VERSION)
                    .header("Upload-Length", "1024")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"fileName":"a.mp4","vmsClipId":"CLIP-1","cctvId":"CCTV-1","lclgvCd":"1168000000"}
                            """);
        }

        @Test
        @DisplayName("★세션_생성은_유효창이_없으면_403이고_핸들러에_닿지_않는다")
        void 세션_생성은_유효창이_없으면_403() throws Exception {
            mvc.perform(createRequest()).andExpect(status().isForbidden());

            verify(service, never()).createSession(anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("세션_생성은_유효창이_있으면_201로_통과한다")
        void 세션_생성은_유효창이_있으면_통과한다() throws Exception {
            when(service.createSession(eq(SUBJECT), anyLong(), any())).thenReturn(UPLOAD_ID);

            mvc.perform(createRequest().header(AdminSessionGate.HEADER, validToken()))
                    .andExpect(status().isCreated());

            verify(service).createSession(eq(SUBJECT), anyLong(), any());
        }

        @Test
        @DisplayName("★★청크_이어올리기는_유효창_없이도_통과한다 — 번지면 대용량 업로드가 만료 시점에 끊긴다")
        void 청크는_유효창_없이도_통과한다() throws Exception {
            when(service.appendChunk(eq(UPLOAD_ID), eq(SUBJECT), anyLong(), any(), anyLong()))
                    .thenReturn(new TusUploadService.TusPatchResult(4L, false, null));

            mvc.perform(patch("/v1/uploads/" + UPLOAD_ID)
                            .header("Tus-Resumable", TUS_VERSION)
                            .header("Upload-Offset", "0")
                            .header(HttpHeaders.CONTENT_LENGTH, "4")
                            .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                            .content(new byte[]{1, 2, 3, 4}))
                    .andExpect(status().isNoContent());

            verify(service).appendChunk(eq(UPLOAD_ID), eq(SUBJECT), anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("★취소는_유효창_없이도_통과한다 — 시작 창구에서 이미 확인한 전송을 되돌리는 것이다")
        void 취소는_유효창_없이도_통과한다() throws Exception {
            mvc.perform(delete("/v1/uploads/" + UPLOAD_ID).header("Tus-Resumable", TUS_VERSION))
                    .andExpect(status().isNoContent());

            verify(service).cancel(UPLOAD_ID, SUBJECT);
        }

        @Test
        @DisplayName("★진행_위치_조회는_유효창_없이도_통과한다 — 조회는 요구 대상이 아니다")
        void 진행_위치_조회는_유효창_없이도_통과한다() throws Exception {
            LsTusUpload session = mock(LsTusUpload.class);
            when(session.getUploadOffset()).thenReturn(512L);
            when(session.getUploadLength()).thenReturn(1024L);
            when(service.getForOwner(UPLOAD_ID, SUBJECT)).thenReturn(session);

            mvc.perform(head("/v1/uploads/" + UPLOAD_ID).header("Tus-Resumable", TUS_VERSION))
                    .andExpect(status().isNoContent());

            verify(service).getForOwner(UPLOAD_ID, SUBJECT);
        }

        @Test
        @DisplayName("★프로토콜_능력_광고는_유효창_없이도_통과한다 — 특정 전송에 속하지 않는 조회다")
        void 능력_광고는_유효창_없이도_통과한다() throws Exception {
            mvc.perform(options("/v1/uploads")).andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("★표식은_세션_생성_한_곳에만_있다 — 클래스 레벨로 올리면 전 창구가 함께 막힌다")
        void 표식은_세션_생성_한_곳에만_있다() {
            assertThat(classAxisAnnotated(TusUploadController.class))
                    .as("클래스 축에 붙이면 PATCH·DELETE·HEAD·OPTIONS 까지 함께 막힌다")
                    .isFalse();

            assertThat(annotatedMethodNames(TusUploadController.class))
                    .containsExactly("create");
        }

        /**
         * 이어 올리기·취소·진행 조회가 <b>토큰 sub 를 넘겨 소유자를 판정</b>한다는 사실이 유효창을
         * 시작 한 곳으로 좁힌 근거다. 그 시그니처가 사라지면 근거 자체가 무너지므로 함께 고정한다.
         */
        @Test
        @DisplayName("★이어올리기·취소·진행조회가_소유자_판정을_계속_받는다 — 좁힌 근거가 이것이다")
        void 소유자_판정_시그니처가_유지된다() throws Exception {
            assertThat(TusUploadService.class.getMethod("getForOwner", UUID.class, String.class))
                    .isNotNull();
            assertThat(TusUploadService.class.getMethod("cancel", UUID.class, String.class))
                    .isNotNull();
            assertThat(TusUploadService.class.getMethod("appendChunk", UUID.class, String.class,
                    long.class, java.io.InputStream.class, long.class)).isNotNull();
        }
    }

    // ================================================================ dev 업로드

    @Nested
    @DisplayName("dev 업로드")
    class DevUpload {

        private DevAutolabelTestService service;
        private MockMvc mvc;

        @BeforeEach
        void setUpMvc() {
            service = mock(DevAutolabelTestService.class);
            mvc = mvcFor(new DevAutolabelTestController(service));
        }

        private MockHttpServletRequestBuilder uploadRequest() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "a.mp4", "video/mp4", new byte[]{1, 2, 3});
            MockMultipartFile meta = new MockMultipartFile(
                    "meta", "meta", MediaType.APPLICATION_JSON_VALUE, """
                    {"vmsClipId":"CLIP-1","cctvId":"CCTV-1","eventTypeCd":"EV02000201",
                     "localGovCd":"1168000000","prvcTypeCd":"ANONY","capturedAt":"2024-05-01T12:00:00Z"}
                    """.getBytes(StandardCharsets.UTF_8));
            return multipart("/v1/dev/upload").file(file).file(meta);
        }

        @Test
        @DisplayName("★dev_업로드는_유효창이_없으면_403이고_핸들러에_닿지_않는다")
        void 유효창이_없으면_403() throws Exception {
            mvc.perform(uploadRequest()).andExpect(status().isForbidden());

            verify(service, never()).upload(any(), any());
        }

        @Test
        @DisplayName("dev_업로드는_유효창이_있으면_통과한다")
        void 유효창이_있으면_통과한다() throws Exception {
            when(service.upload(any(), any()))
                    .thenReturn(new AutolabelTestResponse(1L, "dev-upload/a.mp4", "PROCESSING", 0L));

            mvc.perform(uploadRequest().header(AdminSessionGate.HEADER, validToken()))
                    .andExpect(status().isOk());

            verify(service).upload(any(), any());
        }

        @Test
        @DisplayName("dev_업로드_표식은_업로드_한_곳에만_있다")
        void 표식은_업로드_한_곳에만_있다() {
            assertThat(annotatedMethodNames(DevAutolabelTestController.class))
                    .containsExactly("upload");
        }
    }

    // ================================================================ 인가 표기

    /**
     * 두 <b>시작 창구</b>의 역할 표기가 실효 게이트(관리자)와 같은지, 그리고 <b>이어 올리기 축은
     * 검수자 그대로</b>인지를 함께 고정한다. [@design API-152 · API-158 · AC-1087]
     *
     * <p>표기만 보는 시험이 아니다 — 실효 게이트는 유효창이고 그 발급이 관리자 전용이라 동작은
     * 이미 관리자였다. 여기서 막는 회귀는 <b>두 방향</b>이다: ①시작 창구가 다시 검수자로 내려가
     * 「검수자도 되는 창구」로 읽히는 것 ②이어 올리기 축까지 관리자로 함께 올라가는 것. ②는
     * 유효창을 넓히는 것과 같은 종류의 회귀다(그 자리는 소유자 판정이 지킨다).
     */
    @Nested
    @DisplayName("인가 표기")
    class Authorization {

        @Test
        @DisplayName("★두_시작_창구는_ADMIN_이다 — 유효창을 발급받을 수 있는 역할과 같아야 한다")
        void 시작_창구는_ADMIN() {
            assertThat(preAuthorizeOf(TusUploadController.class, "create"))
                    .as("TUS 세션 생성")
                    .isEqualTo("hasRole('ADMIN')");
            assertThat(preAuthorizeOf(DevAutolabelTestController.class, "upload"))
                    .as("dev 업로드")
                    .isEqualTo("hasRole('ADMIN')");
        }

        @Test
        @DisplayName("★★이어올리기·취소·진행조회·능력광고는_REVIEWER_그대로다 — 올리면 대용량 전송이 끊긴다")
        void 이어올리기_축은_REVIEWER_그대로() {
            for (String method : List.of("patch", "delete", "head", "options")) {
                assertThat(preAuthorizeOf(TusUploadController.class, method))
                        .as("%s 는 유효창을 요구하지 않는 축이라 검수자 그대로여야 한다", method)
                        .isEqualTo("hasRole('REVIEWER')");
            }
        }

        @Test
        @DisplayName("★이어올리기_축에는_유효창_표식이_없다 — 역할과 유효창은 같은 축을 지킨다")
        void 이어올리기_축에는_표식이_없다() {
            assertThat(annotatedMethodNames(TusUploadController.class))
                    .as("표식은 세션 생성 한 곳에만")
                    .containsExactly("create");
        }
    }

    /** 그 핸들러에 붙은 {@code @PreAuthorize} 표현식(없으면 null). */
    private static String preAuthorizeOf(Class<?> controller, String methodName) {
        for (Method m : controller.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
            return annotation == null ? null : annotation.value();
        }
        throw new IllegalArgumentException(controller.getSimpleName() + "#" + methodName + " 없음");
    }

    // ================================================================ 포털(대상 아님)

    @Test
    @DisplayName("★★포털_이용자_자기자산_업로드에는_표식이_없다 — 묶으면 그 기능이 통째로 죽는다")
    void 포털_업로드에는_표식이_없다() {
        List<Class<?>> portalControllers = List.of(
                PortalTusUploadController.class,
                PortalUploadController.class,
                PortalUploadLabelController.class);

        for (Class<?> controller : portalControllers) {
            assertThat(classAxisAnnotated(controller))
                    .as("%s 클래스에 관리자 유효창 표식이 붙었다", controller.getSimpleName())
                    .isFalse();
            assertThat(annotatedMethodNames(controller))
                    .as("%s 의 메서드에 관리자 유효창 표식이 붙었다", controller.getSimpleName())
                    .isEmpty();
        }
    }

    // ================================================================ 헬퍼

    /**
     * <b>인터셉터와 같은 술어</b>로 클래스 축을 본다 — {@code AdminSessionInterceptor} 가
     * {@code AnnotatedElementUtils.hasAnnotation(beanType, ...)} 로 판정하므로, 여기서
     * {@code isAnnotationPresent} 만 쓰면 <b>상위 타입·메타 애노테이션 경유</b>로 클래스 축에 붙는
     * 경우를 놓친다(술어가 갈리면 가드가 실제 판정을 지키지 못한다).
     */
    private static boolean classAxisAnnotated(Class<?> controller) {
        return AnnotatedElementUtils.hasAnnotation(controller, RequiresAdminSession.class);
    }

    /**
     * 그 컨트롤러에서 {@link RequiresAdminSession} 을 단 <b>메서드 이름 전부</b>.
     *
     * <p>클래스 축과 <b>같은 이유로</b> {@code AnnotatedElementUtils} 를 쓴다 — 인터셉터의 실제
     * 술어가 {@code HandlerMethod.hasMethodAnnotation} → {@code AnnotatedElementUtils.hasAnnotation}
     * 이라 <b>메타(합성) 애노테이션을 해석</b>하기 때문이다. 여기서 {@code isAnnotationPresent} 만
     * 쓰면, 표식을 감싼 합성 애노테이션을 {@code PATCH}·{@code DELETE} 에 붙였을 때
     * <b>인터셉터는 막는데 이 가드는 통과</b>시킨다(= 대용량 업로드가 만료 시점에 끊기는 최악 회귀를
     * 못 잡는다). 술어가 갈리면 가드는 실제 판정을 지키지 못한다.
     */
    private static List<String> annotatedMethodNames(Class<?> controller) {
        List<String> names = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(m, RequiresAdminSession.class)) {
                names.add(m.getName());
            }
        }
        names.sort(String::compareTo);
        return names;
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
            return new TokenClaims(SUBJECT, Role.ADMIN, Channel.INTERNAL,
                    Instant.now().plusSeconds(3600));
        }
    }
}
