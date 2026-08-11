package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.dataset.export.json.VlmDescriptionPolicy;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * stale PENDING 회수 tick 당 처리 상한 — 잔재가 대량으로 쌓여도 한 트랜잭션이 무한정 길어지지
     * 않게 한다(무제한 조회 금지). 남은 잔재는 다음 tick 이 이어서 회수한다.
     */
    private static final int STALE_SWEEP_BATCH_SIZE = 200;

    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final LsDatasetVideoMetaRepository videoMetaRepository;
    private final LsLabelRepository labelMasterRepository;
    private final VideoRepository videoRepository;
    private final LsDatasetExportRepository exportRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    /** 원천 축 개인정보 3필드 조달 — 관제 인입 평면값(LS_DATA_INGEST). 연결 규칙은 IngestSourceLink 소유. */
    private final IngestSourceRepository ingestSourceRepository;
    /** {@code video.vd_description} 조달용 메타(LS_DATA_META) 조회. 판정은 {@link VlmDescriptionPolicy}. */
    private final LsDataMetaRepository metaRepository;
    private final NiaJsonBuilder niaJsonBuilder;
    private final LabelContentHasher contentHasher;
    private final ObjectMapper objectMapper;
    /** H1 — 신고 구간 판정 <b>단일 원천</b>(잠금 변형 포함). {@code "F".equals} 재구현 금지. */
    private final kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;
    /**
     * VER_NO 실채번 — 산출 버전 번호를 승인 스냅샷({@code LS_LABEL_VERSION.VER_NO})에 찍는 단일 지점.
     * 번호를 새로 만들지 않고 <b>이 원장의 번호를 그대로</b> 전달한다(두 번째 진실원 금지).
     */
    private final kr.co.cudo.authoring.version.service.OutputVersionStamper outputVersionStamper;

    public DatasetExportTxService(LsDataSrcRepository srcRepository,
                                  LsDataLblRepository labelRepository,
                                  LsDatasetVideoMetaRepository videoMetaRepository,
                                  LsLabelRepository labelMasterRepository,
                                  VideoRepository videoRepository,
                                  LsDatasetExportRepository exportRepository,
                                  LsDeidentProcLogRepository deidentProcLogRepository,
                                  IngestSourceRepository ingestSourceRepository,
                                  LsDataMetaRepository metaRepository,
                                  NiaJsonBuilder niaJsonBuilder,
                                  LabelContentHasher contentHasher,
                                  ObjectMapper objectMapper,
                                  kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate,
                                  kr.co.cudo.authoring.version.service.OutputVersionStamper outputVersionStamper) {
        this.srcRepository = srcRepository;
        this.labelRepository = labelRepository;
        this.videoMetaRepository = videoMetaRepository;
        this.labelMasterRepository = labelMasterRepository;
        this.videoRepository = videoRepository;
        this.exportRepository = exportRepository;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.ingestSourceRepository = ingestSourceRepository;
        this.metaRepository = metaRepository;
        this.niaJsonBuilder = niaJsonBuilder;
        this.contentHasher = contentHasher;
        this.objectMapper = objectMapper;
        this.deidentReportGate = deidentReportGate;
        this.outputVersionStamper = outputVersionStamper;
    }

    /**
     * 산출에 필요한 입력(컨텍스트·프레임·콘텐츠 해시·직전 성공/부분 해시=멱등 baseline)을 DB 재조회로 조립한다.
     *
     * <p>산출 불가(프레임 0건 또는 활성 영상 메타 부재)면 {@link Optional#empty()} 로 skip 을 알린다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ExportPreparation> loadPreparation(long rawSn) {
        // R4 — 폐기된 프레임은 이미지 2벌·프레임 JSON·FRME_CNT 어디에도 들어가지 않는다. 이 목록이
        //   산출물 구성의 유일한 원천이라 여기서 거르면 하위 빌더를 손댈 필요가 없다.
        //   프레임 행·이미지 파일·라벨은 그대로 보존되며(논리 폐기) 복원하면 다시 산출된다.
        List<LsDataSrc> frames = srcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            // ⚠ 전 프레임을 폐기한 영상도 여기로 온다. 그 경우 산출을 갱신하지 않으므로 <b>직전 버전
            //   폴더가 그대로 남는다</b>(관제는 옛 구성을 계속 본다). 프레임 0건과 구분되지 않는
            //   상태이며, "모두 폐기"는 학습데이터로 쓸 것이 없다는 뜻이라 새 빈 버전을 만드는 것도
            //   답이 아니다 — 운영에서 관측되면 별도 판단이 필요한 지점이라 로그로 남긴다.
            log.info("[DatasetExport] no exportable frames — skip export rawSn={}", rawSn);
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

        // 원천 축 개인정보 3필드 — 관제 인입 평면값(LS_DATA_INGEST, V166/V170)을 rawSn 단위 1회 조회한다.
        //   ★ 파생영상(증강·해상도)은 "비식별 처리 전 원천"이라는 대상 자체가 없으므로 NONE 을 넣어
        //     두 블록 모두 null 로 산출한다(결손이 아니라 정상 — SourcePrivacyMeta javadoc 참조).
        //     리포지토리 술어(IngestSourceLink)도 파생을 제외하지만, 그 판정은 <조달 규칙>이고 여기는
        //     <원천 영상 존재 여부>라는 별개 사실이라 영상 행으로 명시 판정한다(fail-safe 이중화).
        //   ★ raw 가 null(영상 행 부재)이면 파생 여부를 알 수 없으므로 NONE — 값을 지어내지 않는다.
        // 인입 평면값은 rawSn 단위 1회만 조회해 두 용도로 쓴다(원천 축 개인정보 + video.event_id).
        // ★ 파생영상에서도 조회한다 — event_id 는 부모 인입값이 그대로 유효하다(IngestSourceLink 의
        //   "두 갈래" 규칙: 개인정보 3필드만 원본 한정이고 나머지 인입값은 파생에도 유효).
        IngestSourceRow ingest = ingestSourceRepository.findSourceMeta(rawSn);
        SourcePrivacyMeta srcPrivacy = resolveSourcePrivacy(raw, ingest);
        String ingestEvntId = (ingest == null) ? null : ingest.getEvntId();

        // video.vd_description(@req R10) — 조달 규칙의 단일 소유자는 VlmDescriptionPolicy 다(복제 금지).
        //   ★ 자기 rawSn 의 메타만 본다 — 조회 시점 <b>부모 폴백을 두지 않는다</b>.
        //     ⚠ 그렇다고 파생영상(증강·해상도)에 서술이 없는 것은 아니다 — DerivedMetaCopier
        //     (copyMetaAndReviews)가 생성 시점에 부모 메타를 <b>키 필터 없이 물리 복사</b>하므로 파생은
        //     자기 rawSn 행으로 부모 서술을 이미 갖는다. 그 값이 산출되는 것이 정합적이다: 파생 비디오는
        //     부모 비식별본의 복사본이고 변환 대상은 프레임 이미지뿐이라 상황묘사가 그대로 유효하다.
        //     여기서 폴백을 두지 않는 것은 "조회 시점 부모 재해석"을 하지 않겠다는 뜻이며(스냅샷 시맨틱 —
        //     이후 부모 서술 정정은 파생에 재전파되지 않는다), 실제로 복사가 없던 파생만 null 이 된다.
        //   조회는 rawSn 단위 1회(N+1 없음). 전량 로드지만 벤더 계약상 콜백당 ≤500 세그먼트 ·
        //   META_VL ≤2000자로 상한이 있고, 이 경로는 @Async 산출 전용이라 응답 지연 축이 아니다.
        List<LsDataMeta> dataMetas = metaRepository.findByRawSn(rawSn);
        String vdDescription = VlmDescriptionPolicy.resolve(dataMetas);
        // 콘텐츠 해시는 라벨뿐 아니라 산출 JSON 에 직렬화되는 프레임(frmExpln 등)·영상 메타
        // (prvcTypeCd/prvcYn·해상도 등)·원천 축 개인정보·VLM 서술까지 반영한다 — frmExpln/개인정보/
        // 서술 정정 재승인의 stale 고착 방지.
        List<LsDataLbl> allLabels = labelRepository.findAllByRawSn(rawSn);
        // R4 — 폐기 프레임 목록을 해시 입력에 함께 넣는다. 위에서 frames 를 걸렀으므로 폐기하면 해시가
        //   이미 달라지지만, 그 근거가 "레코드가 사라졌다"는 <b>간접</b> 신호라 폐기 축이 코드에 드러나지
        //   않는다. 명시 입력으로 두면 나중에 이 조회가 바뀌어도 폐기가 해시에서 조용히 빠지지 않는다.
        //   식별자만 실으므로 PII 표면이 없다.
        List<Long> discardedSrcSns = srcRepository.findDiscardedSrcSnsByRawSn(rawSn);
        String contentHash = contentHasher.hash(allLabels, frames, meta, raw, srcPrivacy,
                vdDescription, discardedSrcSns);

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
        VideoExportContext ctx = niaJsonBuilder.prepareContext(
                meta, raw, usedLabels, eventAnnotation, deidVideoPath, srcPrivacy, ingestEvntId,
                vdDescription);

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

        // co-locate 산출 base 원천 — 라이브 LS_DATA_RAW 우선, 부재 시 활성 메타 스냅샷 값으로 폴백한다.
        String rawFilePathNm = (raw != null && raw.getRawFilePathNm() != null && !raw.getRawFilePathNm().isBlank())
                ? raw.getRawFilePathNm()
                : meta.getRawFilePathNm();

        return Optional.of(new ExportPreparation(
                ctx, frameContexts, contentHash, lastExportedHash, rawFilePathNm));
    }

    /**
     * 다음 산출 버전(=기존 건수+1)을 채번해 PENDING 레코드를 INSERT·flush 한다.
     *
     * <p>동시 승인 TOCTOU(CWE-362) 방어의 최종 백스톱 — {@code saveAndFlush} 로 UK(DATA_RAW_SN,
     * OUTPUT_VER_NO) 위반을 이 트랜잭션 안에서 즉시 발생시킨다. 위반은 caller(오케스트레이터)가
     * {@code DataIntegrityViolationException} 으로 잡아 재채번한다. 각 시도는 REQUIRES_NEW 라 위반으로
     * rollback-only 가 된 이 트랜잭션이 승인/다른 시도와 격리된다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public InsertedExport insertNextVersion(long rawSn, String contentHash, String exportPathNm) {
        int version = (int) (exportRepository.countByDataRawSn(rawSn) + 1);
        // A-4 — OUTPUT_PATH_NM(V173, 구 EXPORT_PATH_NM) 은 <b>영상 루트</b>({dirname(원본)}/{rawSn})다. 관제가 한 경로 아래에서
        // v1·v2… 를 모두 보고 골라야 롤백이 성립하기 때문(버전 루트 저장은 폐기). 값은 호출자가 리졸버로
        // 검증해 넘긴 절대경로이며, 이후 어떤 조회 경로에서도 재계산하지 않는다(S8 — 전략 전환 안전).
        LsDatasetExport record = LsDatasetExport.create(
                rawSn, version, exportPathNm, contentHash);
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

    /**
     * H1 (HIGH · CWE-359/367) — <b>성공/부분 마감을 RAW 잠금 하에 재판정</b>한다.
     *
     * <h3>닫는 창</h3>
     * 진입부 게이트({@code DatasetExportService.export} 선두)는 export 시작 시점 1회 무잠금 판정이다.
     * 그 뒤 수 분간의 프레임 복사 중에 비식별 누락 신고가 커밋되면(신고는 {@code findByRawSnForUpdate}
     * + {@code markDeidentified("F")}), 누락이 확인된 프레임이 이미 {@code v{n+1}} 에 기록된 상태로
     * SUCCEEDED 마감 → {@code V_COMPLETED_VIDEO.OUTPUT_PATH_NM} 갱신 → 통지 발송까지 이어진다.
     *
     * <h3>어떻게 닫는가</h3>
     * 판정({@link DeidentReportGate#isUnderDeidentReportLocked})과 상태 전이를 <b>같은 트랜잭션</b>에서
     * 수행한다. RAW 행을 잠근 채 마감하므로 신고 UPDATE 와 직렬화된다 — 신고가 먼저면 여기서 관측되고,
     * 여기가 먼저면 신고는 이 마감 커밋 뒤에 진행된다(그 경우는 "export 완료 후 신고" = 정상 순서).
     *
     * <h3>차단 시 처리 — 행을 남기지 않는다</h3>
     * 진입부 차단과 동일하게 <b>PENDING 행을 삭제</b>해 "차단 = skip(행 없음)" 불변을 유지한다.
     * FAILED 로 남기면 ① 정책적 보류가 장애로 오분류되고 ② 회수기가 반드시 다시 막힐 재시도로
     * 시도 상한(RTY_NMTM)만 소진한다. 재산출·통지 복구는 신고 resolve 시점 재트리거(M1)가 담당한다.
     * DB 를 먼저 정리(행 삭제)하고 파일 삭제는 호출자가 뒤이어 수행한다 — 순서를 뒤집으면 잠깐이라도
     * "존재하지 않는 폴더를 가리키는 SUCCEEDED 행"이 뷰에 보일 수 있다.
     *
     * <p><b>잠금 순서</b>: RAW → LS_DATASET_EXPORT. 이 순서를 역으로(EXPORT 선점 후 RAW) 잡는 경로는
     * 없다({@code claimForRetry} 는 EXPORT 만, 신고/증강/해상도 경로는 RAW 를 선두로 잡는다) — 사이클 없음.
     *
     * <h3>데이터구축용량은 이 마감과 <b>같은 트랜잭션</b>에서 쓴다 (V173, @req R4)</h3>
     * 별도 UPDATE 로 분리하면 ①마감은 됐는데 용량만 빠진 행이 남을 수 있고 ②차단 분기에서 행이
     * 삭제된 뒤 용량을 쓰는 순서 사고가 열린다. 값 자체는 트랜잭션 <b>밖</b>(호출자)에서 계산해 넘긴다
     * — 파일 순회를 커넥션을 쥔 채 하면 NAS I/O 로 커넥션이 마른다.
     *
     * @param partial      {@code true} 면 PARTIAL, {@code false} 면 SUCCEEDED 로 마감
     * @param dataEtblCpct 산출 폴더 총 바이트. {@code null}(용량 산출 실패)이어도 <b>마감은 그대로</b> 한다.
     * @return 마감했으면 {@code true}, 신고 구간이라 차단(행 삭제)했으면 {@code false}
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeUnlessUnderDeidentReport(long rawSn, long exportSn, int frameCnt, boolean partial,
                                                    Long dataEtblCpct) {
        if (deidentReportGate.isUnderDeidentReportLocked(rawSn)) {
            exportRepository.deleteById(exportSn);
            log.warn("[DatasetExport] finalize blocked — deident report opened during export rawSn={}", rawSn);
            return false;
        }
        exportRepository.findById(exportSn).ifPresent(e -> {
            if (partial) {
                e.markPartial(frameCnt, dataEtblCpct);
            } else {
                e.markSucceeded(frameCnt, dataEtblCpct);
            }
            // VER_NO 실채번(@design D5 / @req R6) — 이 산출의 버전 번호를 승인 스냅샷에 찍는다.
            //   <b>마감과 같은 트랜잭션</b>이라 "번호가 찍힌 스냅샷 ⇔ 실재하는 산출 폴더"가 원자적이다.
            //   차단 분기(위 return false)에서는 폴더가 지워지므로 찍지 않는다.
            //   승인 트랜잭션 시점에는 이 번호를 알 수 없다(채번이 여기 @Async 산출 안에서 일어난다).
            outputVersionStamper.stamp(rawSn, e.getExportVerNo());
        });
        return true;
    }

    /** 산출 실패 — FAILED 전이(승인 트랜잭션과 무관, 별도 커밋). */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long exportSn) {
        exportRepository.findById(exportSn).ifPresent(LsDatasetExport::markFailed);
    }

    /**
     * D-ISSUE-04(b) DEV_FIX(H7①/H7③) — 실패 export 재시도 <b>원자 클레임</b>(짧은 독립 트랜잭션).
     *
     * <p>{@code true} 를 받은 호출자만 재산출을 트리거한다. 클레임 성공 시 시도 이력({@code RTY_NMTM})이
     * 산출 결과와 무관하게 기록되므로 상한(max-attempts)이 실제로 걸리고, 동시에 같은 영상을 두 노드가
     * 동시에 집어가지 못한다(Quartz 클러스터링 설정에 의존하지 않는 DB 레벨 보장 —
     * {@code LsDatasetExportRepository#claimForRetry} 주석 참조).
     *
     * <p>REQUIRES_NEW — 회수 잡은 트랜잭션 밖에서 돌고, 클레임은 즉시 커밋되어야 다른 노드가 관측한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean claimForRetry(long exportSn, int maxAttempts, java.time.LocalDateTime claimCutoff) {
        return exportRepository.claimForRetry(
                exportSn, maxAttempts, claimCutoff, java.time.LocalDateTime.now()) == 1;
    }

    /**
     * cutoff 이전에 생성된 stale PENDING export 를 FAILED 로 회수한다(파일 삭제 없음, 상태만 회수).
     *
     * <p>파일 쓰기/상태 마감 전 크래시로 {@code PENDING} 에 고착된 잔재를 정리한다.
     *
     * <p><b>원자 클레임(Phase 9-B)</b>: 후보를 상한과 함께 조회한 뒤 건별 조건부 UPDATE
     * ({@link LsDatasetExportRepository#claimStalePending})로 회수하고, <b>1행을 얻은 건만</b> 센다.
     * 구 구현("조회 후 엔티티 setter")은 배포 토폴로지(2노드 Active-Active)에서 같은 행을 두 노드가 각자
     * FAILED 로 쓰는 이중 쓰기였다 — Quartz 클러스터링은 기본 꺼져 있어 잡 단위 배타성을 기대할 수 없다.
     *
     * <p>양성 race 무해: 정상 export 는 수 초 내 완료되므로 임계를 넘는 PENDING 은 크래시 잔재다.
     * 설령 초장기 export 가 sweep 으로 FAILED 마킹돼도, 이후 그 export 의 정상 완료가 markSucceeded 로
     * 최종 상태를 덮으므로(last-writer-wins) 무해하다.
     *
     * @param cutoff 이 시각 이전에 생성된 PENDING 만 회수
     * @return 이번 호출이 <b>실제로 클레임해</b> FAILED 로 마감한 건수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int sweepStalePending(java.time.LocalDateTime cutoff) {
        List<Long> anchors = exportRepository.findStalePendingAnchors(cutoff, STALE_SWEEP_BATCH_SIZE);
        int reclaimed = 0;
        for (Long exportSn : anchors) {
            if (exportRepository.claimStalePending(exportSn, cutoff) == 1) {
                reclaimed++;
            }
        }
        // 로그는 caller(sweeper)에서.
        return reclaimed;
    }

    /**
     * 원천 축 개인정보 3필드 조달 — 원본 영상만 관제 인입값을 싣고, 파생영상·영상행 부재는
     * {@link SourcePrivacyMeta#NONE}(두 블록 모두 {@code null}) 이다.
     *
     * <p>인입 행이 아직/영영 없으면 리포지토리가 전 필드 null 인 행을 돌려주는데, 그것은
     * "원천 영상은 있지만 관제가 판정을 안 보냈다"는 뜻이라 {@code ofIngest(null,null,null)} 이
     * 정확하다({@code NONE} 과 달리 {@code image} 블록 상수는 그대로 실린다).
     */
    private static SourcePrivacyMeta resolveSourcePrivacy(LsDataRaw raw, IngestSourceRow ingest) {
        if (raw == null || raw.getOrgnlRawSn() != null || ingest == null) {
            return SourcePrivacyMeta.NONE;
        }
        return SourcePrivacyMeta.ofIngest(
                ingest.getSrcAnonyInclYn(), ingest.getSrcPsdoInclYn(), ingest.getSrcPrvcInclYn());
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
