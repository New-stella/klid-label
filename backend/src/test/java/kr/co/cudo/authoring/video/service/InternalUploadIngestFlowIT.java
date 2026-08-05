package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.upload.dto.InternalUploadCreateRequest;
import kr.co.cudo.authoring.upload.service.DurationProbeFfprobe;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
 * 내부 업로드(TUS) → 인입 행(PENDING) → 폴링 적재 → {@code LS_DATA_RAW} 실동작 검증.
 *
 * <h3>왜 통합 테스트가 필요한가</h3>
 * <p>이 흐름의 핵심은 "업로드가 인입 경로를 <b>우회하지 않는다</b>"이다. 단위 테스트는 writer 를
 * 목으로 대체하므로 <b>실제로 INSERT 된 행이 폴링 배치에 픽업되는지</b>를 구조적으로 확인할 수 없다.
 * 여기서는 실 PostgreSQL 에 INSERT 하고, 그 행을 {@link TrainingVideoIngestTx#ingestOne} 이 그대로
 * 적재하는 것까지 이어서 본다 — 경로 allowlist({@code SRC_TYPE}·{@code RAW_FILE_PATH_NM})가 어긋나면
 * 여기서 {@code FAILED} 로 드러난다.
 *
 * <p><b>Phase 3</b>: 인입 행은 <b>세션 생성(POST)</b> 시점에 생기고 파일은 나중에 도착한다. 그래서
 * "파일이 아직 없는 인입 행을 폴링이 어떻게 다루는가"(= 실패가 아니라 미도착 대기)와 "도착 후 곧바로
 * 픽업되는가"(= {@code NXTM_RTY_DT} 리셋)를 실제 상태 전이로 확인한다.
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

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** ffprobe 바이너리 의존 격리 — 길이 검증은 이 테스트의 관심사가 아니다. */
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

    /** 기술메타(길이·해상도)를 화면에서 채워 보내는 요청 — 나머지는 비워 ffprobe 폴백 대상으로 둔다. */
    private InternalUploadCreateRequest request() {
        return new InternalUploadCreateRequest(
                "clip.mp4", clipId, "CCTV-INTERNAL-01", null, "30200",
                LocalDateTime.of(2026, 3, 1, 9, 30),
                null, null, null, null,
                BigDecimal.valueOf(600), null, null, null, null, null, "1920x1080", null, null,
                null, null, null, null, null, null, "ABA_0001", "차량 정체", "관제일지 본문");
    }

    private UUID createSession() {
        return tusUploadService.createSession(OWNER, MP4_BYTES.length, request());
    }

    private UUID uploadOneClip() {
        UUID uploadId = createSession();
        tusUploadService.appendChunk(uploadId, OWNER, 0,
                new ByteArrayInputStream(MP4_BYTES), MP4_BYTES.length);
        return uploadId;
    }

    @Test
    @DisplayName("세션생성만_해도_인입행이_PENDING으로_남고_그_경로에는_파일이_아직_없다")
    void sessionCreationLeavesPendingIngestRowBeforeFileArrives() {
        // when — 세션만 생성(청크 전송 전)
        createSession();

        // then — 인입 행 1건(PENDING) + 화면 입력값이 그대로 실렸다
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, raw_sn, rty_cnt, src_type, vms_cctv_id, vdo_file_nm,"
                        + " raw_file_path_nm, vdo_len_sec, file_sz, file_fmt, lclgv_cd, resl,"
                        + " evnt_id, evnt_nm, mntr_cn, sht_dt, fps"
                        + " FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(row.get("raw_sn")).isNull();
        assertThat(((Number) row.get("rty_cnt")).intValue()).isZero();
        assertThat(row.get("src_type")).isEqualTo(LsDataIngest.SRC_TYPE_USER_ULD);
        assertThat(row.get("vms_cctv_id")).isEqualTo("CCTV-INTERNAL-01");
        assertThat(row.get("vdo_file_nm")).isEqualTo(clipId + ".mp4");
        assertThat(((Number) row.get("vdo_len_sec")).intValue()).isEqualTo(600);
        assertThat(((Number) row.get("file_sz")).longValue()).isEqualTo(MP4_BYTES.length);
        assertThat(row.get("file_fmt")).isEqualTo("mp4");
        assertThat(row.get("lclgv_cd")).isEqualTo("30200");
        assertThat(row.get("resl")).isEqualTo("1920x1080");
        assertThat(row.get("evnt_id")).isEqualTo("ABA_0001");
        assertThat(row.get("evnt_nm")).isEqualTo("차량 정체");
        assertThat(row.get("mntr_cn")).isEqualTo("관제일지 본문");
        assertThat(row.get("sht_dt")).isNotNull();
        // ★비운 기술메타 키는 null — 적재 후 ffprobe 가 그 키만 채운다(폴백은 키 단위)
        assertThat(row.get("fps")).isNull();

        // then — ★파일은 아직 그 경로에 없다("행 먼저, 파일 나중")
        assertThat(Files.exists(Path.of((String) row.get("raw_file_path_nm")))).isFalse();
    }

    @Test
    @DisplayName("파일_도착전_폴링은_실패시키지_않고_미처리로_되돌린다 — 도착후_예정시각이_당겨져_다시_픽업된다")
    void pollingWaitsForFileThenPicksUpAfterArrival() {
        // given — 세션만 생성(파일 미도착)
        UUID uploadId = createSession();
        long rcptnSn = rcptnSn();

        // when — 폴링이 먼저 돌았다
        boolean ingestedBeforeArrival = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());

        // then — 실패(FAILED)가 아니라 미처리 복귀 + backoff 로 다음 시도가 미뤄졌다
        assertThat(ingestedBeforeArrival).isFalse();
        Map<String, Object> waiting = jdbc.queryForMap(
                "SELECT prcs_stts_cd, rty_cnt, nxtm_rty_dt, prcs_dt"
                        + " FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn);
        assertThat(waiting.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(((Number) waiting.get("rty_cnt")).intValue())
                .as("파일 대기는 실패가 아니므로 재시도 횟수를 올리지 않는다").isZero();
        assertThat(waiting.get("nxtm_rty_dt")).as("backoff 로 후보에서 빠진다").isNotNull();
        // ★판정은 <우리 시계>로 한다 — 폴링 술어가 앱이 넘긴 :now 와 비교하기 때문이다(DB now() 아님).
        //   컨테이너 DB 는 UTC, JVM 은 로컬 타임존이라 DB now() 로 단언하면 시차가 결과를 결정해 버린다.
        assertThat(pollCandidates())
                .as("예정 시각이 미래라 이 행은 지금 폴링 후보가 아니다")
                .doesNotContain(rcptnSn);

        // when — 업로드 완료(파일 도착)
        tusUploadService.appendChunk(uploadId, OWNER, 0,
                new ByteArrayInputStream(MP4_BYTES), MP4_BYTES.length);

        // then — ★예정 시각이 지금으로 당겨져 곧바로 폴링 후보가 된다(20분 업로드가 20분 더 기다리지 않게)
        assertThat(pollCandidates())
                .as("도착 후에는 backoff 가 풀려 곧바로 폴링 후보가 돼야 한다")
                .contains(rcptnSn);

        // then — 대기 예산 앵커(PRCS_DT)는 유지된다(도착은 재큐가 아니다 — 상한이 다시 시작되면 안 된다)
        assertThat(jdbc.queryForMap("SELECT prcs_dt FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn)
                .get("prcs_dt")).isNotNull();
    }

    @Test
    @DisplayName("업로드_완료시_인입행은_여전히_1건이고_파일이_그_경로에_놓인다")
    void completionMovesFileWithoutSecondIngestRow() {
        uploadOneClip();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id = ?", Integer.class, clipId))
                .as("완료가 두 번째 인입 행을 만들면 안 된다").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, raw_file_path_nm FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(Files.exists(Path.of((String) row.get("raw_file_path_nm")))).isTrue();

        // then — 적재는 아직 없고, 비식별 선두 트리거도 아직 발화하지 않았다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, clipId)).isZero();
        verify(asyncDeidentifyRunner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("업로드된_인입행은_관제_인입과_동일하게_폴링적재되어_LS_DATA_RAW가_생성된다")
    void uploadedIngestRowIsPickedUpByPolling() {
        // given — 업로드 완료로 파일까지 도착한 인입 행
        uploadOneClip();
        long rcptnSn = rcptnSn();

        // when — 관제 인입과 <같은> 적재 경로를 태운다
        boolean ingested = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());

        // then — 적재 성공 + 인입 행 DONE
        assertThat(ingested).isTrue();
        Map<String, Object> ingest = jdbc.queryForMap(
                "SELECT prcs_stts_cd, raw_sn FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn);
        assertThat(ingest.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_DONE);

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
    @DisplayName("업로드_취소시_인입행이_FAILED로_종결되고_24시간_재시도로_쌓이지_않는다")
    void cancelTerminatesIngestRow() {
        UUID uploadId = createSession();
        long rcptnSn = rcptnSn();

        tusUploadService.cancel(uploadId, OWNER);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, err_msg, nxtm_rty_dt FROM ls_data_ingest WHERE rcptn_sn = ?",
                rcptnSn);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        assertThat((String) row.get("err_msg")).contains("취소");
        assertThat(row.get("nxtm_rty_dt")).isNull();

        // then — 종결된 행은 폴링 후보가 아니다(파일이 영영 오지 않는 행을 24시간 재시도하지 않는다)
        assertThat(pollCandidates()).doesNotContain(rcptnSn);
        // then — 세션 행·임시 파일도 정리된다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ls_tus_upload WHERE uld_id = ?",
                Integer.class, uploadId)).isZero();
    }

    @Test
    @DisplayName("매직바이트_실패시_세션종결이_롤백에도_살아남고_인입행도_FAILED로_종결된다")
    void magicByteFailureTerminatesSessionAndIngestRow() {
        // given — 영상 컨테이너가 아닌 페이로드(매직바이트 불일치)
        byte[] bogus = new byte[16];
        java.util.Arrays.fill(bogus, (byte) 'X');
        UUID uploadId = tusUploadService.createSession(OWNER, bogus.length, request());
        long rcptnSn = rcptnSn();

        // when — 완료 시점 검증 실패 → 임시 파일은 <이미 지워지고> PATCH 트랜잭션은 롤백된다
        assertThatThrownBy(() -> tusUploadService.appendChunk(uploadId, OWNER, 0,
                new ByteArrayInputStream(bogus), bogus.length))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class)
                .extracting(e -> ((kr.co.cudo.authoring.common.exception.CustomException) e).getErrorCode())
                .isEqualTo(kr.co.cudo.authoring.common.exception.ErrorCode.CONFLICT);

        Map<String, Object> session = jdbc.queryForMap(
                "SELECT stts_cd, uld_offset FROM ls_tus_upload WHERE uld_id = ?", uploadId);

        // then — ★같은 트랜잭션 쓰기는 되돌아갔다는 대조 증거: 오프셋 전진이 롤백돼 0 이다.
        assertThat(((Number) session.get("uld_offset")).longValue())
                .as("같은 트랜잭션 쓰기는 롤백된다")
                .isZero();

        // then — ★그런데 종결은 별도 트랜잭션(afterCompletion + REQUIRES_NEW)이라 살아남는다.
        assertThat(session.get("stts_cd")).isEqualTo("EXPIRED");

        // then — ★인입 행도 롤백에 살아남아 FAILED 로 종결된다(REQUIRES_NEW).
        //   같은 트랜잭션에서 종결하면 함께 되돌아가 파일이 영영 오지 않는 행이 24시간 대기한다.
        Map<String, Object> ingest = jdbc.queryForMap(
                "SELECT prcs_stts_cd, err_msg FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn);
        assertThat(ingest.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        assertThat((String) ingest.get("err_msg")).isNotBlank();
    }

    @Test
    @DisplayName("진행중인_클립을_다시_업로드하려_하면_세션생성_단계에서_409")
    void duplicateClipRejectedAtSessionCreate() {
        createSession();
        assertThat(ingestRepository.findByVmsClipId(clipId)).isPresent();

        assertThatThrownBy(() -> createSession())
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class)
                .extracting(e -> ((kr.co.cudo.authoring.common.exception.CustomException) e).getErrorCode())
                .isEqualTo(kr.co.cudo.authoring.common.exception.ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("M1_취소로_종결된_클립ID는_행을_되살려_재업로드할_수_있다 — 행_삭제도_UK_위반도_없다")
    void cancelledClipIdIsRevivedOnReupload() {
        // given — 취소로 인입 행이 FAILED 종결(파일 미도착). 구 구현은 이 행이 UK 를 영구
        //   점유해 같은 클립 ID 를 어떤 API 로도 회수할 수 없었다(requeue 는 UK 를 풀지 못한다).
        UUID first = createSession();
        long rcptnSn = rcptnSn();
        tusUploadService.cancel(first, OWNER);
        assertThat(jdbc.queryForObject("SELECT prcs_stts_cd FROM ls_data_ingest WHERE rcptn_sn = ?",
                String.class, rcptnSn)).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);

        // when — 같은 클립 ID 로 재업로드
        UUID second = createSession();

        // then — 행은 <같은 PK 로 되살아나> 다시 폴링 대상이 된다(새 행 INSERT 아님 = UK 위반 없음)
        assertThat(second).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id = ?", Integer.class, clipId))
                .as("행을 새로 만들면 UK 위반이고, 지우면 감사 추적이 사라진다").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT rcptn_sn, prcs_stts_cd, err_msg, prcs_dt, nxtm_rty_dt"
                        + " FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(((Number) row.get("rcptn_sn")).longValue()).isEqualTo(rcptnSn);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(row.get("err_msg")).as("되살린 행에 이전 종결 사유가 남으면 오판을 부른다").isNull();
        assertThat(row.get("prcs_dt")).as("대기 예산 앵커도 리셋해야 즉시 재종결되지 않는다").isNull();
        assertThat(row.get("nxtm_rty_dt")).isNull();
        assertThat(pollCandidates()).contains(rcptnSn);
    }

    @Test
    @DisplayName("M1_이미_적재된(DONE)_클립은_되살리지_않고_409 — 적재된_영상을_덮어쓰지_않는다")
    void ingestedClipIsNotRevived() {
        // given — 업로드 → 폴링 적재 완료(인입 DONE + LS_DATA_RAW 존재)
        uploadOneClip();
        ingestTx.ingestOne(ingestRepository.findById(rcptnSn()).orElseThrow());

        assertThatThrownBy(() -> createSession())
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class)
                .extracting(e -> ((kr.co.cudo.authoring.common.exception.CustomException) e).getErrorCode())
                .isEqualTo(kr.co.cudo.authoring.common.exception.ErrorCode.CONFLICT);
    }

    /**
     * 지금 픽업 대상인 인입 행 PK 목록 — <b>프로덕션 폴링과 동일한 조회</b>.
     *
     * <p>{@code NXTM_RTY_DT} 를 SQL 로 직접 비교하지 않는 이유: 폴링 술어는 앱이 넘긴 {@code :now}
     * (우리 시계)와 비교하는데, 테스트 DB 컨테이너는 UTC 라 {@code now()} 로 단언하면 <b>타임존 차이가
     * 결과를 결정</b>한다. 상한을 크게 잡아 다른 시드에 밀려 누락되지 않게 한다.
     */
    private java.util.List<Long> pollCandidates() {
        return ingestRepository
                .findPendingReadyForPolling(LocalDateTime.now(),
                        org.springframework.data.domain.PageRequest.of(0, 500))
                .stream().map(LsDataIngest::getRcptnSn).toList();
    }

    private long rcptnSn() {
        return jdbc.queryForObject(
                "SELECT rcptn_sn FROM ls_data_ingest WHERE vms_clip_id = ?", Long.class, clipId);
    }
}
