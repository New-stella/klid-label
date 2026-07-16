package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportKind;
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
        if (meta == null) {
            throw new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "영상 메타가 null 입니다.");
        }
        Long rawSn = meta.getRawSn();
        String videoId = (rawSn == null) ? null : String.valueOf(rawSn);
        String rawPath = meta.getRawFilePathNm();

        List<NiaCategory> categories = categoryMapper.toCategories(usedLabels);
        NiaInfo info = new NiaInfo(LocalDate.now().getYear(), FORMAT_VERSION, INFO_DESCRIPTION,
                LocalDate.now().toString());
        NiaDataset dataset = new NiaDataset(videoId, baseNameNoExt(rawPath), rawPath, null);
        List<NiaLicence> licences = List.of(NiaLicence.privateUse());

        return new VideoExportContext(meta, raw, videoId, info, dataset, licences, categories);
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
        NiaVideo video = videoMapper.toVideo(ctx.meta(), ctx.raw(), kind);
        NiaImage image = buildImage(ctx, frame.frame(), kind);
        List<NiaAnnotation> annotations = buildAnnotations(frame.labels(), image.id());

        return new NiaAnnotationDoc(
                ctx.info(), ctx.dataset(), ctx.licences(),
                video, image, annotations, ctx.categories(), TYPE_INSTANCES);
    }

    private NiaImage buildImage(VideoExportContext ctx, LsDataSrc src, ExportKind kind) {
        Integer imageId = (src.getSrcSn() == null) ? null : src.getSrcSn().intValue();
        Long frameNo = src.getFrameNo();
        String fileName = (frameNo == null) ? null : "frame-" + frameNo + ".jpg";
        String kindPath = (kind == ExportKind.ORIGINAL) ? src.getSrcFilePathNm() : src.getDeidFilePath();
        String anonymity = (kind == ExportKind.ORIGINAL) ? "N" : "Y";

        LsDatasetVideoMeta meta = ctx.meta();
        String pseudonymity = LsDataRaw.PRVC_TYPE_PSDO.equals(meta.getPrvcTypeCd()) ? "Y" : "N";

        return new NiaImage(
                imageId,
                fileName,
                basename(kindPath),
                meta.getVdoWdth(),
                meta.getVdoHgt(),
                src.getShtDt() == null ? null : src.getShtDt().toString(),
                null, // license_id (미보유)
                ctx.videoId(),
                null, // type (미보유)
                frameNo == null ? null : frameNo.intValue(),
                anonymity,
                pseudonymity,
                meta.getPrvcYn(),
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
            NiaDataset dataset,
            List<NiaLicence> licences,
            List<NiaCategory> categories
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
