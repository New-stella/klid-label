package kr.co.cudo.authoring.dataset.repository;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 통합 메타 저장소 — {@code LS_DATASET_VIDEO_META} + {@code LS_META_REPL_OUTBOX} 의
 * <b>실 DB(PostgreSQL Testcontainer) 통합 테스트</b>.
 *
 * <p>검증 대상:
 * <ol>
 *   <li>ON CONFLICT DO NOTHING 멱등 upsert — 신규 1행 적재(ACTIVE_YN='Y'), 동일 (RAW_SN,SNPSHT_HASH)
 *       재upsert 시 중복 차단(예외 없이 0행).</li>
 *   <li>동시 2요청 같은 해시 upsert → 정확히 1행만 삽입(CWE-362 race 안전).</li>
 *   <li>{@code deactivatePrevious} — 신규 해시 적재 후 이전 활성 스냅샷 'N' 전환, 다른 RAW_SN 무영향.</li>
 *   <li>outbox insert 후 PENDING 폴링 조회(등록순).</li>
 * </ol>
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * 네이티브 {@code @Modifying} 쿼리는 활성 트랜잭션이 필요하므로 {@code controlTransactionManager}
 * 기반 {@link TransactionTemplate} 안에서 호출한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LsDatasetVideoMetaRepositoryIT {
    // ── DB-ISSUE-01 / V146: 자식 행이 참조할 부모 영상(LS_DATA_RAW) 시드 ──
    //   FK 신설 전에는 임의 정수를 rawSn 으로 써도 통과했지만 그렇게 만든 데이터는 실제로는 고아였다.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate parentVideoJdbc;

    private final java.util.List<Long> seededParentRawSns = new java.util.ArrayList<>();

    /** 실재하는 부모 영상 1건을 만들고 rawSn 을 돌려준다(V146 FK). */
    private long newVideo() {
        long rawSn = kr.co.cudo.authoring.support.RawVideoFixture.newRaw(parentVideoJdbc);
        seededParentRawSns.add(rawSn);
        return rawSn;
    }

    @org.junit.jupiter.api.AfterEach
    void cleanSeededParentVideos() {
        // 부모 삭제 = 자식(동결 메타·아웃박스·export 등) CASCADE 삭제.
        seededParentRawSns.forEach(
                sn -> kr.co.cudo.authoring.support.RawVideoFixture.deleteRaws(parentVideoJdbc, sn));
        seededParentRawSns.clear();
    }


    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    @Autowired
    private LsMetaReplOutboxRepository outboxRepository;

    private final TransactionTemplate txTemplate;

    LsDatasetVideoMetaRepositoryIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private LsDatasetVideoMeta snapshot(long rawSn, String hash) {
        return LsDatasetVideoMeta.builder()
                .rawSn(rawSn)
                .snpshtHash(hash)
                .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                .orgnlRawSn(null)
                .vmsClipId("CLIP-" + rawSn)
                .vmsCctvId("CCTV-1")
                .rawFilePathNm("/nas/raw/" + rawSn + ".mp4")
                .shtDt(LocalDateTime.now().minusDays(1))
                .vdoLenSec(30)
                .lclgvCd("1111000000")
                .prvcYn("Y")
                .prvcTypeCd("PRVC")
                .deIdentYn("Y")
                .aiCrtYn("N")
                .evntTypeCd("EVT01")
                .cctvNm("교차로 CCTV")
                .wgs84Lat(new BigDecimal("37.5665000"))
                .wgs84Lot(new BigDecimal("126.9780000"))
                .sidoNm("서울특별시")
                .sggNm("중구")
                .fileFmt("mp4")
                .evntNm("보행자")
                .vdoCdc("h264")
                .fps(new BigDecimal("25"))
                .bitRt(4_000_000L)
                .asprtRt(new BigDecimal("1.777778"))
                .resl("1920x1080")
                .vdoWdth(1920)
                .vdoHgt(1080)
                .fileSz(15_000_000L)
                .dayNgtCd("DAY")
                .sesnCd("FALL")
                .wthrNm(null)
                .rvwCmplDt(LocalDateTime.now())
                .regDt(LocalDateTime.now())
                .regId("reviewer1")
                .build();
    }

    private int upsert(LsDatasetVideoMeta m) {
        return txTemplate.execute(s -> metaRepository.upsertSnapshot(m));
    }

    private List<LsDatasetVideoMeta> loadByRaw(long rawSn) {
        return txTemplate.execute(s -> metaRepository.findByRawSn(rawSn));
    }

    @Test
    @DisplayName("통합메타_upsert시_ACTIVE_Y_1행_적재")
    void upsert_persistsSingleActiveRow() {
        // given
        long rawSn = newVideo();

        // when
        int affected = upsert(snapshot(rawSn, "hash-a"));

        // then — 정확히 1행 삽입, ACTIVE_YN='Y' 로 영속
        assertThat(affected).isEqualTo(1);
        List<LsDatasetVideoMeta> rows = loadByRaw(rawSn);
        assertThat(rows).hasSize(1);
        LsDatasetVideoMeta row = rows.get(0);
        assertThat(row.getActiveYn()).isEqualTo("Y");
        assertThat(row.getSnpshtHash()).isEqualTo("hash-a");
        assertThat(row.getResl()).isEqualTo("1920x1080");
        assertThat(row.getFps()).isEqualByComparingTo("25");
        assertThat(row.getWgs84Lat()).isEqualByComparingTo("37.5665000");
    }

    @Test
    @DisplayName("동일_RAW_SN_HASH_재upsert시_중복_차단_멱등")
    void duplicateUpsert_isIdempotent() {
        // given — 최초 삽입
        long rawSn = newVideo();
        assertThat(upsert(snapshot(rawSn, "hash-dup"))).isEqualTo(1);

        // when — 동일 (RAW_SN, SNPSHT_HASH) 재upsert
        int second = upsert(snapshot(rawSn, "hash-dup"));

        // then — 예외 없이 0행(멱등), 여전히 1행만 존재
        assertThat(second).isEqualTo(0);
        assertThat(loadByRaw(rawSn)).hasSize(1);
    }

    /**
     * 동시 upsert 경합 — 같은 {@code (RAW_SN, SNPSHT_HASH)} 를 활성으로 동시에 넣어도
     * 정확히 1행만 삽입되고 <b>예외가 나지 않는다</b>.
     *
     * <h3>왜 라운드를 반복하고 배리어를 쓰나 (이 가드가 한때 가드를 멈췄다)</h3>
     * 이 테이블은 유니크 제약이 둘인데 {@code ON CONFLICT} 는 하나만 중재한다
     * ({@code LsDatasetVideoMetaRepository#upsertSnapshot} 참조). 경합 창은
     * <b>"아직 커밋된 행이 없는 순간"에만</b> 열리므로 <b>라운드마다 새 영상</b>(콜드 스타트)이 필요하다.
     *
     * <p>구 판은 라운드 1회 + 스레드 생성 직후 시작이라, 두 스레드의 <b>시작 스큐</b>(트랜잭션 개시·
     * 커넥션 획득·SpEL 파라미터 바인딩) 때문에 대개 한쪽이 먼저 커밋해 창이 닫힌 뒤에 다른 쪽이
     * 진입했다 — 그래서 <b>결함이 살아 있는데도 10회 연속 통과</b>했고(변이 실증으로 확인), 전체 회귀의
     * 부하 아래에서만 간헐 실패해 오래 플레이크로 오인됐다. 배리어를 <b>트랜잭션 개시 이후</b>에 두어
     * 스큐를 제거하고 라운드를 반복하면 결함이 있을 때 사실상 매번 드러난다.
     *
     * <p>스레드는 2 를 넘기지 않는다 — 테스트 커넥션 풀이 2 로 고정된 <b>의도된 설정</b>이라
     * ({@code src/test/resources/application-local.yml}) 그 이상은 풀에서 직렬화되어 오히려 경합을 가린다.
     */
    @Test
    @DisplayName("동시_2요청_같은해시_upsert시_1행만_적재")
    void concurrentUpsertSameHash_insertsExactlyOnce() throws Exception {
        final int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                long rawSn = newVideo();
                CyclicBarrier gate = new CyclicBarrier(2);
                Callable<Integer> task = () -> txTemplate.execute(s -> {
                    awaitGate(gate);
                    return metaRepository.upsertSnapshot(snapshot(rawSn, "hash-race"));
                });

                Future<Integer> a = pool.submit(task);
                Future<Integer> b = pool.submit(task);
                int inserted = a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS);

                // 두 요청의 삽입 합계 1, 실제 1행만 존재(advisory 락 직렬화 + ON CONFLICT 흡수).
                assertThat(inserted).as("라운드 %d 삽입 합계", round).isEqualTo(1);
                assertThat(loadByRaw(rawSn)).as("라운드 %d 적재 행수", round).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 배리어 대기 — 상대가 오지 않으면 매달리지 말고 빠르게 실패시킨다. */
    private static void awaitGate(CyclicBarrier gate) {
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 실행 배리어 대기 실패", e);
        }
    }

    @Test
    @DisplayName("deactivate_then_insert시_기존_ACTIVE_N_전환_활성1건_유지")
    void deactivateThenInsert_flipsOldActiveToN() {
        // given — 동일 RAW_SN 에 이전 스냅샷(hash-old) 적재
        long rawSn = newVideo();
        assertThat(upsert(snapshot(rawSn, "hash-old"))).isEqualTo(1);
        // 다른 RAW_SN 의 활성 스냅샷(무영향 검증용) — 두 번째 영상도 실재해야 한다(V146 FK:
        // 구 픽스처 rawSn+1 은 존재하지 않는 영상을 참조하는 고아였다).
        long otherRaw = newVideo();
        assertThat(upsert(snapshot(otherRaw, "hash-other"))).isEqualTo(1);

        // when — Phase 2 순서: 기존 활성 비활성화 → 신규 스냅샷(hash-new) 삽입
        // (부분 유니크 인덱스 V99 하에서는 insert-then-deactivate 시 순간 2 활성이라 위반하므로
        //  반드시 deactivate 를 먼저 수행한다.)
        int deactivated = txTemplate.execute(s -> metaRepository.deactivatePrevious(rawSn, "hash-new"));
        assertThat(upsert(snapshot(rawSn, "hash-new"))).isEqualTo(1);

        // then — 이전 hash-old 만 'N', 신규 hash-new 는 'Y'(활성 정확히 1건)
        assertThat(deactivated).isEqualTo(1);
        List<LsDatasetVideoMeta> rows = loadByRaw(rawSn);
        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(r -> r.getSnpshtHash().equals("hash-old"))
                .allMatch(r -> r.getActiveYn().equals("N"));
        assertThat(rows).filteredOn(r -> r.getSnpshtHash().equals("hash-new"))
                .allMatch(r -> r.getActiveYn().equals("Y"));
        assertThat(rows).filteredOn(r -> r.getActiveYn().equals("Y")).hasSize(1);

        // 다른 RAW_SN 은 무영향(여전히 'Y')
        assertThat(loadByRaw(otherRaw)).allMatch(r -> r.getActiveYn().equals("Y"));
    }

    @Test
    @DisplayName("동시_다른해시_승인시_활성_1건_불변식_부분유니크인덱스_원천차단")
    void partialUniqueIndex_blocksTwoActiveRows() {
        // given — 활성 스냅샷 1건(hash-a)
        long rawSn = newVideo();
        assertThat(upsert(snapshot(rawSn, "hash-a"))).isEqualTo(1);

        // when / then — 기존 활성을 내리지 않고 다른 해시 활성 행을 삽입하면(잘못된 순서)
        // 부분 유니크 인덱스(RAW_SN WHERE ACTIVE_YN='Y')가 위반을 원천 차단한다.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> upsert(snapshot(rawSn, "hash-b")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 활성은 여전히 정확히 1건(hash-a).
        List<LsDatasetVideoMeta> rows = loadByRaw(rawSn);
        assertThat(rows).filteredOn(r -> r.getActiveYn().equals("Y")).hasSize(1);
        assertThat(rows).filteredOn(r -> r.getActiveYn().equals("Y"))
                .allMatch(r -> r.getSnpshtHash().equals("hash-a"));
    }

    @Test
    @DisplayName("deactivate_then_insert_정상순서시_활성_1건_전환_성공")
    void deactivateThenInsert_properOrder_keepsSingleActive() {
        // given — 활성 hash-a
        long rawSn = newVideo();
        assertThat(upsert(snapshot(rawSn, "hash-a"))).isEqualTo(1);

        // when — 올바른 순서(deactivate → insert)로 다른 해시(hash-b) 활성 전환
        txTemplate.executeWithoutResult(s -> {
            metaRepository.deactivatePrevious(rawSn, "hash-b");
            metaRepository.upsertSnapshot(snapshot(rawSn, "hash-b"));
        });

        // then — 활성은 정확히 1건(hash-b), hash-a 는 'N'
        List<LsDatasetVideoMeta> rows = loadByRaw(rawSn);
        assertThat(rows).filteredOn(r -> r.getActiveYn().equals("Y")).hasSize(1);
        assertThat(rows).filteredOn(r -> r.getActiveYn().equals("Y"))
                .allMatch(r -> r.getSnpshtHash().equals("hash-b"));
    }

    @Test
    @DisplayName("outbox_insert후_PENDING_폴링_조회")
    void outbox_pollsPendingByRegDt() {
        // given — PENDING outbox 2건 insert
        long rawSn = newVideo();
        // 2건은 서로 다른 영상 것이어야 하므로 두 번째 영상도 실재하게 시드한다(V146 FK).
        long otherRawSn = newVideo();
        txTemplate.executeWithoutResult(s -> {
            outboxRepository.save(LsMetaReplOutbox.create(rawSn, "hash-1", "{\"rawSn\":" + rawSn + "}"));
            outboxRepository.save(LsMetaReplOutbox.create(otherRawSn, "hash-2", "{}"));
        });

        // when — PENDING 폴링(limit=10)
        List<LsMetaReplOutbox> pending = txTemplate.execute(s ->
                outboxRepository.findBySttsCdOrderByRegDtAsc(
                        LsMetaReplOutbox.STATUS_PENDING, PageRequest.of(0, 10)));

        // then — 방금 넣은 PENDING 이 조회되고, 상태/기본값이 올바르다
        assertThat(pending).extracting(LsMetaReplOutbox::getSnpshtHash).contains("hash-1", "hash-2");
        assertThat(pending).allMatch(o -> o.getSttsCd().equals("PENDING"));
        assertThat(pending).allMatch(o -> o.getRtryNmtm() == 0);
        assertThat(pending).allMatch(o -> o.getPrcsDt() == null);
    }
}
