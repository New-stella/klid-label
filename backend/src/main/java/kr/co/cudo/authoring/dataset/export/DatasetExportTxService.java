package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 4 — 학습데이터 파일 산출의 <b>트랜잭션 경계 DB 게이트웨이</b>.
 *
 * <p>{@code @Async} 러너({@link AsyncDatasetExportRunner})는 검수 승인 커밋 <i>이후</i> 별도 스레드에서
 * 실행되므로 영속성 컨텍스트가 없다. 따라서 모든 로딩/상태전이는 여기서 {@code REQUIRES_NEW} 트랜잭션으로
 * DB 를 재조회한다({@code AsyncDeidentifyRunner.loadRaw} 패턴). 오케스트레이션(의사결정·파일쓰기·재시도)은
 * {@link DatasetExportService} 가, DB I/O 만 이 게이트웨이가 담당해 자기호출 프록시 우회 없이 REQUIRES_NEW
 * 경계가 실제로 열리도록 분리한다.
 *
 * <p>모든 조회는 파생/@Query 파라미터 바인딩만 사용(CWE-89 표면 없음). 로그는 rawSn·건수만 출력(CWE-359/117).
 */
@Service
public class DatasetExportTxService {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportTxService.class);

    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final LsDatasetVideoMetaRepository videoMetaRepository;
    private final LsLabelRepository labelMasterRepository;
    private final VideoRepository videoRepository;
    private final LsDatasetExportRepository exportRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final NiaJsonBuilder niaJsonBuilder;
    private final LabelContentHasher contentHasher;
    private final DatasetExportPathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public DatasetExportTxService(LsDataSrcRepository srcRepository,
                                  LsDataLblRepository labelRepository,
                                  LsDatasetVideoMetaRepository videoMetaRepository,
                                  LsLabelRepository labelMasterRepository,
                                  VideoRepository videoRepository,
                                  LsDatasetExportRepository exportRepository,
                                  LsDeidentProcLogRepository deidentProcLogRepository,
                                  NiaJsonBuilder niaJsonBuilder,
                                  LabelContentHasher contentHasher,
                                  DatasetExportPathResolver pathResolver,
                                  ObjectMapper objectMapper) {
        this.srcRepository = srcRepository;
        this.labelRepository = labelRepository;
        this.videoMetaRepository = videoMetaRepository;
        this.labelMasterRepository = labelMasterRepository;
        this.videoRepository = videoRepository;
        this.exportRepository = exportRepository;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.niaJsonBuilder = niaJsonBuilder;
        this.contentHasher = contentHasher;
        this.pathResolver = pathResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 산출에 필요한 입력(컨텍스트·프레임·콘텐츠 해시·직전 성공/부분 해시=멱등 baseline)을 DB 재조회로 조립한다.
     *
     * <p>산출 불가(프레임 0건 또는 활성 영상 메타 부재)면 {@link Optional#empty()} 로 skip 을 알린다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ExportPreparation> loadPreparation(long rawSn) {
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            log.info("[DatasetExport] no frames — skip export rawSn={}", rawSn);
            return Optional.empty();
        }
        List<LsDatasetVideoMeta> metas = videoMetaRepository.findByRawSnAndActiveYn(
                rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        if (metas.isEmpty()) {
            // 정상 승인 흐름에서는 materialize 로 활성 메타가 있어야 한다 — 없으면 fail-secure skip.
            log.warn("[DatasetExport] no active video meta — skip export rawSn={}", rawSn);
            return Optional.empty();
        }
        LsDatasetVideoMeta meta = metas.get(0);
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);

        List<LsDataLbl> allLabels = labelRepository.findAllByRawSn(rawSn);
        // 콘텐츠 해시는 라벨뿐 아니라 산출 JSON 에 직렬화되는 프레임(frmExpln 등)·영상 메타
        // (prvcTypeCd/prvcYn·해상도 등)까지 반영한다 — frmExpln/개인정보 정정 재승인의 stale 고착 방지.
        String contentHash = contentHasher.hash(allLabels, frames, meta, raw);

        Map<Long, List<LsDataLbl>> labelsBySrc = allLabels.stream()
                .filter(l -> l.getSrcSn() != null)
                .collect(Collectors.groupingBy(LsDataLbl::getSrcSn));

        List<LsLabel> usedLabels = loadUsedLabels(allLabels);
        // 동결 event_annotation(C2) — 활성 메타 스냅샷의 EVNT_ANNO_CN(승인 시점 동결본)만 사용한다.
        // export 는 LS_EVNT_ANNO(라이브)를 조회하지 않으므로 승인 후 편집분에 오염되지 않는다(멱등).
        JsonNode eventAnnotation = parseEventAnnotation(meta.getEvntAnnoCn(), rawSn);
        // 비식별 영상 경로(DE_IDNTF_FILE_PATH_NM) — DEIDENTIFIED 산출 JSON 의 dataset/video 경로 필드가
        // 원본이 아닌 비식별 경로를 참조하도록 rawSn 단위 1회 조회한다(최신 SUCCEEDED procLog, N+1 없음).
        // 미상이면 null → 빌더가 fail-secure(원본 절대경로 미노출, CWE-359).
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .orElse(null);
        VideoExportContext ctx = niaJsonBuilder.prepareContext(meta, raw, usedLabels, eventAnnotation, deidVideoPath);

        List<FrameContext> frameContexts = new ArrayList<>(frames.size());
        for (LsDataSrc frame : frames) {
            frameContexts.add(new FrameContext(
                    frame, labelsBySrc.getOrDefault(frame.getSrcSn(), List.of())));
        }

        // 멱등 baseline 은 "직전 SUCCEEDED+PARTIAL export"의 해시다. 최신이 FAILED/PENDING 이어도
        // 그 이전 성공/부분 산출 해시를 쿼리 레벨 상태 IN 필터로 정확히 찾는다("최신 1건 후 필터" 방식은
        // 직전이 FAILED 면 null → 재산출 폭증). PARTIAL 을 포함하는 이유: 원천 이미지가 지속 부재해 매번
        // PARTIAL 로 마감되는 영상을 무수정 재승인할 때 contentHash 가 직전 PARTIAL 과 같으면 재산출해도
        // 같은 PARTIAL 결과라, skip 시켜 버전 무한 채번 + 이미지 무한 재복사(디스크 누적)를 막는다.
        // FAILED(written==0)는 재시도 유도를 위해 baseline 에서 계속 제외.
        String lastExportedHash = exportRepository
                .findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(
                        rawSn, List.of(LsDatasetExport.STATUS_SUCCEEDED, LsDatasetExport.STATUS_PARTIAL))
                .map(LsDatasetExport::getContentHash)
                .orElse(null);

        return Optional.of(new ExportPreparation(ctx, frameContexts, contentHash, lastExportedHash));
    }

    /**
     * 다음 산출 버전(=기존 건수+1)을 채번해 PENDING 레코드를 INSERT·flush 한다.
     *
     * <p>동시 승인 TOCTOU(CWE-362) 방어의 최종 백스톱 — {@code saveAndFlush} 로 UK(DATA_RAW_SN,
     * EXPORT_VER_NO) 위반을 이 트랜잭션 안에서 즉시 발생시킨다. 위반은 caller(오케스트레이터)가
     * {@code DataIntegrityViolationException} 으로 잡아 재채번한다. 각 시도는 REQUIRES_NEW 라 위반으로
     * rollback-only 가 된 이 트랜잭션이 승인/다른 시도와 격리된다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public InsertedExport insertNextVersion(long rawSn, String contentHash) {
        int version = (int) (exportRepository.countByDataRawSn(rawSn) + 1);
        // 버전 루트({labeling_root}/{rawSn}/v{n}) — 리졸버가 base 이탈(CWE-22)을 이미 검증한 하위.
        Path versionRoot = pathResolver.resolve(rawSn, ExportKind.ORIGINAL, version).getParent();
        LsDatasetExport record = LsDatasetExport.create(
                rawSn, version, versionRoot.toString(), contentHash);
        LsDatasetExport saved = exportRepository.saveAndFlush(record);
        return new InsertedExport(saved.getExportSn(), version);
    }

    /** 산출 성공 — SUCCEEDED 전이 + 산출 프레임 수 반영. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(long exportSn, int frameCnt) {
        exportRepository.findById(exportSn).ifPresent(e -> e.markSucceeded(frameCnt));
    }

    /** 일부 산출 — PARTIAL 전이 + 정상 기록된 프레임 수 반영. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markPartial(long exportSn, int frameCnt) {
        exportRepository.findById(exportSn).ifPresent(e -> e.markPartial(frameCnt));
    }

    /** 산출 실패 — FAILED 전이(승인 트랜잭션과 무관, 별도 커밋). */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long exportSn) {
        exportRepository.findById(exportSn).ifPresent(LsDatasetExport::markFailed);
    }

    /**
     * cutoff 이전에 생성된 stale PENDING export 를 일괄 FAILED 로 마감한다(파일 삭제 없음, 상태만 회수).
     *
     * <p>파일 쓰기/상태 마감 전 크래시로 {@code PENDING} 에 고착된 잔재를 정리한다. 조회된 엔티티는
     * 이 트랜잭션의 영속 컨텍스트에 있어 {@code markFailed} 후 dirty checking 으로 flush 된다.
     * 대상은 stale-minutes 임계를 넘긴 소수 잔재뿐이라 엔티티 순회로 충분하다(대량 아님).
     *
     * <p>양성 race 무해: 정상 export 는 수 초 내 완료되므로 임계를 넘는 PENDING 은 크래시 잔재다.
     * 설령 초장기 export 가 sweep 으로 FAILED 마킹돼도, 이후 그 export 의 정상 완료가 markSucceeded 로
     * 최종 상태를 덮으므로(last-writer-wins) 무해하다.
     *
     * @param cutoff 이 시각 이전에 생성된 PENDING 만 회수
     * @return FAILED 로 마감한 건수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int sweepStalePending(java.time.LocalDateTime cutoff) {
        List<LsDatasetExport> stale = exportRepository.findByExportSttsCdAndRegDtBefore(
                LsDatasetExport.STATUS_PENDING, cutoff);
        stale.forEach(LsDatasetExport::markFailed);
        // 엔티티는 영속 상태라 dirty checking 으로 flush 됨. 로그는 caller(sweeper)에서.
        return stale.size();
    }

    /**
     * 동결 event_annotation payload(jsonb 원문 문자열)를 {@link JsonNode} 로 파싱한다 — 각 프레임 문서에
     * 최상위 {@code event_annotation} 으로 pass-through(키 순서·형태 보존)하기 위함이다.
     *
     * <p>null/blank 면 null(동결 대상 없음). 파싱 실패는 산출을 깨지 않도록 null 로 fail-secure 처리하고
     * rawSn 만 로깅한다(payload 원문/PII 미출력, CWE-359/117). 동결본은 저장 전 검증된 jsonb 이므로
     * 정상 경로에서는 항상 파싱된다.
     */
    private JsonNode parseEventAnnotation(String evntAnnoCn, long rawSn) {
        if (evntAnnoCn == null || evntAnnoCn.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(evntAnnoCn);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[DatasetExport] frozen event_annotation parse failed — omitted rawSn={}", rawSn);
            return null;
        }
    }

    /** 라벨에서 참조된 라벨 마스터(categories 원천)를 distinct labelId 로 일괄 로드. */
    private List<LsLabel> loadUsedLabels(List<LsDataLbl> labels) {
        Set<Long> ids = new LinkedHashSet<>();
        for (LsDataLbl l : labels) {
            if (l.getLabelId() != null) {
                ids.add(l.getLabelId());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return labelMasterRepository.findAllById(ids);
    }
}
