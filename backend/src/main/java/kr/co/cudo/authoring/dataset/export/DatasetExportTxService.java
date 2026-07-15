package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
    private final NiaJsonBuilder niaJsonBuilder;
    private final LabelContentHasher contentHasher;
    private final DatasetExportPathResolver pathResolver;

    public DatasetExportTxService(LsDataSrcRepository srcRepository,
                                  LsDataLblRepository labelRepository,
                                  LsDatasetVideoMetaRepository videoMetaRepository,
                                  LsLabelRepository labelMasterRepository,
                                  VideoRepository videoRepository,
                                  LsDatasetExportRepository exportRepository,
                                  NiaJsonBuilder niaJsonBuilder,
                                  LabelContentHasher contentHasher,
                                  DatasetExportPathResolver pathResolver) {
        this.srcRepository = srcRepository;
        this.labelRepository = labelRepository;
        this.videoMetaRepository = videoMetaRepository;
        this.labelMasterRepository = labelMasterRepository;
        this.videoRepository = videoRepository;
        this.exportRepository = exportRepository;
        this.niaJsonBuilder = niaJsonBuilder;
        this.contentHasher = contentHasher;
        this.pathResolver = pathResolver;
    }

    /**
     * 산출에 필요한 입력(컨텍스트·프레임·콘텐츠 해시·직전 성공 해시)을 DB 재조회로 조립한다.
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
        VideoExportContext ctx = niaJsonBuilder.prepareContext(meta, raw, usedLabels);

        List<FrameContext> frameContexts = new ArrayList<>(frames.size());
        for (LsDataSrc frame : frames) {
            frameContexts.add(new FrameContext(
                    frame, labelsBySrc.getOrDefault(frame.getSrcSn(), List.of())));
        }

        // 멱등 판정 키는 "최신 export 1건이 SUCCEEDED 인 경우"가 아니라 "최신 SUCCEEDED export"의 해시다.
        // 최신이 FAILED/PENDING 이어도 그 이전 SUCCEEDED 해시를 쿼리 레벨 상태 필터로 정확히 찾는다
        // (구 findFirst...OrderBy + filter 방식은 직전이 FAILED 면 null → 재산출 폭증하던 버그).
        String lastSucceededHash = exportRepository
                .findFirstByDataRawSnAndExportSttsCdOrderByExportVerNoDesc(
                        rawSn, LsDatasetExport.STATUS_SUCCEEDED)
                .map(LsDatasetExport::getContentHash)
                .orElse(null);

        return Optional.of(new ExportPreparation(ctx, frameContexts, contentHash, lastSucceededHash));
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

    /** 산출 실패 — FAILED 전이(승인 트랜잭션과 무관, 별도 커밋). */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long exportSn) {
        exportRepository.findById(exportSn).ifPresent(LsDatasetExport::markFailed);
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
