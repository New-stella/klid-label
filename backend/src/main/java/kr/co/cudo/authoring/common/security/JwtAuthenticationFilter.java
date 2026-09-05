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

    public JwtAuthenticationFilter(JwtKeyResolver keyResolver,
                                   JwtIssuerValidator issuerValidator,
                                   UserRoleResolver userRoleResolver,
                                   LastLoginRecorder lastLoginRecorder,
                                   AutoWorkerRegistrar autoWorkerRegistrar,
                                   ControlUserProvisioner controlUserProvisioner) {
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
        this.keyResolver = keyResolver;
        this.issuerValidator = issuerValidator;
        this.userRoleResolver = userRoleResolver;
        this.lastLoginRecorder = lastLoginRecorder;
        this.autoWorkerRegistrar = autoWorkerRegistrar;
        this.controlUserProvisioner = controlUserProvisioner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Jws<Claims> jws = Jwts.parser()
                        .verifyWith(keyResolver.resolve())
                        .build()
                        .parseSignedClaims(token);
                Claims body = jws.getPayload();

                if (!issuerValidator.isAllowed(body.getIssuer())) {
                    log.debug("[Auth] rejected unknown issuer");
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
                    log.debug("[Auth] rejected token without exp claim");
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

                String channelStr = body.get("channel", String.class);
                Channel channel = channelStr == null ? Channel.INTERNAL : Channel.valueOf(channelStr);

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
                // channel 클레임 없는 토큰은 위에서 INTERNAL 로 기본값 처리(fail-closed: 내부 사용자 호환).
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
                log.debug("[Auth] jwt validation failed message={}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
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
