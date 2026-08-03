package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/frames/{srcSn}/image} — <b>라벨링 캔버스 프레임 서빙 정책 정합</b> 검증.
 *
 * <p>배경(실측 결함): 이 엔드포인트는 {@code SRC_FILE_PATH_NM} 만 읽었다. 그래서
 * <ul>
 *   <li>해상도·증강 <b>파생 프레임</b>(원본 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM=null},
 *       E-ISSUE-41 정책 A)은 무조건 404 → 라벨링 캔버스가 백지(246 dev raw_sn=54 등 9건).</li>
 *   <li>일반 영상에서는 <b>원본(비식별 전) 프레임</b>이 WORKER 에게 그대로 서빙됐다 —
 *       "라벨링은 비식별 영상의 프레임으로 한다"는 설계와 어긋난 미배선 상태.</li>
 * </ul>
 *
 * <p>정합 후 계약은 {@code FrameImageService.serve(rawSn, frameNo, allowRaw, actor)} 와
 * <b>동일한 단일 판정</b>이다: 기본 DEID → REVIEWER {@code raw=true} 만 원본 →
 * 비식별 경로가 없으면 민감영상(PRVC/PSDO)은 404, ANONY 레거시는 원본 폴백.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FrameImageSrcSnPolicyTest {

    private static final byte[] DEID_BYTES =
            ("ÿØÿ" + "DEID-FRAME-CONTENT").getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] RAW_BYTES =
            ("ÿØÿ" + "RAW-ORIGINAL-FRAME-CONTENT-LONGER").getBytes(StandardCharsets.ISO_8859_1);

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidentifiedPath;

    private String reviewerToken;
    private String workerToken;
    private String otherWorkerToken;
    private Path rawBase;
    private Path deidBase;
    private String unique;

    @BeforeEach
    void setup() {
        reviewerToken   = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken     = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        otherWorkerToken = JwtTestSupport.token(secret, "101", "WORKER",  "INTERNAL", issuer, 60);
        rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
        deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        unique = "fisp-" + System.nanoTime();
    }

    // ---------- 픽스처 ----------

    private LsDataRaw seedRaw(String prvcTypeCd) {
        return rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + unique + "-" + System.nanoTime(), "CCTV-FISP", "EVT_FALL", "11680",
                prvcTypeCd, "/var/raw/" + unique + ".mp4", LocalDateTime.now(), 30));
    }

    /** 비식별 프레임 파일을 규약 서브트리({@code frames/deid/{rawSn}}) 에 실제로 만든다. */
    private String writeDeidFile(long rawSn, int frameNo) throws IOException {
        String rel = "frames/deid/" + rawSn + "/" + unique + "-" + frameNo + ".jpg";
        Path file = deidBase.resolve(rel).normalize();
        Files.createDirectories(file.getParent());
        Files.write(file, DEID_BYTES);
        return rel;
    }

    /** 원본 프레임 파일을 규약 서브트리({@code frames/raw/{rawSn}}) 에 실제로 만든다. */
    private String writeRawFile(long rawSn, int frameNo) throws IOException {
        String rel = "frames/raw/" + rawSn + "/" + unique + "-" + frameNo + ".jpg";
        Path file = rawBase.resolve(rel).normalize();
        Files.createDirectories(file.getParent());
        Files.write(file, RAW_BYTES);
        return rel;
    }

    private void assign(long rawSn) {
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private byte[] fetch(long srcSn, String token, String... rawQuery) throws Exception {
        var req = get("/v1/frames/" + srcSn + "/image").header("Authorization", "Bearer " + token);
        if (rawQuery.length > 0) {
            req = req.queryParam("raw", rawQuery[0]);
        }
        return mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    // ---------- R1 — 파생 프레임(원본 경로 없음) ----------

    @Test
    @DisplayName("파생_프레임은_원본경로가_없어도_비식별_이미지로_200")
    void derivedFrameServedFromDeidPath() throws Exception {
        // given — 해상도/증강 파생: SRC_FILE_PATH_NM=null, 비식별 경로만 존재
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_ANONY);
        raw.markDeidentified("Y");
        rawRepository.save(raw);
        String deidRel = writeDeidFile(raw.getRawSn(), 0);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 0, null, null, deidRel, LocalDateTime.now()));

        // when / then — 404(백지)가 아니라 비식별 이미지 200
        assertThat(fetch(src.getSrcSn(), reviewerToken)).isEqualTo(DEID_BYTES);
    }

    // ---------- R2 — 기본 서빙은 비식별, 원본은 REVIEWER raw=true 만 ----------

    @Test
    @DisplayName("WORKER_는_raw_true_를_보내도_비식별_프레임을_받는다")
    void workerRawTrueIgnored() throws Exception {
        // given — 원본·비식별 두 벌이 모두 있는 일반 영상 + WORKER 본인 배정
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_ANONY);
        String deidRel = writeDeidFile(raw.getRawSn(), 1);
        String rawRel = writeRawFile(raw.getRawSn(), 1);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 1, null, rawRel, deidRel, LocalDateTime.now()));
        assign(raw.getRawSn());

        // when / then — raw=true 를 보내도 원본이 아니라 비식별본
        assertThat(fetch(src.getSrcSn(), workerToken, "true")).isEqualTo(DEID_BYTES);
        // 파라미터 미지정(기본)도 동일하게 비식별본
        assertThat(fetch(src.getSrcSn(), workerToken)).isEqualTo(DEID_BYTES);
    }

    @Test
    @DisplayName("REVIEWER_가_raw_true_요청하면_원본_프레임을_받는다")
    void reviewerRawTrueServesOriginal() throws Exception {
        // given — 원본·비식별 두 벌 모두 존재
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_PRVC);
        String deidRel = writeDeidFile(raw.getRawSn(), 2);
        String rawRel = writeRawFile(raw.getRawSn(), 2);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 2, null, rawRel, deidRel, LocalDateTime.now()));

        // when / then — 명시 요청 시에만 원본, 기본은 비식별
        assertThat(fetch(src.getSrcSn(), reviewerToken, "true")).isEqualTo(RAW_BYTES);
        assertThat(fetch(src.getSrcSn(), reviewerToken)).isEqualTo(DEID_BYTES);
    }

    // ---------- R3 — 레거시(비식별 경로 없음) 프레임은 기존 폴백 규칙 유지 ----------

    @Test
    @DisplayName("비식별경로_없는_ANONY_레거시_프레임은_원본으로_폴백된다")
    void legacyAnonyFallsBackToOriginal() throws Exception {
        // given — 비식별 이전(raw_sn<=22) 레거시: 비식별 경로 없음 + ANONY
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_ANONY);
        String rawRel = writeRawFile(raw.getRawSn(), 3);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 3, rawRel, LocalDateTime.now()));

        // when / then — 백필 없이도 기존 동작(원본 폴백) 유지
        assertThat(fetch(src.getSrcSn(), reviewerToken)).isEqualTo(RAW_BYTES);
    }

    @Test
    @DisplayName("개인정보포함_영상은_비식별본이_없으면_프레임이_404다")
    void controlMetaAbsentVideoFailsClosedWithoutDeid() throws Exception {
        // given — 관제가 개인정보유형을 주지 않는 현행 실데이터 형태(인입 테이블에 컬럼 자체가 없다).
        //         적재 기본값(TrainingVideoIngestTx.DEFAULT_PRVC_TYPE = PRVC)으로 들어오므로
        //         needsDeidentify()=true 가 되어 비식별본이 없으면 원본 폴백이 닫힌다(의도된 fail-closed).
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_PRVC);
        String rawRel = writeRawFile(raw.getRawSn(), 11);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 11, rawRel, LocalDateTime.now()));

        // when / then — 마스킹 전 원본이 200 으로 나가지 않는다(CWE-359).
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비식별경로_없는_민감영상_프레임은_404")
    void legacySensitiveWithoutDeidReturns404() throws Exception {
        // given — PRVC(민감) 영상인데 비식별 경로 미준비 → 원본 폴백 금지
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_PRVC);
        String rawRel = writeRawFile(raw.getRawSn(), 4);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 4, rawRel, LocalDateTime.now()));

        // when / then
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    // ---------- R4 — 보안 불변식 (게이트 순서 / 서브트리) ----------

    @Test
    @DisplayName("비식별_누락_신고_구간이면_412")
    void deidentReportOpenReturns412() throws Exception {
        // given — 신고 구간(DE_IDNTF_YN='F') 영상 + 배정된 WORKER
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_ANONY);
        String deidRel = writeDeidFile(raw.getRawSn(), 5);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 5, null, null, deidRel, LocalDateTime.now()));
        assign(raw.getRawSn());
        raw.markDeidentified("F");
        rawRepository.save(raw);

        // when / then — 역할 무관 프리컨디션 412 (REVIEWER 도 동일)
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed());

        // 그리고 인가는 게이트보다 <b>먼저</b> 평가된다 — 미배정 WORKER 는 412 가 아니라 403 이어야
        // 프레임 존재 여부가 게이트 응답으로 새지 않는다(CWE-209/639 순서 회귀 가드).
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + otherWorkerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비식별_서브트리_밖_경로는_거부")
    void deidColumnOutsideDeidSubtreeRejected() throws Exception {
        // given — 비식별 컬럼이 원본 프레임 서브트리(frames/raw/**)를 가리키도록 오염
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_PRVC);
        String pollutedRel = "frames/raw/" + raw.getRawSn() + "/" + unique + "-polluted.jpg";
        Path polluted = deidBase.resolve(pollutedRel).normalize();
        Files.createDirectories(polluted.getParent());
        Files.write(polluted, RAW_BYTES);
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 6, null, null, pollutedRel, LocalDateTime.now()));

        // when / then — 원본 픽셀이 "비식별본"으로 서빙되지 않는다
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    // ---------- M-2 — 원본 분기도 "판정한 실경로"를 연다 (CWE-367/22) ----------

    @Test
    @DisplayName("원본_분기_심링크가_base밖을_가리키면_거부")
    void rawSymlinkEscapingBaseRejected() throws Exception {
        // given — 원본 컬럼이 rawBase 안의 심링크를 가리키고, 그 실체는 base 밖 파일
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_ANONY);
        Path outside = Files.createTempFile("fisp-outside-", ".jpg");
        Files.write(outside, RAW_BYTES);
        String linkRel = "frames/raw/" + raw.getRawSn() + "/" + unique + "-escape.jpg";
        Path link = rawBase.resolve(linkRel).normalize();
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 가드는 검증 대상 외
        }
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 8, linkRel, LocalDateTime.now()));

        // when / then — lexical 통과 후에도 실경로 기준으로 거부된다(검증 대상 = 사용 대상)
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        Files.deleteIfExists(outside);
    }

    @Test
    @DisplayName("원본_분기_base안_심링크는_실경로_대상을_그대로_서빙한다")
    void rawSymlinkInsideBaseServesRealTarget() throws Exception {
        // given — base 안에서 유효한 심링크(정상 배치에서도 있을 수 있는 형태).
        //         NOFOLLOW open 은 <b>판정한 실경로</b>에 적용되므로 이 케이스가 깨지면 안 된다.
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_ANONY);
        String targetRel = writeRawFile(raw.getRawSn(), 9);
        Path target = rawBase.resolve(targetRel).normalize();
        String linkRel = "frames/raw/" + raw.getRawSn() + "/" + unique + "-inside.jpg";
        Path link = rawBase.resolve(linkRel).normalize();
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 가드는 검증 대상 외
        }
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 9, linkRel, LocalDateTime.now()));

        // when / then — 실경로(target)의 바이트가 그대로 나온다
        assertThat(fetch(src.getSrcSn(), reviewerToken)).isEqualTo(RAW_BYTES);
    }

    @Test
    @DisplayName("비식별_분기_서브트리_안_심링크는_실경로_대상을_그대로_서빙한다")
    void deidSymlinkInsideSubtreeServesRealTarget() throws Exception {
        // given — frames/deid/** 안에서만 도는 심링크(서브트리 이탈 없음)
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_PRVC);
        String targetRel = writeDeidFile(raw.getRawSn(), 10);
        Path target = deidBase.resolve(targetRel).normalize();
        String linkRel = "frames/deid/" + raw.getRawSn() + "/" + unique + "-inside.jpg";
        Path link = deidBase.resolve(linkRel).normalize();
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 가드는 검증 대상 외
        }
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 10, null, null, linkRel, LocalDateTime.now()));

        // when / then — 판정한 실경로로 열리므로 200 + 비식별 바이트(NOFOLLOW 가 정상 케이스를 깨지 않는다)
        assertThat(fetch(src.getSrcSn(), reviewerToken)).isEqualTo(DEID_BYTES);
    }

    @Test
    @DisplayName("비식별_경로가_심링크면_거부")
    void deidSymlinkEscapingSubtreeRejected() throws Exception {
        // given — frames/deid/** 안의 심링크가 원본 프레임을 가리키는 우회(CWE-59)
        LsDataRaw raw = seedRaw(LsDataRaw.PRVC_TYPE_PRVC);
        String rawRel = writeRawFile(raw.getRawSn(), 7);
        Path target = rawBase.resolve(rawRel).normalize();
        String linkRel = "frames/deid/" + raw.getRawSn() + "/" + unique + "-link.jpg";
        Path link = deidBase.resolve(linkRel).normalize();
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 가드는 검증 대상 외
        }
        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 7, null, null, linkRel, LocalDateTime.now()));

        // when / then — 실경로 기준 서브트리 판정이 원본 노출을 차단
        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }
}
