package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.common.config.DeployFlavorResolver;
import kr.co.cudo.authoring.user.service.AutoWorkerRegistrar;
import kr.co.cudo.authoring.user.service.ControlUserProvisioner;
import kr.co.cudo.authoring.user.service.LastLoginRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_NAME_ATTR = "kr.co.cudo.authoring.auth.name";

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 포털 채널 전용 인계 헤더 (@design INT-013).
     *
     * <p>포털은 저작도구 프론트를 자기 화면 안에서 실행하는 임베딩이라 같은 출처 브라우저 저장소로
     * 토큰을 넘겨받는 전제가 성립하지 않는다. Host 가 주입한 인계 창구에서 얻은 access token 을
     * <b>이 전용 헤더</b>로 싣는다. 계약이 <b>Bearer 스킴 미사용</b>이므로 여기서 접두를 요구하지
     * 않으며, 접두가 붙어 오면 그 문자열 전체가 토큰으로 취급돼 파싱에서 거부된다(관대 처리 없음).
     */
    static final String PORTAL_TOKEN_HEADER = "x-access-token";


    private final JwtKeyResolver keyResolver;
    private final JwtIssuerValidator issuerValidator;
    private final UserRoleResolver userRoleResolver;
    private final LastLoginRecorder lastLoginRecorder;
    /** 진입 시 작업자 자동 등록기 — 역할이 없는 INTERNAL 사용자에게만 돈다(@design AC-1016). */
    private final AutoWorkerRegistrar autoWorkerRegistrar;
    /**
     * 관제 인계 미등록 진입자 로컬 식별 레코드 발급기 — INTERNAL 채널 + 비숫자 sub + userId 조회
     * 0건일 때만 돈다(@design ADR-063 · UC-041 · AC-1016).
     */
    private final ControlUserProvisioner controlUserProvisioner;
    /**
     * 채널 판정기 — <b>배포 향</b>이 채널을 확정한다(@design ADR-012).
     *
     * <p>이 필터는 판정하지 않고 <b>결과만 받는다</b>. 판정을 여기에도 두면 같은 사실을 두 곳에서
     * 정하게 되고, 둘이 어긋나는 순간 어느 쪽이 참인지 가릴 수단이 없다.
     */
    private final DeployFlavorResolver deployFlavorResolver;

    /**
     * <b>시험 전용 생성자 — 관제 향(종전 동작)으로 고정된다.</b>
     *
     * <p>배포 향 인자가 없던 시절의 형태를 남겨 둔 것이라 채널 판정이 {@code CONTROL} 로 굳는다.
     * ⚠ <b>운영 배선에 쓰지 말 것</b> — 이 생성자로 조립하면 포털 향 배포본에서도 인계 토큰이
     * 관제 채널로 해석되어 이 클래스가 고치려는 결함이 그대로 되살아난다. 운영 배선은
     * {@link SecurityConfig} 한 곳이며 아래 7-인자 생성자를 쓴다.
     */
    public JwtAuthenticationFilter(JwtKeyResolver keyResolver,
                                   JwtIssuerValidator issuerValidator,
                                   UserRoleResolver userRoleResolver,
                                   LastLoginRecorder lastLoginRecorder,
                                   AutoWorkerRegistrar autoWorkerRegistrar,
                                   ControlUserProvisioner controlUserProvisioner) {
        this(keyResolver, issuerValidator, userRoleResolver, lastLoginRecorder,
                autoWorkerRegistrar, controlUserProvisioner, DeployFlavorResolver.ofDefault());
    }

    public JwtAuthenticationFilter(JwtKeyResolver keyResolver,
                                   JwtIssuerValidator issuerValidator,
                                   UserRoleResolver userRoleResolver,
                                   LastLoginRecorder lastLoginRecorder,
                                   AutoWorkerRegistrar autoWorkerRegistrar,
                                   ControlUserProvisioner controlUserProvisioner,
                                   DeployFlavorResolver deployFlavorResolver) {
        if (keyResolver == null) {
            throw new IllegalArgumentException("keyResolver must not be null (fail-closed)");
        }
        if (issuerValidator == null) {
            throw new IllegalArgumentException("issuerValidator must not be null (fail-closed)");
        }
        if (userRoleResolver == null) {
            throw new IllegalArgumentException("userRoleResolver must not be null (fail-closed)");
        }
        // 기록기 부재는 <배선 버그>다 — 인증은 계속 되지만 최종로그인일시가 조용히 영원히 비어
        // 사용자 관리 화면이 다시 "데이터가 없는 컬럼" 으로 돌아간다. 기록 <실패> 시의 fail-open 은
        // 기록기 내부 책임이고, 기록기 <부재> 는 여기서 즉시 드러낸다.
        if (lastLoginRecorder == null) {
            throw new IllegalArgumentException("lastLoginRecorder must not be null (wiring bug)");
        }
        // 등록기 부재도 <배선 버그>다 — 인증은 계속 되지만 역할 없는 진입자가 영영 등록되지 않아
        // 배정 목록에 뜨지 않는다. 등록 <실패> 시의 fail-open 은 등록기 내부 책임이고, 등록기
        // <부재> 는 여기서 즉시 드러낸다.
        if (autoWorkerRegistrar == null) {
            throw new IllegalArgumentException("autoWorkerRegistrar must not be null (wiring bug)");
        }
        // 프로비저너 부재도 <배선 버그>다 — 인증은 계속 되지만 미등록 관제 진입자가 영영 userNo 를
        // 발급받지 못해 관제 채널이 통째로 무권한으로 잠긴다(부트스트랩 불가). 발급 <실패> 시의
        // fail-closed 는 프로비저너 내부 책임이고, 프로비저너 <부재> 는 여기서 즉시 드러낸다.
        if (controlUserProvisioner == null) {
            throw new IllegalArgumentException("controlUserProvisioner must not be null (wiring bug)");
        }
        // 채널 판정기 부재는 <배선 버그>다 — 없으면 채널을 정할 수 없고, 기본값으로 때우면
        // 포털 향 배포본이 조용히 관제 채널로 동작한다(이 클래스가 고치려는 바로 그 결함).
        if (deployFlavorResolver == null) {
            throw new IllegalArgumentException("deployFlavorResolver must not be null (wiring bug)");
        }
        this.keyResolver = keyResolver;
        this.issuerValidator = issuerValidator;
        this.userRoleResolver = userRoleResolver;
        this.lastLoginRecorder = lastLoginRecorder;
        this.autoWorkerRegistrar = autoWorkerRegistrar;
        this.controlUserProvisioner = controlUserProvisioner;
        this.deployFlavorResolver = deployFlavorResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TokenIngress ingress = extractToken(request);
        if (ingress.conflict()) {
            // ★ 두 인계 자리에 <서로 다른> 토큰이 실렸다 — 어느 쪽 신원으로 동작할지 서버가 고를 수
            //   없는 모호한 상태다. 조용히 한쪽을 채택하면 <의도치 않은 신원>으로 요청이 처리되므로
            //   거부한다(fail-closed). 서명·만료가 유효한 토큰이 섞여 있어도 채택하지 않는다.
            //   * 인지·수용한 대가 — 경유 장비가 Authorization 을 자동으로 덧붙이는 형상에서는 포털
            //     요청이 전량 거부된다. 다만 <즉시 드러나며> 잘못된 신원으로 도는 것보다 낫다.
            //   * 거부 방식은 다른 실패 경로(서명 불일치·issuer 불일치·exp 부재)와 동일하다 —
            //     컨텍스트를 비우고 체인을 이어, 보호 엔드포인트는 진입점이 401 을 표준 응답 형식으로
            //     낸다. 필터가 직접 응답을 쓰면 그 형식이 이 경로에서만 갈린다.
            log.warn("[Auth] rejected conflicting token headers");
            SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }
        String token = ingress.token();
        if (token != null) {
            try {
                Jws<Claims> jws = Jwts.parser()
                        .verifyWith(keyResolver.resolve())
                        .build()
                        .parseSignedClaims(token);
                Claims body = jws.getPayload();

                if (!issuerValidator.isAllowed(body.getIssuer())) {
                    log.warn("[Auth] rejected unknown issuer iss={}", body.getIssuer());
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }

                // A-ISSUE-01 (HIGH, CWE-613 Insufficient Session Expiration) — exp 클레임 필수화.
                //   저작도구는 토큰을 발급하지 않고 폐기(revocation/blacklist) 수단도 없으므로 베어러 토큰의
                //   수명 상한은 오직 exp 로만 강제된다. jjwt 는 exp 가 "없으면" 만료 검사를 통째로 건너뛰므로
                //   (과거 exp 는 정상 거부되지만 exp 부재는 통과) 클레임 부재 토큰이 영구 유효한 자격증명이
                //   된다. 발급 주체가 외부(관제/포털)라 "항상 exp 를 넣는다"는 계약을 검증 없이 신뢰할 수
                //   없으므로, issuer 게이트와 동일하게 fail-closed 로 거부한다.
                //   ※ Jwts.parser().require("exp", ...) 는 값 고정 비교라 부적합해 명시 분기로 처리한다.
                if (body.getExpiration() == null) {
                    log.warn("[Auth] rejected token without exp claim iss={}", body.getIssuer());
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }

                // [design: ADR-063 ⑦] [design: AC-1017] [design: AC-1016]
                // ★ 토큰 종류가 refresh 면 인증에 쓰지 않는다 — 채널·인계 자리(Bearer/포털 헤더) 무관.
                //   관제 refresh 토큰(7일)은 access 와 <같은 비밀키>로 서명되고 같은 출처 저장소(tokenInfo)에
                //   놓인다. 이 판정이 없으면 서명·만료·발급처만 보는 이 필터를 그대로 통과해 7일짜리 API
                //   자격증명이 된다. refresh 토큰의 정당한 쓰임은 갱신 중계 창구의 <요청 본문>뿐이다.
                //   * 거부 방식은 다른 검증 실패와 같다 — 컨텍스트를 비우고 체인을 이어 보호 창구는 401.
                //   * 신원 해석·자동 등록·접속 기록보다 <앞>에 둔다 — 거부될 토큰이 부수 쓰기를 남기면 안 된다.
                //   ⚠ 「access 만 허용」으로 좁히지 말 것(ADR-063 기각안) — 종류 클레임이 없는 토큰
                //     (개발 로그인·포털 인계)이 전부 막힌다. 문자열이 아닌 종류 값도 종전대로 수용한다.
                if (isRefreshTokenType(body)) {
                    log.warn("[Auth] rejected refresh-type token — not an access credential");
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }

                // 이름 클레임은 인가 이전에 읽는다 — 자동 등록이 사용자 마스터의 표시 이름을
                //   채우는 데 쓰기 때문이다(그 창구가 부트스트랩 전용으로 닫히면서 이름을 채우는
                //   유일한 경로가 여기로 옮겨왔다). 요청 속성 노출은 종전 위치·의미 그대로다.
                //   ★ 관제 인계 토큰은 이름을 name 이 아니라 userNm 에 넣는다(@design ADR-063) —
                //     name 우선, 없으면 userNm 으로 폴백한다. 이름은 표시용이며 인가에 쓰지 않는다.
                String name = body.get("name", String.class);
                if (name == null) {
                    name = body.get("userNm", String.class);
                }

                // ★ 채널 판정 — <배포 향>이 정한다 (@design ADR-012 · INT-013 · AC-1103).
                //   포털 향 배포본은 이 클레임을 <읽지 않고> PORTAL 로 확정한다(기본값 전환이
                //   아니라 강제). 관제 향은 종전 그대로 — 값이 있으면 그 값, 없으면 INTERNAL.
                //   판정 본체는 DeployFlavorResolver 가 소유한다(여기서 다시 판정하지 않는다).
                String channelStr = body.get("channel", String.class);
                Channel channel = deployFlavorResolver.resolveChannel(channelStr);

                // 역할 분리 Phase 3 — 인가 역할 출처를 JWT role 클레임 → 저작도구 소유 LS_USER_ROLE 로 전환.
                //   * INTERNAL: sub(userNo)로 LS 조회(UserRoleResolver, 캐시+fail-closed). 비숫자/누락 sub 는
                //     filter 가 null 로 선처리(캐시키 일관성: resolve/evict 모두 userNo Long 키).
                //   * PORTAL: LS 미조회, role=PORTAL_USER 고정. 채널 격리는 SecurityConfig 가 강제.
                // JWT 의 role 클레임(관제 역할 LEARN_MANAGER 등)은 더 이상 인가에 사용하지 않는다.
                // 주체 식별자(sub) — 기본은 토큰 원문이다. INTERNAL 채널에서 관제 인계
                //   토큰(비숫자 sub)을 userId 로 유일 해석한 경우에만 아래에서 해석된 userNo 로
                //   정규화한다(@design ADR-063 · UC-041 · AC-1016). 그 외(숫자 sub·PORTAL·해석
                //   실패)는 원문을 그대로 둔다 — 회귀 0 이 최우선이며 fail-closed 를 풀지 않는다.
                String subject = body.getSubject();
                Role role;
                if (channel == Channel.INTERNAL) {
                    // 식별 — 숫자 sub(USER_NO)를 먼저 시도한다(내부·포털 기존 경로, 무변경).
                    //   비숫자 sub(관제 인계: sub="admin")면 userId 클레임으로 LS_ACNT_USER.USER_ID 를
                    //   조회한다(@design ADR-063 · UC-041). 다중/0건 매칭이면 null(fail-closed, CWE-639).
                    //   ★ 숫자 sub 경로는 그대로다 — resolveUserNoByUserId 는 비숫자 sub 일 때만 탄다.
                    String userIdClaim = body.get("userId", String.class);
                    Long numericSub = parseUserNo(body.getSubject());
                    Long userNo = numericSub != null
                            ? numericSub
                            : userRoleResolver.resolveUserNoByUserId(userIdClaim);
                    // 미등록 관제 진입자 로컬 식별 레코드 자동발급 (@design ADR-063 결정⑤ ·
                    //   UC-041 step5 · AC-1016) — 비숫자 sub(관제 인계)를 userId 로 조회했는데 0건이면
                    //   진입 순간 우리 userNo 를 시퀀스로 발급·원자 등록한다(role 미부여). 그래야 아래
                    //   principal 정규화가 발급 userNo 로 이어져 부트스트랩(role-claim)이 성립한다.
                    //   * numericSub != null(숫자 sub) 이면 진입하지 않는다 — 기존 경로 무변경.
                    //   * PORTAL 채널은 이 분기 자체가 INTERNAL 전용이라 닿지 않는다.
                    //   * 다중 매칭(userNo==null 이지만 0건 아님)은 프로비저너 내부에서 발급 없이
                    //     닫는다(AC-1017, CWE-639). 발급 실패도 프로비저너가 null 을 돌려 fail-closed 다.
                    if (userNo == null && numericSub == null) {
                        userNo = controlUserProvisioner.provision(userIdClaim, name);
                    }
                    role = userNo == null ? null : userRoleResolver.resolve(userNo);
                    // 진입 시 작업자 자동 등록 (ADR-055 · @design AC-1016) — 역할 자가부여 창구가
                    //   관리자 부트스트랩 전용으로 좁혀지면서 일반 사용자가 등록될 통로가 사라졌다.
                    //   그래서 <진입 자체>가 등록 시점이 된다.
                    //   * 조건부다 — 역할이 이미 해석되면 호출조차 하지 않으므로 사용자당 사실상 1회다
                    //     (매 요청 쓰기가 아니다).
                    //   * 이미 부여된 역할은 덮지 않는다 — 관리자가 지정한 역할이 다음 요청에
                    //     되돌려지면 안 된다. 덮지 않아 삽입이 일어나지 않은 경우는 null 이 돌아와
                    //     무권한으로 흐른다(fail-closed).
                    //   * 등록 실패는 요청을 죽이지 않는다(fail-open, 등록기 내부에서 흡수).
                    //   ★ 숫자 sub 전용이다 (@design AC-1016) — userId 조회 경로(관제)는 대상이 아니다.
                    //     매칭이 0건이면 삽입할 userNo 자체가 없어 지어낼 수 없고, 신규 USER_NO 를
                    //     만드는 것은 숫자 sub 뿐이다. 그래서 numericSub 가 있을 때만 돈다.
                    //   ★ PORTAL 채널은 대상이 아니다 — 이 분기 자체가 INTERNAL 전용이다.
                    if (role == null && numericSub != null) {
                        role = autoWorkerRegistrar.registerAsWorker(numericSub, name);
                    }
                    // 최종로그인일시 기록 (V12 · @design SCREEN-024) — 저작도구엔 독립 로그인 UI 가 없어
                    //   "로그인" 이벤트가 존재하지 않는다. 관측 가능한 가장 가까운 사실이 <검증을 통과한
                    //   요청>이라 여기가 기록 지점이다.
                    //   * 매 요청 UPDATE 가 아니다 — 기록기가 넘기는 throttle 창을 DB 조건절이 판정한다.
                    //   * 기록 실패는 요청을 죽이지 않는다(fail-open, 기록기 내부에서 흡수).
                    //   * 역할 해석 성공 여부와 무관하게 기록한다 — 역할 미배정(role=null)이어도
                    //     "그 사용자가 접속했다"는 사실은 참이며, 인가 결과에 따라 기록이 갈리면
                    //     휴면 계정 판단이 왜곡된다.
                    //   ★ PORTAL 채널은 기록하지 않는다 — 포털 토큰의 sub 는 문자열 식별자로 쓰이고
                    //     이 마스터의 USER_NO 와의 매핑이 확인되지 않았다. 추측으로 조인하면 남의 행에
                    //     접속 기록을 쓰게 된다(CWE-639).
                    lastLoginRecorder.record(userNo);
                    // ★ principal 신원 정규화 (@design ADR-063 결정④ · UC-041 step4 · AC-1016) —
                    //   비숫자 sub(관제 인계)를 userId 클레임으로 LS_ACNT_USER.USER_ID 에서 <유일>
                    //   해석한 경우에만 principal 의 sub 를 그 userNo(문자열)로 치환한다. 그러면
                    //   관제 토큰이 내부·포털 토큰(sub 가 이미 숫자 userNo)과 동형이 되어, 다운스트림의
                    //   숫자 파싱 경로(parseUserNo)와 문자열 직접사용 경로(감사 식별자·작업 잠금 키·
                    //   소유자 비교)가 모두 실제 userNo 를 일관되게 본다(CWE-807/778 해소).
                    //   * 숫자 sub 경로(numericSub != null)는 치환하지 않는다 — 원문을 보존해
                    //     zero-padded("00123") 같은 값이 String.valueOf(123)="123" 으로 바뀌는
                    //     회귀를 원천 차단한다(내부·포털 동작 무변경, 회귀 0).
                    //   * 해석 실패(userNo == null, 다중/0건 매칭)면 raw 를 유지한다 → 다운스트림
                    //     parseUserNo=null → 기존 fail-closed(무권한) 그대로. 정규화가 fail-closed 를
                    //     풀지 않는다.
                    if (numericSub == null && userNo != null) {
                        subject = String.valueOf(userNo);
                    }
                } else {
                    role = Role.PORTAL_USER;
                }
                // exp 는 위 게이트에서 non-null 이 보장된다(A-ISSUE-01).
                TokenClaims claims = new TokenClaims(
                        subject,
                        role,
                        channel,
                        Instant.ofEpochMilli(body.getExpiration().getTime())
                );

                // R5-1: ROLE_* + CHANNEL_* 권한 부여 → SecurityConfig 가 채널 격리를 인가 단계에서 강제.
                // channel 클레임 없는 토큰은 <관제 향에서> INTERNAL 로 기본값 처리된다
                //   (fail-closed: 내부 사용자 호환 — 관제 인계 토큰이 원래 channel 을 싣지 않는다).
                //   ⚠ 그 근거는 <조건부>다 (@design ADR-012) — 포털 향에서는 같은 처리가 오히려
                //     fail-open 이라(포털 사용자가 관제 사용자로 해석된다) 위에서 PORTAL 로 강제한다.
                // 불변(LOW 2-1): JWT 발급 경로는 ROLE_*/CHANNEL_* authority 만 부여한다. 서명 스트림 전용
                // STREAM_SIGNED(StreamSignatureFilter.AUTHORITY_STREAM_SIGNED) 권한은 여기서 절대 부여하지
                // 않으므로, 사용자는 어떤 토큰(sub 위장 포함)으로도 /stream 인가를 획득할 수 없다(CWE-863).
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (role != null) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
                }
                authorities.add(new SimpleGrantedAuthority("CHANNEL_" + channel.name()));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(claims, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);

                if (name != null) {
                    request.setAttribute(AUTH_NAME_ATTR, name);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // ★ WARN 이다 — DEBUG 로 두면 <기본 설정의 운영 로그에 한 줄도 안 남는다>.
                //   2026-09-07 현장에서 인계 로그인이 안 되는데 서버 로그가 조용해, 원인을
                //   가리는 데만 한나절이 들었다. 실패는 보이는 것이 안전한 쪽이다.
                // ★ alg 와 키 길이를 함께 남긴다 — 이 둘이 가장 흔한 원인이고,
                //   토큰 본문·시크릿은 남기지 않는다(CWE-532).
                log.warn("[Auth] jwt validation failed alg={} keyBytes={} message={}",
                        peekAlg(token), keyResolver.resolve().getEncoded().length, e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 토큰 종류 클레임이 {@code refresh}(대소문자·앞뒤 공백 무시)인가.
     *
     * <p>★ 클레임을 {@code body.get(name, String.class)} 로 읽지 않는다 — 값이 문자열이 아니면 그 호출이
     * 예외를 던져 <b>종전에 수용되던 토큰까지 거부</b>된다(과잉 차단, AC-1016). 원시 값으로 읽어 문자열이고
     * refresh 일 때만 참이다.
     */
    static boolean isRefreshTokenType(Claims body) {
        Object type = body.get(TOKEN_TYPE_CLAIM);
        return type instanceof String s && REFRESH_TOKEN_TYPE.equalsIgnoreCase(s.trim());
    }

    /** 토큰 종류 클레임명 — 관제는 access/refresh 를 이 클레임으로 가른다(@design ADR-063 ⑦). */
    static final String TOKEN_TYPE_CLAIM = "type";

    /** 인증에 쓰지 않는 토큰 종류 값. */
    static final String REFRESH_TOKEN_TYPE = "refresh";

    /**
     * 검증 실패 진단용으로 토큰 헤더의 {@code alg} 만 들여다본다.
     *
     * <p>★ 서명을 확인하지 않고 읽으므로 <b>신뢰하는 값이 아니다</b> — 로그에만 쓰고
     * 인가 판정에는 절대 쓰지 않는다. 그럼에도 남기는 이유는, 발급 주체가 외부(관제/포털)라
     * 서명 알고리즘이 환경마다 다를 수 있고 그것이 실패의 가장 흔한 원인이기 때문이다.
     * 예: HS512 토큰은 키가 64바이트 미만이면 값이 맞아도 검증이 실패한다.
     *
     * <p>파싱이 안 되면 {@code "?"} 를 돌린다 — 진단 문자열 하나 때문에 예외를 던지지 않는다.
     */
    private static String peekAlg(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot <= 0) {
                return "?";
            }
            String header = new String(
                    java.util.Base64.getUrlDecoder().decode(token.substring(0, dot)),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\"alg\"\\s*:\\s*\"([A-Za-z0-9]{1,10})\"").matcher(header);
            return m.find() ? m.group(1) : "?";
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /**
     * 두 인계 자리에서 얻은 토큰 — 또는 <b>모호(conflict)</b> 표식.
     *
     * @param token    검증 대상 토큰. 없으면 {@code null}
     * @param conflict 두 헤더가 서로 다른 토큰을 실었는가(모호 → 거부)
     */
    record TokenIngress(String token, boolean conflict) {
        static final TokenIngress ABSENT = new TokenIngress(null, false);
        static final TokenIngress CONFLICT = new TokenIngress(null, true);
    }

    /**
     * <b>토큰을 어디서 읽는가</b>의 단일 지점 (@design INT-013 · ADR-012).
     *
     * <p>인계 자리는 채널마다 갈린다 — 관제(내부)는 {@code Authorization: Bearer}, 포털은 전용 헤더
     * {@link #PORTAL_TOKEN_HEADER}. <b>검증은 갈리지 않는다</b> — 두 채널이 같은 발급 서버를 쓰므로
     * 어느 자리로 들어오든 아래 단일 경로에서 같은 서명·발급자·만료 검증을 탄다. 이 메서드가 하는
     * 일은 <b>"토큰 문자열을 고르는 것"뿐</b>이며 <b>헤더로 채널을 추정하지 않는다.</b>
     *
     * <p>⚠ <b>구 서술 폐기</b> — <i>"채널 판정은 여전히 JWT {@code channel} 클레임이 소유한다"</i>
     * 는 <b>더 이상 사실이 아니다.</b> {@code ADR-012} 「★ 채널 판정 축 — 배포 향이 정한다」
     * (2026-09-09 사용자 확정, 구속)가 판정 축을 <b>설치 시점 배포 향</b>으로 옮겼고, 판정 본체는
     * {@link kr.co.cudo.authoring.common.config.DeployFlavorResolver} 가 소유한다.
     * <b>「헤더로 추정하지 않는다」는 결론은 그대로 유효하니 함께 지우지 말 것</b> — 바뀐 것은
     * 판정의 <b>소유자</b>이지 「인계 자리로 채널을 정하지 않는다」는 규칙이 아니다.
     *
     * <p>정규화 규칙(갈릴 수 없게 한 지점에 둔다):
     * <ul>
     *   <li>{@code Authorization} 은 {@code Bearer } 접두가 있을 때만 인정한다(종전 동작 보존).</li>
     *   <li>{@link #PORTAL_TOKEN_HEADER} 는 접두를 요구하지 않는다 — 계약이 Bearer 미사용이다.</li>
     *   <li>다듬은 값이 빈 문자열이면 <b>「없음」과 같이</b> 다룬다(공백만 실린 헤더 = 미첨부).</li>
     *   <li>둘 다 있고 값이 <b>다르면</b> {@link TokenIngress#CONFLICT}, <b>같으면</b> 그 값을 쓴다.</li>
     * </ul>
     *
     * <p><b>★다듬기(trim)의 적용 범위 — 「비교·공백판정」에만 쓰고 파싱 값은 원문을 유지한다.</b>
     * {@code Authorization} 단독 경로가 파서에 넘기는 값은 <b>{@code Bearer } 를 벗긴 원문 그대로</b>다.
     * 여기서 다듬으면 {@code "Bearer  T"}(공백 2개) · {@code "Bearer T\t"} · {@code "Bearer T\r"} ·
     * C0 제어문자가 섞인 값 등 <b>종전에 거부되던 형태가 통과</b>한다({@link String#trim()} 은 U+0020
     * 이하 전 문자를 벗긴다). 권한이 오르지는 않지만(여전히 유효 서명·미만료·허용 발급자 필요) 자격증명
     * 문자열 해석이 RFC 9110 의 {@code Bearer SP token68} 과 갈라져, 앞단에서 {@code Authorization} 을
     * 파싱하는 구성요소(게이트웨이·WAF·토큰 차단목록·자격증명 기반 rate limit)와 <b>해석 차이
     * 표면</b>(CWE-436)이 생긴다. 인증 입구가 느는 변경이라 회귀 0 을 우선한다.
     *
     * <p>두 헤더가 <b>함께</b> 왔을 때만 다듬은 값으로 비교하고 그 값을 파서에 넘긴다 —
     * {@code "Bearer  X"} 와 {@code "X"} 를 원문끼리 비교하면 <b>같은 토큰인데 거짓 충돌</b>로
     * 거부되기 때문이다. 그 조합은 전용 헤더가 생기기 전에는 <b>존재하지 않던 새 조합</b>이라
     * 회귀가 아니다.
     *
     * <p><b>⚠ 사각 — 같은 헤더가 여러 번 실린 경우.</b> {@code getHeader} 는 <b>첫 값만</b> 돌려주므로
     * {@code x-access-token: A} + {@code x-access-token: B} 는 A 로 인증되고 <b>충돌 판정에 닿지
     * 않는다</b>({@code Authorization} 도 종전부터 동일). 권한 상승은 아니지만 「모호한 인증 상태를
     * 거부한다」는 선언이 <b>그 형태에서는 성립하지 않는다</b>. 현재 동작을 시험으로 고정해 두었으니
     * 다음 감사가 결함으로 재발견하지 않게 이 문단을 함께 읽을 것.
     */
    static TokenIngress extractToken(HttpServletRequest request) {
        // ★ 원문 유지 — 공백 판정에만 다듬은 값을 쓰고, 파서에는 벗긴 원문을 그대로 넘긴다.
        String bearerRaw = stripBearer(request.getHeader("Authorization"));
        String bearer = (bearerRaw == null || bearerRaw.trim().isEmpty()) ? null : bearerRaw;
        // 전용 헤더는 새 표면이라 종전 판정이 없다 — 다듬은 값을 그대로 쓴다(회귀 대상 아님).
        String portal = normalize(request.getHeader(PORTAL_TOKEN_HEADER));
        if (bearer != null && portal != null) {
            // 두 헤더 병존 — 「같은 토큰인가」는 다듬은 값으로 판정한다("Bearer  X" 와 "X" 는 같다).
            String bearerToken = bearer.trim();
            return bearerToken.equals(portal)
                    ? new TokenIngress(bearerToken, false)
                    : TokenIngress.CONFLICT;
        }
        if (bearer != null) {
            return new TokenIngress(bearer, false);
        }
        if (portal != null) {
            return new TokenIngress(portal, false);
        }
        return TokenIngress.ABSENT;
    }

    /** {@code Bearer } 접두가 있을 때만 뒤를 돌려준다. 접두가 없는 Authorization 은 종전대로 무시. */
    private static String stripBearer(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    /**
     * 공백만 실린 헤더는 「없음」과 같이 다룬다. <b>전용 헤더 전용</b>이다 —
     * {@code Authorization} 은 파싱 값 원문을 유지해야 해서 이 경로를 쓰지 않는다(위 javadoc 참조).
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * JWT subject(sub)를 userNo(Long)로 파싱한다. fail-closed — null/공백/비숫자면 null 반환해
     * 무권한으로 흐른다(NumberFormatException 미전파). resolve/evict 캐시키 일관성을 위해 파싱은
     * filter 에서 선처리한다.
     */
    private static Long parseUserNo(String sub) {
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sub.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
