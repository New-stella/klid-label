package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("local")
class Sam2TrackServiceTest {

    @Autowired private Sam2TrackService sam2TrackService;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsPjtUserAuthrtRepository authrtRepository;

    @MockBean private AiServerClient aiServerClient;

    private static Path tmpRawDir;

    @DynamicPropertySource
    static void overrideStorageRawPath(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("sam2-track-raw-");
        registry.add("authoring.storage.raw-path", () -> tmpRawDir.toAbsolutePath().toString());
    }

    private Long src0;
    private Long src1;
    private Long src2;
    private long rawSn;

    private TokenClaims reviewer;
    private TokenClaims workerAssigned;
    private TokenClaims workerNotAssigned;

    @BeforeEach
    void setup() throws IOException {
        // 동일 영상의 3 프레임 시드.
        labelRepository.deleteAll();
        authrtRepository.deleteAll();
        srcRepository.deleteAll();
        rawSn = 9001L;
        // ai-server 로 전송할 base64 인코딩을 위해 실제 파일 생성 (상대 경로).
        Files.write(tmpRawDir.resolve("0.jpg"), new byte[]{0x01, 0x02});
        Files.write(tmpRawDir.resolve("1.jpg"), new byte[]{0x03, 0x04});
        Files.write(tmpRawDir.resolve("2.jpg"), new byte[]{0x05, 0x06});
        src0 = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        src1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();
        src2 = srcRepository.save(LsDataSrc.create(rawSn, 2, "2.jpg", LocalDateTime.now())).getSrcSn();

        // 작업자 100 만 rawSn 에 LABELER 배정 — IDOR 검증용
        authrtRepository.save(LsPjtUserAuthrt.createLabeler(rawSn, 100L, 1L));

        Instant exp = Instant.now().plusSeconds(60);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, exp);
        workerAssigned = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, exp);
        workerNotAssigned = new TokenClaims("101", Role.WORKER, Channel.INTERNAL, exp);
    }

    @Test
    @Disabled("TODO Phase 6: 신규 DB 설계 — autoLblYn/confScore/lblSrcCd 는 LS_DATA_LBL_AI_INFO 로 분리됨. LsDataLbl 에서 @Transient 이므로 persist 후 null. AI Info 조회 기반으로 재작성 필요.")
    @DisplayName("Sam2TrackService_연속_프레임_동일_TRCK_ID_전파")
    void trackPropagatesSameTrackId() {
        // ai-server mock — 매 호출 시 동일 trackId 반환 + 좌표만 변동
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(
                new Sam2TrackResponse("track-AAA",
                        List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                        0.92)));

        // 시작 라벨 = src0 의 POLYGON 1건 (TRCK_ID 컬럼 없으므로 LABEL 에 같이 묶음)
        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-AAA",
                List.of(List.of(10.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                "person",
                List.of(src1, src2));

        Sam2TrackResponseDto res = sam2TrackService.track(req, reviewer);

        assertThat(res.tracked()).hasSize(2); // src1, src2 두 프레임 라벨 생성
        assertThat(res.tracked()).allSatisfy(item -> assertThat(item.trackId()).isEqualTo("track-AAA"));

        // ai-server 가 정확히 2회 호출되었고, 동일 trackId 가 전파되었음.
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest.class);
        verify(aiServerClient, atLeast(2)).track(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(r -> assertThat(r.trackId()).isEqualTo("track-AAA"));

        // DB 에 저장된 라벨은 모두 AUTO_LBL_YN = 'Y' (SAM2 자동) + POLYGON 타입
        List<LsDataLbl> savedSrc1 = labelRepository.findBySrcSn(src1);
        List<LsDataLbl> savedSrc2 = labelRepository.findBySrcSn(src2);
        assertThat(savedSrc1).hasSize(1).allSatisfy(l -> {
            assertThat(l.getAutoLblYn()).isEqualTo("Y");
            assertThat(l.getLblTypeCd()).isEqualTo("POLYGON");
            assertThat(l.getLabel()).isEqualTo("person");
        });
        assertThat(savedSrc2).hasSize(1);
    }

    @Test
    @DisplayName("미배정_WORKER가_sam2_track_호출시_403_FORBIDDEN")
    void notAssignedWorkerForbidden() {
        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-XYZ",
                List.of(List.of(10.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                "person",
                List.of(src1, src2));

        assertThatThrownBy(() -> sam2TrackService.track(req, workerNotAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        // ai-server 호출이 일어나지 않아야 함 (IDOR 차단이 ai 호출 이전).
        verify(aiServerClient, never()).track(any());
        // DB 에 라벨 INSERT 도 없어야 함.
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
        assertThat(labelRepository.findBySrcSn(src2)).isEmpty();
    }

    @Test
    @DisplayName("배정된_WORKER는_sam2_track_정상_동작")
    void assignedWorkerCanTrack() {
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(
                new Sam2TrackResponse("track-OK",
                        List.of(List.of(20.0, 20.0), List.of(40.0, 40.0), List.of(20.0, 40.0)),
                        0.85)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-OK",
                List.of(List.of(10.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                "car",
                List.of(src1));

        Sam2TrackResponseDto res = sam2TrackService.track(req, workerAssigned);

        assertThat(res.tracked()).hasSize(1);
        assertThat(labelRepository.findBySrcSn(src1)).hasSize(1);
    }
}
