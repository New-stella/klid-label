package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaUpdateRequest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 — 촬영환경 수동 저장이 <b>배치가 동시에 갱신하는 다른 컬럼을 덮어쓰지 않음</b>을 실 DB로 고정한다
 * (S8, CWE-362 lost update).
 *
 * <p>시나리오: 촬영환경 저장 트랜잭션이 영상 행을 로드한 뒤(스냅샷 확보), 별도 트랜잭션(배치 writer)이
 * {@code DATA_STTS_CD}·{@code DE_IDENT_YN} 을 커밋한다. 이어서 촬영환경 저장이 flush 되는데,
 * 전체 컬럼 UPDATE(정적 update)라면 stale 값으로 배치 갱신을 되돌려버린다.
 * {@code @DynamicUpdate} + 3필드 전용 도메인 메서드(dirty checking) 조합이면 촬영환경 컬럼만 UPDATE 된다.
 */
@SpringBootTest
@ActiveProfiles("local")
class EnvironmentMetaConcurrencyIT {

    @Autowired private EnvironmentMetaService service;
    @Autowired private VideoRepository videoRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate requiresNewTemplate;
    private final List<Long> seededRawSns = new ArrayList<>();

    private final TokenClaims reviewer =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    EnvironmentMetaConcurrencyIT(@Qualifier("controlDataSource") DataSource dataSource,
                                 @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);
        this.requiresNewTemplate = new TransactionTemplate(txManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

    private long seedRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-ENV-CONC-" + System.nanoTime(), "CCTV-ENV-CONC", "EVT01", "LG01",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/env-conc.mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), 30);
        Long rawSn = requiresNewTemplate.execute(s -> videoRepository.saveAndFlush(raw).getRawSn());
        seededRawSns.add(rawSn);
        return rawSn;
    }

    @Test
    @DisplayName("촬영환경_저장이_배치의_다른_컬럼_갱신을_덮어쓰지_않음")
    void 촬영환경_저장이_배치의_다른_컬럼_갱신을_덮어쓰지_않음() {
        // given — 적재 직후(PENDING · 미비식별) 영상
        long rawSn = seedRaw();

        txTemplate.executeWithoutResult(s -> {
            // 1) 촬영환경 저장 트랜잭션이 영상 행을 먼저 로드(이 시점 스냅샷: PENDING / DE_IDENT_YN='N')
            videoRepository.findById(rawSn).orElseThrow();

            // 2) 그 사이 배치 writer 가 별도 트랜잭션으로 상태·비식별 완료를 커밋
            requiresNewTemplate.executeWithoutResult(inner -> jdbc.update(
                    "UPDATE LS_DATA_RAW SET DATA_STTS_CD = ?, DE_IDENT_YN = ? WHERE RAW_SN = ?",
                    LsDataRaw.DATA_STTS_COMPLETED, "Y", rawSn));

            // 3) 촬영환경 수동 저장(같은 트랜잭션에 편승) → flush
            service.update(rawSn, new EnvironmentMetaUpdateRequest("비", "DAY", "SUMMER"), reviewer);
        });

        // then — 촬영환경은 저장되고, 배치가 갱신한 컬럼은 stale 값으로 되돌아가지 않는다
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT WTHR_NM, DAY_NGT_CD, SESN_CD, DATA_STTS_CD, DE_IDENT_YN "
                        + "FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        assertThat(row.get("wthr_nm")).isEqualTo("비");
        assertThat(row.get("day_ngt_cd")).isEqualTo("DAY");
        assertThat(row.get("sesn_cd")).isEqualTo("SUMMER");
        assertThat(row.get("data_stts_cd")).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        assertThat(String.valueOf(row.get("de_ident_yn"))).isEqualTo("Y");
    }
}
