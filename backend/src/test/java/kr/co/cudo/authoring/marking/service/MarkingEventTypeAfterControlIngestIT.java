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
import java.util.List;
import java.util.Map;

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
 * <h3>이벤트유형 <b>자동등록 배선</b>도 여기서 고정한다</h3>
 * <p>같은 적재 호출이 {@code LS_EVNT_TYPE} 에 신규 유형을 등록한다(V168). 등록 로직 자체와 트랜잭션
 * 격리는 각각 {@code LsEvntTypeAutoRegisterIT}·{@code EventTypeAutoRegisterIsolationIT} 가 보지만,
 * <b>인입 → 적재 → 등록</b>을 잇는 실동작 증거는 여기에만 있다. 배선이 조용히 끊기면(호출 누락)
 * 다른 어떤 테스트도 잡지 못한다 — 이 저장소의 "코드는 맞는데 실동작 0건" 사고 패턴이다.
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

    /**
     * 실행 전 이벤트유형 마스터 스냅샷 — 이 테스트는 <b>실제 적재</b>를 돌리므로 자동등록이 함께
     * 일어나 {@code LS_EVNT_TYPE} 에 행이 생긴다. 그대로 두면 같은 JVM 의 뒤 테스트가 보는 필터
     * 옵션이 늘어나 <b>이 테스트가 다른 테스트를 깨뜨린다</b>. 종료 시 원상 복구한다.
     */
    private List<String> preExistingEvntTypeCds;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        runId = String.valueOf(System.nanoTime());
        preExistingEvntTypeCds =
                jdbc.queryForList("SELECT evnt_type_cd FROM ls_evnt_type", String.class);
    }

    @AfterEach
    void tearDown() {
        // 실제 커밋을 일으키는 테스트라 롤백 정리가 없다 — 시드 접두로만 지운다.
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        // 적재가 자동등록한 이벤트유형만 되돌린다(사전 스냅샷 기준).
        for (String code : jdbc.queryForList("SELECT evnt_type_cd FROM ls_evnt_type", String.class)) {
            if (!preExistingEvntTypeCds.contains(code)) {
                jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd = ?", code);
            }
        }
    }

    @Test
    @DisplayName("관제_인입으로_적재된_영상은_마킹_프리컨디션을_통과한다")
    void ingestedVideoPassesMarkingPreconditions() throws IOException {
        // given — 관제가 이벤트유형코드를 <인입 평면값으로 직접> 보냈다(V166 신설 컬럼).
        //   V167 로 공유 이벤트리스트가 제거된 뒤로 이것이 유일한 조달처다.
        LsDataRaw raw = ingestAndReload("OK", "INTRUSION");

        // then — ★적재 시점에 이벤트유형이 채워진다(이 값이 비면 자동마킹이 전량 400 이었다).
        assertThat(raw.getEvntTypeCd()).isEqualTo("INTRUSION");

        // when/then — 마킹 프리컨디션이 통과한다(사용자가 겪은 400 이 재현되지 않는다).
        markReadyForMarking(raw);
        assertThatCode(() -> MarkingGuards.requirePreconditions(raw)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이벤트유형_매칭이_없으면_적재는_성공하되_마킹은_기존_가드가_막는다")
    void unresolvedEventTypeStillIngestsButMarkingStaysBlocked() throws IOException {
        // given — 관제가 이벤트유형코드를 보내지 않았다(인입 컬럼 null).
        LsDataRaw raw = ingestAndReload("MISS", null);

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

    @Test
    @DisplayName("인입_적재가_이벤트유형을_마스터에_자동등록한다")
    void 인입_적재가_이벤트유형을_마스터에_자동등록한다() throws IOException {
        // given — 관제가 유형코드와 함께 <이름·대분류·카테고리>까지 인입 평면값으로 보냈다.
        //   마스터에 없던 신규 유형이다(테스트 실행마다 고유).
        String newCode = "ITMK" + runId.substring(runId.length() - 8);
        assertThat(evntTypeRow(newCode)).as("사전 조건 — 아직 등록되지 않은 유형").isNull();

        // when — 실제 적재를 돌린다(등록 로직을 직접 부르지 않는다 — <배선>을 보는 것이 목적).
        LsDataRaw raw = ingestAndReload("AUTOREG", newCode, "관제이벤트명", "09", "0007");

        // then — 적재 자체는 기존 계약대로다(기존 검증축을 약화시키지 않는다).
        assertThat(raw.getEvntTypeCd()).isEqualTo(newCode);

        // then — ★인입 → 적재 → 자동등록 배선이 실제로 이어져 마스터에 행이 생긴다.
        //   이 단언이 없으면 register(...) 호출이 조용히 빠져도(배선 단절) 어떤 테스트도 못 잡는다
        //   — 이 저장소의 "코드는 맞는데 실동작 0건" 사고 패턴이다.
        Map<String, Object> registered = evntTypeRow(newCode);
        assertThat(registered).as("적재가 이벤트유형을 자동등록해야 한다").isNotNull();

        // then — ★값도 <관제가 보낸 그대로> 옮겨져야 한다(배선 단절뿐 아니라 오적재도 잡는다).
        assertThat(registered.get("evnt_nm")).isEqualTo("관제이벤트명");
        assertThat(registered.get("evnt_clsf_cd")).isEqualTo("09");
        assertThat(registered.get("evnt_ctgry_cd")).isEqualTo("0007");
        // 인입으로 실제 들어온 유형이므로 수집대상 기본값 Y 로 등록된다(필터에 즉시 노출).
        assertThat(((String) registered.get("clct_yn")).trim()).isEqualTo("Y");
        // 운영자 칸은 관제가 쓰지 않는다 — 자동등록이 채우면 정정 보호가 무너진다.
        assertThat(registered.get("optr_indct_nm")).isNull();
    }

    // ---------------------------------------------------------------- fixtures

    /** {@code LS_EVNT_TYPE} 단건 조회 — 미등록이면 null. */
    private Map<String, Object> evntTypeRow(String evntTypeCd) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ls_evnt_type WHERE evnt_type_cd = ?", evntTypeCd);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 인입 1건을 심고 실제 적재를 돌린 뒤 적재 결과({@code LS_DATA_RAW})를 돌려준다.
     *
     * @param evntTypeCd 관제가 인입 평면값({@code LS_DATA_INGEST.EVNT_TYPE_CD})으로 보낸 유형코드.
     *                   {@code null} 이면 관제 미송신 — 적재는 성공하되 마킹 가드가 막는다.
     */
    private LsDataRaw ingestAndReload(String suffix, String evntTypeCd) throws IOException {
        return ingestAndReload(suffix, evntTypeCd, null, null, null);
    }

    /**
     * 인입 1건(관제 수신 이벤트 축 전체)을 심고 실제 적재를 돌린다.
     *
     * @param evntNm      관제 수신 이벤트유형명 — 자동등록의 이름 원천
     * @param evntClsfCd  관제 수신 대분류 — 제외 필터 판정축
     * @param evntCtgryCd 관제 수신 카테고리 — 표시명 폴백의 근거
     */
    private LsDataRaw ingestAndReload(String suffix, String evntTypeCd, String evntNm,
                                      String evntClsfCd, String evntCtgryCd) throws IOException {
        Path video = ArtifactRootTestSupport.seedOriginalVideo("mark-evnt");
        String clipId = CLIP_PREFIX + suffix + "-" + runId;
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, proc_stts_cd, vdo_len_sec, lclgv_cd, evnt_type_cd,
                     evnt_nm, evnt_clsf_cd, evnt_ctgry_cd)
                VALUES (?, 'CCTV-MARK-01', 'clip.mp4', ?, 'RELAY', now(), 'PENDING', 600, '30200', ?,
                        ?, ?, ?)
                """, clipId, video.toString(), evntTypeCd, evntNm, evntClsfCd, evntCtgryCd);
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
