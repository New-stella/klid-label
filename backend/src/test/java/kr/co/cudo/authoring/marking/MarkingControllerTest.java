package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MarkingController 통합 테스트 (SpringBootTest + MockMvc + PostgreSQL Testcontainer).
 *
 * <p><b>커밋 기반 시드 (MEDIUM-1 이후 필수):</b> AUTO 마킹의 영상 길이 해석({@code VideoDurationResolver})은
 * 이제 컨트롤러가 <b>서비스 쓰기 트랜잭션 진입 전</b>에 수행하며, ffprobe 폴백이 DB 커넥션을 보유하지 않도록
 * 값싼 DB read(VDO_LEN_SEC·메타)를 <b>별도 {@code REQUIRES_NEW} 트랜잭션</b>으로 읽는다. 이 pre-read 는
 * 테스트의 ambient(롤백) 트랜잭션에 <b>미커밋</b> 시드된 영상을 보지 못하므로(별도 tx = 미커밋 불가시),
 * 시드 영상/배정을 {@link #committedTx}(REQUIRES_NEW)로 <b>실제 커밋</b>해 pre-read 가 보게 한다.
 *
 * <p>반면 마킹 생성({@code create}) 자체는 여전히 클래스 레벨 {@code @Transactional} 의 ambient 트랜잭션에
 * join 하여 테스트 종료 시 롤백된다 — 이렇게 해야 {@code MarkingBatchBridge} 의 {@code AFTER_COMMIT}
 * 리스너(→ 비동기 배치)가 발화하지 않아 테스트 부작용을 차단한다. 커밋한 시드(영상·배정)만 {@link #tearDown}
 * 에서 별도 커밋 트랜잭션으로 정리한다(LS_MARKING↔LS_DATA_RAW FK 없음 — 롤백 대기 데드락 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class MarkingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsMarkingRepository markingRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /** 시드/정리를 ambient(롤백) 트랜잭션과 독립적으로 <b>실제 커밋</b>하기 위한 REQUIRES_NEW 템플릿. */
    private final TransactionTemplate committedTx;

    private String reviewerToken;
    private String workerToken;
    /** 타인(2002) 토큰 — I4 수평 권한 상승 재현용 (sub 200 토큰은 위 workerToken 과 별개). */
    private String otherWorkerToken;
    private Long rawSn;

    /** workerToken 의 sub = 100 (= LABELER 배정 대상), otherWorkerToken 의 sub = 200 (미배정). */
    private static final long WORKER_NO = 100L;

    MarkingControllerTest(@Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.committedTx = new TransactionTemplate(controlTxManager);
        this.committedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        otherWorkerToken = JwtTestSupport.token(secret, "200", "WORKER", "INTERNAL", issuer, 60);

        // 영상 시드 — 마킹은 비식별 완료(deIdntfYn='Y') + 마킹 준비(MARKING_READY) 영상 대상이므로
        // 시드도 비식별 완료 + MARKING_READY 로 둔다(배치 단계 가드 통과). VDO_LEN_SEC=60 이라
        // AUTO 마킹의 트랜잭션-외 duration pre-read 가 프로브 없이 60초를 해석한다.
        // ★ 커밋 필수: pre-read 는 REQUIRES_NEW 라 ambient 미커밋 시드를 못 본다.
        committedTx.executeWithoutResult(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "CLIP-MARKING-" + System.nanoTime(), "CCTV-001", "FIRE", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/marking-test.mp4",
                    LocalDateTime.now(), 60);
            raw.markDeidentified("Y");
            raw.markMarkingReady();
            LsDataRaw saved = videoRepository.save(raw);
            this.rawSn = saved.getRawSn();

            // I4: WORKER(sub=100) 를 이 영상의 LABELER 로 배정 — 본인 배정 영상은 마킹 생성/조회 가능해야 한다.
            assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, WORKER_NO, 1L));
        });
    }

    @AfterEach
    void tearDown() {
        // 커밋한 시드(영상·배정)를 별도 커밋 트랜잭션으로 정리한다. create() 가 만든 마킹은 ambient
        // 롤백으로 사라지므로(미커밋) 커밋 정리 tx 에서는 보이지 않는다. FK(LS_MARKING→LS_DATA_RAW)가
        // 없어 미커밋 자식으로 인한 부모 삭제 대기(데드락)도 없다.
        if (rawSn == null) {
            return;
        }
        committedTx.executeWithoutResult(s -> {
            markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn).forEach(markingRepository::delete);
            assignmentRepository
                    .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_LABELER, List.of(rawSn))
                    .forEach(assignmentRepository::delete);
            videoRepository.deleteById(rawSn);
        });
    }

    @Test
    @DisplayName("POST_마킹_자동모드_생성_201")
    void createAutoMode201() throws Exception {
        // given — intervalFrames=300 (30fps * 10sec), durationSec=60 (트랜잭션-외 pre-read 로 해석)
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.markingMode").value("AUTO"))
                // 이벤트명은 요청이 아니라 영상의 evntTypeCd(FIRE)에서 자동 소싱된다.
                .andExpect(jsonPath("$.data.eventName").value("FIRE"))
                .andExpect(jsonPath("$.data.intervalFrames").value(300))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.marks").isArray())
                // BE-1 off-by-one 수정: 60*30=1800 totalFrames, 0..1500 (1800/300=6개) — 끝 경계 1800 미포함
                .andExpect(jsonPath("$.data.marks.length()").value(6));

        // DB 검증 (ambient tx 에서 방금 생성된 마킹 조회)
        List<LsMarking> saved = markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getMarkModeCd()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("POST_마킹_수동모드_생성_201")
    void createManualMode201() throws Exception {
        // given
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05"),
                new MarkItem(300, "00:10")
        );
        MarkingRequest req = new MarkingRequest("MANUAL", null, marks);

        // when / then
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.markingMode").value("MANUAL"))
                // 이벤트명은 영상의 evntTypeCd(FIRE)에서 자동 소싱된다.
                .andExpect(jsonPath("$.data.eventName").value("FIRE"))
                .andExpect(jsonPath("$.data.marks.length()").value(3))
                .andExpect(jsonPath("$.data.marks[0].frameIndex").value(0))
                .andExpect(jsonPath("$.data.marks[2].frameIndex").value(300));
    }

    @Test
    @DisplayName("POST_미존재_영상_마킹_404")
    void createNonExistentVideo404() throws Exception {
        // given
        Long nonExistentRawSn = 999999L;
        MarkingRequest req = new MarkingRequest("AUTO", 10, null);

        // when / then — 미존재 영상은 duration pre-read 가 null(리더가 영상 못 찾음)이어도
        // create() 의 findById 가 NOT_FOUND 로 거부한다.
        mockMvc.perform(post("/v1/videos/" + nonExistentRawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("POST_이벤트유형_미지정_영상_400")
    void createOnVideoWithoutEventType400() throws Exception {
        // given — evntTypeCd 가 없는 비식별 완료 영상 (이벤트 유형 자동 소싱 불가). 커밋 시드해
        // create() 의 findById(ambient) 와 duration pre-read(REQUIRES_NEW) 양쪽에서 보이게 한다.
        Long[] holder = new Long[1];
        committedTx.executeWithoutResult(s -> {
            LsDataRaw noEventRaw = LsDataRaw.createFromIngest(
                    "CLIP-NOEVT-" + System.nanoTime(), "CCTV-003", null, "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/noevt.mp4",
                    LocalDateTime.now(), 60);
            noEventRaw.markDeidentified("Y");
            noEventRaw.markMarkingReady();
            holder[0] = videoRepository.save(noEventRaw).getRawSn();
        });
        Long noEventRawSn = holder[0];

        try {
            MarkingRequest req = new MarkingRequest("AUTO", 10, null);

            // when / then — 이벤트 유형 미지정 영상은 마킹 불가(400)
            mockMvc.perform(post("/v1/videos/" + noEventRawSn + "/markings")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        } finally {
            committedTx.executeWithoutResult(s -> videoRepository.deleteById(noEventRawSn));
        }
    }

    @Test
    @DisplayName("미인증_요청_401")
    void unauthenticated401() throws Exception {
        // given
        MarkingRequest req = new MarkingRequest("AUTO", 10, null);

        // when / then — Authorization 헤더 없이 요청
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ── I4 (CWE-639) 수평 권한 상승 가드 — WORKER 는 본인 배정 영상만 ──

    @Test
    @DisplayName("I4_타인배정_영상_마킹생성_미배정_WORKER_403")
    void createMarkingUnassignedWorkerForbidden() throws Exception {
        // given — otherWorkerToken(sub=200) 은 이 영상의 LABELER 가 아니다 (배정자는 sub=100)
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then — 미배정 WORKER 의 마킹 생성은 403
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + otherWorkerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // DB 검증 — 마킹이 생성되지 않아야 한다
        assertThat(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("I4_본인배정_영상_마킹생성_WORKER_201")
    void createMarkingAssignedWorkerOk() throws Exception {
        // given — workerToken(sub=100) 본인 배정 영상
        MarkingRequest req = new MarkingRequest("MANUAL", null,
                List.of(new MarkItem(0, "00:00")));

        // when / then — 본인 배정 WORKER 의 마킹 생성은 201
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

}
