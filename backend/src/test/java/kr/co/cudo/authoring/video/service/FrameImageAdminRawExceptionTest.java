package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.service.FrameImageLookupService.FrameSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>역할 계층이 미치지 않는 유일한 예외</b> — 원본(비-비식별) 프레임 이미지 열람은 관리자가
 * 물려받지 않는다. [design: ADR-055] [design: ROLE-004] [design: AC-125] [design: API-021] [design: API-046]
 *
 * <h3>무엇을 고정하는가</h3>
 * <p>관리자가 {@code raw=true} 를 <b>명시</b>해도 원본이 아니라 <b>비식별 프레임</b>이 나간다.
 * 그 판정은 {@code FrameImageService.serveFrame} 의 {@code actor.role() == Role.REVIEWER} 한 줄이며,
 * 계층 이관 라운드에서 그 줄만 {@code hasRole(Role.REVIEWER)} 로 바뀌면 개인정보 노출면이 관리자까지
 * <b>조용히</b> 넓어진다(CWE-359). 코드 주석은 게이트가 아니므로 이 시험이 그 되돌림을 막는다.
 *
 * <h3>왜 서비스 직접 호출인가 (MockMvc 가 아니라)</h3>
 * <p>HTTP 종단 경로는 {@code LabelAccessGuard}(라벨링 도메인 소유)의 역할 판정을 먼저 통과해야 하는데,
 * 그쪽 이관은 <b>이 변경의 경계 밖</b>이라 아직 관리자를 403 으로 막는다. 그 상태에서 종단 시험을 두면
 * 이 시험이 지키려는 축(원본 서빙 판정)이 아니라 <b>남의 게이트</b>를 측정하게 되고, 그쪽이 이관되는
 * 순간 이 시험이 무엇을 지켰는지도 알 수 없게 된다. 그래서 4 경로가 공유하는 <b>판정 단일 원천</b>
 * ({@code serveFrame})을 직접 겨눈다.
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <ul>
 *   <li>원본·비식별 파일에 <b>서로 다른 바이트</b>를 넣고 <b>서빙된 바이트</b>로 판정한다 — 상태코드만
 *       보면 어느 벌이 나갔는지 구분되지 않는다.</li>
 *   <li>「관리자에게 원본이 안 나간다」와 짝으로 <b>「검수자에게는 원본이 나간다」</b>를 같은 픽스처로
 *       확인한다. 원본 분기가 애초에 도달 불가면 앞 단언은 항상 참이 되어 아무것도 지키지 않는다.</li>
 *   <li>두 진입점({@code serve} · {@code serveBySrcSn})을 모두 건다 — 둘은 같은 원천을 쓰지만 그
 *       사실이 시험으로 고정돼 있지 않으면 한쪽만 갈라져도 초록이다.</li>
 * </ul>
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>판정을 {@code actor.hasRole(Role.REVIEWER)} 로 되돌리면 이 클래스의 관리자 시험 2건이
 * <b>FAILED</b> 가 된다(원본 바이트가 서빙되어 비식별 바이트와 길이·내용이 어긋난다).
 */
class FrameImageAdminRawExceptionTest {

    /** 원본 프레임 픽셀 대역 — 관리자에게는 <b>어떤 경우에도</b> 나가면 안 되는 바이트. */
    private static final byte[] RAW_PIXELS = "RAW-ORIGINAL-PIXELS-NOT-FOR-ADMIN".getBytes(StandardCharsets.UTF_8);
    /** 비식별 프레임 픽셀 대역 — 길이가 원본과 다르도록 일부러 어긋나게 둔다. */
    private static final byte[] DEID_PIXELS = "DEID".getBytes(StandardCharsets.UTF_8);

    private static final long RAW_SN = 4242L;
    private static final int FRAME_NO = 0;
    private static final long SRC_SN = 909090L;

    private FrameImageService service;
    private FrameImageLookupService lookupService;

    @BeforeEach
    void setUp(@TempDir Path storageBase) throws IOException {
        // 운영 형상과 같게 raw/deid base 를 <b>같은 디렉터리</b>로 둔다(/nas-storage). 그래야 두 벌을
        // 가르는 것이 base 검사가 아니라 서브트리 규약임이 시험에도 그대로 반영된다.
        Path rawFile = storageBase.resolve("frames/raw/" + RAW_SN + "/f_0.jpg");
        Path deidFile = storageBase.resolve("frames/deid/" + RAW_SN + "/f_0.jpg");
        Files.createDirectories(rawFile.getParent());
        Files.createDirectories(deidFile.getParent());
        Files.write(rawFile, RAW_PIXELS);
        Files.write(deidFile, DEID_PIXELS);

        lookupService = mock(FrameImageLookupService.class);
        // 개인정보 포함(PRVC) 영상 — 비식별본이 준비돼 있다. 이관 산출물이 아니다.
        FrameSpec spec = new FrameSpec(RAW_SN, (long) FRAME_NO,
                "frames/raw/" + RAW_SN + "/f_0.jpg",
                "frames/deid/" + RAW_SN + "/f_0.jpg",
                true, false);
        when(lookupService.byRawSnAndFrameNo(anyLong(), anyInt())).thenReturn(spec);
        when(lookupService.bySrcSn(anyLong(), any())).thenReturn(spec);

        service = new FrameImageService(lookupService);
        ReflectionTestUtils.setField(service, "storageRawPath", storageBase.toString());
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", storageBase.toString());
    }

    private static TokenClaims actor(String sub, Role role) {
        return new TokenClaims(sub, role, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private static byte[] bodyOf(ResponseEntity<Resource> response) throws IOException {
        try (InputStream in = response.getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ---------- 예외 본체 — 관리자는 원본을 물려받지 않는다 ----------

    @Test
    @DisplayName("★관리자가_raw_true_를_명시해도_원본이_아니라_비식별_프레임이_서빙된다_계층_예외")
    void adminRawRequestIsIgnoredAndDeidIsServed() throws IOException {
        // given: 관리자 토큰 + 원본 명시 요청
        // when
        ResponseEntity<Resource> response =
                service.serve(RAW_SN, FRAME_NO, true, actor("969300001", Role.ADMIN));

        // then: 거부(4xx)가 아니라 <b>무시</b>다 — 200 이되 나가는 것은 비식별 벌이다.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(bodyOf(response))
                .as("관리자에게는 비식별 프레임이 나가야 한다")
                .isEqualTo(DEID_PIXELS)
                .as("원본 픽셀이 관리자에게 새면 안 된다 (CWE-359)")
                .isNotEqualTo(RAW_PIXELS);
    }

    @Test
    @DisplayName("★프레임PK_경로에서도_관리자의_raw_true_는_무시된다_두_진입점이_같은_판정을_쓴다")
    void adminRawRequestIsIgnoredOnSrcSnEntryPoint() throws IOException {
        // given/when: /v1/frames/{srcSn}/image 백엔드 — serve 와 같은 serveFrame 을 공유해야 한다
        ResponseEntity<Resource> response =
                service.serveBySrcSn(SRC_SN, true, actor("969300001", Role.ADMIN));

        // then
        assertThat(bodyOf(response))
                .as("진입점이 갈라져 한쪽만 예외를 지키는 상태를 막는다")
                .isEqualTo(DEID_PIXELS)
                .isNotEqualTo(RAW_PIXELS);
    }

    // ---------- 대조군 — 원본 분기가 실제로 도달 가능함을 확인 ----------

    @Test
    @DisplayName("검수자의_raw_true_는_원본이_서빙된다_원본_분기가_도달_가능함을_고정")
    void reviewerRawRequestStillServesOriginal() throws IOException {
        // 이 시험이 없으면 위 관리자 시험은 "원본 분기가 애초에 죽어 있어도" 통과한다(항상 참인 시험).
        ResponseEntity<Resource> response =
                service.serve(RAW_SN, FRAME_NO, true, actor("1", Role.REVIEWER));

        assertThat(bodyOf(response)).isEqualTo(RAW_PIXELS);
    }

    @Test
    @DisplayName("작업자의_raw_true_는_무시되고_비식별_프레임이_서빙된다_기존_정책_불변")
    void workerRawRequestIsIgnored() throws IOException {
        ResponseEntity<Resource> response =
                service.serve(RAW_SN, FRAME_NO, true, actor("100", Role.WORKER));

        assertThat(bodyOf(response)).isEqualTo(DEID_PIXELS);
    }

    @Test
    @DisplayName("관리자가_raw_를_요청하지_않으면_당연히_비식별_프레임이_서빙된다")
    void adminWithoutRawGetsDeid() throws IOException {
        ResponseEntity<Resource> response =
                service.serve(RAW_SN, FRAME_NO, false, actor("969300001", Role.ADMIN));

        assertThat(bodyOf(response)).isEqualTo(DEID_PIXELS);
    }
}
