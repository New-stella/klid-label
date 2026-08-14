package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelHistoryResponse;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — bulkUpsert full-replace + 저장 이벤트 before→after diff 통합 테스트
 * (@SpringBootTest + Testcontainers PostgreSQL).
 *
 * <p>검증 범위:
 * <ul>
 *   <li>full-replace: 요청에 빠진 라벨은 실제 삭제(자식 ATTR_VAL/AI_INFO 포함, FK 위반 없음) — HIGH #1/#2.</li>
 *   <li>DELETED 저장 이벤트에 before 스냅샷 기록(사후 복구 근거) — HIGH #5/#6.</li>
 *   <li>UPDATED 의 before≠after(수정 전 값 캡처) — HIGH #3.</li>
 *   <li>무변경(빈 요청 + 기존 없음)이면 이력·통지 미생성 — LOW #12/R7.</li>
 *   <li>손상된 diff JSON 조회 시 200 + 빈 changes 폴백 — HIGH #9.</li>
 *   <li>타인 배정 프레임 저장은 403 — IDOR 회귀.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
@RecordApplicationEvents
class LabelServiceFullReplaceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LabelService labelService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsDataLblAttrValRepository attrValRepository;
    @Autowired private LsLabelRepository labelMasterRepository;
    @Autowired private LsLabelAttrRepository labelAttrRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsDataLblHstryRepository historyRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;
    @Autowired private ApplicationEvents applicationEvents;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerAssignedToken;
    private String workerNotAssignedToken;

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        workerNotAssignedToken = JwtTestSupport.token(secret, "101", "WORKER", "INTERNAL", issuer, 60);

        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-FR-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now())).getSrcSn();

        // 작업자 100 만 배정 (101 은 미배정 — IDOR 검증).
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private TokenClaims workerAssigned() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LabelItemDto item(Long id, String label, double x, double y) {
        return new LabelItemDto(id, "BBOX", null, label,
                List.of(List.of(x, y), List.of(x + 10, y + 10)), null);
    }

    private void put(LabelBulkUpsertRequest req, String token) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("저장시_요청에_빠진_라벨은_실제_삭제된다")
    void omittedLabelIsPhysicallyDeleted() {
        // given — 라벨 2건 직접 시드.
        LsDataLbl a = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]", 100L));
        LsDataLbl b = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[3.0,3.0],[4.0,4.0]]", 100L));
        labelRepository.flush();

        // when — a 만 담아 저장(b 누락) → full-replace 로 b 삭제.
        labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(item(a.getLblSn(), "person", 5.0, 5.0))),
                workerAssigned());

        // then — a 만 생존, b 는 물리 삭제 (bulk 삭제 후 findById 는 L1 캐시를 볼 수 있어 DB 를 치는
        // JPQL findBySrcSn 로 검증한다).
        List<LsDataLbl> after = labelRepository.findBySrcSn(srcSn);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getLblSn()).isEqualTo(a.getLblSn());
        assertThat(after).extracting(LsDataLbl::getLblSn).doesNotContain(b.getLblSn());
    }

    @Test
    @DisplayName("프레임_단위_저장은_추적_식별자를_수용하지_않는다")
    void 프레임_단위_저장은_추적_식별자를_수용하지_않는다() {
        // given — 트랙에 묶인 라벨. 사양(API-196)이 trackId 를 정의한 곳은 확정 저장 경로뿐이다.
        LsDataLbl a = labelRepository.save(LsDataLbl.createRestored(srcSn, "BBOX", null, "person",
                "[[1.0,1.0],[2.0,2.0]]", "N", null, "T-1", null));
        labelRepository.flush();

        // when — 프레임 단위 저장에 trackId 를 실어 보낸다
        LabelItemDto moved = new LabelItemDto(a.getLblSn(), "BBOX", null, "person",
                List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null, null, null, null, "T-2");
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of(moved)), workerAssigned());
        labelRepository.flush();

        // then — 무시된다. 트랙 재지정은 TrackMergeService(영상 배타 락·겹침 검사·보간 정리·통지)가
        //   소유하는 행위라, 가드 있는 문 옆에 가드 없는 문을 내지 않는다.
        assertThat(labelRepository.findBySrcSn(srcSn).get(0).getTrackId()).isEqualTo("T-1");
    }

    @Test
    @DisplayName("삭제된_라벨의_속성값과_AI정보도_함께_제거된다")
    void deletedLabelChildrenRemovedWithoutFkViolation() {
        // given — 라벨 2건, b 에 속성값(실 FK) + AI 정보 부착.
        LsDataLbl a = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]", 100L));
        LsDataLbl b = labelRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "car", "[[3.0,3.0],[4.0,4.0]]", new BigDecimal("0.9"), null));
        LsLabel master = labelMasterRepository.findByLabelNmIgnoreCaseAndUseYn("car", "Y")
                .orElseGet(() -> labelMasterRepository.save(LsLabel.create("car", "#00FF00", "BBOX", 0, "test")));
        LsLabelAttr attr = labelAttrRepository.save(
                LsLabelAttr.create(master.getLabelId(), "색상", "TEXT", null, null, "Y", 0, "test"));
        attrValRepository.save(LsDataLblAttrVal.create(b.getLblSn(), attr.getAttrId(), "빨강"));
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        b.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.9"));
        labelRepository.saveAndFlush(b);
        labelRepository.flush();

        // when — a 만 담아 저장(b 누락) → b + 자식 삭제. FK 위반(500) 없어야 한다.
        labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(item(a.getLblSn(), "person", 5.0, 5.0))),
                workerAssigned());

        // then — b 및 그 속성값 제거(고아 0). bulk 삭제 후라 DB 를 치는 JPQL 쿼리로 검증.
        //   V6 — 생산이력은 라벨 행의 컬럼이라 라벨이 사라지면 함께 사라진다(고아 자체가 성립 불가).
        assertThat(labelRepository.findBySrcSn(srcSn)).extracting(LsDataLbl::getLblSn)
                .containsExactly(a.getLblSn());
        assertThat(attrValRepository.findByLblSnIn(List.of(b.getLblSn()))).isEmpty();
    }

    @Test
    @DisplayName("삭제_라벨은_저장이벤트에_DELETED_before스냅샷으로_기록된다")
    void deletedLabelRecordedWithBeforeSnapshot() {
        LsDataLbl a = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]", 100L));
        LsDataLbl b = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[3.0,3.0],[4.0,4.0]]", 100L));
        labelRepository.flush();

        labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(item(a.getLblSn(), "person", 5.0, 5.0))),
                workerAssigned());

        Page<LabelHistoryResponse> page = labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        LabelHistoryResponse ev = page.getContent().get(0);
        assertThat(ev.delCnt()).isEqualTo(1);
        LabelHistoryResponse.LabelChangeView deleted = ev.changes().stream()
                .filter(c -> "DELETED".equals(c.changeKind()))
                .findFirst().orElseThrow();
        assertThat(deleted.lblSn()).isEqualTo(b.getLblSn());
        assertThat(deleted.before()).isNotNull();
        assertThat(deleted.before().labelNm()).isEqualTo("car");
        assertThat(deleted.before().pointCn()).contains("3.0");
        assertThat(deleted.after()).isNull();
    }

    @Test
    @DisplayName("첫_저장은_모든_객체가_ADDED로_기록된다")
    void firstSaveAllAdded() {
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of(
                item(null, "person", 1.0, 1.0),
                item(null, "car", 3.0, 3.0))), workerAssigned());

        Page<LabelHistoryResponse> page = labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20));
        LabelHistoryResponse ev = page.getContent().get(0);
        assertThat(ev.addCnt()).isEqualTo(2);
        assertThat(ev.mdfcnCnt()).isEqualTo(0);
        assertThat(ev.delCnt()).isEqualTo(0);
        assertThat(ev.changes()).hasSize(2);
        assertThat(ev.changes()).allMatch(c -> "ADDED".equals(c.changeKind()));
        assertThat(ev.changes()).allMatch(c -> c.before() == null && c.after() != null);
    }

    @Test
    @DisplayName("수정_객체는_이전값과_새값이_모두_기록된다")
    void updatedRecordsBeforeAndAfter() {
        LsDataLbl seed = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[5.0,5.0],[40.0,40.0]]", 100L));
        labelRepository.flush();

        // 좌표 + 라벨명 변경.
        labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(item(seed.getLblSn(), "bus", 99.0, 99.0))),
                workerAssigned());

        Page<LabelHistoryResponse> page = labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20));
        LabelHistoryResponse ev = page.getContent().get(0);
        assertThat(ev.mdfcnCnt()).isEqualTo(1);
        LabelHistoryResponse.LabelChangeView updated = ev.changes().stream()
                .filter(c -> "UPDATED".equals(c.changeKind()))
                .findFirst().orElseThrow();
        assertThat(updated.before()).isNotNull();
        assertThat(updated.after()).isNotNull();
        assertThat(updated.before().labelNm()).isEqualTo("car");
        assertThat(updated.after().labelNm()).isEqualTo("bus");
        assertThat(updated.before().pointCn()).isNotEqualTo(updated.after().pointCn());
        assertThat(updated.after().pointCn()).contains("99.0");
    }

    @Test
    @DisplayName("아무_변경없이_저장하면_이력도_통지도_생기지_않는다")
    void noChangeNoHistory() {
        // 기존 라벨 없음 + 빈 요청 → 델타 0.
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of()), workerAssigned());

        assertThat(historyRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();
        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
    }

    @Test
    @DisplayName("타인_배정_프레임_저장은_403")
    void idorForbiddenOnSave() {
        TokenClaims notAssigned = new TokenClaims("101", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(item(null, "person", 1.0, 1.0))), notAssigned))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다")
    void corruptDiffJsonGracefulFallback() throws Exception {
        // given — 정상 저장 이벤트 1건 기록 후 CHG_DTL_CN 을 손상값으로 덮어쓴다(reflection — 저장 후 손상 시뮬레이션).
        LabelChange ch = LabelChange.added(999L, "person", new LabelSnapshot("BBOX", null, "person", "[]"));
        LsDataLblHstry ev = historyRepository.saveAndFlush(LsDataLblHstry.recordSaveEvent(srcSn, "100", List.of(ch)));
        Field f = LsDataLblHstry.class.getDeclaredField("chgDtlCn");
        f.setAccessible(true);
        f.set(ev, "{this is : not valid json ][");
        historyRepository.saveAndFlush(ev);

        // when/then — 조회는 500 이 아니라 200 + 빈 changes(레코드 단위 격리, HIGH #9).
        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].addCnt").value(1))
                .andExpect(jsonPath("$.data.content[0].changes.length()").value(0));
    }

    @Test
    @DisplayName("조회API는_이벤트요약과_before_after_변경상세를_반환한다")
    void historyApiReturnsSummaryAndDiff() throws Exception {
        LsDataLbl seed = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[5.0,5.0],[40.0,40.0]]", 100L));
        labelRepository.flush();

        put(new LabelBulkUpsertRequest(List.of(
                item(seed.getLblSn(), "bus", 11.0, 11.0),   // UPDATED
                item(null, "person", 1.0, 1.0))),           // ADDED
                workerAssignedToken);

        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].addCnt").value(1))
                .andExpect(jsonPath("$.data.content[0].mdfcnCnt").value(1))
                .andExpect(jsonPath("$.data.content[0].changes.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].changes[?(@.changeKind=='UPDATED')].before.labelNm").value("car"))
                .andExpect(jsonPath("$.data.content[0].changes[?(@.changeKind=='UPDATED')].after.labelNm").value("bus"));
    }

    /** 검수 완료(APPROVED) 상태로 만들어 TASK_MODIFIED 통지 경로를 활성화한다(무변경 통지 억제 검증용). */
    private void approveRaw() {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        st.transitionTo(LsRawDataStatus.STTS_APPROVED);
        rawDataStatusRepository.saveAndFlush(st);
    }

    @Test
    @DisplayName("무변경_재저장시_이력도_통지도_생기지_않는다")
    void noChangeResaveNoHistoryNoNotification() {
        // given — 검수완료(APPROVED) 영상 + 기존 라벨 1건. 좌표는 정수 JSON 으로 저장해 재직렬화 시
        //   표현차(5 vs 5.0)를 유발한다(정규화 비교가 무변경으로 판정해야 함, R7/HIGH-1).
        approveRaw();
        LsDataLbl seed = labelRepository.save(
                LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[5,5],[15,15]]", 100L));
        labelRepository.flush();

        // when — 라벨명/타입/labelId 동일 + 좌표 수치 동일(표현만 5 vs 5.0)으로 프레임 전체 세트 재전송.
        labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(item(seed.getLblSn(), "car", 5.0, 5.0))),
                workerAssigned());

        // then — 실질 무변경 → 이력 0행 + TASK_MODIFIED 통지 미발행(APPROVED 라 통지 경로는 활성 상태).
        assertThat(historyRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();
        assertThat(applicationEvents.stream(TaskModifiedEvent.class)
                .filter(e -> e.rawSn().equals(rawSn))).isEmpty();
        // 라벨 자체는 유지(삭제되지 않음).
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("평탄포맷_레거시라벨_무변경_재저장시_이력_통지_없음")
    void flatLegacyPointsNoChangeResave() {
        // given — 검수완료(APPROVED) 영상 + Phase 1 정규화 이전 평탄 포맷 [x1,y1,x2,y2] 레거시 라벨을
        //   리포지토리 직접 저장으로 시드(정규화 전 brownfield 데이터 시뮬레이션).
        approveRaw();
        LsDataLbl seed = labelRepository.save(
                LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[3,4,5,6]", 100L));
        labelRepository.flush();

        // when — 동일 좌표를 정규 요청 [[3,4],[5,6]] 으로 재저장(라벨명/타입/labelId 동일).
        LabelItemDto same = new LabelItemDto(seed.getLblSn(), "BBOX", null, "car",
                List.of(List.of(3.0, 4.0), List.of(5.0, 6.0)), null);
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of(same)), workerAssigned());

        // then — 3포맷 정규화 비교로 실질 무변경 판정 → 이력 0 + TASK_MODIFIED 미발행(R7).
        assertThat(historyRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();
        assertThat(applicationEvents.stream(TaskModifiedEvent.class)
                .filter(e -> e.rawSn().equals(rawSn))).isEmpty();
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("객체배열포맷_레거시라벨_무변경_재저장시_이력_통지_없음")
    void objectArrayLegacyPointsNoChangeResave() {
        // given — 검수완료(APPROVED) 영상 + 객체배열 포맷 [{"x":,"y":},...] 레거시 라벨 시드.
        approveRaw();
        LsDataLbl seed = labelRepository.save(
                LsDataLbl.createManual(srcSn, "BBOX", null, "car",
                        "[{\"x\":3,\"y\":4},{\"x\":5,\"y\":6}]", 100L));
        labelRepository.flush();

        // when — 동일 좌표를 정규 요청 [[3,4],[5,6]] 으로 재저장.
        LabelItemDto same = new LabelItemDto(seed.getLblSn(), "BBOX", null, "car",
                List.of(List.of(3.0, 4.0), List.of(5.0, 6.0)), null);
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of(same)), workerAssigned());

        // then — 실질 무변경 → 이력 0 + 통지 0(R7).
        assertThat(historyRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();
        assertThat(applicationEvents.stream(TaskModifiedEvent.class)
                .filter(e -> e.rawSn().equals(rawSn))).isEmpty();
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("기존라벨_전체삭제시_모두_삭제되고_DELETED만_기록되고_delCnt_N")
    void allExistingDeleted() {
        // given — existing 2건.
        labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]", 100L));
        labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[3.0,3.0],[4.0,4.0]]", 100L));
        labelRepository.flush();

        // when — 빈 items → 프레임 전체 교체로 전량 삭제.
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of()), workerAssigned());

        // then — DB 고아 0 + 이력 1건(add=0/mdfcn=0/del=2, 전부 DELETED).
        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
        LabelHistoryResponse ev = labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20))
                .getContent().get(0);
        assertThat(ev.addCnt()).isEqualTo(0);
        assertThat(ev.mdfcnCnt()).isEqualTo(0);
        assertThat(ev.delCnt()).isEqualTo(2);
        assertThat(ev.changes()).hasSize(2);
        assertThat(ev.changes()).allMatch(c -> "DELETED".equals(c.changeKind()));
    }

    @Test
    @DisplayName("추가_수정_삭제가_동시에_발생하면_각_카운트가_정확하다")
    void addUpdateDeleteMixed() {
        // given — existing a(수정 대상) + b(삭제 대상).
        LsDataLbl a = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[5.0,5.0],[15.0,15.0]]", 100L));
        labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "dog", "[[3.0,3.0],[4.0,4.0]]", 100L));
        labelRepository.flush();

        // when — a 실제수정(좌표+라벨명) + 신규 추가 + b 누락(삭제) 을 1회 저장에 혼합.
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of(
                        item(a.getLblSn(), "bus", 50.0, 50.0),   // UPDATED (실제 변경)
                        item(null, "person", 1.0, 1.0))),        // ADDED  (b 는 누락 → DELETED)
                workerAssigned());

        // then — 카운트 정확 + changes 에 3종 반영.
        LabelHistoryResponse ev = labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20))
                .getContent().get(0);
        assertThat(ev.addCnt()).isEqualTo(1);
        assertThat(ev.mdfcnCnt()).isEqualTo(1);
        assertThat(ev.delCnt()).isEqualTo(1);
        assertThat(ev.changes()).extracting(LabelHistoryResponse.LabelChangeView::changeKind)
                .containsExactlyInAnyOrder("ADDED", "UPDATED", "DELETED");
        // 최종 DB: a(수정) + 신규 person = 2건, b 삭제됨.
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(2);
    }

    @Test
    @DisplayName("다건_라벨_동시삭제시_delCnt와_DB_고아가_정확하다")
    void multiDeleteNoOrphans() {
        // given — existing 3건, 그중 b/c 에 속성값·AI정보 부착(고아 정리 검증).
        LsDataLbl a = labelRepository.save(LsDataLbl.createManual(srcSn, "BBOX", null, "l1", "[[1.0,1.0],[2.0,2.0]]", 100L));
        LsDataLbl b = labelRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "l2", "[[3.0,3.0],[4.0,4.0]]", new BigDecimal("0.9"), null));
        LsDataLbl c = labelRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "l3", "[[5.0,5.0],[6.0,6.0]]", new BigDecimal("0.8"), null));
        LsLabel master = labelMasterRepository.findByLabelNmIgnoreCaseAndUseYn("l2", "Y")
                .orElseGet(() -> labelMasterRepository.save(LsLabel.create("l2", "#00FF00", "BBOX", 0, "test")));
        LsLabelAttr attr = labelAttrRepository.save(
                LsLabelAttr.create(master.getLabelId(), "색상", "TEXT", null, null, "Y", 0, "test"));
        attrValRepository.save(LsDataLblAttrVal.create(b.getLblSn(), attr.getAttrId(), "빨강"));
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        b.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.9"));
        labelRepository.saveAndFlush(b);
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        c.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.8"));
        labelRepository.saveAndFlush(c);
        labelRepository.flush();

        // when — 빈 items → 3건 전량 삭제.
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of()), workerAssigned());

        // then — delCnt=3, 부모/자식 고아 0. (bulk 삭제 후 findById 는 L1 캐시를 볼 수 있어 DB 를 치는
        //   JPQL findBySrcSn/findByLblSnIn 으로 검증한다 — omittedLabelIsPhysicallyDeleted 와 동일 패턴.)
        assertThat(labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20))
                .getContent().get(0).delCnt()).isEqualTo(3);
        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
        assertThat(attrValRepository.findByLblSnIn(List.of(b.getLblSn()))).isEmpty();
    }

    @Test
    @DisplayName("중복된_id가_오면_last_value_wins로_한번만_처리된다")
    void duplicateIdDeduped() {
        // given — existing 라벨 1건.
        LsDataLbl seed = labelRepository.save(
                LsDataLbl.createManual(srcSn, "BBOX", null, "car", "[[5.0,5.0],[15.0,15.0]]", 100L));
        labelRepository.flush();

        // when — 동일 id 를 2회 담아 저장(뒤 항목이 최종값이어야 함).
        labelService.bulkUpsert(srcSn, new LabelBulkUpsertRequest(List.of(
                        item(seed.getLblSn(), "bus", 10.0, 10.0),   // 먼저
                        item(seed.getLblSn(), "truck", 20.0, 20.0))), // 나중(=최종)
                workerAssigned());

        // then — 라벨 1건만 존재(중복 처리/삭제 없음) + last-value(truck, 20) 반영 + UPDATED 1건만 기록.
        List<LsDataLbl> after = labelRepository.findBySrcSn(srcSn);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getLblSn()).isEqualTo(seed.getLblSn());
        assertThat(after.get(0).getLabelNm()).isEqualTo("truck");
        assertThat(after.get(0).getPointCn()).contains("20.0");
        LabelHistoryResponse ev = labelService.getHistory(srcSn, workerAssigned(), PageRequest.of(0, 20))
                .getContent().get(0);
        assertThat(ev.mdfcnCnt()).isEqualTo(1);
        assertThat(ev.delCnt()).isEqualTo(0);
    }
}
