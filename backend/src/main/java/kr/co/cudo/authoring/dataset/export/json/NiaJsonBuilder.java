package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.databind.JsonNode;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 한 프레임의 NIA COCO 확장 어노테이션 문서({@link NiaAnnotationDoc})를 조립하는 빌더.
 *
 * <p>파일 IO/DB 조회 없음 — 입력은 이미 로드된 엔티티. 사용 흐름:
 * <ol>
 *   <li>{@link #prepareContext} 로 rawSn 단위 컨텍스트(video meta·categories·info·dataset·licences)를 1회 준비</li>
 *   <li>프레임마다 {@link #build}(context, frame, kind) 로 image/annotations 를 조립</li>
 * </ol>
 * {@code anonymity}/이미지 경로가 {@link ExportKind} 에 의존하므로 video/image 는 build 시점에 조립한다.
 */
@Component
public class NiaJsonBuilder {

    private static final Logger log = LoggerFactory.getLogger(NiaJsonBuilder.class);

    /** NIA 포맷 버전 (xlsx v1.3). */
    private static final String FORMAT_VERSION = "1.3";
    private static final String INFO_DESCRIPTION = "AI기반 CCTV 관제지원시스템 학습데이터";
    private static final String TYPE_INSTANCES = "instances";

    private final LabelToAnnotationMapper labelMapper;
    private final VideoMetaMapper videoMapper;
    private final CategoryMapper categoryMapper;

    public NiaJsonBuilder(LabelToAnnotationMapper labelMapper,
                          VideoMetaMapper videoMapper,
                          CategoryMapper categoryMapper) {
        this.labelMapper = labelMapper;
        this.videoMapper = videoMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * rawSn 단위 공통 컨텍스트를 1회 준비한다(프레임 무관·kind 무관 부분 + 원자재 meta/raw).
     *
     * @param meta       영상 메타 스냅샷 (필수)
     * @param raw        원시 영상 (선택 — null 허용)
     * @param usedLabels 이 영상에서 사용된 라벨 마스터 집합 (categories 원천)
     */
    public VideoExportContext prepareContext(LsDatasetVideoMeta meta, LsDataRaw raw, Collection<LsLabel> usedLabels) {
        return prepareContext(meta, raw, usedLabels, null, null);
    }

    /**
     * rawSn 단위 공통 컨텍스트를 1회 준비한다(동결 event_annotation 포함 오버로드, 비식별 영상 경로 없음).
     */
    public VideoExportContext prepareContext(LsDatasetVideoMeta meta, LsDataRaw raw,
                                             Collection<LsLabel> usedLabels, JsonNode eventAnnotation) {
        return prepareContext(meta, raw, usedLabels, eventAnnotation, null);
    }

    /**
     * rawSn 단위 공통 컨텍스트를 1회 준비한다(동결 event_annotation + 비식별 영상 경로 포함 오버로드).
     *
     * @param meta            영상 메타 스냅샷 (필수)
     * @param raw             원시 영상 (선택 — null 허용)
     * @param usedLabels      이 영상에서 사용된 라벨 마스터 집합 (categories 원천)
     * @param eventAnnotation 승인 시점 동결된 event_annotation payload(JsonNode, null 허용) — 각 프레임
     *                        문서 최상위 {@code event_annotation} 으로 pass-through(키/형태 보존)
     * @param deidVideoPath   비식별 <b>영상</b> 파일 경로(LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM, null 허용).
     *                        DEIDENTIFIED 산출의 dataset/video 경로 필드에 사용된다 — 원본 경로가 비식별
     *                        산출물에 새지 않도록 kind 별로 분기한다({@link #build}). null 이면 fail-secure
     *                        (원본 절대경로 대신 null 노출, CWE-359).
     */
    public VideoExportContext prepareContext(LsDatasetVideoMeta meta, LsDataRaw raw,
                                             Collection<LsLabel> usedLabels, JsonNode eventAnnotation,
                                             String deidVideoPath) {
        if (meta == null) {
            throw new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "영상 메타가 null 입니다.");
        }
        Long rawSn = meta.getRawSn();
        String videoId = (rawSn == null) ? null : String.valueOf(rawSn);

        List<NiaCategory> categories = categoryMapper.toCategories(usedLabels);
        NiaInfo info = new NiaInfo(LocalDate.now().getYear(), FORMAT_VERSION, INFO_DESCRIPTION,
                LocalDate.now().toString());
        List<NiaLicence> licences = List.of(NiaLicence.privateUse());

        return new VideoExportContext(meta, raw, videoId, info, deidVideoPath, licences, categories, eventAnnotation);
    }

    /**
     * 한 프레임의 자기완결 {@link NiaAnnotationDoc} 를 조립한다.
     *
     * @param ctx   {@link #prepareContext} 산출 컨텍스트
     * @param frame 프레임 + 그 라벨
     * @param kind  산출 종류 (anonymity/이미지 경로 결정)
     */
    public NiaAnnotationDoc build(VideoExportContext ctx, FrameContext frame, ExportKind kind) {
        if (ctx == null || frame == null || kind == null) {
            throw new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "빌드 입력이 null 입니다.");
        }
        NiaVideo video = videoMapper.toVideo(ctx.meta(), ctx.raw(), kind, ctx.deidVideoPath());
        NiaDataset dataset = buildDataset(ctx, kind);
        NiaImage image = buildImage(ctx, frame.frame(), kind);
        List<NiaAnnotation> annotations = buildAnnotations(frame.labels(), image.id());

        return new NiaAnnotationDoc(
                ctx.info(), dataset, ctx.licences(),
                video, ctx.eventAnnotation(), image, annotations, ctx.categories(), TYPE_INSTANCES);
    }

    /**
     * kind 별 {@code dataset} 블록(src_path/name)을 조립한다.
     *
     * <p>ORIGINAL=원본 raw 영상 경로, DEIDENTIFIED=비식별 영상 경로(proc log DE_IDNTF_FILE_PATH_NM).
     * 비식별 산출물에 원본 경로가 새지 않도록 kind 로 분기한다. deid 경로 미상이면 <b>fail-secure</b> —
     * 원본 절대경로를 넣지 않고 null 로 둔다(정보노출 CWE-359 방지).
     */
    private NiaDataset buildDataset(VideoExportContext ctx, ExportKind kind) {
        String path = (kind == ExportKind.ORIGINAL)
                ? ctx.meta().getRawFilePathNm()
                : ctx.deidVideoPath();
        return new NiaDataset(ctx.videoId(), baseNameNoExt(path), path, null);
    }

    private NiaImage buildImage(VideoExportContext ctx, LsDataSrc src, ExportKind kind) {
        Integer imageId = (src.getSrcSn() == null) ? null : src.getSrcSn().intValue();
        Long frameNo = src.getFrameNo();
        // 파일명은 export writer·관제 통지와 <b>동일한 단일 지점</b>({@link ExportFileNaming})을 따른다.
        // 여기만 구 규칙(frame-{n}.jpg)을 쓰면 같은 폴더에 실재하지 않는 파일을 JSON 이 가리키게 된다.
        // (frame_num 은 별개 개념 — 추출 순번 기반 파일명과 달리 영상 내 위치를 뜻하므로 여기서 바꾸지 않는다)
        String fileName = (frameNo == null) ? null : ExportFileNaming.imageFileName(frameNo);
        // A-6 — frame_num 은 <b>실제 영상 내 프레임 위치</b>(VDO_FRM_NO)다. 구 구현은 추출 순번(FRM_NO)을
        // 실어 "0002.json 의 frame_num=2" 처럼 파일명과 동어반복이 되어 영상 내 위치 정보를 잃었다.
        // 미측정(null)이면 <b>null 그대로</b> 내보낸다 — FRM_NO 폴백은 의미가 다른 값을 위치로 위장시킨다.
        Long videoFrameNo = src.getVideoFrameNo();
        LsDatasetVideoMeta meta = ctx.meta();
        // ★ 개인정보 3필드는 판정을 여기서 하지 않는다 — ExportPrivacyPolicy 단일 지점에 위임한다.
        //   (기본값 + 수동 override 적용 범위 + 그 근거·해소 조건은 모두 그 클래스 주석에 있다.)
        //   요약: ORIGINAL=프레임 수동값 우선 / DEIDENTIFIED=수동값 무시하고 기본값 고정.
        //   DEIDENTIFIED 에서 수동값을 무시하는 이유는 video 블록(VideoMetaMapper)이 <b>영상 단위</b>라
        //   프레임 수동값을 태울 수 없어, 허용하면 같은 문서 안에서 video/image 가 모순되기 때문이다
        //   (2026-07-31 적대검증 실행 재현). 영상 단위 개인정보 메타 저장소가 생기면 재배선한다.
        String anonymity = ExportPrivacyPolicy.resolveAnonymity(kind, src.getAnonyInclYn());
        String pseudonymity = ExportPrivacyPolicy.resolvePseudonymity(
                kind, src.getPsdoInclYn(), meta.getPrvcTypeCd());
        String privacyIncluded = ExportPrivacyPolicy.resolvePrivacyIncluded(
                kind, src.getPrvcInclYn(), meta.getPrvcYn());

        return new NiaImage(
                imageId,
                fileName,
                meta.getVdoWdth(),
                meta.getVdoHgt(),
                src.getShtDt() == null ? null : src.getShtDt().toString(),
                null, // license_id (미보유)
                ctx.videoId(),
                null, // type (미보유)
                videoFrameNo == null ? null : videoFrameNo.intValue(),
                anonymity,
                pseudonymity,
                privacyIncluded,
                src.getFrmExpln()
        );
    }

    private List<NiaAnnotation> buildAnnotations(List<LsDataLbl> labels, Integer imageId) {
        List<NiaAnnotation> result = new ArrayList<>();
        if (labels == null) {
            return result;
        }
        int skipped = 0;
        for (LsDataLbl lbl : labels) {
            if (lbl == null) {
                continue;
            }
            try {
                result.add(labelMapper.toAnnotation(lbl, imageId));
            } catch (CustomException e) {
                // 방어(CWE-20 fail-secure): malformed 라벨 1건은 문서 전체를 깨지 않고 skip.
                // 좌표 원문/PII 미노출 — lblSn 만 로깅.
                skipped++;
                log.warn("[NiaJsonBuilder] annotation skipped lblSn={}", lbl.getLblSn());
            }
        }
        if (skipped > 0) {
            log.warn("[NiaJsonBuilder] malformed annotations skipped count={}", skipped);
        }
        return result;
    }

    /** 경로 basename ('/' '\' 처리). */
    private static String basename(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String n = path.replace('\\', '/');
        int idx = n.lastIndexOf('/');
        String name = (idx >= 0) ? n.substring(idx + 1) : n;
        return name.isBlank() ? null : name;
    }

    /** basename 에서 확장자 제거 (dataset.name 용). */
    private static String baseNameNoExt(String path) {
        String base = basename(path);
        if (base == null) {
            return null;
        }
        int dot = base.lastIndexOf('.');
        return (dot > 0) ? base.substring(0, dot) : base;
    }

    /**
     * rawSn 단위 준비 컨텍스트 — build 간 재사용되는 kind 무관 부분 + 원자재(meta/raw).
     */
    public record VideoExportContext(
            LsDatasetVideoMeta meta,
            LsDataRaw raw,
            String videoId,
            NiaInfo info,
            String deidVideoPath,
            List<NiaLicence> licences,
            List<NiaCategory> categories,
            JsonNode eventAnnotation
    ) {
    }

    /**
     * 프레임 단위 입력 — 프레임(LS_DATA_SRC) + 그 라벨(LS_DATA_LBL 목록).
     */
    public record FrameContext(
            LsDataSrc frame,
            List<LsDataLbl> labels
    ) {
    }
}
