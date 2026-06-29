package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 — findLatestByDataRawSnIn 가 각 DATA_RAW_SN 별 최신 procLog 1행만 단일 IN 쿼리로 반환하는지
 * DB 라운드트립으로 검증한다. 재비식별로 한 rawSn 에 다중행이 쌓여도 가장 최근(PROC_LOG_SN 최대) 1행만
 * 채택해야 한다 (영상 목록의 비식별 상태 파생 정확도 + N+1 회피의 근거).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDeidentProcLogRepositoryLatestInTest {

    @Autowired
    private LsDeidentProcLogRepository repository;

    private LsDeidentProcLog requested(long rawSn) {
        return LsDeidentProcLog.request(rawSn, "req-" + rawSn + "-" + System.nanoTime(),
                "/var/raw/c" + rawSn + ".mp4", "tester");
    }

    @Test
    @DisplayName("findLatestByDataRawSnIn은_각_rawSn당_최신_1행만_반환한다")
    void returnsLatestRowPerRawSn() {
        // given — rawSn=971001 에 procLog 2건(재비식별): 먼저 REQUESTED, 이후 SUCCEEDED 행 추가.
        long rawA = 971_001L;
        long rawB = 971_002L;
        repository.saveAndFlush(requested(rawA)); // 1차 (오래된 행)

        LsDeidentProcLog laterA = requested(rawA); // 2차 (최신 행)
        laterA.succeed("/nas/deid/971001.mp4");
        repository.saveAndFlush(laterA);

        // rawB 는 단일 REQUESTED 행
        repository.saveAndFlush(requested(rawB));

        // when
        List<LsDeidentProcLog> latest = repository.findLatestByDataRawSnIn(List.of(rawA, rawB));

        // then — rawSn 당 정확히 1행씩(총 2행), rawA 는 최신(SUCCEEDED) 행이어야 한다.
        assertThat(latest).hasSize(2);
        Map<Long, LsDeidentProcLog> byRaw = latest.stream()
                .collect(Collectors.toMap(LsDeidentProcLog::getDataRawSn, Function.identity()));
        assertThat(byRaw).containsOnlyKeys(rawA, rawB);
        assertThat(byRaw.get(rawA).getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(byRaw.get(rawA).getProcLogSn()).isEqualTo(laterA.getProcLogSn());
        assertThat(byRaw.get(rawB).getProcSttsCd()).isEqualTo(LsDeidentProcLog.REQUESTED);
    }

    @Test
    @DisplayName("findLatestByDataRawSnIn은_빈_입력에_쿼리없이_빈리스트를_반환한다")
    void emptyInputShortCircuits() {
        assertThat(repository.findLatestByDataRawSnIn(List.of())).isEmpty();
        assertThat(repository.findLatestByDataRawSnIn(null)).isEmpty();
    }
}
