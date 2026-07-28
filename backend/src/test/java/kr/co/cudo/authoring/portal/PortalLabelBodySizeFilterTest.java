package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.portal.config.PortalLabelBodySizeFilter;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 (DEV_FIX M-1) — 라벨 PUT 본문 크기 상한 필터 단위 검증.
 * 경로 한정(라벨 PUT 만)·413(초과)·411(chunked)·정상 통과·타 경로/메서드 미적용을 확인한다.
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

    /** MockHttpServletRequest.getContentLengthLong() 는 실제 content 길이(없으면 -1)를 반환한다. */
    private MockHttpServletRequest req(String method, String path, byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        if (body != null) {
            r.setContent(body);
        }
        return r;
    }

    @Test
    @DisplayName("본문_상한초과_413")
    void tooLarge() throws Exception {
        MockHttpServletRequest r = req("PUT", LABEL_PUT, new byte[(int) (MAX + 1)]);
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
}
