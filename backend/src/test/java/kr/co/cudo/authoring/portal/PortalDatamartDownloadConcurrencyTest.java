package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.config.MvcAsyncExecutorConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadService;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 작업 데이터 내려받기의 <b>동시 스트리밍 상한</b> — 요청 스레드 고갈 차단(CWE-400 / OWASP API4).
 *
 * <h3>무엇을 막는가</h3>
 * <p>{@link MvcAsyncExecutorConfig} 의 포화 정책은 {@code CallerRunsPolicy} 다. 그 선택 자체는 옳다 —
 * 거부하면 응답이 이미 커밋된 뒤라 사용자에게 <b>깨진 내려받기</b>로 보인다. 그러나 되밀린 작업은
 * <b>컨테이너 요청 스레드</b>가 직접 수행하므로, 동시 요청을 제한하지 않으면 실질 동시성 상한이
 * 요청 스레드 수(기본 200)까지 올라간다. 200개가 30분씩 붙잡히면 내려받기만 느려지는 게 아니라
 * <b>앱 전체가 무응답</b>이 되고, 사용자에게는 "다운로드 실패" 가 아니라 <b>모든 화면의 무한 로딩</b>
 * 으로 보인다.
 *
 * <h3>어떻게 막는가</h3>
 * <p>비동기 처리를 <b>시작하기 전에</b> 자리를 잡는다. 응답이 커밋되기 전이라 거부가 깨끗한 429 로
 * 나가고, FE 는 이미 429 를 "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요." 로 안내한다
 * ({@code frontend/src/features/portal/downloadError.ts}). 30분 매달리는 것보다 낫다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalDatamartDownloadConcurrencyTest {

    private static final long RAW_SN = 100L;

    @Mock PortalDatamartDownloadTxService txService;
    @Mock VideoArtifactRootResolver artifactRootResolver;

    @TempDir Path deidRoot;

    private PortalDatamartDownloadService service;

    private final TokenClaims actor =
            new TokenClaims("portal-user", Role.PORTAL_USER, Channel.PORTAL, null);

    @BeforeEach
    void setUp() {
        service = new PortalDatamartDownloadService(txService, artifactRootResolver, deidRoot.toString());
        // 파일이 없는 계획서 — 이 테스트의 관심은 ZIP 내용이 아니라 «자리» 회계다.
        when(txService.plan(anyLong(), any())).thenReturn(new PortalDatamartDownloadTxService.DownloadPlan(
                RAW_SN, "{}".getBytes(), List.of(), null, null));
        when(artifactRootResolver.readableDeidVideoBases(anyLong(), any())).thenReturn(List.of());
    }

    /** 상한만큼 응답을 만들되 <b>본문은 쓰지 않는다</b> — 전송 중인 상태를 흉내 낸다. */
    private List<ResponseEntity<StreamingResponseBody>> fillToLimit() {
        return java.util.stream.IntStream.range(0, MvcAsyncExecutorConfig.MAX_POOL_SIZE)
                .mapToObj(i -> service.download(RAW_SN, actor))
                .toList();
    }

    @Test
    @DisplayName("동시_스트리밍이_상한에_이르면_다음_요청은_즉시_429다")
    void beyondLimitIsRejectedFast() {
        fillToLimit();

        assertThatThrownBy(() -> service.download(RAW_SN, actor))
                .as("상한을 넘겨도 받아 주면 되밀린 작업이 요청 스레드를 30분씩 붙잡는다")
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("응답_본문이_끝나면_자리가_반납된다")
    void slotIsReleasedWhenBodyCompletes() throws IOException {
        List<ResponseEntity<StreamingResponseBody>> inFlight = fillToLimit();

        // 하나가 전송을 마치면 그만큼 다시 받아야 한다 — 반납이 없으면 첫 상한 이후 영구히 429 다.
        inFlight.get(0).getBody().writeTo(new ByteArrayOutputStream());

        assertThat(service.download(RAW_SN, actor)).isNotNull();
    }

    @Test
    @DisplayName("본문_작성_중_전송이_끊겨도_자리가_반납된다")
    void slotIsReleasedWhenBodyFails() {
        List<ResponseEntity<StreamingResponseBody>> inFlight = fillToLimit();

        // 대용량 내려받기에서 가장 흔한 종료는 정상 완료가 아니라 «클라이언트 이탈» 이다.
        // 그 경로에서 반납이 빠지면 자리가 조금씩 새다가 결국 전건 429 가 된다.
        assertThatThrownBy(() -> inFlight.get(0).getBody().writeTo(brokenPipe()))
                .isInstanceOf(IOException.class);

        assertThat(service.download(RAW_SN, actor)).isNotNull();
    }

    @Test
    @DisplayName("계획_단계에서_거부되면_자리를_잡지_않는다")
    void rejectedPlanDoesNotConsumeSlot() {
        when(txService.plan(anyLong(), any()))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "데이터마트 미노출"));

        // 403/412/410 로 끝나는 요청은 아무것도 전송하지 않는다. 그런 요청이 자리를 물고 있으면
        // 정상 요청이 남의 실패 때문에 429 가 된다.
        for (int i = 0; i < MvcAsyncExecutorConfig.MAX_POOL_SIZE + 5; i++) {
            assertThatThrownBy(() -> service.download(RAW_SN, actor))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("동시_스트리밍_상한이_비동기_풀의_최대_스레드_수를_넘지_않는다")
    void limitDoesNotExceedPoolSize() {
        // 이 관계가 성립해야 «되밀기가 구조적으로 도달 불가» 가 된다. 상한을 풀보다 크게 잡으면
        // 초과분이 큐를 지나 CallerRuns 로 넘어가 요청 스레드를 다시 붙잡는다.
        assertThat(PortalDatamartDownloadService.MAX_CONCURRENT_STREAMS)
                .isLessThanOrEqualTo(MvcAsyncExecutorConfig.MAX_POOL_SIZE)
                .isPositive();
    }

    /** 전송 도중 끊긴 소켓 흉내 — 첫 write 에서 실패한다. */
    private static OutputStream brokenPipe() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("broken pipe");
            }
        };
    }
}
