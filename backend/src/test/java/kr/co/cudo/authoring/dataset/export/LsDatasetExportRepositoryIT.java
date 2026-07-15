package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code LS_DATASET_EXPORT} 실 DB(PostgreSQL Testcontainer) 통합 테스트 — 버전 도출 누적과
 * UK(DATA_RAW_SN, EXPORT_VER_NO) 중복 차단, 상태 전이 영속을 검증한다.
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LsDatasetExportRepositoryIT {

    @Autowired
    private LsDatasetExportRepository exportRepository;

    private final TransactionTemplate txTemplate;

    LsDatasetExportRepositoryIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private LsDatasetExport save(long rawSn, int verNo, String path) {
        return txTemplate.execute(s ->
                exportRepository.save(LsDatasetExport.create(rawSn, verNo, path)));
    }

    @Test
    @DisplayName("버전은_기존_export_건수+1로_도출된다")
    void versionDerivedFromCountPlusOne() {
        // given — 고유 rawSn 에 아직 export 없음 → count=0, 다음 버전=1
        long rawSn = System.nanoTime();
        long v1 = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn)) + 1;
        assertThat(v1).isEqualTo(1);
        save(rawSn, (int) v1, "/labeling/" + rawSn + "/v1");

        // when — 다음 산출 버전은 누적 건수 + 1 = 2
        long v2 = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn)) + 1;

        // then
        assertThat(v2).isEqualTo(2);
        long finalCount = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn));
        assertThat(finalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("LS_DATASET_EXPORT_UK_중복_버전_삽입시_제약위반")
    void duplicateVersionViolatesUniqueConstraint() {
        // given — 같은 rawSn, 같은 버전 1건 적재
        long rawSn = System.nanoTime();
        save(rawSn, 1, "/labeling/" + rawSn + "/v1");

        // when / then — 동일 (DATA_RAW_SN, EXPORT_VER_NO) 재삽입 시 UK 위반
        assertThatThrownBy(() -> save(rawSn, 1, "/labeling/" + rawSn + "/v1-dup"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existsByDataRawSnAndExportVerNo_존재여부_판정")
    void existsByRawSnAndVerNo() {
        // given
        long rawSn = System.nanoTime();
        save(rawSn, 1, "/labeling/" + rawSn + "/v1");

        // when / then
        boolean existsV1 = txTemplate.execute(s ->
                exportRepository.existsByDataRawSnAndExportVerNo(rawSn, 1));
        boolean existsV2 = txTemplate.execute(s ->
                exportRepository.existsByDataRawSnAndExportVerNo(rawSn, 2));
        assertThat(existsV1).isTrue();
        assertThat(existsV2).isFalse();
    }

    @Test
    @DisplayName("findFirstByDataRawSnOrderByExportVerNoDesc_최신버전_반환")
    void findsLatestVersion() {
        // given — v1, v2, v3 순서 무관 적재
        long rawSn = System.nanoTime();
        save(rawSn, 1, "/labeling/" + rawSn + "/v1");
        save(rawSn, 3, "/labeling/" + rawSn + "/v3");
        save(rawSn, 2, "/labeling/" + rawSn + "/v2");

        // when
        Optional<LsDatasetExport> latest = txTemplate.execute(s ->
                exportRepository.findFirstByDataRawSnOrderByExportVerNoDesc(rawSn));

        // then — 최대 버전(3)
        assertThat(latest).isPresent();
        assertThat(latest.get().getExportVerNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("findFirstByDataRawSnAndExportSttsCd_최신이_FAILED여도_이전_SUCCEEDED를_반환")
    void findsLatestSucceededDespiteLaterFailed() {
        // given — v1 SUCCEEDED, v2 FAILED (최신)
        long rawSn = System.nanoTime();
        txTemplate.executeWithoutResult(s -> {
            LsDatasetExport v1 = exportRepository.save(
                    LsDatasetExport.create(rawSn, 1, "/labeling/" + rawSn + "/v1", "hashH"));
            v1.markSucceeded(10);
            LsDatasetExport v2 = LsDatasetExport.create(rawSn, 2, "/labeling/" + rawSn + "/v2", "hashX");
            v2.markFailed();
            exportRepository.save(v2);
        });

        // when — SUCCEEDED 상태 필터로 최신 조회
        Optional<LsDatasetExport> latestSucceeded = txTemplate.execute(s ->
                exportRepository.findFirstByDataRawSnAndExportSttsCdOrderByExportVerNoDesc(
                        rawSn, LsDatasetExport.STATUS_SUCCEEDED));

        // then — 최신(v2 FAILED)이 아니라 이전 v1 SUCCEEDED 의 해시를 반환
        assertThat(latestSucceeded).isPresent();
        assertThat(latestSucceeded.get().getExportVerNo()).isEqualTo(1);
        assertThat(latestSucceeded.get().getContentHash()).isEqualTo("hashH");
    }

    @Test
    @DisplayName("markSucceeded_상태와_프레임수_반영")
    void markSucceededPersistsStatusAndFrameCount() {
        // given — PENDING 으로 적재
        long rawSn = System.nanoTime();
        LsDatasetExport saved = save(rawSn, 1, "/labeling/" + rawSn + "/v1");
        assertThat(saved.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_PENDING);
        Long id = saved.getExportSn();

        // when — SUCCEEDED 전이 + 프레임수 반영 후 재조회
        txTemplate.executeWithoutResult(s -> {
            LsDatasetExport e = exportRepository.findById(id).orElseThrow();
            e.markSucceeded(120);
        });

        // then — DB 재조회 시 상태/프레임수 영속
        LsDatasetExport reloaded = txTemplate.execute(s -> exportRepository.findById(id).orElseThrow());
        assertThat(reloaded.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
        assertThat(reloaded.getFrameCnt()).isEqualTo(120);
    }

    @Test
    @DisplayName("markFailed_상태전이_영속")
    void markFailedPersistsStatus() {
        // given
        long rawSn = System.nanoTime();
        Long id = save(rawSn, 1, "/labeling/" + rawSn + "/v1").getExportSn();

        // when
        txTemplate.executeWithoutResult(s ->
                exportRepository.findById(id).orElseThrow().markFailed());

        // then
        LsDatasetExport reloaded = txTemplate.execute(s -> exportRepository.findById(id).orElseThrow());
        assertThat(reloaded.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_FAILED);
    }
}
