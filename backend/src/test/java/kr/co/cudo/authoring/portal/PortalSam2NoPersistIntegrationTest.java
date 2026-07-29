package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.portal.service.PortalSam2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 9 — CRITICAL #1: 포털 SAM2 자동추적 호출 후 내부 LS_DATA_LBL row 수가 불변임을 실 DB 로 검증.
 *
 * <p>내부 {@link kr.co.cudo.authoring.label.service.Sam2TrackService} 는 추적 결과를 즉시
 * LS_DATA_LBL 에 persist 하지만, 포털 경로({@link PortalSam2Service})는 좌표만 반환하고 저장하지 않는다.
 * APPROVED 영상에 대해 포털 추적을 수행해도 내부 라벨이 1건도 생성되지 않아야 한다(데이터마트 오염 차단).
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalSam2NoPersistIntegrationTest {

    @Autowired private PortalSam2Service portalSam2Service;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;

    @MockBean private AiServerClient aiServerClient;

    private static Path tmpRawDir;
    /**
     * S7 — 포털 SAM2 는 <b>비식별 프레임만</b> ai-server 로 보낸다(원본 폴백 금지, 신고 게이트 경유).
     * 따라서 픽스처도 비식별 저장소 base 와 그 하위 규약 경로({@code frames/deid/{rawSn}/…})를 갖춰야
     * 실제 경로를 탄다.
     */
    private static Path tmpDeidDir;

    @DynamicPropertySource
    static void overrideStoragePaths(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("portal-sam2-raw-");
        tmpDeidDir = Files.createTempDirectory("portal-sam2-deid-");
        registry.add("authoring.storage.raw-path", () -> tmpRawDir.toAbsolutePath().toString());
        registry.add("authoring.storage.deidentified-path", () -> tmpDeidDir.toAbsolutePath().toString());
    }

    private final long rawSn = 78001L;
    private Long src0;
    private Long src1;

    private final TokenClaims portalUser =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() throws IOException {
        labelRepository.deleteAll();
        srcRepository.deleteAll();
        rawDataStatusRepository.deleteById(rawSn);

        Files.write(tmpRawDir.resolve("0.jpg"), new byte[]{0x01, 0x02});
        Files.write(tmpRawDir.resolve("1.jpg"), new byte[]{0x03, 0x04});
        Path deidFrameDir = tmpDeidDir.resolve("frames").resolve("deid").resolve(String.valueOf(rawSn));
        Files.createDirectories(deidFrameDir);
        Path deid0 = Files.write(deidFrameDir.resolve("0.jpg"), new byte[]{0x11, 0x12});
        Path deid1 = Files.write(deidFrameDir.resolve("1.jpg"), new byte[]{0x13, 0x14});
        src0 = srcRepository.save(LsDataSrc.create(rawSn, 0, 0L, "0.jpg",
                deid0.toString(), LocalDateTime.now())).getSrcSn();
        src1 = srcRepository.save(LsDataSrc.create(rawSn, 1, 1L, "1.jpg",
                deid1.toString(), LocalDateTime.now())).getSrcSn();

        LsRawDataStatus approved = LsRawDataStatus.initial(rawSn);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        rawDataStatusRepository.save(approved);
    }

    @AfterEach
    void tearDown() {
        labelRepository.deleteAll();
        srcRepository.deleteAll();
        rawDataStatusRepository.deleteById(rawSn);
    }

    @Test
    @DisplayName("포털_SAM2추적_호출후_내부_LS_DATA_LBL_row수_불변")
    void portalTrackDoesNotPersistInternalLabels() {
        long before = labelRepository.countByRawSn(rawSn);
        assertThat(before).isZero();

        when(aiServerClient.track(any())).thenReturn(Mono.just(
                new Sam2TrackResponse("track-1",
                        List.of(List.of(20.0, 20.0), List.of(40.0, 40.0), List.of(20.0, 40.0)), 0.85)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-1",
                List.of(List.of(10.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                "person", List.of(src1));

        Sam2TrackResponseDto res = portalSam2Service.track(req, portalUser);

        // 좌표는 반환되지만
        assertThat(res.tracked()).hasSize(1);
        // 내부 LS_DATA_LBL 은 1건도 생성되지 않아야 함 (CRITICAL — 데이터마트 오염 차단).
        assertThat(labelRepository.countByRawSn(rawSn)).isZero();
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
    }
}
