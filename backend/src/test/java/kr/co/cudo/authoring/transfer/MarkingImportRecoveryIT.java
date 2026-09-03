package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobArtclRepository;
import kr.co.cudo.authoring.transfer.service.MarkingImportItemProcessor;
import kr.co.cudo.authoring.transfer.service.MarkingImportJobMeta;
import kr.co.cudo.authoring.transfer.service.MarkingImportRecoveryTxService;
import kr.co.cudo.authoring.transfer.service.MarkingImportJobTxService;
import kr.co.cudo.authoring.transfer.service.MarkingImportWorkerDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멈춘 일괄 적재의 <b>재기동 복구</b>를 실제 원장 위에서 고정한다(AC-1033).
 *
 * <h3>이 축은 시험이 없으면 검증 대상이 0 이다</h3>
 * <p>복구는 「노드가 처리 도중 다시 떴을 때」만 드러나는 경로라 정상 동선을 아무리 돌려도 지나가지
 * 않는다. 그런데 이 경로가 없으면 <b>작업이 영원히 끝나지 않고</b>, 있어도 마감이 빠지면
 * <b>같은 항목이 처리중과 대기 사이를 영원히 오간다</b>. 둘 다 조용한 고착이라 오류로 드러나지 않는다.
 *
 * <h3>복구 잡의 스케줄은 시험에서 꺼 둔다</h3>
 * <p>발화에 기대면 시험이 시각에 의존해 불안정해지고, 켜 두면 이 잡이 자기 스케줄링을 등록해 캐시된
 * 모든 컨텍스트에서 남의 잡까지 함께 깨운다. 그래서 잡 메서드를 <b>직접 부른다</b> — 이 저장소의
 * 기존 스윕 잡들과 같은 방식이다.
 *
 * <h3>{@code @SpringBootTest} 설정은 다른 이관 통합 시험과 글자 그대로 같다</h3>
 * <p>다르게 쓰면 캐시되는 스프링 컨텍스트가 하나 더 늘어난다.
 *
 * @design DOMAIN-017
 * @design API-217
 * @design AC-1033
 * @design SEQ-030
 */
@SpringBootTest(properties = "authoring.storage.external-read-roots=../docs")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MarkingImportRecoveryIT {

    /** 이 시험이 만드는 작업을 가리키는 표식 — 정리 대상을 한 눈에 가릴 수 있게 고유 대역을 쓴다. */
    private static final String FOLDER = "/tmp/marking-recovery-it";

    /** 처리 중인 채로 멈춘 지 이만큼 지난 것으로 꾸민다 — 임계보다 확실히 커야 한다. */
    private static final String STALE_AGE = "1 day";

    /** 실물 파일을 두는 자리 — 허용된 저장소 범위 안이어야 복사가 시작된다. */
    private static final String ASSET_FOLDER = ".marking-heartbeat-it";

    /** 이 시험이 만드는 영상 파일 이름의 뿌리. */
    private static final String ASSET_CLIP = "HBIT0001";

    /** 진행이 끝나기를 기다리는 상한(ms). */
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    @Autowired private MarkingImportRecoveryTxService recoveryJob;
    @Autowired private MarkingImportJobTxService jobTxService;
    @Autowired private LsEblcUldJobArtclRepository artclRepository;
    @Autowired private MarkingImportItemProcessor itemProcessor;
    @Autowired private MarkingImportWorkerDispatcher dispatcher;
    @Autowired private MarkingImportProperties properties;

    @Value("${authoring.storage.raw-path}") private String storageRawPath;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        txTemplate = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ------------------------------------------------------------------ 되돌리기

    @Test
    @DisplayName("처리_중인_채로_오래_멈춘_항목을_다시_집을_수_있게_되돌리고_횟수를_올린다")
    void 처리_중인_채로_오래_멈춘_항목을_다시_집을_수_있게_되돌리고_횟수를_올린다() {
        long jobSn = openJob(1);
        long artclSn = addItem(jobSn, "a.json", "PROCESSING", 0);
        makeStale(artclSn);

        assertThat(recoveryJob.reclaimStale()).isEqualTo(1);

        Map<String, Object> row = itemOf(artclSn);
        // 되돌리지 않으면 그 작업은 영원히 끝나지 않는다.
        assertThat(row.get("artcl_stts_cd")).isEqualTo("PENDING");
        // 횟수를 올리지 않으면 같은 항목이 처리중과 대기 사이를 영원히 오간다.
        assertThat(row.get("rtry_nmtm")).isEqualTo(1);
        assertThat(row.get("bgng_dt")).isNull();
    }

    @Test
    @DisplayName("★임계_안에_있는_항목은_빼앗지_않는다")
    void 임계_안에_있는_항목은_빼앗지_않는다() {
        // ★지금 돌고 있는 처리를 빼앗으면 같은 영상을 두 건이 동시에 적재하려 든다. 마지막 방어선은
        //  영상 식별자의 유일 제약이라 두 번 적재되지는 않지만, 정상 항목 하나가 「이미 있음」으로
        //  건너뛰어져 사람이 원인을 알기 어렵다.
        long jobSn = openJob(1);
        long artclSn = addItem(jobSn, "fresh.json", "PROCESSING", 0);

        assertThat(recoveryJob.reclaimStale()).isZero();
        assertThat(itemOf(artclSn).get("artcl_stts_cd")).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("끝난_항목은_되돌리지_않는다")
    void 끝난_항목은_되돌리지_않는다() {
        // 되돌리면 이미 적재된 영상을 한 번 더 적재하려 든다.
        long jobSn = openJob(3);
        long success = addItem(jobSn, "s.json", "SUCCESS", 0);
        long failed = addItem(jobSn, "f.json", "FAILED", 0);
        long skipped = addItem(jobSn, "k.json", "SKIPPED", 0);
        makeStale(success);
        makeStale(failed);
        makeStale(skipped);

        assertThat(recoveryJob.reclaimStale()).isZero();
        assertThat(itemOf(success).get("artcl_stts_cd")).isEqualTo("SUCCESS");
        assertThat(itemOf(failed).get("artcl_stts_cd")).isEqualTo("FAILED");
        assertThat(itemOf(skipped).get("artcl_stts_cd")).isEqualTo("SKIPPED");
    }

    // ------------------------------------------------------------------ 마감

    @Test
    @DisplayName("★되돌리기와_마감은_같은_항목을_동시에_집지_않는다")
    void 되돌리기와_마감은_같은_항목을_동시에_집지_않는다() {
        // 두 조건이 겹치면 어느 쪽이 먼저 도느냐에 따라 결과가 달라져, 같은 형상에서 실행마다
        // 다른 답이 나온다. 겹치지 않는 것이 두 조건의 계약이다.
        int maxRetry = properties.effectiveMaxRetry();
        long jobSn = openJob(2);
        long retriable = addItem(jobSn, "retriable.json", "PROCESSING", maxRetry - 1);
        long exhausted = addItem(jobSn, "exhausted.json", "PROCESSING", maxRetry);
        makeStale(retriable);
        makeStale(exhausted);

        assertThat(recoveryJob.reclaimStale()).isEqualTo(1);
        assertThat(recoveryJob.failExhausted()).isEqualTo(1);

        // 아직 시도할 여지가 남은 항목은 다시 집힌다.
        assertThat(itemOf(retriable).get("artcl_stts_cd")).isEqualTo("PENDING");
        assertThat(itemOf(retriable).get("rtry_nmtm")).isEqualTo(maxRetry);
        // 다 쓴 항목은 실패로 마감된다 — 마감이 없으면 작업이 끝나지 않는다.
        assertThat(itemOf(exhausted).get("artcl_stts_cd")).isEqualTo("FAILED");
        assertThat(String.valueOf(itemOf(exhausted).get("fail_rsn"))).contains("횟수");
    }

    @Test
    @DisplayName("마감_사유는_사람이_읽고_무엇을_고쳐야_하는지_알_수_있는_문장이다")
    void 마감_사유는_사람이_읽고_무엇을_고쳐야_하는지_알_수_있는_문장이다() {
        long jobSn = openJob(1);
        long artclSn = addItem(jobSn, "x.json", "PROCESSING", properties.effectiveMaxRetry());
        makeStale(artclSn);

        recoveryJob.failExhausted();

        Object reason = itemOf(artclSn).get("fail_rsn");
        // ★사유가 <있어야> 한다. 없으면 아래 「내부 구조가 없다」 단언이 언제나 참이 되어
        //  마감이 아예 일어나지 않아도 통과한다.
        assertThat(reason).isNotNull();
        assertThat(String.valueOf(reason)).contains("횟수")
                // 내부 구조를 드러내지 않는다(CWE-209) — 클래스명·경로·예외 종류가 실리면 안 된다.
                .doesNotContain("Exception").doesNotContain("/").doesNotContain("kr.co.cudo");
    }

    // ------------------------------------------------------------------ 일꾼 다시 띄우기

    @Test
    @DisplayName("진행중인_작업에_일꾼을_다시_띄운다")
    void 진행중인_작업에_일꾼을_다시_띄운다() {
        // 노드가 다시 뜨면 작업 행은 남지만 일꾼은 사라진다. 띄우지 않으면 대기 항목이 그대로 남는다.
        long jobSn = openJob(1);
        addItem(jobSn, "pending.json", "PENDING", 0);

        assertThat(recoveryJob.resumeRunningJobs()).isPositive();
    }

    @Test
    @DisplayName("종결된_작업에는_일꾼을_띄우지_않는다")
    void 종결된_작업에는_일꾼을_띄우지_않는다() {
        long jobSn = openJob(1);
        addItem(jobSn, "done.json", "SUCCESS", 0);
        jdbc.update("UPDATE ls_eblc_uld_job SET job_stts_cd = 'COMPLETED' WHERE eblc_uld_job_sn = ?", jobSn);

        assertThat(recoveryJob.resumeRunningJobs()).isZero();
    }

    // ------------------------------------------------------------------ 항목별 격리

    @Test
    @DisplayName("한_항목이_실패해도_나머지가_이어지고_작업은_실패로_종결된다")
    void 한_항목이_실패해도_나머지가_이어지고_작업은_실패로_종결된다() throws Exception {
        // 항목마다 따로 트랜잭션이 아니면 한 건 때문에 전부 되돌아간다(SEQ-030). 여기서는 두 항목이
        // 모두 열 수 없는 위치를 가리키므로 각자 실패로 마감되고, 그 자체가 <건별 마감이 성립한다>는
        // 증거다 — 한 트랜잭션이면 마감 기록조차 남지 않는다.
        long jobSn = openJob(2);
        addItem(jobSn, "missing-1.json", "PENDING", 0);
        addItem(jobSn, "missing-2.json", "PENDING", 0);

        dispatcher.dispatch(jobSn, properties.effectiveConcurrency());
        awaitFinished(jobSn);

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT job_stts_cd, scs_nocs, fail_nocs FROM ls_eblc_uld_job WHERE eblc_uld_job_sn = ?", jobSn);
        assertThat(job.get("job_stts_cd")).isEqualTo("FAILED");
        assertThat(job.get("scs_nocs")).isEqualTo(0);
        assertThat(job.get("fail_nocs")).isEqualTo(2);

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT artcl_stts_cd, fail_rsn FROM ls_eblc_uld_job_artcl WHERE eblc_uld_job_sn = ?", jobSn);
        assertThat(items).hasSize(2);
        // 짝을 찾지 못한 것이 아니라 <처리하다 멈춘> 것이므로 실패다 — 건너뜀과 구분한다.
        assertThat(items).allSatisfy(row -> {
            assertThat(row.get("artcl_stts_cd")).isEqualTo("FAILED");
            assertThat(row.get("fail_rsn")).isNotNull();
        });
    }

    @Test
    @DisplayName("짝을_찾지_못한_항목은_실패가_아니라_건너뜀으로_마감된다")
    void 짝을_찾지_못한_항목은_실패가_아니라_건너뜀으로_마감된다() throws Exception {
        // 합치면 「이미 들어와 있어서 넘어감」이 「적재 실패」로 집계되어 사람이 무엇을 고쳐야 하는지
        // 알 수 없다(ERD-032 가 두 값을 나눈 이유).
        long jobSn = openJob(1);
        jdbc.update("INSERT INTO ls_eblc_uld_job_artcl "
                + "(eblc_uld_job_sn, mark_file_path_nm, vdo_file_path_nm, artcl_stts_cd, rtry_nmtm) "
                + "VALUES (?, ?, NULL, 'PENDING', 0)", jobSn, FOLDER + "/unpaired.json");

        dispatcher.dispatch(jobSn, properties.effectiveConcurrency());
        awaitFinished(jobSn);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT artcl_stts_cd, fail_rsn FROM ls_eblc_uld_job_artcl WHERE eblc_uld_job_sn = ?", jobSn);
        assertThat(row.get("artcl_stts_cd")).isEqualTo("SKIPPED");
        assertThat(String.valueOf(row.get("fail_rsn"))).contains("영상");
    }

    // ------------------------------------------------------------------ 처리 중 신호 (핵심)

    @Test
    @DisplayName("★★복사가_처리_중_신호를_적어_되돌리기가_그_항목을_집지_못한다")
    void 복사가_처리_중_신호를_적어_되돌리기가_그_항목을_집지_못한다() throws IOException {
        // ★「신호를 불렀다」가 아니라 <갱신 시각이 실제로 밀려 되돌리기가 집지 못한다>를 겨눈다.
        //  호출만 단언하면 그 호출이 원장에 닿는지, 되돌리기 술어가 그 값을 보는지가 검증되지 않는다.
        Path assets = prepareAssets();
        long jobSn = openJob(1);
        long artclSn = addRealItem(jobSn, assets);
        assertThat(jobTxService.claimNext(jobSn)).isPresent();

        // 집은 지 오래돼 회수 대상이 된 상태로 만든다 — 복사가 신호를 적지 않으면 여기서 뺏긴다.
        makeStale(artclSn);
        assertThat(itemOf(artclSn).get("artcl_stts_cd")).isEqualTo("PROCESSING");

        // 적재는 지정값이 규칙에 맞지 않아 실패로 끝난다 — <복사까지는 반드시 일어나고> 그 뒤 단계가
        // 영상 행을 만들지 않아 이 시험이 남기는 것이 없다. 보려는 것은 복사 구간의 신호다.
        MarkingImportItemProcessor.ItemOutcome outcome =
                itemProcessor.process(loadItem(artclSn), badMeta(), "admin");
        assertThat(outcome.succeeded()).isFalse();

        // ★신호가 갱신 시각을 밀었으므로 되돌리기가 이 항목을 집지 못한다.
        assertThat(recoveryJob.reclaimStale()).isZero();
        Map<String, Object> row = itemOf(artclSn);
        assertThat(row.get("artcl_stts_cd")).isEqualTo("PROCESSING");
        assertThat(row.get("rtry_nmtm")).isEqualTo(0);
    }

    @Test
    @DisplayName("신호가_없었다면_되돌리기가_그_항목을_집는다")
    void 신호가_없었다면_되돌리기가_그_항목을_집는다() throws IOException {
        // 위 시험의 <대조군>이다. 이것이 없으면 「집지 못한다」가 신호 덕인지, 애초에 되돌리기가
        // 그 항목을 볼 수 없는 형상이었는지 구분되지 않는다(가드가 조용히 공회전하는 형태).
        Path assets = prepareAssets();
        long jobSn = openJob(1);
        long artclSn = addRealItem(jobSn, assets);
        assertThat(jobTxService.claimNext(jobSn)).isPresent();
        makeStale(artclSn);

        // 복사를 돌리지 않는다 — 그 차이 하나만 다르다.
        assertThat(recoveryJob.reclaimStale()).isEqualTo(1);
        assertThat(itemOf(artclSn).get("artcl_stts_cd")).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ 원자 클레임

    @Test
    @DisplayName("★같은_항목을_두_번_집는_것은_집기_자체가_막는다")
    void 같은_항목을_두_번_집는_것은_집기_자체가_막는다() {
        // ★후보 조회를 거치지 않고 <집기만> 두 번 부른다.
        //  후보 조회를 거치면 첫 집기 이후 그 항목이 후보 목록에서 빠져 <조회가 대신 막아 준다> —
        //  그러면 집기의 조건을 통째로 지워도 시험이 통과해 이 가드가 조용히 공회전한다.
        //  실제 경합은 두 노드가 <같은 후보 목록>을 이미 손에 든 뒤에 일어나므로, 막는 주체가
        //  조회가 아니라 집기여야 한다.
        long jobSn = openJob(1);
        long artclSn = addItem(jobSn, "only.json", "PENDING", 0);
        LocalDateTime now = LocalDateTime.now();

        assertThat(inTx(() -> artclRepository.claim(artclSn, now))).isEqualTo(1);
        assertThat(inTx(() -> artclRepository.claim(artclSn, now))).isZero();
    }

    @Test
    @DisplayName("이미_끝난_항목은_다시_집히지_않는다")
    void 이미_끝난_항목은_다시_집히지_않는다() {
        // 되돌리기 잡이 항목을 대기로 되돌린 직후 원래 일꾼이 살아 돌아와도, 그 일꾼은 이미 다른
        // 일꾼이 집어 간 항목을 덮어쓰지 못한다 — 마감도 같은 이유로 조건부다.
        long jobSn = openJob(1);
        long artclSn = addItem(jobSn, "done.json", "SUCCESS", 0);

        assertThat(inTx(() -> artclRepository.claim(artclSn, LocalDateTime.now()))).isZero();
        assertThat(inTx(() -> artclRepository.markSuccess(artclSn, 1L, LocalDateTime.now()))).isZero();
        assertThat(itemOf(artclSn).get("artcl_stts_cd")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("집기는_다음_대기_항목_하나에만_소유권을_준다")
    void 집기는_다음_대기_항목_하나에만_소유권을_준다() {
        long jobSn = openJob(1);
        addItem(jobSn, "only.json", "PENDING", 0);

        assertThat(jobTxService.claimNext(jobSn)).isPresent();
        assertThat(jobTxService.claimNext(jobSn)).isEmpty();
        assertThat(jobTxService.hasPending(jobSn)).isFalse();
    }

    // ------------------------------------------------------------------ 보조

    /** 실물 마킹 문서와 영상을 만든다 — 복사가 실제로 일어나야 신호가 나간다. */
    private Path prepareAssets() throws IOException {
        Path base = docsRoot().resolve(ASSET_FOLDER);
        deleteRecursively(base);
        Files.createDirectories(base);
        Files.writeString(base.resolve(ASSET_CLIP + ".json"), """
                [{"id":1,"video_name":"%s.mp4","notes":"이벤트",
                  "images":[{"filename":"a.jpg","frame":100,"time":"00:00:10.000"},
                            {"filename":"b.jpg","frame":400,"time":"00:00:20.000"}]}]
                """.formatted(ASSET_CLIP), StandardCharsets.UTF_8);
        Files.writeString(base.resolve(ASSET_CLIP + ".mp4"), "video", StandardCharsets.UTF_8);
        return base.toRealPath();
    }

    private long addRealItem(long jobSn, Path assets) {
        Long artclSn = jdbc.queryForObject(
                "INSERT INTO ls_eblc_uld_job_artcl "
                        + "(eblc_uld_job_sn, mark_file_path_nm, vdo_file_path_nm, artcl_stts_cd, rtry_nmtm) "
                        + "VALUES (?, ?, ?, 'PENDING', 0) RETURNING eblc_uld_job_artcl_sn",
                Long.class, jobSn,
                assets.resolve(ASSET_CLIP + ".json").toString(),
                assets.resolve(ASSET_CLIP + ".mp4").toString());
        return artclSn;
    }

    private LsEblcUldJobArtcl loadItem(long artclSn) {
        return artclRepository.findById(artclSn).orElseThrow();
    }

    /**
     * 적재 단계에서 거부되는 지정값 — 복사는 일어나되 영상 행은 만들어지지 않는다.
     *
     * <p>이 시험이 보려는 것은 복사 구간의 신호뿐이라, 뒤 단계가 남기는 것이 없게 만든다.
     */
    private static MarkingImportJobMeta badMeta() {
        return new MarkingImportJobMeta("EV1", "4113500000", "CCTV_IT_0001", "PRVC", null, null);
    }

    private static Path docsRoot() {
        return Paths.get("..", "docs");
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // 정리 실패는 시험 결과를 바꾸지 않는다 — 다음 실행이 다시 지운다.
        }
    }

    private long openJob(int targetCount) {
        Long jobSn = jdbc.queryForObject(
                "INSERT INTO ls_eblc_uld_job (orgnl_fldr_path_nm, trgt_nocs, dmnd_cn) VALUES (?, ?, ?) "
                        + "RETURNING eblc_uld_job_sn", Long.class, FOLDER, targetCount, jobMetaJson());
        return jobSn;
    }

    /** 작업 행에 담아 두는 지정값 — 이것이 없으면 일꾼이 무엇을 붙여 적재할지 알 수 없다. */
    private static String jobMetaJson() {
        return "{\"eventTypeCd\":\"EV01000101\",\"localGovCd\":\"4113500000\","
                + "\"cctvId\":\"CCTV_IT_0001\",\"prvcTypeCd\":\"PRVC\",\"capturedAt\":null,\"historySn\":null}";
    }

    private long addItem(long jobSn, String markName, String status, int retry) {
        Long artclSn = jdbc.queryForObject(
                "INSERT INTO ls_eblc_uld_job_artcl "
                        + "(eblc_uld_job_sn, mark_file_path_nm, vdo_file_path_nm, artcl_stts_cd, rtry_nmtm) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING eblc_uld_job_artcl_sn",
                Long.class, jobSn, FOLDER + "/" + markName, FOLDER + "/" + markName + ".mp4",
                status, retry);
        return artclSn;
    }

    /** 처리 중인 채로 오래 멈춘 것으로 꾸민다 — 시각을 뒤로 미는 것이 유일한 재현 수단이다. */
    private void makeStale(long artclSn) {
        jdbc.update("UPDATE ls_eblc_uld_job_artcl SET mdfcn_dt = now() - interval '" + STALE_AGE + "' "
                + "WHERE eblc_uld_job_artcl_sn = ?", artclSn);
    }

    /** 각 호출을 <서로 다른 트랜잭션>으로 돌린다 — 두 노드가 따로 집는 상황과 같은 모양이다. */
    private int inTx(java.util.function.IntSupplier action) {
        Integer result = txTemplate.execute(status -> action.getAsInt());
        return result == null ? 0 : result;
    }

    private Map<String, Object> itemOf(long artclSn) {
        return jdbc.queryForMap("SELECT artcl_stts_cd, rtry_nmtm, fail_rsn, bgng_dt "
                + "FROM ls_eblc_uld_job_artcl WHERE eblc_uld_job_artcl_sn = ?", artclSn);
    }

    private void awaitFinished(long jobSn) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT job_stts_cd FROM ls_eblc_uld_job WHERE eblc_uld_job_sn = ?", String.class, jobSn);
            if (!"RUNNING".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private void cleanup() {
        deleteRecursively(docsRoot().resolve(ASSET_FOLDER));
        deleteRecursively(Paths.get(storageRawPath).resolve("imports").resolve(ASSET_CLIP));
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id = '" + ASSET_CLIP + "'");
        jdbc.update("DELETE FROM ls_eblc_uld_job_artcl WHERE eblc_uld_job_sn IN "
                + "(SELECT eblc_uld_job_sn FROM ls_eblc_uld_job WHERE orgnl_fldr_path_nm = ?)", FOLDER);
        jdbc.update("DELETE FROM ls_eblc_uld_job WHERE orgnl_fldr_path_nm = ?", FOLDER);
    }
}
