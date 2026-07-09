package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V70 마이그레이션으로 추가된 LS_DATA_SRC.VDO_FRM_NO(실제 영상 프레임 위치) 컬럼의
 * <b>실 PostgreSQL(Testcontainer) round-trip 런타임 검증</b>.
 *
 * <p>기존 IT 들은 boot+ddl-validate 를 간접 증명할 뿐, videoFrameNo 가 실제 VDO_FRM_NO 컬럼에
 * 매핑·저장·조회되는지를 직접 고정하는 테스트가 없었다(R2 ddl-validate 정합 PARTIAL). 본 IT 는
 * 실제 마이그레이션(V70 포함)이 적용된 Testcontainers DB 에서 다음을 런타임 증명한다.
 *
 * <ol>
 *   <li>VDO_FRM_NO 컬럼 실재 + JPA 매핑: videoFrameNo=1500 저장 → 동일 srcSn 조회 시 1500 round-trip.</li>
 *   <li>FRM_NO(추출순번)와 VDO_FRM_NO(실 프레임 위치)의 의미 분리: frameNo=0 / videoFrameNo=1500 동시 보존.</li>
 *   <li>nullable 정합: 4인자 create(videoFrameNo 미지정) 저장 시 VDO_FRM_NO 가 null 로 round-trip.</li>
 * </ol>
 *
 * <p>컨테이너는 {@code spring.factories} 의 {@code PostgresContainerContextCustomizerFactory} 가
 * 모든 Spring 컨텍스트에 자동 주입한다. {@code LsDataSrcRepository} 는 {@code @ControlRepo} 이므로
 * control 데이터소스의 {@code controlTransactionManager} 트랜잭션 안에서 동작한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataSrcRepositoryVideoFrameNoIT {

    @Autowired
    private LsDataSrcRepository repository;

    @Test
    @DisplayName("LsDataSrc_videoFrameNo가_실제_PostgreSQL_VDO_FRM_NO_컬럼에_저장되고_조회된다")
    void videoFrameNo가_실제_PostgreSQL_VDO_FRM_NO_컬럼에_저장되고_조회된다() {
        // given — frameNo(추출순번)=0, videoFrameNo(실 영상 프레임 위치)=1500 인 프레임 row
        long rawSn = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        LsDataSrc src = LsDataSrc.create(rawSn, 0, 1500, "/frames/raw/" + rawSn + "/000000.jpg", now);

        // when — 실 PostgreSQL 에 저장 후 같은 srcSn 으로 재조회
        LsDataSrc saved = repository.saveAndFlush(src);
        LsDataSrc found = repository.findById(saved.getSrcSn()).orElseThrow();

        // then — VDO_FRM_NO 컬럼이 실재하며 1500 이 round-trip, FRM_NO(추출순번)는 0 으로 의미 분리 보존
        assertThat(found.getVideoFrameNo()).isEqualTo(1500);
        assertThat(found.getFrameNo()).isEqualTo(0);
    }

    @Test
    @DisplayName("videoFrameNo_미지정_4인자_create는_VDO_FRM_NO가_null로_round_trip된다")
    void videoFrameNo_미지정시_VDO_FRM_NO가_null로_round_trip된다() {
        // given — videoFrameNo 를 받지 않는 4인자 팩토리 (레거시 추출 경로 호환)
        long rawSn = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/frames/raw/" + rawSn + "/000000.jpg", now);

        // when
        LsDataSrc saved = repository.saveAndFlush(src);
        LsDataSrc found = repository.findById(saved.getSrcSn()).orElseThrow();

        // then — VDO_FRM_NO 컬럼은 null 로 round-trip (nullable 정합)
        assertThat(found.getVideoFrameNo()).isNull();
        assertThat(found.getFrameNo()).isEqualTo(0);
    }
}
