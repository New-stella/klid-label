package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestTx;
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
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>관제 인입으로 적재된 영상이 마킹 프리컨디션을 통과하는가</b> — 적재→마킹 경계 회귀 테스트.
 *
 * <h3>재현한 실장애</h3>
 * <p>관제 인입 경로로 적재된 영상은 {@code LS_DATA_RAW.EVNT_TYPE_CD} 가 <b>항상 null</b> 이라
 * 자동마킹({@code POST /v1/videos/{rawSn}/markings})이 <b>100% 400</b> 으로 실패했다.
 * <pre>{@code {"errorCode":"INVALID_INPUT","message":"이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다."}}</pre>
 * 적재 주체가 관제 인입으로 반전된 뒤 이 경로가 주 경로이므로 <b>신규 영상 전량</b>이 마킹 불가였다.
 *
 * <h3>왜 두 도메인을 가로지르는 테스트인가</h3>
 * <p>결함은 어느 한쪽에 있지 않았다 — 적재({@code video})는 "유형코드는 관제가 안 준다"고 null 을 넣고,
 * 마킹({@code marking})은 그 값을 <b>필수 프리컨디션</b>으로 요구했다. 각 도메인의 단위 테스트는
 * 양쪽 모두 통과했고 <b>경계에서만</b> 깨졌다. 그래서 이 테스트는 실제 적재를 돌린 뒤 그 산출물을
 * {@link MarkingGuards} 에 그대로 먹인다(가드는 package-private 이라 이 패키지에 둔다).
 *
 * <p>비식별 본체는 관심사가 아니므로 {@link AsyncDeidentifyRunner} 만 목으로 대체한다. 비식별 완료·
 * {@code MARKING_READY} 는 마킹 진입의 <b>선행 단계</b>일 뿐 이 테스트의 검증축이 아니므로, 적재 산출물에
 * 그 두 상태만 얹어 <b>이벤트유형 축</b>만 남긴다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT
})
class MarkingEventTypeAfterControlIngestIT {

    private static final String CLIP_PREFIX = "MARK-EVNT-IT-";
    private static final String EVNT_ID_PREFIX = "MARK-EVNT-IT-EVT-";

    @Autowired
    private TrainingVideoIngestTx ingestTx;

    @Autowired
    private LsDataIngestRepository ingestRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 비식별 본체는 관심사가 아니다 — 적재 후 체인이 도는 것만 막아 테스트를 격리한다. */
    @MockBean
    private AsyncDeidentifyRunner asyncDeidentifyRunner;

    private JdbcTemplate jdbc;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        runId = String.valueOf(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        // 실제 커밋을 일으키는 테스트라 롤백 정리가 없다 — 시드 접두로만 지운다.
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM mng_clip_evnt_lst WHERE evnt_id LIKE ?", EVNT_ID_PREFIX + "%");
    }

    @Test
    @DisplayName("관제_인입으로_적재된_영상은_마킹_프리컨디션을_통과한다")
    void ingestedVideoPassesMarkingPreconditions() throws IOException {
        // given — 관제가 준 EVNT_ID 에 대응하는 이벤트 유형이 공유 이벤트리스트에 있다.
        String evntId = EVNT_ID_PREFIX + "OK-" + runId;
        seedEventType(evntId, "INTRUSION");
        LsDataRaw raw = ingestAndReload("OK", evntId);

        // then — ★적재 시점에 이벤트유형이 채워진다(이 값이 비면 자동마킹이 전량 400 이었다).
        assertThat(raw.getEvntTypeCd()).isEqualTo("INTRUSION");

        // when/then — 마킹 프리컨디션이 통과한다(사용자가 겪은 400 이 재현되지 않는다).
        markReadyForMarking(raw);
        assertThatCode(() -> MarkingGuards.requirePreconditions(raw)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이벤트유형_매칭이_없으면_적재는_성공하되_마킹은_기존_가드가_막는다")
    void unresolvedEventTypeStillIngestsButMarkingStaysBlocked() throws IOException {
        // given — EVNT_ID 에 매칭되는 유형 행이 없다(공유 이벤트리스트 미등록).
        String evntId = EVNT_ID_PREFIX + "MISS-" + runId;
        LsDataRaw raw = ingestAndReload("MISS", evntId);

        // then — 적재는 성공한다. 적재를 실패시키면 영상이 아예 안 들어와 되돌리기가 더 어렵다.
        assertThat(raw).isNotNull();
        assertThat(raw.getEvntTypeCd()).isNull();

        // then — 마킹은 기존 가드가 그대로 막는다(현행 동작 유지 — 무단 완화가 아니다).
        markReadyForMarking(raw);
        assertThatThrownBy(() -> MarkingGuards.requirePreconditions(raw))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다.")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------------------------------------------------------------- fixtures

    /** 관제 공유 이벤트리스트에 (EVNT_ID, EVNT_TYPE_CD) 1행을 심는다 — 읽기 소스 모사. */
    private void seedEventType(String evntId, String evntTypeCd) {
        jdbc.update("INSERT INTO mng_clip_evnt_lst (evnt_id, evnt_type_cd, sht_dt)"
                + " VALUES (?, ?, now())", evntId, evntTypeCd);
    }

    /** 인입 1건을 심고 실제 적재를 돌린 뒤 적재 결과({@code LS_DATA_RAW})를 돌려준다. */
    private LsDataRaw ingestAndReload(String suffix, String evntId) throws IOException {
        Path video = ArtifactRootTestSupport.seedOriginalVideo("mark-evnt");
        String clipId = CLIP_PREFIX + suffix + "-" + runId;
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, proc_stts_cd, vdo_len_sec, lclgv_cd, evnt_id)
                VALUES (?, 'CCTV-MARK-01', 'clip.mp4', ?, 'RELAY', now(), 'PENDING', 600, '30200', ?)
                """, clipId, video.toString(), evntId);
        Long rcptnSn = jdbc.queryForObject(
                "SELECT rcptn_sn FROM ls_data_ingest WHERE vms_clip_id = ?", Long.class, clipId);

        boolean ingested = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());
        assertThat(ingested).isTrue();
        return videoRepository.findByVmsClipId(clipId).orElseThrow();
    }

    /**
     * 마킹 진입의 <b>선행 단계</b>(비식별 완료 + MARKING_READY)만 얹는다.
     *
     * <p>이 두 값은 이 테스트의 검증축이 아니라 이벤트유형 가드까지 도달하기 위한 전제다 —
     * 가드 평가 순서가 ③비식별 → ④MARKING_READY → ⑤이벤트유형이라, 앞 단계에서 막히면
     * 정작 보려는 ⑤가 평가되지 않는다.
     */
    private static void markReadyForMarking(LsDataRaw raw) {
        ReflectionTestUtils.setField(raw, "deIdntfYn", MarkingGuards.DEIDENTIFIED);
        ReflectionTestUtils.setField(raw, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
    }
}
