package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.upload.dto.TusCreateCommand;
import kr.co.cudo.authoring.upload.service.DurationProbeFfprobe;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
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
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 내부 업로드(TUS 완료) → 인입 행(PENDING) → 폴링 적재 → {@code LS_DATA_RAW} 실동작 검증 (Phase 1).
 *
 * <h3>왜 통합 테스트가 필요한가</h3>
 * <p>이 Phase 의 핵심은 "업로드가 인입 경로를 <b>우회하지 않는다</b>"이다. 단위 테스트는 writer 를
 * 목으로 대체하므로 <b>실제로 INSERT 된 행이 폴링 배치에 픽업되는지</b>를 구조적으로 확인할 수 없다.
 * 여기서는 실 PostgreSQL 에 INSERT 하고, 그 행을 {@link TrainingVideoIngestTx#ingestOne} 이 그대로
 * 적재하는 것까지 이어서 본다 — 경로 allowlist({@code SRC_TYPE}·{@code RAW_FILE_PATH_NM})가 어긋나면
 * 여기서 {@code FAILED} 로 드러난다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — writer 의 {@code REQUIRES_NEW} 커밋과
 * 폴링 클레임(조건부 UPDATE)이 실제로 일어나야 하기 때문이다. 시드는 접두로 격리하고 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 관제 NAS 마운트 모사 — 이 하위만 적재 가능(고정 allowlist).
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
        // ★업로드 저장 base(raw-path)는 그 allowlist <하위>여야 한다. 밖이면 인입이 REJECTED 로
        //   영구 종결되고, 그 형상은 InternalUploadWiringGuard 가 배포 환경에서 업로드 기능을 닫는다
        //   (503 — 앱 기동은 정상. 실패 범위를 기능 단위로 한정, DEV_FIX F4-b).
        "authoring.storage.raw-path=" + ArtifactRootTestSupport.IT_MOUNT_ROOT + "/internal-upload",
        "authoring.control.training-scan.not-arrived-timeout-hours=1"
})
class InternalUploadIngestFlowIT {

    private static final String CLIP_PREFIX = "INTERNAL-ULD-IT-";
    private static final String OWNER = "reviewer-it";
    /** mp4 ISO BMFF 시그니처 + 패딩(16바이트) — 매직바이트 검증 통과용. */
    private static final byte[] MP4_BYTES = new byte[]{
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0};

    @Autowired
    private TusUploadService tusUploadService;

    @Autowired
    private TrainingVideoIngestTx ingestTx;

    @Autowired
    private LsDataIngestRepository ingestRepository;

    /** UK 위반 race 를 실제로 만들기 위한 인입 행 선점 통로(프로덕션과 동일 writer). */
    @Autowired
    private InternalUploadIngestWriter ingestWriter;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** ffprobe 바이너리 의존 격리 — 길이 추출은 이 테스트의 관심사가 아니다. */
    @MockBean
    private DurationProbeFfprobe durationProbe;

    /** 비식별 본체는 관심사가 아니다 — 트리거 <시점>만 본다(업로드 시점이 아니라 적재 시점). */
    @MockBean
    private AsyncDeidentifyRunner asyncDeidentifyRunner;

    private JdbcTemplate jdbc;
    private String clipId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        clipId = CLIP_PREFIX + System.nanoTime();
        when(durationProbe.probe(any(Path.class))).thenReturn(600);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_tus_upload WHERE user_no = ?", OWNER);
    }

    private UUID uploadOneClip() {
        TusCreateCommand cmd = new TusCreateCommand(MP4_BYTES.length, "clip.mp4", clipId,
                "CCTV-INTERNAL-01", "", "30200", "PRVC", null);
        UUID uploadId = tusUploadService.createSession(OWNER, cmd);
        tusUploadService.appendChunk(uploadId, OWNER, 0,
                new ByteArrayInputStream(MP4_BYTES), MP4_BYTES.length);
        return uploadId;
    }

    @Test
    @DisplayName("업로드_완료시_인입행이_PENDING으로_남고_LS_DATA_RAW는_아직_없다")
    void uploadCreatesPendingIngestRowOnly() {
        // when — 세션 생성 + 단일 청크로 완료
        uploadOneClip();

        // then — 인입 행 1건(PENDING). 업로드가 적재까지 해버리지 않는다.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT proc_stts_cd, raw_sn, rty_cnt, src_type, vms_cctv_id, vdo_file_nm,"
                        + " raw_file_path_nm, vdo_len_sec, file_sz, file_fmt, lclgv_cd"
                        + " FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_PENDING);
        assertThat(row.get("raw_sn")).isNull();
        assertThat(((Number) row.get("rty_cnt")).intValue()).isZero();
        assertThat(row.get("src_type")).isEqualTo(LsDataIngest.SRC_TYPE_USER_ULD);
        assertThat(row.get("vms_cctv_id")).isEqualTo("CCTV-INTERNAL-01");
        assertThat(row.get("vdo_file_nm")).isEqualTo(clipId + ".mp4");
        assertThat(((Number) row.get("vdo_len_sec")).intValue()).isEqualTo(600);
        assertThat(((Number) row.get("file_sz")).longValue()).isEqualTo(MP4_BYTES.length);
        assertThat(row.get("file_fmt")).isEqualTo("mp4");
        assertThat(row.get("lclgv_cd")).isEqualTo("30200");

        // then — 파일이 그 경로에 실제로 있다(폴링이 NOT_ARRIVED 로 대기하지 않는다)
        assertThat(Files.exists(Path.of((String) row.get("raw_file_path_nm")))).isTrue();

        // then — 적재는 아직 없고, 비식별 선두 트리거도 아직 발화하지 않았다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, clipId)).isZero();
        verify(asyncDeidentifyRunner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("업로드된_인입행은_관제_인입과_동일하게_폴링적재되어_LS_DATA_RAW가_생성된다")
    void uploadedIngestRowIsPickedUpByPolling() {
        // given — 업로드 완료로 만들어진 인입 행
        uploadOneClip();
        long rcptnSn = jdbc.queryForObject(
                "SELECT rcptn_sn FROM ls_data_ingest WHERE vms_clip_id = ?", Long.class, clipId);

        // when — 관제 인입과 <같은> 적재 경로를 태운다
        boolean ingested = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());

        // then — 적재 성공 + 인입 행 DONE
        assertThat(ingested).isTrue();
        Map<String, Object> ingest = jdbc.queryForMap(
                "SELECT proc_stts_cd, raw_sn FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn);
        assertThat(ingest.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_DONE);

        // then — LS_DATA_RAW 에 업로드 출처유형 그대로 복사됐다(allowlist 통과 증거)
        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT raw_sn, src_type, vdo_len_sec, lclgv_cd FROM ls_data_raw WHERE vms_clip_id = ?",
                clipId);
        assertThat(raw.get("src_type")).isEqualTo(LsDataIngest.SRC_TYPE_USER_ULD);
        assertThat(((Number) raw.get("vdo_len_sec")).intValue()).isEqualTo(600);
        assertThat(raw.get("lclgv_cd")).isEqualTo("30200");
        assertThat(((Number) ingest.get("raw_sn")).longValue())
                .isEqualTo(((Number) raw.get("raw_sn")).longValue());

        // then — ★비식별 선두 트리거는 <적재 시점>에 발화한다(업로드가 앞질러 발행하지 않는다)
        verify(asyncDeidentifyRunner, timeout(5_000))
                .runAsync(((Number) raw.get("raw_sn")).longValue());
    }

    @Test
    @DisplayName("완료중_UK위반이_나면_409_이면서_옮긴파일이_회수되고_세션이_종결된다 — 교착없이")
    void ingestUniqueViolationDuringCompletionRollsBackFile() {
        // given — 세션 생성(사전 조회 통과) 이후 같은 클립이 인입된 race 를 실제로 만든다.
        TusCreateCommand cmd = new TusCreateCommand(MP4_BYTES.length, "clip.mp4", clipId,
                "CCTV-INTERNAL-01", "", "30200", "PRVC", null);
        UUID uploadId = tusUploadService.createSession(OWNER, cmd);
        ingestWriter.insertPending(InternalUploadIngestCommand.ofInternalUpload(
                clipId, "CCTV-INTERNAL-01", clipId + ".mp4",
                ArtifactRootTestSupport.IT_MOUNT_ROOT + "/internal-upload/data/upload/v2/"
                        + clipId + ".mp4",
                null, java.math.BigDecimal.TEN, 10L, "mp4", "30200"));

        // when — 마지막 청크: 파일 이동 성공 → 완료 전이 성공 → 인입 INSERT 가 UK 위반으로 실패
        //   ★이 경로는 ①REQUIRES_NEW INSERT ②PESSIMISTIC_WRITE 로 잠긴 세션 행 ③커밋 후 별도
        //     트랜잭션 종결이 겹친다. 종결을 트랜잭션 보유 중에 REQUIRES_NEW 로 하면 여기서 교착한다.
        assertThatThrownBy(() -> tusUploadService.appendChunk(uploadId, OWNER, 0,
                new ByteArrayInputStream(MP4_BYTES), MP4_BYTES.length))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class)
                .extracting(e -> ((kr.co.cudo.authoring.common.exception.CustomException) e).getErrorCode())
                .isEqualTo(kr.co.cudo.authoring.common.exception.ErrorCode.CONFLICT);

        // then — ★비식별 전 원본이 인입 영역에 고아로 남지 않는다(아무 정리 주체도 지우지 않는 파일)
        Path orphan = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT,
                "internal-upload", "data", "upload", "v2", clipId + ".mp4")
                .toAbsolutePath().normalize();
        assertThat(Files.exists(orphan))
                .as("INSERT 실패 시 옮긴 파일은 회수돼야 한다: %s", orphan)
                .isFalse();

        // then — 세션은 <별도 트랜잭션>으로 종결돼 롤백에 휩쓸리지 않는다(재개 불가 세션 방지)
        Map<String, Object> session = jdbc.queryForMap(
                "SELECT stts_cd, expry_dt FROM ls_tus_upload WHERE uld_id = ?", uploadId);
        assertThat(session.get("stts_cd")).isEqualTo("EXPIRED");

        // then — 인입 행은 먼저 들어간 1건뿐이다(중복 생성 없음)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id = ?", Integer.class, clipId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("매직바이트_실패시_세션종결이_롤백에도_살아남는다 — 오프셋은_롤백되고_상태는_남는다")
    void magicByteFailureTerminationSurvivesRollback() {
        // given — 영상 컨테이너가 아닌 페이로드(매직바이트 불일치)
        byte[] bogus = new byte[16];
        java.util.Arrays.fill(bogus, (byte) 'X');
        TusCreateCommand cmd = new TusCreateCommand(bogus.length, "clip.mp4", clipId,
                "CCTV-INTERNAL-01", "", "30200", "PRVC", null);
        UUID uploadId = tusUploadService.createSession(OWNER, cmd);

        // when — 완료 시점 검증 실패 → 임시 파일은 <이미 지워지고> PATCH 트랜잭션은 롤백된다
        assertThatThrownBy(() -> tusUploadService.appendChunk(uploadId, OWNER, 0,
                new ByteArrayInputStream(bogus), bogus.length))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class)
                .extracting(e -> ((kr.co.cudo.authoring.common.exception.CustomException) e).getErrorCode())
                .isEqualTo(kr.co.cudo.authoring.common.exception.ErrorCode.CONFLICT);

        Map<String, Object> session = jdbc.queryForMap(
                "SELECT stts_cd, uld_offset FROM ls_tus_upload WHERE uld_id = ?", uploadId);

        // then — ★같은 트랜잭션 쓰기는 되돌아갔다는 대조 증거: 오프셋 전진이 롤백돼 0 이다.
        //   구 구현(session.markExpired() + save())도 이 롤백에 함께 휩쓸려 무효였다.
        assertThat(((Number) session.get("uld_offset")).longValue())
                .as("같은 트랜잭션 쓰기는 롤백된다")
                .isZero();

        // then — ★그런데 종결은 별도 트랜잭션(afterCompletion + REQUIRES_NEW)이라 살아남는다.
        //   살아남지 않으면 세션이 IN_PROGRESS 로 부활해 재시도가 <사라진 임시 파일>로 향한다.
        assertThat(session.get("stts_cd")).isEqualTo("EXPIRED");

        // then — 인입 행은 만들어지지 않았다(검증 실패분이 적재 대기열에 들어가지 않는다)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id = ?", Integer.class, clipId))
                .isZero();
    }

    @Test
    @DisplayName("같은_클립을_다시_업로드하려_하면_세션생성_단계에서_409")
    void duplicateClipRejectedAtSessionCreate() {
        uploadOneClip();

        TusCreateCommand duplicate = new TusCreateCommand(MP4_BYTES.length, "clip.mp4", clipId,
                "CCTV-INTERNAL-01", "", "30200", "PRVC", null);
        assertThat(ingestRepository.findByVmsClipId(clipId)).isPresent();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> tusUploadService.createSession(OWNER, duplicate))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class);
    }
}
