package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FfmpegFrameExtractor 비식별 프레임 경로 <b>DB 영속 회귀 방지</b> 통합 테스트 (DEV_FIX).
 *
 * <p><b>버그(dev cudo_246 실측):</b> 검수 승인 export 가 항상 PARTIAL(비식별 프레임 0장)로 끝났다.
 * 원인은 {@link FfmpegFrameExtractor} 가 프레임 row 를 비식별 경로 없이 INSERT 한 뒤, 같은 처리 흐름에서
 * {@code src.attachDeidPath(...)} setter dirty-update 로 나중에 채우던 옛 패턴이었다. 프로덕션 경로
 * ({@link FfmpegFrameExtractor#execute}) 는 {@code execute()} 가 {@code this.extractByMarks(...)} 를
 * <b>self-invocation</b> 하므로 Spring AOP 프록시가 가로채지 못해 {@code extractByMarks} 의
 * {@code REQUIRES_NEW} 트랜잭션이 적용되지 않는다. ambient 트랜잭션이 없으면 Spring Data {@code save()}
 * 가 각자 짧은 트랜잭션으로 즉시 커밋되어 엔티티가 <b>detached</b> 되고, 이후 {@code attachDeidPath}
 * dirty-update 는 어떤 트랜잭션에도 flush 되지 않아 {@code DE_IDNTF_SRC_FILE_PATH_NM} 이 NULL 로 남는다.
 * export 는 이 컬럼(DB)만 신뢰하므로 비식별 프레임을 전부 스킵 → PARTIAL.
 *
 * <p><b>검증:</b> 비식별 procLog(SUCCEEDED + base 하위 실존 deid 영상)가 있는 상태에서 프로덕션 진입점
 * {@code execute()} 를 <b>ambient 트랜잭션 없이</b> 호출한 뒤, <b>별도 트랜잭션에서 DB 재조회</b>
 * (findByRawSn)한 프레임의 {@code deIdntfSrcFilePathNm} 이 모두 non-null 임을 단언한다. 수정 전 코드였다면
 * detached 엔티티의 dirty-update 미반영으로 NULL 이라 FAIL 한다(RED). 수정(6-arg create 로 INSERT 시점에
 * 함께 저장)으로 GREEN.
 *
 * <p>RAW-only 분기(비식별 procLog 부재)에서는 {@code deIdntfSrcFilePathNm} 이 NULL 로 유지되며 예외 없이
 * 성공하는 기존 동작도 함께 검증한다(무회귀).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {"spring.quartz.auto-startup=false"})
class FfmpegFrameExtractorDeidPersistIT {

    /** 원본/비식별 프레임 base — 프로덕션(/nas-storage)처럼 동일 경로 1개로 주입한다. */
    private static final Path STORAGE_BASE = createTempBase();

    private static Path createTempBase() {
        try {
            return Files.createTempDirectory("ffx-deid-it").toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.raw-path", STORAGE_BASE::toString);
        registry.add("authoring.storage.deidentified-path", STORAGE_BASE::toString);
    }

    /** 실제 ffmpeg 바이너리 의존 격리 — 더미 파일을 쓰는 스텁으로 대체. */
    @MockBean
    private FfmpegFrameExtractor.FrameWriter frameWriter;

    @Autowired
    private FfmpegFrameExtractor extractor;

    @Autowired
    private LsDataSrcRepository srcRepository;

    @Autowired
    private LsDeidentProcLogRepository deidentProcLogRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private LsMarkingRepository markingRepository;

    private final TransactionTemplate txTemplate;

    private Long rawSn;

    FfmpegFrameExtractorDeidPersistIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void tearDown() {
        if (rawSn != null) {
            Long sn = rawSn;
            txTemplate.executeWithoutResult(s -> {
                srcRepository.findByRawSnOrderByFrameNoAsc(sn).forEach(f -> srcRepository.deleteById(f.getSrcSn()));
                deidentProcLogRepository.findLatestSuccessByDataRawSn(sn)
                        .ifPresent(l -> deidentProcLogRepository.deleteById(l.getProcLogSn()));
                markingRepository.findAll().stream()
                        .filter(m -> sn.equals(m.getRawSn()))
                        .forEach(m -> markingRepository.deleteById(m.getMarkingSn()));
                videoRepository.deleteById(sn);
            });
        }
    }

    private void stubFrameWriter() throws IOException {
        when(frameWriter.sourceExists(any())).thenAnswer(inv -> Files.exists((Path) inv.getArgument(0)));
        // writeFrame: 부모 디렉토리 생성 후 더미 프레임 파일을 쓴다(체크섬 계산이 파일을 읽으므로 실제 파일 필요).
        org.mockito.Mockito.doAnswer(inv -> {
            Path out = inv.getArgument(1);
            if (out.getParent() != null && !Files.exists(out.getParent())) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, ("frame-" + inv.getArgument(2)).getBytes());
            return null;
        }).when(frameWriter).writeFrame(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** 원본 영상 파일 + LsDataRaw(deIdntfYn=Y) 시드 → rawSn 반환. */
    private Long seedRaw() throws IOException {
        Path rawVideo = STORAGE_BASE.resolve("src").resolve("clip-" + System.nanoTime() + ".mp4");
        Files.createDirectories(rawVideo.getParent());
        Files.write(rawVideo, new byte[]{0, 0, 0});
        return txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "CLIP-DEID-" + System.nanoTime(), "CCTV-001", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, rawVideo.toString(), null, 60);
            raw.markDeidentified("Y");
            raw.markMarkingReady();
            return videoRepository.save(raw).getRawSn();
        });
    }

    /** SUCCEEDED 비식별 procLog + base 하위 실존 deid 영상 파일 시드. */
    private void seedSucceededDeidLog(Long sn) throws IOException {
        Path deidVideo = STORAGE_BASE.resolve("videos").resolve(String.valueOf(sn)).resolve("clip-deid.mp4");
        Files.createDirectories(deidVideo.getParent());
        Files.write(deidVideo, new byte[]{0, 0, 0});
        txTemplate.executeWithoutResult(s -> {
            LsDeidentProcLog log = LsDeidentProcLog.request(sn, "req-" + sn,
                    STORAGE_BASE.resolve("src").resolve("orig.mp4").toString(), "tester");
            log.succeed(deidVideo.toString());
            deidentProcLogRepository.save(log);
        });
    }

    /** fps pin 을 담은 마킹 시드 — resolveFps(ffprobe) 재조회를 우회한다. */
    private void seedMarking(Long sn, Path rawVideoPath) {
        txTemplate.executeWithoutResult(s -> {
            LsMarking marking = LsMarking.createAuto(sn, 150,
                    "[{\"frameIndex\":0,\"timestamp\":\"00:00\"},{\"frameIndex\":150,\"timestamp\":\"00:05\"}]",
                    "1", 30.0);
            markingRepository.save(marking);
        });
    }

    private BatchContext buildCtx(Long sn) {
        LsDataRaw raw = txTemplate.execute(s -> videoRepository.findById(sn).orElseThrow());
        List<LsMarking> markings = txTemplate.execute(s ->
                markingRepository.findAll().stream().filter(m -> sn.equals(m.getRawSn())).toList());
        BatchContext ctx = new BatchContext(sn, raw);
        ctx.setMarkings(markings);
        ctx.setMarks(List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05")));
        return ctx;
    }

    @Test
    @DisplayName("execute_프로덕션경로_비식별프레임경로가_DB에_실제_영속된다_재조회_non_null")
    void execute_persistsDeidFramePathToDatabase() throws IOException {
        // given — 비식별 완료 영상 + SUCCEEDED procLog(base 하위 deid 영상 실존) + fps pin 마킹.
        stubFrameWriter();
        rawSn = seedRaw();
        LsDataRaw seeded = txTemplate.execute(s -> videoRepository.findById(rawSn).orElseThrow());
        seedSucceededDeidLog(rawSn);
        seedMarking(rawSn, Path.of(seeded.getRawFilePathNm()));
        BatchContext ctx = buildCtx(rawSn);

        // 사전 조건: ambient 트랜잭션 없음(프로덕션 비트랜잭션 오케스트레이션과 동일 — self-invocation 재현 핵심).
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        // when — 프로덕션 진입점 execute() 호출(내부에서 extractByMarks self-invocation).
        extractor.execute(ctx);

        // then — 별도 트랜잭션에서 DB 재조회. 수정 전이면 dirty-update 미반영으로 NULL(FAIL), 수정 후 non-null.
        List<LsDataSrc> reloaded = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded)
                .as("비식별 프레임 경로가 DB(DE_IDNTF_SRC_FILE_PATH_NM)에 실제 영속되어야 한다 — export PARTIAL 회귀 방지")
                .allMatch(f -> f.getDeIdntfSrcFilePathNm() != null && !f.getDeIdntfSrcFilePathNm().isBlank());
        // 스킴 A 세그먼트 확인: 원본=frames/raw, 비식별=frames/deid 로 분기.
        assertThat(reloaded).allMatch(f -> f.getSrcFilePathNm().replace('\\', '/').contains("/frames/raw/"));
        assertThat(reloaded).allMatch(f -> f.getDeIdntfSrcFilePathNm().replace('\\', '/').contains("/frames/deid/"));
    }

    /**
     * ★★ DEV_FIX 3라운드 — <b>재사용 분기의 비식별 경로 백필이 실제로 DB 에 커밋되는지</b> 고정한다 (@req R1).
     *
     * <h3>왜 IT 가 필요한가 (단위 테스트로는 증명 불가)</h3>
     * <p>백필은 새 행을 만들지 않고 <b>관리 엔티티의 dirty-update</b>({@code attachDeidPath})로 반영된다.
     * {@code FfmpegFrameExtractorTest} 는 {@code srcRepository} 를 mock 하므로 그 {@code existing} 은 그냥
     * POJO 다 — <b>flush 여부와 무관하게</b> 필드 단언이 통과한다. 그런데 이 클래스 상단 Javadoc 이 인용하는
     * 선례가 바로 그 함정이다: <i>"신규 INSERT 직후 같은 트랜잭션에서 attachDeidPath setter dirty-update 로
     * 채우던 옛 패턴은 컬럼이 DB 에 반영되지 않아 export 가 PARTIAL 이 됐다"</i>. 같은 계열의 무증상 결손을
     * 반복하지 않도록 <b>실 DB 재조회</b>로 컬럼을 확인한다.
     *
     * <h3>시나리오 (재시도 회차 재현)</h3>
     * <p>1회차는 비식별 procLog 없이 돌려 프레임을 {@code DE_IDNTF_SRC_FILE_PATH_NM=NULL} 로 적재한다
     * (RAW only). 그 뒤 비식별 procLog·영상을 시드하고 <b>같은 영상으로 다시</b> {@code execute} 한다
     * (자동 재시도 큐가 하는 일과 동일). 이때 기존 행은 <b>위치가 검증된</b>({@code VDO_FRM_NO} non-null,
     * 마킹과 일치) 재사용이므로 백필 대상이며, {@code SRC_SN} 이 보존된 채 경로만 채워져야 한다.
     */
    @Test
    @DisplayName("재사용_백필의_비식별_경로가_DB에_실제_커밋된다_SRC_SN_보존")
    void reuseBackfill_persistsDeidPathToDatabase_preservingSrcSn() throws IOException {
        // given — 1회차: 비식별 procLog 부재 → RAW only 로 프레임 2건 적재.
        stubFrameWriter();
        rawSn = seedRaw();
        LsDataRaw seeded = txTemplate.execute(s -> videoRepository.findById(rawSn).orElseThrow());
        seedMarking(rawSn, Path.of(seeded.getRawFilePathNm()));
        extractor.execute(buildCtx(rawSn));

        List<LsDataSrc> afterFirst = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(afterFirst).hasSize(2);
        assertThat(afterFirst).allMatch(f -> f.getDeIdntfSrcFilePathNm() == null);
        List<Long> srcSnsBefore = afterFirst.stream().map(LsDataSrc::getSrcSn).toList();

        // when — 비식별 영상이 이제 보이고, 재시도가 같은 영상을 다시 돌린다.
        seedSucceededDeidLog(rawSn);
        extractor.execute(buildCtx(rawSn));

        // then — 별도 트랜잭션 재조회에서 컬럼이 <b>실제로</b> 채워져 있어야 한다(dirty-update 반영).
        List<LsDataSrc> afterRetry = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(afterRetry)
                .as("재사용 프레임 수가 늘면 중복 INSERT 다(UNIQUE 제약·라벨 고아)")
                .hasSize(2);
        assertThat(afterRetry)
                .as("dirty-update 가 DB 에 flush 되지 않으면 export 가 영구 PARTIAL 이다(선례 재발)")
                .allMatch(f -> f.getDeIdntfSrcFilePathNm() != null && !f.getDeIdntfSrcFilePathNm().isBlank());
        assertThat(afterRetry).allMatch(f -> f.getDeIdntfSrcFilePathNm().replace('\\', '/').contains("/frames/deid/"));
        // SRC_SN 보존 — 새 PK 가 발급되면 그 프레임에 달린 라벨이 고아가 된다.
        assertThat(afterRetry).extracting(LsDataSrc::getSrcSn)
                .as("재사용은 기존 SRC_SN 을 그대로 유지해야 한다")
                .containsExactlyElementsOf(srcSnsBefore);
        // 비식별 이미지 파일도 실재해야 한다(경로만 채우고 파일이 없으면 404 로 이어진다).
        assertThat(afterRetry).allMatch(f -> Files.exists(Path.of(f.getDeIdntfSrcFilePathNm())));
    }

    @Test
    @DisplayName("execute_비식별_procLog없으면_RAW만_영속되고_비식별경로는_NULL_유지_무회귀")
    void execute_rawOnly_whenNoDeidLog_persistsNullDeidPath() throws IOException {
        // given — 비식별 완료(deIdntfYn=Y) 됐으나 procLog 부재 → RAW only graceful.
        stubFrameWriter();
        rawSn = seedRaw();
        LsDataRaw seeded = txTemplate.execute(s -> videoRepository.findById(rawSn).orElseThrow());
        seedMarking(rawSn, Path.of(seeded.getRawFilePathNm()));
        BatchContext ctx = buildCtx(rawSn);

        // when — 예외 없이 성공.
        extractor.execute(ctx);

        // then — DB 재조회 시 비식별 경로는 NULL 유지(무회귀).
        List<LsDataSrc> reloaded = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded).allMatch(f -> f.getDeIdntfSrcFilePathNm() == null);
    }
}
