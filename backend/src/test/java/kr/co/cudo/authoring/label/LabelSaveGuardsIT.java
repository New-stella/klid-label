package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 4-B — 라벨 저장 가드 통합 테스트 (실 DB: Testcontainers PostgreSQL).
 *
 * <ul>
 *   <li>C-ISSUE-21 — 라벨셋 버전 CAS: stale 저장 409 + 앞선 라벨 보존 / 미첨부 하위호환 / 성공 시 +1.</li>
 *   <li>C-ISSUE-22 — 이미지 경계 초과 좌표 400, 경계 이내 정상 저장, 이미지 부재 시 상한만 skip.</li>
 *   <li>C-ISSUE-25 — 사용중지 마스터를 참조하는 <b>기존</b> 라벨은 저장 통과, <b>신규</b> 부여는 409.</li>
 *   <li>C-ISSUE-22(3차) — 신고 구간({@code DE_IDNTF_YN='F'}) 저장 412 + 기존 라벨 보존
 *       (작업락 6h 만료 후에도 차단 — 조회 412 ↔ 저장 200 비대칭 폐쇄).</li>
 *   <li>C-ISSUE-61/62 (TC-LABEL-150) — 그 프레임에 <b>실재하지 않는</b> id 를 붙여도 좌표 경계 상한·
 *       {@code MAX_POINTS_PER_LABEL} 상한이 그대로 강제된다(저장 분기와 동일 술어).
 *       판정 축이 "id 실재 여부"가 아니라 "<b>이 프레임에</b> 실재하는가"임을 고정하기 위해
 *       <b>타 프레임에 실재하는 id</b> 케이스(크로스 프레임 우회)도 함께 검증한다.</li>
 *   <li>배치 원자성 — 정상 아이템과 위반 아이템이 <b>한 요청</b>에 섞이면 전체가 400 으로 거부되고
 *       정상 아이템도 저장되지 않는다(부분 저장 없음).</li>
 * </ul>
 *
 * <p>트랜잭션 롤백(@Transactional)을 쓰지 않는다 — 프레임 행 락/원자 UPDATE 의 실제 커밋 동작을 봐야 하므로
 * {@link TransactionTemplate} 으로 명시 커밋하고, 각 테스트는 고유 영상/프레임을 새로 만들어 격리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LabelSaveGuardsIT {

    @Autowired private LabelService labelService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsLabelRepository labelMasterRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private WorkLockService workLockService;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    private final TransactionTemplate tx;

    LabelSaveGuardsIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    private static final long WORKER_A = 4101L;
    private static final long WORKER_B = 4102L;

    private Long rawSn;
    private Long srcSn;

    @BeforeEach
    void setUp() {
        rawSn = tx.execute(s -> rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-GUARD-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn());
        // 한 영상에 LABELER 2명 배정 — UK(RAW_DATA_ID, USER_NO, TASK_TYPE_CD)가 이를 허용한다는 사실이
        // "프레임은 단일 WORKER 배정" 가정이 틀렸다는 근거이며, 동시 편집이 실제로 가능한 조건이다.
        tx.executeWithoutResult(s -> {
            assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, WORKER_A, 1L));
            assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, WORKER_B, 1L));
        });
    }

    /** 원천 이미지가 <b>없는</b> 프레임 (상한 검증 skip 경로). */
    private Long frameWithoutImage() {
        return tx.execute(s -> srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/nonexistent/frame-" + System.nanoTime() + ".jpg",
                        LocalDateTime.now())).getSrcSn());
    }

    /** 실제 PNG 파일(width x height)을 storage 하위에 만들고 그 프레임을 반환. */
    private Long frameWithImage(int width, int height) throws Exception {
        Path base = Path.of(storageRawPath).toAbsolutePath().normalize();
        Path dir = base.resolve("it-label-guards");
        Files.createDirectories(dir);
        Path file = dir.resolve("frame-" + System.nanoTime() + ".png");
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        String relative = base.relativize(file).toString();
        return tx.execute(s -> srcRepository.save(
                LsDataSrc.create(rawSn, 1, relative, LocalDateTime.now())).getSrcSn());
    }

    private TokenClaims worker(long userNo) {
        return new TokenClaims(String.valueOf(userNo), Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));
    }

    private LabelItemDto bbox(Long id, String label, double x, double y) {
        return new LabelItemDto(id, "BBOX", null, label,
                List.of(List.of(x, y), List.of(x + 5, y + 5)), null);
    }

    // ------------------------------------------------------------------ C-ISSUE-21

    @Test
    @DisplayName("두_작업자가_동시에_라벨_저장시_stale_버전은_409_이고_앞선_라벨이_삭제되지_않음")
    void staleVersionRejectedAndEarlierLabelSurvives() {
        srcSn = frameWithoutImage();
        // given — 두 작업자가 같은 시점(버전 v0)의 프레임을 열었다.
        long openedVersion = tx.execute(s ->
                labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());

        // when — A 가 먼저 라벨 1건을 저장(버전 v0 첨부) → 성공.
        LabelResponse saved = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1)), openedVersion),
                worker(WORKER_A)));
        assertThat(saved.items()).hasSize(1);
        Long survivingLblSn = saved.items().get(0).id();

        // then — B 가 <b>같은 낡은 버전</b>으로 자기 작업본(A 의 라벨 없음)을 저장하면 409.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "car", 9, 9)), openedVersion),
                worker(WORKER_B))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 그리고 A 의 라벨은 살아있다(full-replace 로 조용히 삭제되지 않았다).
        List<LsDataLbl> remaining = labelRepository.findBySrcSn(srcSn);
        assertThat(remaining).extracting(LsDataLbl::getLblSn).containsExactly(survivingLblSn);
    }

    @Test
    @DisplayName("락_대기중_타_트랜잭션이_커밋해도_stale_저장은_409_로_거부되고_앞선_라벨이_살아남음")
    void staleVersionRejectedUnderLockWaitInterleaving() throws Exception {
        srcSn = frameWithoutImage();
        // given — 두 작업자가 같은 시점(v0)에 프레임을 열었다.
        long openedVersion = tx.execute(s ->
                labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());

        // T2: 트랜잭션 안에서 라벨을 추가(프레임 행 락 획득 + 버전 +1)한 뒤, <b>커밋하지 않은 채</b>
        //     신호를 보내고 잠시 대기한다 → T1 이 락을 기다리는 구간이 실제로 만들어진다.
        CountDownLatch t2Locked = new CountDownLatch(1);
        AtomicReference<Long> survivingLblSn = new AtomicReference<>();
        AtomicReference<Throwable> t2Error = new AtomicReference<>();
        Thread t2 = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    LabelResponse saved = labelService.bulkUpsert(srcSn,
                            new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1)), openedVersion),
                            worker(WORKER_A));
                    survivingLblSn.set(saved.items().get(0).id());
                    t2Locked.countDown();
                    // 커밋 전 대기 — 이 구간 동안 T1 은 FOR UPDATE 에서 블록된다.
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable e) {
                t2Error.set(e);
                t2Locked.countDown();
            }
        }, "label-save-t2");
        t2.start();
        assertThat(t2Locked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(t2Error.get()).isNull();

        // when — T1 이 <b>낡은 버전(v0)</b>으로 자기 작업본(T2 라벨 없음)을 저장한다. T1 은 진입부 인가
        //   검사에서 프레임 행을 먼저 읽으므로(1차 캐시에 v0 적재) 락 획득 시점에 캐시값을 그대로 쓰면
        //   CAS 가 통과해 T2 라벨이 삭제된다 — 이 인터리빙이 바로 방어가 무력화되던 경로다.
        Throwable t1Error = catchThrowable(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "car", 9, 9)), openedVersion),
                worker(WORKER_B))));
        t2.join(20_000L);

        // then — 409 로 거부되고(락 시점의 DB 실제 버전 v1 을 읽었다는 증거), T2 라벨이 살아남는다.
        assertThat(t1Error)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(labelRepository.findBySrcSn(srcSn))
                .extracting(LsDataLbl::getLblSn)
                .containsExactly(survivingLblSn.get());
    }

    @Test
    @DisplayName("버전_미첨부_요청은_기존대로_저장되어_하위호환이_유지됨")
    void requestWithoutVersionStillSaves() {
        srcSn = frameWithoutImage();
        // given — 이미 다른 세션이 저장해 버전이 올라간 상태.
        tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A)));
        long current = tx.execute(s -> labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());
        assertThat(current).isGreaterThan(0L);

        // when — 버전을 <b>첨부하지 않은</b> 요청(구 FE) — 검사 없이 통과해야 한다.
        LabelResponse res = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "car", 2, 2))), worker(WORKER_B)));

        // then — 정상 저장(예외 없음).
        assertThat(res.items()).extracting(LabelResponse.Item::label).containsExactly("car");
    }

    @Test
    @DisplayName("라벨_저장_성공시_버전이_증가하고_조회응답에_반영됨")
    void versionIncrementsAndIsExposed() {
        srcSn = frameWithoutImage();
        long v0 = tx.execute(s -> labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());

        LabelResponse saved = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1)), v0), worker(WORKER_A)));

        // 저장 응답과 재조회 응답이 모두 v0+1 이어야 한다(FE 왕복 가능).
        assertThat(saved.labelVersion()).isEqualTo(v0 + 1);
        long reloaded = tx.execute(s -> labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());
        assertThat(reloaded).isEqualTo(v0 + 1);

        // 그리고 최신 버전을 첨부한 저장은 통과한다(회귀 방어 — 게이트가 정상 플로우를 막지 않음).
        LabelResponse again = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(saved.items().get(0).id(), "person", 3, 3)), reloaded),
                worker(WORKER_A)));
        assertThat(again.labelVersion()).isEqualTo(v0 + 2);
    }

    // ------------------------------------------------------------------ C-ISSUE-22

    @Test
    @DisplayName("이미지_경계를_벗어난_좌표_저장시_400")
    void outOfBoundsCoordinateRejected() throws Exception {
        srcSn = frameWithImage(64, 48);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(999999.0, 888888.0), List.of(1000000.0, 999000.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
    }

    @Test
    @DisplayName("경계_이내_좌표는_정상_저장됨")
    void withinBoundsCoordinateSaved() throws Exception {
        srcSn = frameWithImage(64, 48);

        // 경계값(정확히 width/height)도 정상 좌표다 — 우/하단 끝 지정.
        LabelResponse res = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(0.0, 0.0), List.of(64.0, 48.0)), null))),
                worker(WORKER_A)));

        assertThat(res.items()).hasSize(1);
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("프레임_이미지를_읽을_수_없으면_상한검증은_스킵되고_저장은_성공하며_경고가_남음")
    void unreadableImageSkipsUpperBoundButStillSaves() {
        // given — 원천 이미지가 없는 프레임(파생영상/NAS 장애 상황). 상한 기준값을 얻을 수 없다.
        srcSn = frameWithoutImage();

        // when — 상한을 훌쩍 넘는 좌표라도 저장은 성공해야 한다(정상 작업 차단 금지).
        LabelResponse res = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(999999.0, 888888.0), List.of(1000000.0, 999000.0)), null))),
                worker(WORKER_A)));

        // then — 저장됨. ("검증 불가"는 FrameBoundsResolver 의 WARN 로그로 드러난다 — 조용한 통과 아님.)
        assertThat(res.items()).hasSize(1);
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
        // 하한(음수)·형식 검증은 이미지 유무와 무관하게 그대로 적용된다.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(-1.0, 5.0), List.of(10.0, 10.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class);
    }

    // ------------------------------------------------------------------ C-ISSUE-25

    @Test
    @DisplayName("사용중지된_마스터를_참조하는_기존라벨이_있어도_프레임_저장이_가능함")
    void disabledMasterOnExistingLabelDoesNotBlockSave() {
        srcSn = frameWithoutImage();
        // given — 사용중지(USE_YN='N') 마스터를 참조하는 기존 라벨 1건 + 정상 라벨 1건.
        LsLabel disabled = tx.execute(s -> {
            LsLabel m = labelMasterRepository.save(
                    LsLabel.create("pose-skeleton-" + System.nanoTime(), "#123456", "BBOX", 0, "test"));
            m.softDelete("test");
            return labelMasterRepository.saveAndFlush(m);
        });
        assertThat(disabled.getUseYn()).isEqualTo("N");

        LsDataLbl legacy = tx.execute(s -> labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", disabled.getLabelId(), "pose", "[[1.0,1.0],[2.0,2.0]]", WORKER_A)));

        // when — full-replace 계약대로 기존 라벨(사용중지 마스터 참조)을 그대로 포함해 재전송 + 신규 1건 추가.
        LabelResponse res = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(
                        new LabelItemDto(legacy.getLblSn(), "BBOX", disabled.getLabelId(), "pose",
                                List.of(List.of(1.0, 1.0), List.of(9.0, 9.0)), null),
                        bbox(null, "person", 2, 2))),
                worker(WORKER_A)));

        // then — 409 없이 저장된다(기존 라벨 유지 = 신규 부여 아님).
        assertThat(res.items()).hasSize(2);
    }

    @Test
    @DisplayName("사용중지된_마스터를_신규로_부여하면_409")
    void disabledMasterOnNewAssignmentRejected() {
        srcSn = frameWithoutImage();
        LsLabel disabled = tx.execute(s -> {
            LsLabel m = labelMasterRepository.save(
                    LsLabel.create("retired-" + System.nanoTime(), "#654321", "BBOX", 0, "test"));
            m.softDelete("test");
            return labelMasterRepository.saveAndFlush(m);
        });

        // (1) 신규 라벨(id==null)에 사용중지 마스터 부여 → 409.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(null, "BBOX", disabled.getLabelId(),
                        "retired", List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // (2) 우회 방지 — 기존 라벨 id 를 붙여도 labelId 를 <b>사용중지 마스터로 바꾸는</b> 것은 거부된다.
        LsDataLbl existing = tx.execute(s -> labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]", WORKER_A)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(existing.getLblSn(), "BBOX",
                        disabled.getLabelId(), "person",
                        List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // -------------------------------------------------- C-ISSUE-22(3차) 신고 구간 저장 게이트

    /** 이 영상을 비식별 누락 신고 구간({@code DE_IDNTF_YN='F'})으로 만든다. */
    private void openDeidentReport() {
        tx.executeWithoutResult(s -> rawRepository.findById(rawSn)
                .orElseThrow()
                .markDeidentified("F"));
    }

    @Test
    @DisplayName("비식별_신고_구간에서_라벨_저장_시_412_반환된다")
    void saveBlockedWith412WhileDeidentReportOpen() {
        srcSn = frameWithoutImage();
        openDeidentReport();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 차단됐으므로 신규 라벨도 만들어지지 않는다.
        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
    }

    @Test
    @DisplayName("신고_구간에서_빈_배열_저장_시도해도_412로_차단되어_기존_라벨이_보존된다")
    void emptyItemsSaveDuringReportDoesNotWipeExistingLabels() {
        srcSn = frameWithoutImage();
        // given — 신고 전에 정상 저장된 라벨 2건.
        LabelResponse saved = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1), bbox(null, "car", 2, 2))),
                worker(WORKER_A)));
        List<Long> before = saved.items().stream().map(LabelResponse.Item::id).sorted().toList();
        assertThat(before).hasSize(2);

        // when — 비식별 누락 신고 접수(라벨은 삭제하지 않고 보존하는 것이 확정 정책).
        openDeidentReport();

        // then — full-replace 계약상 "전량 삭제"가 되는 빈 배열 저장이 412 로 막힌다.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of()), worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 그리고 기존 라벨은 DB 에 그대로 남아 있다(데이터 유실 없음).
        assertThat(labelRepository.findBySrcSn(srcSn))
                .extracting(LsDataLbl::getLblSn)
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("작업락_만료_후에도_신고_구간이면_저장이_412로_차단된다")
    void saveBlockedAfterWorkLockExpired() {
        srcSn = frameWithoutImage();
        openDeidentReport();
        // given — 신고 락은 6h 만료 후 WorkLockSweepJob 이 회수한다. 그 이후 상태(LOCKED 0건)를 재현.
        assertThat(workLockService.isRawLocked(rawSn)).isFalse();

        // when/then — 락이 없어도 신고 구간이면 저장은 여전히 차단된다(락 수명 ≠ 신고 구간 수명).
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("신고_구간이면_작업락_유무와_무관하게_동일하게_412_로_통일된다")
    void reportGateResponseIsUniformRegardlessOfWorkLock() {
        srcSn = frameWithoutImage();
        openDeidentReport();
        workLockService.lockRawForRedeident(rawSn, "1");
        try {
            assertThat(workLockService.isRawLocked(rawSn)).isTrue();
            // 락이 살아 있는 구간에서도 신고 축의 응답은 412 — 락 유무가 응답 코드로 드러나지 않는다
            // (409/412 로 갈리면 응답 자체가 영상 잠금 상태를 알려주는 오라클이 된다).
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                    new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A))))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        } finally {
            workLockService.releaseRawInNewTx(rawSn, "1", LsAuthWorkLock.REASON_REDEIDENT);
        }
    }

    // ------------------------------------------- C-ISSUE-61/62 (TC-LABEL-150) 미존재 id 우회

    /** 그 프레임(그리고 어떤 프레임에도) 실재하지 않는 라벨 id. */
    private static final long GHOST_LBL_SN = 99999999L;

    @Test
    @DisplayName("존재하지_않는_id를_붙인_좌표가_프레임_경계를_벗어나면_400으로_거부된다")
    void ghostIdCannotBypassBoundsValidation() throws Exception {
        srcSn = frameWithImage(64, 48);

        // 저장 분기는 "그 프레임에 없는 id" 를 신규 라벨로 만든다 → 상한 검증 대상이어야 한다.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(GHOST_LBL_SN, "BBOX", null, "bypass-oob",
                        List.of(List.of(999999.0, 888888.0), List.of(1000000.0, 999000.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 경계 밖 좌표가 DB 에 적재되지 않았다.
        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
    }

    @Test
    @DisplayName("존재하지_않는_id를_붙여도_MAX_POINTS_상한이_적용된다")
    void ghostIdCannotBypassMaxPoints() {
        // 상한 기준값(이미지)이 없는 프레임 — 좌표 상한과 무관하게 점 개수 상한만으로 거부돼야 한다.
        srcSn = frameWithoutImage();

        List<List<Double>> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= LabelService.MAX_POINTS_PER_LABEL; i++) {
            tooMany.add(List.of((double) (i % 50), (double) (i % 40)));
        }

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(GHOST_LBL_SN, "POLYGON", null,
                        "bypass-points", tooMany, null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
    }

    @Test
    @DisplayName("실재하는_id로_기존_라벨을_수정할_때는_기존_동작과_동일하게_통과한다")
    void realIdUpdateStillPasses() throws Exception {
        srcSn = frameWithImage(64, 48);
        // given — 정상 신규 생성(id=null 회귀 케이스 포함).
        LabelResponse created = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A)));
        Long realId = created.items().get(0).id();

        // when — 실재 id + 경계 이내 좌표로 수정.
        LabelResponse updated = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(realId, "BBOX", null, "person",
                        List.of(List.of(10.0, 10.0), List.of(40.0, 40.0)), null))),
                worker(WORKER_A)));

        // then — 신규 생성이 아니라 <b>같은 라벨의 수정</b>으로 통과한다(id 유지, 건수 1).
        assertThat(updated.items()).hasSize(1);
        assertThat(updated.items().get(0).id()).isEqualTo(realId);
        assertThat(labelRepository.findBySrcSn(srcSn))
                .extracting(LsDataLbl::getLblSn).containsExactly(realId);
    }

    @Test
    @DisplayName("타_프레임에_실재하는_id를_붙여도_신규로_판정되어_경계검증이_적용된다")
    void crossFrameIdCannotBypassBoundsValidation() throws Exception {
        // given — 같은 영상의 서로 다른 두 프레임. 프레임 A 에는 정상 라벨 1건이 저장돼 있다.
        Long frameA = frameWithoutImage();
        LabelResponse savedOnA = tx.execute(s -> labelService.bulkUpsert(frameA,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A)));
        Long foreignLblSn = savedOnA.items().get(0).id();
        String pointsBefore = labelRepository.findById(foreignLblSn).orElseThrow().getPointCn();

        Long frameB = frameWithImage(64, 48);
        srcSn = frameB;

        // when/then — 프레임 A 의 <b>실재</b> 라벨 id 를 프레임 B 저장 요청에 붙이고, 좌표는 B 의 경계를
        //   벗어나게 보낸다. idIndex 는 findBySrcSn(frameB) 로만 채워지므로 이 id 는 "이 프레임에 없는 id"
        //   = 신규로 판정돼야 하고, 그 결과 신규 경로의 경계 상한(validateWithinBounds)이 그대로 강제된다.
        //   (ghost id 와 달리 실제로 DB 에 존재하는 id 라, 판정 축이 "id 실재 여부"가 아니라
        //    "이 프레임에 실재하는가"임을 증명하는 케이스다.)
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(frameB,
                new LabelBulkUpsertRequest(List.of(new LabelItemDto(foreignLblSn, "BBOX", null, "cross-frame",
                        List.of(List.of(999999.0, 888888.0), List.of(1000000.0, 999000.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 그리고 프레임 A 의 원본 라벨은 소속 프레임·좌표 모두 그대로다 — 타 프레임 라벨이 이 요청으로
        // 수정되거나 프레임 B 로 이관되지 않는다(IDOR/오삭제 회귀 가드).
        LsDataLbl untouched = labelRepository.findById(foreignLblSn).orElseThrow();
        assertThat(untouched.getSrcSn()).isEqualTo(frameA);
        assertThat(untouched.getPointCn()).isEqualTo(pointsBefore);
        assertThat(labelRepository.findBySrcSn(frameA))
                .extracting(LsDataLbl::getLblSn).containsExactly(foreignLblSn);
        // 프레임 B 에는 경계 밖 라벨이 적재되지 않았다.
        assertThat(labelRepository.findBySrcSn(frameB)).isEmpty();
    }

    @Test
    @DisplayName("혼합_배열에서_일부_아이템이_경계를_벗어나면_요청_전체가_거부되고_정상_아이템도_저장되지_않는다")
    void mixedBatchIsRejectedAtomically() throws Exception {
        srcSn = frameWithImage(64, 48);
        // given — 이미 저장된 정상 라벨 1건(full-replace 삭제 대상이 되지 않게 요청에도 그대로 포함한다).
        LabelResponse seeded = tx.execute(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(bbox(null, "person", 1, 1))), worker(WORKER_A)));
        Long keptLblSn = seeded.items().get(0).id();
        // 비교 기준은 <b>요청 전</b>에 캡처한다(요청 후에 읽으면 자기 자신과 비교하는 무의미한 단언이 된다).
        String keptPointsBefore = labelRepository.findById(keptLblSn).orElseThrow().getPointCn();
        long versionBefore = tx.execute(s ->
                labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());

        // when — 정상 좌표 아이템(기존 1 + 신규 1) 과 경계 밖 ghost-id 아이템 1건을 <b>같은 배열</b>에
        //   담아 전송한다. 정상 아이템을 앞에 두어, 뒤 아이템의 실패가 앞 아이템의 저장을 되돌리는지 본다.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> labelService.bulkUpsert(srcSn,
                new LabelBulkUpsertRequest(List.of(
                        new LabelItemDto(keptLblSn, "BBOX", null, "person",
                                List.of(List.of(2.0, 2.0), List.of(20.0, 20.0)), null),
                        bbox(null, "car", 10, 10),
                        new LabelItemDto(GHOST_LBL_SN, "BBOX", null, "bypass-oob",
                                List.of(List.of(999999.0, 888888.0), List.of(1000000.0, 999000.0)), null))),
                worker(WORKER_A))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // then — all-or-nothing. 정상 아이템("car")도 저장되지 않고, 기존 라벨 좌표도 변경되지 않는다.
        //   왜 통과하나: ①좌표 사전 검증 루프가 <b>어떤 쓰기보다 먼저</b> 전 아이템을 훑어 400 으로 끊고
        //   ②설령 뒤 단계에서 실패하더라도 bulkUpsert 는 단일 @Transactional 이라 전체 롤백된다
        //   (부분 저장 창이 없다). 이 테스트는 그 성질을 고정하는 회귀 가드다.
        assertThat(labelRepository.findBySrcSn(srcSn))
                .extracting(LsDataLbl::getLblSn).containsExactly(keptLblSn);
        assertThat(labelRepository.findById(keptLblSn).orElseThrow().getPointCn())
                .isEqualTo(keptPointsBefore);
        // 라벨셋 버전도 오르지 않는다(거부된 요청이 다른 세션의 보유 버전을 무효화하지 않는다).
        long versionAfter = tx.execute(s ->
                labelService.getByFrame(srcSn, worker(WORKER_A)).labelVersion());
        assertThat(versionAfter).isEqualTo(versionBefore);
    }

    /** 테스트가 만든 임시 이미지 파일 정리(누적 방지). 실패해도 테스트 결과에 영향 없음. */
    @SuppressWarnings("unused")
    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }
}
