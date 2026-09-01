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

    public JwtAuthenticationFilter(JwtKeyResolver keyResolver,
                                   JwtIssuerValidator issuerValidator,
                                   UserRoleResolver userRoleResolver,
                                   LastLoginRecorder lastLoginRecorder,
                                   AutoWorkerRegistrar autoWorkerRegistrar) {
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
        this.keyResolver = keyResolver;
        this.issuerValidator = issuerValidator;
        this.userRoleResolver = userRoleResolver;
        this.lastLoginRecorder = lastLoginRecorder;
        this.autoWorkerRegistrar = autoWorkerRegistrar;
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
                String name = body.get("name", String.class);

                String channelStr = body.get("channel", String.class);
                Channel channel = channelStr == null ? Channel.INTERNAL : Channel.valueOf(channelStr);

                // 역할 분리 Phase 3 — 인가 역할 출처를 JWT role 클레임 → 저작도구 소유 LS_USER_ROLE 로 전환.
                //   * INTERNAL: sub(userNo)로 LS 조회(UserRoleResolver, 캐시+fail-closed). 비숫자/누락 sub 는
                //     filter 가 null 로 선처리(캐시키 일관성: resolve/evict 모두 userNo Long 키).
                //   * PORTAL: LS 미조회, role=PORTAL_USER 고정. 채널 격리는 SecurityConfig 가 강제.
                // JWT 의 role 클레임(관제 역할 LEARN_MANAGER 등)은 더 이상 인가에 사용하지 않는다.
                Role role;
                if (channel == Channel.INTERNAL) {
                    Long userNo = parseUserNo(body.getSubject());
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
                    //   ★ PORTAL 채널은 대상이 아니다 — 이 분기 자체가 INTERNAL 전용이다.
                    if (role == null) {
                        role = autoWorkerRegistrar.registerAsWorker(userNo, name);
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
                } else {
                    role = Role.PORTAL_USER;
                }
                // exp 는 위 게이트에서 non-null 이 보장된다(A-ISSUE-01).
                TokenClaims claims = new TokenClaims(
                        body.getSubject(),
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
