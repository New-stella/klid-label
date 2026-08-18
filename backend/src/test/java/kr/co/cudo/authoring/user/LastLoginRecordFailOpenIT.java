package kr.co.cudo.authoring.user;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.StubControllers;
import kr.co.cudo.authoring.user.service.LastLoginTouchTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 최종로그인일시 기록 실패가 <b>API 를 죽이지 않는다</b>는 것을 실제 필터 체인으로 고정한다.
 *
 * <p>단위 테스트({@code LastLoginRecorderTest})는 recorder 를 <b>직접</b> 부르므로 실제 필터 체인에서도
 * 예외가 새지 않는지는 알 수 없다(필터가 기록기를 감싸 다시 던질 수도 있다). 그래서 기록 경로만
 * 실패시키고 요청이 2xx 로 완주하는지를 여기서 확인한다.
 *
 * <p>⚠ <b>이 테스트가 덮지 <i>않는</i> 것</b> — try/catch 가 트랜잭션 프록시 <b>바깥</b>에 있는지는
 * 여기서 알 수 없다(트랜잭션 경계 빈을 mock 으로 갈아 끼웠으니 트랜잭션 자체가 없다). 안쪽에서
 * 삼키면 rollback-only 표시 때문에 커밋 시점에 다시 터지는데, 그 배치는
 * {@code LastLoginRecorderTest} 의 구조 가드가 고정한다.
 *
 * <p>기록 서비스를 mock 으로 갈아 끼우므로 <b>이 클래스는 별도 컨텍스트</b>다({@code LastLoginRecordIT}
 * 의 실제 SQL 검증과 섞이면 그쪽이 mock 을 물어 무의미해진다).
 *
 * @design SCREEN-024
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class LastLoginRecordFailOpenIT {

    @MockBean
    private LastLoginTouchTxService touchTxService;

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Test
    @DisplayName("기록에_실패해도_요청은_정상_처리된다")
    void 기록에_실패해도_요청은_정상_처리된다() throws Exception {
        // given — 기록 경로가 DB 장애로 실패한다.
        when(touchTxService.touch(anyLong(), any(), any()))
                .thenThrow(new QueryTimeoutException("db down"));

        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("1")            // V9001 시드: 1 → REVIEWER
                .issuer(issuer)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        // when & then — 부가 기록 실패가 인증·인가를 막지 않는다.
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(touchTxService, atLeastOnce()).touch(anyLong(), any(), any());
    }

    private SecretKey key() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            return Keys.hmacShaKeyFor(padded);
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
