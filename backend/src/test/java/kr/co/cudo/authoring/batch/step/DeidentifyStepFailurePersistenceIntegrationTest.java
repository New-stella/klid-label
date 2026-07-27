package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 라이브 검증 결함 회귀 — DeidentifyStep 실패 경로의 {@code DE_IDENT_YN='F'} 영속 검증.
 *
 * <p>UC018 경로 단일화 후 레거시 동기 SPI 경로는 제거되었으므로, 본 테스트는 <b>local mock 경로</b>
 * (원본 부재 → 실패)에서 실패 기록이 REQUIRES_NEW 롤백과 독립 커밋되는지 검증한다.
 *
 * <p><b>근본 원인</b>: {@code runMock()} 은 {@code REQUIRES_NEW} 트랜잭션이다. 실패 분기에서 인라인으로
 * {@code markDeidentified("F")} + {@code procLog.fail(...)} 한 직후 {@code CustomException} 을 던지면,
 * RuntimeException 전파로 Spring 이 그 트랜잭션을 전체 롤백한다 → 'F' 와 FAIL 기록이 함께 롤백된다.
 *
 * <p>수정 방향: 실패 기록을 별도 빈({@code BatchTransitionService})의 {@code REQUIRES_NEW} 메서드로
 * 위임해 독립 커밋한다. 본 테스트는 {@code run()} 이 던진 뒤 별도 조회 트랜잭션에서 'F' 가 살아있는지
 * (=커밋됨)를 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // Phase 5A — co-locate 산출 base 허용 마운트 루트(원본 영상이 이 하위에 있어야 한다)
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
        // 테스트 기본(application-local.yml): kpst.deid.enabled=false + deidentify.mock-mode=true → mock 경로.
        "authoring.integration.deidentify.mock-mode=true"
})
class DeidentifyStepFailurePersistenceIntegrationTest {

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    /** 구 위치(비식별 저장소) base — "기본 루트로 조용히 폴백하지 않는다" 검증 기준. */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    private LsDataRaw saveRaw(String filePath) {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "clip-fail-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, filePath, null, 60));
    }

    @Test
    @DisplayName("mock_원본부재시_DE_IDNTF_YN이_F로_DB에_영속된다 — REQUIRES_NEW 롤백과 독립 커밋")
    void missingSource_persistsFFlag() {
        // given — 실제 적재된 영상(deIdntfYn='N', PENDING). 원본 파일은 존재하지 않는 경로.
        String missing = "/var/raw/no-such-" + System.nanoTime() + ".mp4";
        LsDataRaw raw = saveRaw(missing);
        Long rawSn = raw.getRawSn();

        // when — run() 실패(CustomException). run() 의 REQUIRES_NEW 는 롤백됨.
        assertThatThrownBy(() -> deidentifyStep.run(raw))
                .isInstanceOf(CustomException.class);

        // then — 별도 조회 트랜잭션에서 'F' 가 커밋되어 살아있어야 한다(영속). 롤백되면 'N'.
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getDeIdntfYn()).isEqualTo("F");
        // MARKING_READY 미전이(성공 위장 0) — 적재 직후 PENDING 유지.
        assertThat(reloaded.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("mock_실패시_procLog에_FAIL_기록이_DB에_영속된다")
    void missingSource_persistsProcLogFail() {
        // given
        String missing = "/var/raw/no-such-" + System.nanoTime() + ".mp4";
        LsDataRaw raw = saveRaw(missing);
        Long rawSn = raw.getRawSn();

        // when
        assertThatThrownBy(() -> deidentifyStep.run(raw))
                .isInstanceOf(CustomException.class);

        // then — 커밋된 FAIL procLog 레코드가 존재(REQUESTED 레코드는 run T1 롤백으로 사라짐).
        List<LsDeidentProcLog> logs = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn);
        assertThat(logs).isNotEmpty();
        assertThat(logs).anyMatch(l -> LsDeidentProcLog.FAILED.equals(l.getProcSttsCd()));
        // 보안(CWE-209): 에러 메시지에 원본경로/PII/외부원문 미노출 — 고정 코드/예외클래스명만.
        LsDeidentProcLog failLog = logs.stream()
                .filter(l -> LsDeidentProcLog.FAILED.equals(l.getProcSttsCd()))
                .findFirst().orElseThrow();
        assertThat(failLog.getErrorMsg()).doesNotContain(missing);
        assertThat(failLog.getErrorMsg()).doesNotContain("/var/raw");
    }

    @Test
    @DisplayName("mock_성공경로_회귀 — 원본 존재 시 Y 전이 + 비식별경로 복사(무변경)")
    void successPath_regression() throws Exception {
        // given — 허용 마운트 루트 하위에 실제 원본 파일 존재.
        Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("ok-raw");
        LsDataRaw raw = saveRaw(rawFile.toString());
        Long rawSn = raw.getRawSn();

        // when
        DeidentResult returned = deidentifyStep.run(raw);

        // then — 비식별 결과가 co-locate 위치(dirname(원본)/{rawSn}/deid/)에 복사되고 Y 전이(동기 완료).
        Path expected = ArtifactRootTestSupport.expectedMockDeidPath(rawFile, rawSn);
        assertThat(returned.completed()).isTrue();
        assertThat(returned.deidFilePath()).isEqualTo(expected.toString());
        assertThat(Files.readString(expected)).isEqualTo("raw-bytes");
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("S1_원본경로가_허용마운트루트_밖이면_기본루트로_폴백하지_않고_F로_마감된다")
    void baseOutsideAllowlist_failsClosed_noFallback() throws Exception {
        // given — 원본 파일은 실재하지만(=존재검증 통과) 허용 마운트 루트 밖(호스트 임시경로)이다.
        //         실측상 RAW_FILE_PATH_NM 에는 컨테이너에 없는 호스트 절대경로가 섞여 있다.
        Path outside = Files.createTempFile("outside-raw", ".mp4");
        Files.writeString(outside, "raw-bytes");
        LsDataRaw raw = saveRaw(outside.toString());
        Long rawSn = raw.getRawSn();
        // 구 위치(기본 루트) 폴백 여부는 "이번 실행이 새로 만들었는가"로 판정한다 — 공유 storage 디렉터리에
        // 이전 실행 잔재가 있을 수 있으므로 실행 전 상태를 기준선으로 잡는다.
        Path legacyFallback = Path.of(deidPath).toAbsolutePath().normalize()
                .resolve("videos").resolve(String.valueOf(rawSn)).resolve("deidentified.mp4");
        boolean legacyExistedBefore = Files.exists(legacyFallback);

        // when — base 검증 실패로 산출을 만들지 못하고 실패로 종결해야 한다.
        assertThatThrownBy(() -> deidentifyStep.run(raw)).isInstanceOf(CustomException.class);

        // then ① 기본 루트(구 위치)로 조용히 폴백해 산출물을 만들지 않았다.
        assertThat(Files.exists(legacyFallback)).isEqualTo(legacyExistedBefore);
        // then ② 원본 디렉터리(허용 밖)에도 산출 트리를 만들지 않았다.
        assertThat(Files.exists(outside.getParent().resolve(String.valueOf(rawSn)))).isFalse();
        // then ③ 실패가 DB 에 영속(F) — 성공 위장 0.
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("F");
        // then ④ 예외/에러 메시지에 내부 경로 미노출(CWE-209).
        List<LsDeidentProcLog> logs = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn);
        assertThat(logs).anyMatch(l -> LsDeidentProcLog.FAILED.equals(l.getProcSttsCd()));
        logs.forEach(l -> assertThat(l.getErrorMsg() == null ? "" : l.getErrorMsg())
                .doesNotContain(outside.getParent().toString()));
    }

    @Test
    @DisplayName("S2_원본경로에_상위참조가_섞여_허용루트를_벗어나면_차단된다")
    void baseWithParentTraversal_isRejected() throws Exception {
        // given — 허용 루트 하위처럼 보이지만 '..' 로 빠져나가는 경로(정규화 후 allowlist 밖).
        Path seeded = ArtifactRootTestSupport.seedOriginalVideo("traversal-raw");
        Path traversal = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT, "videos", "..", "..", "..", "escaped")
                .toAbsolutePath();
        Files.createDirectories(traversal.normalize());
        Path escapedVideo = traversal.resolve(seeded.getFileName().toString());
        Files.copy(seeded, escapedVideo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        LsDataRaw raw = saveRaw(traversal + java.io.File.separator + seeded.getFileName());
        Long rawSn = raw.getRawSn();

        // when / then — 차단(fail-secure). 산출 트리도 만들어지지 않는다.
        assertThatThrownBy(() -> deidentifyStep.run(raw)).isInstanceOf(CustomException.class);
        assertThat(Files.exists(traversal.normalize().resolve(String.valueOf(rawSn)))).isFalse();
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("S5_mock_비식별_산출_전후로_원본_영상_파일이_변하지_않는다")
    void originalVideoUntouched_afterDeidentify() throws Exception {
        // given — 원본 체크섬 기록.
        Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("untouched-raw");
        byte[] before = Files.readAllBytes(rawFile);
        String sha256Before = sha256(before);
        LsDataRaw raw = saveRaw(rawFile.toString());

        // when
        DeidentResult returned = deidentifyStep.run(raw);

        // then — 원본은 존재도 내용도 불변이며, 산출물은 원본과 다른 경로에 생성된다.
        assertThat(returned.completed()).isTrue();
        assertThat(Files.exists(rawFile)).isTrue();
        assertThat(sha256(Files.readAllBytes(rawFile))).isEqualTo(sha256Before);
        assertThat(returned.deidFilePath()).isNotEqualTo(rawFile.toString());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
