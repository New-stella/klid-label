package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import kr.co.cudo.authoring.video.service.ResolutionReservationPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-6 — 파생영상 파일 경로가 <b>실제 DB 커밋 결과</b>에서도 파생 RAW_SN 으로 키잉되는지 검증한다.
 *
 * <p>단위 테스트(mock 리포지토리)만으로는 "INSERT 이후 경로 재배정이 실제로 flush 되는가"를 증명하지
 * 못한다(모의 객체는 같은 인스턴스를 돌려주므로 항상 통과). 여기서는 실 트랜잭션이 커밋된 뒤 DB 를
 * 다시 읽어 확인한다 — 잠정 경로({@code .pending})가 커밋되면 실패한다.
 *
 * <p>{@link AsyncResolutionRunner} 는 mock 으로 대체한다. AFTER_COMMIT 트리거가 실제로 돌면 비식별
 * 원본 파일이 없어 확정이 실패하고 파생 RAW 를 FAILED 로 지워버려 검증 대상이 사라지기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ResolutionReservationPathIT {

    private static final long PARENT = 990501L;

    @MockBean AsyncResolutionRunner asyncResolutionRunner;

    @Autowired ResolutionReservationPersister persister;
    @Autowired VideoRepository videoRepository;

    private final JdbcTemplate jdbc;
    private Long parentFrameSrcSn;
    private Long newRawSn;
    private Long dataAugSn;

    ResolutionReservationPathIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) "
                + "VALUES (?, ?, 'CCTV', 'EVT', '11680', 'PRVC', 'Y', 'Y', '/x/p.mp4', 'COMPLETED', CURRENT_TIMESTAMP)",
                PARENT, "CLIP-" + PARENT);
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 0, '/x/p0.jpg')", PARENT);
        parentFrameSrcSn = jdbc.queryForObject(
                "SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ? AND FRM_NO = 0", Long.class, PARENT);
    }

    @AfterEach
    void cleanup() {
        if (dataAugSn != null) {
            jdbc.update("DELETE FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", dataAugSn);
        }
        if (newRawSn != null) {
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", newRawSn);
        }
        jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", PARENT);
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", PARENT);
    }

    @Test
    @DisplayName("A6_예약_커밋후_DB의_파생영상_경로가_파생RAW_SN으로_키잉된다")
    void committedDerivativeVideoPathIsKeyedByDerivativeRawSn() {
        LsDataRaw parent = videoRepository.findById(PARENT).orElseThrow();

        ResolutionReservationPersister.Reservation reservation =
                persister.reserveAndCreate(parent, ResolutionPreset.RESL_720P, parentFrameSrcSn, "rev1");
        newRawSn = reservation.newRawSn();
        dataAugSn = reservation.dataAugSn();

        // 커밋된 값을 DB 에서 다시 읽는다(영속성 컨텍스트 캐시가 아닌 실제 저장값).
        String committedPath = jdbc.queryForObject(
                "SELECT RAW_FILE_PATH_NM FROM LS_DATA_RAW WHERE RAW_SN = ?", String.class, newRawSn);

        assertThat(committedPath)
                .endsWith("/videos/resolution/" + PARENT + "/" + newRawSn + "/RESL_720P.mp4");
        // 잠정(자리표시자) 경로가 커밋되면 안 된다.
        assertThat(committedPath).doesNotContain(".pending");
    }
}
