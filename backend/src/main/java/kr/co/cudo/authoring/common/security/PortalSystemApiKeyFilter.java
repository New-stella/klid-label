package kr.co.cudo.authoring.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * 포털 서버간 <b>정리 삭제 트리거</b> 창구의 사전 공유 API 키 인증 필터
 * (@design INT-014 · API-244 · AC-1103).
 *
 * <h2>왜 토큰이 아니라 키인가</h2>
 * <p>포털 회신(2026-09-09)으로 이 창구의 인증이 <b>사전 공유 고정 문자열</b>로 확정됐다. 포털의
 * 기존 시스템간 연동 방식과 같은 축이며, 서명·만료·주체 클레임이 없다.
 *
 * <p>⚠ <b>같은 축이 두 번 뒤집혔다.</b> 연동점은 한때 *"별도 API 키 헤더는 쓰지 않는다"* 를
 * 규칙으로 갖고 있었고 그 근거가 포털 회신이었는데, 포털이 그 방식으로 되돌아왔다. 판단의 정본은
 * {@code INT-014} 이며 <b>여기서 그 계약을 다시 정하지 않는다.</b>
 *
 * <h2>★ 비교는 상수시간이어야 한다</h2>
 * <p>{@link String#equals}로 비교하면 <b>일치하는 접두 길이에 따라 걸리는 시간이 달라진다.</b>
 * 그 차이를 재면 키를 한 글자씩 좁혀 갈 수 있다. {@link MessageDigest#isEqual}은 길이가 같은
 * 입력에 대해 그 편차를 만들지 않는다.
 *
 * <p>⚠ 길이가 다르면 그 자체로 빨리 끝나므로 <b>길이는 숨겨지지 않는다.</b> 그것까지 감추려면
 * 해시를 비교해야 하는데, 사전 공유 키의 길이는 비밀이 아니므로 여기서는 그 대가를 치르지 않는다.
 *
 * <h2>★★ 키가 없으면 창구가 닫힌다 (fail-closed)</h2>
 * <p>설정이 비어 있으면 <b>어떤 요청도 통과하지 못한다.</b> 값이 없을 때 열리는 것이 아니라
 * <b>닫히는</b> 방향이라 안전하다 — 배포에서 키 주입을 잊었을 때 창구가 무인증으로 열려 있는 것보다
 * 아예 안 받는 편이 낫다.
 *
 * <p>⚠ <b>그 대가</b>: 포털이 호출을 시작했는데 우리 쪽 키가 비어 있으면 <b>전건 거부</b>가 되고,
 * 포털에는 「키가 틀렸다」로 보인다. 그 상태를 배포 로그에서 알아볼 수 있도록 기동 시 한 번 경고한다.
 *
 * <h2>이 필터가 하지 않는 것</h2>
 * <ul>
 *   <li><b>다른 경로를 건드리지 않는다.</b> 이 창구 경로에만 반응하고 그 밖에는 즉시 통과시킨다 —
 *       인증 축이 다른 창구(사용자 주체 토큰)의 판정에 끼어들면 안 된다.</li>
 *   <li><b>역할을 부여하지 않는다.</b> 부르는 쪽이 사람이 아니므로 저작도구 역할이 없다.
 *       전용 권한 하나만 주고, 그 권한을 요구하는 자리는 이 창구뿐이다.</li>
 *   <li><b>거부를 스스로 응답하지 않는다.</b> 인증 컨텍스트를 세우지 않고 넘기면 인가 계층이
 *       기존 진입점으로 거부하므로, 응답 본문 형식이 다른 거부와 갈리지 않는다.</li>
 * </ul>
 *
 * <p>⚠ <b>키 값을 로그에 남기지 않는다.</b> 인가 판정 키라 유출되면 위장의 재료가 된다.
 * 로그 마스킹이 {@code x-api-key} 를 이미 대상으로 갖고 있으나, 여기서는 애초에 싣지 않는다.
 */
@Slf4j
@Component
public class PortalSystemApiKeyFilter extends OncePerRequestFilter {

    /**
     * 이 필터가 반응하는 유일한 경로 (@design API-244).
     *
     * <p>정확 일치다 — 접두 일치로 넓히지 않는다. 같은 접두 아래 인증 축이 다른 창구가 생길 수 있고
     * (일일 저작 집계는 <b>인증 수단이 미확정</b>이다), 넓혀 두면 그 창구가 이 키로 열린다.
     */
    static final String PROTECTED_PATH = "/v1/portal-system/dataset-cleanups";

    /** 사전 공유 키를 싣는 요청 헤더 — 이름의 단일 진실원이다(리터럴을 새로 박지 말 것). */
    public static final String API_KEY_HEADER = "x-api-key";

    /**
     * 이 창구를 통과한 호출에만 부여하는 권한.
     *
     * <p>⚠ {@code ROLE_} 접두를 쓰지 않는다 — 역할이 아니라 <b>인증 경로</b>의 표식이고, 역할 계층에
     * 얹히면 다른 역할이 물려받는다. 부여하는 자리는 이 필터 하나뿐이라 사용자가 어떤 요청으로도
     * 스스로 얻을 수 없다.
     */
    public static final String AUTHORITY_PORTAL_SYSTEM_API = "PORTAL_SYSTEM_API";

    /** 설정된 키의 바이트. 비어 있으면 아무도 통과하지 못한다. */
    private final byte[] expectedKey;

    public PortalSystemApiKeyFilter(
            @Value("${authoring.portal.cleanup-api-key:}") String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        this.expectedKey = trimmed.isEmpty()
                ? null
                : trimmed.getBytes(StandardCharsets.UTF_8);
        if (this.expectedKey == null) {
            // WARN 이지 기동 실패가 아니다 — 이 값이 없어도 저작도구의 나머지는 정상이고, 닫히는 것은
            //   이 창구 하나뿐이다. 기동을 막으면 이 연동을 쓰지 않는 배포본(관제 채널)까지 함께 죽는다.
            log.warn("[PortalSystemApiKey] 정리 트리거 창구의 사전 공유 키가 설정되지 않았습니다 — "
                    + "그 창구가 닫힙니다(fail-closed). 포털 연동을 켜려면 "
                    + "authoring.portal.cleanup-api-key 를 설정해야 합니다.");
        } else {
            // ★ 키 <값>을 남기지 않는다. 운영자에게 필요한 것은 "설정됐다"는 사실이지 그 값이 아니다.
            log.info("[PortalSystemApiKey] 정리 트리거 창구의 사전 공유 키가 설정됐습니다.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 이 창구 밖에서는 아무 일도 하지 않는다 — 다른 인증 축의 판정에 끼어들지 않기 위해서다.
        //
        // ⚠ getServletPath() 를 쓰지 말 것 — 서블릿 매핑 방식에 따라 <빈 문자열>이 돌아와
        //   필터가 통째로 건너뛰어진다(그러면 이 창구가 무인증으로 열리는 것이 아니라, 권한을
        //   부여할 자가 없어 전건 거부된다 — 조용히 깨지는 방향이다).
        //   UrlPathHelper 는 컨텍스트 경로를 뺀 <애플리케이션 내부 경로>를 돌려주고 퍼센트 인코딩도
        //   푼다. 그래서 %64ataset-cleanups 같은 변형으로 이 판정을 비껴갈 수 없다.
        return !PROTECTED_PATH.equals(
                UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (matches(request.getHeader(API_KEY_HEADER))) {
            // 주체가 없다 — 사람이 아니라 시스템이 부르는 창구이므로 principal 자리에 이 창구의
            //   이름만 둔다. 사용자 식별자로 오인될 값을 넣지 않는다.
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    AUTHORITY_PORTAL_SYSTEM_API,
                    null,
                    List.of(new SimpleGrantedAuthority(AUTHORITY_PORTAL_SYSTEM_API)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else {
            // ★ 키가 아니면 <앞선 필터가 세운 인증을 지운다>. 이 창구는 키로만 가르기 때문이다.
            //   지우지 않으면 포털 사용자 토큰을 함께 실었을 때 「인증은 됐고 권한이 없다」가 되어
            //   403 이 나가고, 키가 틀렸다는 사실이 응답에서 사라진다. 계약은 그 경우를 401
            //   (키가 없거나 값이 다르다)로 규정한다.
            //   ⚠ 이 경로에서만 지운다 — shouldNotFilter 가 다른 경로를 이미 걸러 낸다.
            SecurityContextHolder.clearContext();
        }
        // 거부를 여기서 직접 응답하지 않는다. 인가 계층이 기존 진입점으로 거부하므로 응답 형식이
        //   다른 거부와 갈리지 않는다.
        chain.doFilter(request, response);
    }

    /**
     * 사전 공유 키가 일치하는가.
     *
     * <p>★ 비교는 {@link MessageDigest#isEqual} 이다 — 일치하는 접두 길이에 따라 걸리는 시간이
     * 달라지면 그 차이로 키를 한 글자씩 좁혀 갈 수 있다.
     *
     * @param presented 요청이 실어 온 값. null·공백이면 거짓(fail-closed)
     * @return 설정된 키가 있고 값이 정확히 같으면 참
     */
    private boolean matches(String presented) {
        if (expectedKey == null || presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }
}
