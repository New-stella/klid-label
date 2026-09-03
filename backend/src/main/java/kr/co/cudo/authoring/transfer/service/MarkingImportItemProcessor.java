package kr.co.cudo.authoring.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import kr.co.cudo.authoring.transfer.ImportPathPolicy;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import kr.co.cudo.authoring.transfer.parser.MarkingDocument;
import kr.co.cudo.authoring.transfer.parser.MarkingDocumentParser;
import kr.co.cudo.authoring.video.dto.MarkingImportIngestCommand;
import kr.co.cudo.authoring.video.dto.MarkingImportIngestResult;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 항목 하나를 실제로 적재한다 — 영상 복사 → 영상 만들기 → <b>예약 마킹</b> 만들기.
 *
 * <h3>트랜잭션을 잡지 않는다</h3>
 * <p>이 클래스가 하는 일의 대부분은 파일 복사와 영상 판독, 즉 <b>NAS 입출력</b>이다. 그 구간을
 * 트랜잭션 안에 두면 커넥션을 분 단위로 쥐고, 항목 몇 건만 겹쳐도 커넥션이 마른다. 쓰기는 각자
 * 자기 트랜잭션을 가진 협력자가 하고 이 클래스는 순서만 정한다.
 *
 * <h3>순서에 이유가 있다 — 함부로 바꾸지 말 것</h3>
 * <ol>
 *   <li><b>속도 대조를 복사·적재보다 먼저</b> 한다. 뒤로 미루면 영상은 이미 들어왔는데 마킹만 못 다는
 *       상태가 되어, <b>아무도 처리하지 않는 영상</b>이 남는다.</li>
 *   <li><b>식별자 중복 확인을 복사보다 먼저</b> 한다. 저장 위치가 식별자에서 나오므로, 뒤로 미루면
 *       이미 있는 영상의 파일 자리에 <b>다른 영상을 덮어쓴다</b>. 그 손실은 되돌릴 수 없다.</li>
 *   <li><b>복사를 적재보다 먼저</b> 한다. 적재가 발행하는 신호가 곧바로 비식별을 태우는데, 그때 파일이
 *       아직 없으면 비식별이 대상 없이 실패한다.</li>
 *   <li><b>예약 마킹을 적재 뒤에</b> 만든다. 영상 식별자가 있어야 걸 자리가 정해지기 때문이다. 그
 *       사이에 비식별이 먼저 끝날 수 있어 아래 「따라잡기」가 필요하다.</li>
 * </ol>
 *
 * <h3>★ 따라잡기 — 예약을 걸기 전에 비식별이 끝났을 수 있다</h3>
 * <p>비식별은 적재 신호를 받아 <b>비동기로</b> 시작한다. 그것이 예약을 거는 것보다 빨리 끝나면, 깨우는
 * 쪽은 예약을 보지 못하고 예약은 <b>영원히 깨어나지 않는다</b>. 그래서 예약을 건 직후 영상 상태를 다시
 * 보고, 이미 마킹 가능 상태면 여기서 깨우고 이미 비식별이 실패했으면 여기서 마감한다. 깨우기·마감은
 * 둘 다 조건부 UPDATE 라 두 번 불려도 한 번만 걸린다(멱등).
 *
 * <h3>건너뜀과 실패를 가른다</h3>
 * <p>짝을 찾지 못했거나 이미 들어와 있어 <b>처리하지 않은</b> 것은 건너뜀이고, 처리하다 멈춘 것은
 * 실패다. 합치면 「이미 들어와 있어서 넘어감」이 「적재 실패」로 집계되어 사람이 무엇을 고쳐야 하는지
 * 알 수 없다.
 *
 * @design DOMAIN-017
 * @design ADR-052
 * @design ADR-053
 * @design API-217
 * @design AC-1032
 * @design AC-1033
 * @design SEQ-030
 */
@Slf4j
@Component
public class MarkingImportItemProcessor {

    /**
     * 신호 간격의 상한(초) — 되돌리기 임계를 아무리 길게 잡아도 이보다 뜸해지지 않는다. 임계가 길다는
     * 것은 회수를 늦게 한다는 뜻일 뿐, 처리 중인 항목을 오래 방치해도 된다는 뜻이 아니다.
     */
    public static final long HEARTBEAT_MAX_INTERVAL_SEC = 60L;

    /**
     * 신호 간격의 하한(초) — 임계를 아주 짧게 줄여도 이보다 잦아지지 않는다. 신호 하나가 원장에 쓰는
     * 일이라, 너무 잦으면 큰 파일 하나가 수천 번의 쓰기를 만들어 다른 처리의 커넥션을 잠식한다.
     */
    public static final long HEARTBEAT_MIN_INTERVAL_SEC = 5L;

    private final ImportSourcePolicy sourcePolicy;
    private final MarkingDocumentParser parser;
    private final MarkingImportAssessor assessor;
    private final VideoProbe videoProbe;
    private final VideoRepository videoRepository;
    private final ImportFileStager fileStager;
    private final TrainingVideoIngestService ingestService;
    private final LsMarkingRepository markingRepository;
    private final MarkingActivationTxService markingActivationTxService;
    private final ObjectMapper objectMapper;
    private final String storageRawPath;

    /** 처리 중 신호를 원장에 적는 자리 — 복사 도중 「멈춘 것으로 오인」되는 것을 막는다. */
    private final MarkingImportJobTxService jobTxService;

    /**
     * 신호를 실제로 적는 최소 간격(ns) — 되돌리기 임계에서 <b>파생</b>한 값이다
     * ({@link #heartbeatIntervalSec}). 따로 두면 임계만 줄였을 때 신호가 그보다 뜸해져 방어가
     * 조용히 무력해진다.
     */
    private final long heartbeatIntervalNanos;

    /**
     * 시각 원천 — 간격이 실제로 지켜지는지를 시험이 <b>기다리지 않고</b> 확인할 수 있게 뺐다.
     * 실제 시각으로만 검증하면 시험이 초 단위로 잠들어야 하고, 그러면 느린 기계에서 흔들린다.
     */
    private final LongSupplier nanoTime;

    /**
     * ★생성자가 둘이므로 <b>주입에 쓸 쪽을 명시</b>한다.
     *
     * <p>표시하지 않으면 스프링이 어느 쪽을 쓸지 정하지 못해 <b>기본 생성자를 찾다가 실패</b>하고,
     * 그 실패는 이 빈 하나로 끝나지 않는다 — 컨텍스트가 통째로 뜨지 못해 이 도메인의 통합 시험이
     * 전부 함께 빨개진다.
     */
    @Autowired
    public MarkingImportItemProcessor(ImportSourcePolicy sourcePolicy,
                                      MarkingDocumentParser parser,
                                      MarkingImportAssessor assessor,
                                      VideoProbe videoProbe,
                                      VideoRepository videoRepository,
                                      ImportFileStager fileStager,
                                      TrainingVideoIngestService ingestService,
                                      LsMarkingRepository markingRepository,
                                      MarkingActivationTxService markingActivationTxService,
                                      ObjectMapper objectMapper,
                                      MarkingImportJobTxService jobTxService,
                                      MarkingImportProperties properties,
                                      @Value("${authoring.storage.raw-path}") String storageRawPath) {
        this(sourcePolicy, parser, assessor, videoProbe, videoRepository, fileStager, ingestService,
                markingRepository, markingActivationTxService, objectMapper, jobTxService, properties,
                storageRawPath, System::nanoTime);
    }

    /** 시각 원천 주입 생성자 — 신호 간격 검증(회귀 가드) 전용. */
    public MarkingImportItemProcessor(ImportSourcePolicy sourcePolicy,
                                      MarkingDocumentParser parser,
                                      MarkingImportAssessor assessor,
                                      VideoProbe videoProbe,
                                      VideoRepository videoRepository,
                                      ImportFileStager fileStager,
                                      TrainingVideoIngestService ingestService,
                                      LsMarkingRepository markingRepository,
                                      MarkingActivationTxService markingActivationTxService,
                                      ObjectMapper objectMapper,
                                      MarkingImportJobTxService jobTxService,
                                      MarkingImportProperties properties,
                                      String storageRawPath,
                                      LongSupplier nanoTime) {
        this.sourcePolicy = sourcePolicy;
        this.parser = parser;
        this.assessor = assessor;
        this.videoProbe = videoProbe;
        this.videoRepository = videoRepository;
        this.fileStager = fileStager;
        this.ingestService = ingestService;
        this.markingRepository = markingRepository;
        this.markingActivationTxService = markingActivationTxService;
        this.objectMapper = objectMapper;
        this.jobTxService = jobTxService;
        this.storageRawPath = storageRawPath;
        this.nanoTime = nanoTime;
        this.heartbeatIntervalNanos = TimeUnit.SECONDS.toNanos(
                heartbeatIntervalSec(properties.effectiveStaleTimeoutMinutes()));
    }

    /**
     * 신호를 적는 최소 간격(초) — 되돌리기 임계에서 <b>파생</b>한다.
     *
     * <p>임계의 <b>1/4</b>을 취하고 {@link #HEARTBEAT_MIN_INTERVAL_SEC}~{@link #HEARTBEAT_MAX_INTERVAL_SEC}
     * 로 자른다. 상수로 박지 않고 파생하는 이유는, 운영자가 임계를 짧게 줄였을 때 신호가 그보다
     * 뜸하면 <b>정상 처리가 그대로 멈춘 것으로 판정</b>되기 때문이다 — 두 값이 따로 놀면 안 된다.
     * 1/4 인 것은 임계 안에 신호가 최소 세 번은 들어가게 하기 위해서다(한 번을 놓쳐도 남는다).
     *
     * <p>임계가 0·음수여도 여기서는 하한으로 잘려 무해하다. 임계 자체의 하한은 설정이 따로 지킨다.
     */
    public static long heartbeatIntervalSec(long staleTimeoutMinutes) {
        long quarterSec = Math.max(0L, staleTimeoutMinutes) * 60L / 4L;
        return Math.max(HEARTBEAT_MIN_INTERVAL_SEC, Math.min(HEARTBEAT_MAX_INTERVAL_SEC, quarterSec));
    }

    /**
     * 항목 하나를 처리한다 — <b>예외를 밖으로 던지지 않는다</b>.
     *
     * <p>일꾼은 항목 하나가 어떻게 끝나든 다음 항목으로 넘어가야 한다. 예외가 올라가면 그 일꾼이
     * 멈춰 남은 항목이 처리되지 않고, 그 항목은 처리중인 채로 남아 되돌리기 잡이 임계 시간을 다
     * 기다린 뒤에야 회수한다.
     */
    public ItemOutcome process(LsEblcUldJobArtcl item, MarkingImportJobMeta meta, String actorId) {
        try {
            return processInternal(item, meta, actorId);
        } catch (CustomException e) {
            // 판정기가 이미 사람이 읽을 수 있는 문장으로 만든 사유다(내부 경로를 담지 않는다).
            return ItemOutcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            // CWE-209 — 원인 메시지를 그대로 사유에 담지 않는다. 운영 로그에는 종류만 남긴다.
            log.warn("[MarkingImport] item failed artclSn={} cause={}",
                    item.getEblcUldJobArtclSn(), e.getClass().getSimpleName());
            return ItemOutcome.failed("적재하는 도중 처리하지 못했다.");
        }
    }

    private ItemOutcome processInternal(LsEblcUldJobArtcl item, MarkingImportJobMeta meta, String actorId) {
        if (item.getVdoFilePathNm() == null) {
            return ItemOutcome.skipped("마킹 문서가 가리키는 영상을 찾지 못해 처리하지 않았다.");
        }

        // 훑기와 처리 사이에 폴더가 바뀌었을 수 있다 — 허용 범위 판정을 다시 하고, 그 판정이
        // 돌려준 실경로로만 연다(CWE-367).
        Path markingPath = sourcePolicy.verifyVideoFile(item.getMarkFilePathNm());
        Path videoPath = sourcePolicy.verifyVideoFile(item.getVdoFilePathNm());

        MarkingDocument document = parser.parse(markingPath);
        if (!document.hasUsableContent()) {
            return ItemOutcome.skipped("마킹 문서에서 적재할 내용을 읽지 못해 처리하지 않았다.");
        }

        Double probedFps = probedFpsOf(videoPath);
        if (assessor.isFpsMismatch(document.declaredFps(), probedFps)) {
            // 어긋난 채로 진행하면 이벤트가 없는 엉뚱한 자리의 프레임을 뽑는다. 적재 자체를 하지 않는다.
            return ItemOutcome.failed(
                    "마킹 문서에서 역산한 프레임 재생 속도가 영상에서 읽은 값과 크게 달라 적재하지 않았다.");
        }

        String clipId = document.clipId();
        Optional<LsDataRaw> existing = videoRepository.findByVmsClipId(clipId);
        if (existing.isPresent()) {
            // 조용히 덮어쓰면 검수 중이거나 승인된 내용이 사라진다. 그 항목만 건너뛴다.
            return ItemOutcome.skipped(existing.get().getRawSn(), "같은 영상 식별자가 이미 쓰이고 있어 건너뛰었다.");
        }

        String targetPath = ImportPathPolicy.rawFilePath(storageRawPath, clipId, document.videoFileName());
        copyWithHeartbeat(videoPath, targetPath, item.getEblcUldJobArtclSn());

        MarkingImportIngestResult ingested;
        try {
            ingested = ingestService.ingestMarkingImport(new MarkingImportIngestCommand(
                    clipId, meta.cctvId(), meta.eventTypeCd(), meta.localGovCd(),
                    meta.prvcTypeCd(), targetPath, meta.capturedAt()));
        } catch (IllegalArgumentException e) {
            // 값이 규칙에 맞지 않는다 — 다시 해도 같은 결과라 재시도 대상이 아니다.
            fileStager.cleanupQuietly(List.of(targetPath));
            return ItemOutcome.failed("적재에 필요한 값이 규칙에 맞지 않아 적재하지 않았다.");
        } catch (RuntimeException e) {
            fileStager.cleanupQuietly(List.of(targetPath));
            throw e;
        }

        if (ingested.duplicateClipId()) {
            // 위 사전 확인을 빠져나간 동시 적재다. ⚠ 여기서 복사한 파일을 지우지 않는다 —
            // 저장 위치가 식별자에서 나오므로 그 자리는 <이미 있던 영상>의 파일 자리이기도 하다.
            log.warn("[MarkingImport] duplicate clip detected after copy artclSn={} rawSn={}",
                    item.getEblcUldJobArtclSn(), ingested.rawSn());
            return ItemOutcome.skipped(ingested.rawSn(), "같은 영상 식별자가 이미 쓰이고 있어 건너뛰었다.");
        }

        long rawSn = ingested.rawSn();
        reserveMarking(rawSn, document, probedFps, actorId);
        catchUpReservation(rawSn);
        return ItemOutcome.success(rawSn);
    }

    /**
     * 복사하면서 <b>처리 중 신호</b>를 원장에 적는다.
     *
     * <p>★영상 한 건이 수백 MB~수 GB 라 복사 하나가 되돌리기 임계를 넘길 수 있다. 그 동안 원장의
     * 갱신 시각이 집은 순간에 멈춰 있으면 되돌리기가 <b>지금 돌고 있는 처리를 빼앗아</b> 다른 일꾼이
     * 같은 영상을 다시 집는다. 데이터가 깨지지는 않지만(마감이 조건부라 집계가 겹치지 않고 복사가
     * 원자 이름 바꾸기라 파일도 성하다) 같은 대용량 파일을 두 번 옮기고, 정상 항목 하나가
     * 「이미 있음」 건너뜀으로 끝나 사람이 원인을 되짚기 어렵다.
     *
     * <p>덩어리마다 오는 신호를 <b>시간</b>으로 걸러 적는다. 덩어리 수로 걸면 그 사이 간격에 상한이
     * 없다 — 느린 저장소에서는 덩어리 몇 개만으로도 임계를 넘긴다.
     */
    private void copyWithHeartbeat(Path source, String target, long artclSn) {
        long[] lastBeat = {0L};
        boolean[] beaten = {false};
        fileStager.copy(source, target, copied -> {
            long now = nanoTime.getAsLong();
            if (beaten[0] && now - lastBeat[0] < heartbeatIntervalNanos) {
                return;
            }
            lastBeat[0] = now;
            beaten[0] = true;
            try {
                jobTxService.heartbeat(artclSn);
            } catch (RuntimeException e) {
                // 신호를 적지 못한 것 때문에 멀쩡한 복사를 실패로 만들지 않는다 — 신호는 복사를
                // 돕는 것이지 복사의 조건이 아니다. 못 적었으면 그 항목이 회수 대상이 될 뿐이고,
                // 그때도 마감이 조건부라 결과가 겹치지는 않는다.
                log.warn("[MarkingImport] heartbeat failed artclSn={} cause={}",
                        artclSn, e.getClass().getSimpleName());
            }
        });
    }

    /**
     * 외부가 준 시점 배열을 <b>예약 상태</b>의 마킹으로 담아 둔다.
     *
     * <p>적재 시점의 영상은 아직 비식별되지 않아 마킹을 곧바로 활성화할 수 없다. 예약은 활성 축 밖에
     * 있어 「영상당 활성 마킹 하나」 제약을 점유하지 않으며, 그래서 비식별이 실패해도 사람이 그 영상을
     * 다시 마킹할 수 있다(ADR-052).
     *
     * <p>고정하는 속도는 <b>영상에서 읽은 값</b>이다. 그 값을 읽지 못했을 때만 문서에서 역산한 값으로
     * 물러선다 — 둘 다 없으면 비운 채 두고, 추출 단계가 그때 다시 찾는다(지어내지 않는다).
     */
    private void reserveMarking(long rawSn, MarkingDocument document, Double probedFps, String actorId) {
        String marksJson;
        try {
            marksJson = objectMapper.writeValueAsString(document.marks());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("마킹 시점을 저장 형식으로 옮기지 못했다.", e);
        }
        Double fps = probedFps != null ? probedFps : document.declaredFps();
        markingRepository.save(LsMarking.createReserved(rawSn, marksJson, actorId, fps));
    }

    /**
     * ★ 예약을 걸기 <b>전에</b> 비식별이 끝났거나 실패했으면 여기서 따라잡는다.
     *
     * <p>비식별은 적재 신호를 받아 비동기로 도므로 예약보다 먼저 끝날 수 있다. 그때 깨우는 쪽은 예약을
     * 보지 못했고, 이 확인이 없으면 그 예약은 <b>아무도 손대지 않는 채</b> 남는다. 두 동작 모두 조건부
     * UPDATE 라 깨우는 쪽과 여기가 동시에 불려도 한 번만 걸린다.
     *
     * <p>실패해도 삼킨다 — 적재는 이미 끝났고 되돌리지 않는다. 여기서 예외를 올리면 정상 적재가
     * 실패로 마감되고, 그 예약은 어차피 뒤에 오는 신호가 깨운다.
     */
    private void catchUpReservation(long rawSn) {
        try {
            LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
            if (raw == null) {
                return;
            }
            if (LsDataRaw.DATA_STTS_MARKING_READY.equals(raw.getDataSttsCd())) {
                markingActivationTxService.activateReserved(rawSn)
                        .ifPresent(markingSn -> log.info(
                                "[MarkingImport] reservation activated by catch-up rawSn={} markingSn={}",
                                rawSn, markingSn));
            } else if (DEIDENT_FAILED.equals(raw.getDeIdntfYn())) {
                markingActivationTxService.closeReservations(rawSn,
                        "비식별이 끝내 실패해 예약 마킹을 적용하지 못했다.");
            }
        } catch (RuntimeException e) {
            log.warn("[MarkingImport] reservation catch-up failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 비식별 실패 표식.
     *
     * <p>⚠ 같은 값을 <b>비식별 누락 신고 접수</b>도 쓴다. 이 경로에서 그 구분이 문제가 되지 않는 이유는
     * 여기가 <b>방금 적재한 영상</b>만 보기 때문이다 — 신고는 사람이 마킹 화면에서 하는 것이라 적재
     * 직후의 영상에는 있을 수 없다.
     */
    private static final String DEIDENT_FAILED = "F";

    private Double probedFpsOf(Path video) {
        try {
            VideoProbe.VideoMeta meta = videoProbe.probe(video);
            return meta == null ? null : meta.fps();
        } catch (RuntimeException e) {
            log.warn("[MarkingImport] video probe failed cause={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 항목 하나의 처리 결과.
     *
     * @param status {@link LsEblcUldJobArtcl} 의 항목 상태
     * @param rawSn  만들어졌거나 이미 있던 영상의 식별번호. 그 밖에는 {@code null}
     * @param reason 실패했거나 건너뛴 사유
     */
    public record ItemOutcome(String status, Long rawSn, String reason) {

        public static ItemOutcome success(long rawSn) {
            return new ItemOutcome(LsEblcUldJobArtcl.ARTCL_STTS_SUCCESS, rawSn, null);
        }

        public static ItemOutcome skipped(String reason) {
            return new ItemOutcome(LsEblcUldJobArtcl.ARTCL_STTS_SKIPPED, null, reason);
        }

        public static ItemOutcome skipped(Long rawSn, String reason) {
            return new ItemOutcome(LsEblcUldJobArtcl.ARTCL_STTS_SKIPPED, rawSn, reason);
        }

        public static ItemOutcome failed(String reason) {
            return new ItemOutcome(LsEblcUldJobArtcl.ARTCL_STTS_FAILED, null, reason);
        }

        public boolean succeeded() {
            return LsEblcUldJobArtcl.ARTCL_STTS_SUCCESS.equals(status);
        }
    }

}
