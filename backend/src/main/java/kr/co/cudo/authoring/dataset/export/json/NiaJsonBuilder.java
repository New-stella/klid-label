package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.databind.JsonNode;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.dataset.export.SourcePrivacyMeta;
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

    /**
     * {@code dataset.name} 접미사 — 데이터셋명 규칙 <b>{@code {이벤트명} 데이터셋 구축}</b> (R9-a).
     *
     * <p><b>같은 규칙이 SQL 로도 존재한다</b> — {@code V_COMPLETED_VIDEO.DATST_NM}/{@code DATST_EXPLN}
     * ({@code V174__rebuild_completed_video_view.sql}). 코드를 공유할 수 없으므로 두 표현이 같은 값을
     * 내는지는 {@code V174CompletedVideoViewContractIT} 가 실 DB 에서 대조해 고정한다 — 한쪽만 바꾸면
     * 그 테스트가 깨진다. 이 문자열을 호출부에 복제하지 말 것.
     */
    private static final String DATASET_NAME_SUFFIX = " 데이터셋 구축";

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
        return prepareContext(meta, raw, usedLabels, eventAnnotation, deidVideoPath, SourcePrivacyMeta.NONE);
    }

    /**
     * rawSn 단위 공통 컨텍스트를 1회 준비한다(<b>원천 축 개인정보 입력 포함</b> — 산출 경로가 쓰는 정본).
     *
     * @param srcPrivacy 원천 축 입력 — 관제 인입 판정({@code LS_DATA_INGEST}, V166/V170). 파생영상·영상
     *                   행 부재면 {@link SourcePrivacyMeta#NONE} 이며 그때 원천 3필드는 두 블록 모두
     *                   {@code null} 이다(상수도 싣지 않는다).
     */
    public VideoExportContext prepareContext(LsDatasetVideoMeta meta, LsDataRaw raw,
                                             Collection<LsLabel> usedLabels, JsonNode eventAnnotation,
                                             String deidVideoPath, SourcePrivacyMeta srcPrivacy) {
        return prepareContext(meta, raw, usedLabels, eventAnnotation, deidVideoPath, srcPrivacy, null);
    }

    /**
     * rawSn 단위 공통 컨텍스트를 1회 준비한다(<b>인입 이벤트 식별자 포함</b> — 산출 경로가 쓰는 정본).
     *
     * @param ingestEvntId 관제 인입 이벤트 식별자({@code LS_DATA_INGEST.EVNT_ID}, 예 {@code ABA_0001}) —
     *                     {@code video.event_id} 의 유일한 조달처(R9-c). 관제 미송신이면 {@code null} 이며
     *                     {@code EVNT_TYPE_CD} 로 폴백하지 않는다(축이 다른 값을 싣던 것이 애초의 결함).
     */
    public VideoExportContext prepareContext(LsDatasetVideoMeta meta, LsDataRaw raw,
                                             Collection<LsLabel> usedLabels, JsonNode eventAnnotation,
                                             String deidVideoPath, SourcePrivacyMeta srcPrivacy,
                                             String ingestEvntId) {
        return prepareContext(meta, raw, usedLabels, eventAnnotation, deidVideoPath, srcPrivacy,
                ingestEvntId, null);
    }

    /**
     * rawSn 단위 공통 컨텍스트를 1회 준비한다(<b>VLM 서술 포함</b> — 산출 경로가 쓰는 정본).
     *
     * @param vdDescription {@code video.vd_description} 값. 조달 규칙의 단일 소유자는
     *                      {@link VlmDescriptionPolicy} 이며 여기서는 <b>판정된 값</b>만 받는다.
     *                      원천(외부 VLM {@code verify} 서술 / 보존된 레거시 구간 행)이 없으면
     *                      {@code null} 이다 — 값을 지어내지 않는다. [req: R10]
     */
    public VideoExportContext prepareContext(LsDatasetVideoMeta meta, LsDataRaw raw,
                                             Collection<LsLabel> usedLabels, JsonNode eventAnnotation,
                                             String deidVideoPath, SourcePrivacyMeta srcPrivacy,
                                             String ingestEvntId, String vdDescription) {
        if (meta == null) {
            throw new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "영상 메타가 null 입니다.");
        }
        Long rawSn = meta.getRawSn();
        String videoId = (rawSn == null) ? null : String.valueOf(rawSn);

        List<NiaCategory> categories = categoryMapper.toCategories(usedLabels);
        // year = <데이터 구축 연도> = 검수 완료 연도(R9-b). LocalDate.now() 를 쓰면 같은 영상을 다시
        // 산출할 때(승인 후 수정 → 재export) 값이 바뀐다 — 연말·연초 재산출에서 실제로 갈린다.
        // 뷰 V_COMPLETED_VIDEO.DATA_ETBL_YR(to_char(RVW_CMPL_DT,'YYYY'))과 같은 개념·같은 조달처이며,
        // 미상(null)일 때 값을 지어내지 않는 것도 그 뷰와 동일하다(to_char(NULL) = NULL).
        // date_created 는 그대로 now() 다 — 이 문서를 만든 날짜라는 <다른 개념>이다.
        Integer year = (meta.getRvwCmplDt() == null) ? null : meta.getRvwCmplDt().getYear();
        NiaInfo info = new NiaInfo(year, FORMAT_VERSION, INFO_DESCRIPTION, LocalDate.now().toString());
        List<NiaLicence> licences = List.of(NiaLicence.privateUse());

        return new VideoExportContext(meta, raw, videoId, info, deidVideoPath, licences, categories,
                eventAnnotation, srcPrivacy == null ? SourcePrivacyMeta.NONE : srcPrivacy, ingestEvntId,
                vdDescription);
    }

    /**
     * 한 프레임의 자기완결 {@link NiaAnnotationDoc} 를 조립한다.
     *
     * @param ctx   {@link #prepareContext} 산출 컨텍스트
     * @param frame 프레임 + 그 라벨
     * @param kind  산출 종류 (anonymity/이미지 경로 결정)
     */
    public NiaAnnotationDoc build(VideoExportContext ctx, FrameContext frame, ExportKind kind) {
        if (frame == null) {
            throw new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "빌드 입력이 null 입니다.");
        }
        return build(ctx, frame.frame(), toSources(frame.labels()), kind);
    }

    /**
     * 한 프레임의 자기완결 {@link NiaAnnotationDoc} 를 조립한다(<b>저장소 중립</b> 오버로드).
     *
     * <p>라벨을 엔티티가 아니라 {@link AnnotationSource} 로 받으므로 내부 파이프라인 라벨과 포털 사용자
     * 작업 라벨이 <b>같은 빌더·같은 판정</b>으로 같은 구조의 문서를 만든다. 위 {@link FrameContext}
     * 오버로드는 이 메서드로 위임하며 동작이 동일하다.
     *
     * @param ctx     {@link #prepareContext} 산출 컨텍스트
     * @param frame   대상 프레임 (LS_DATA_SRC — 포털도 데이터마트 프레임을 그대로 쓴다)
     * @param sources 그 프레임의 라벨 최소 입력 목록 (null 허용 = 라벨 0건)
     * @param kind    산출 종류 (anonymity/이미지 경로 결정)
     *
     * @design API-203
     */
    public NiaAnnotationDoc build(VideoExportContext ctx, LsDataSrc frame,
                                  List<AnnotationSource> sources, ExportKind kind) {
        if (ctx == null || frame == null || kind == null) {
            throw new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "빌드 입력이 null 입니다.");
        }
        // vd_description(@req R10)은 <b>영상 단위</b> 값이라 kind 로 갈리지 않는다 — 두 벌(orgnl/deid)이
        //   같은 서술을 싣는다(개인정보 3필드처럼 산출종류로 갈리는 축이 아니다).
        NiaVideo video = videoMapper.toVideo(ctx.meta(), ctx.raw(), kind, ctx.deidVideoPath(),
                ctx.srcPrivacy(), ctx.ingestEvntId(), ctx.vdDescription());
        NiaDataset dataset = buildDataset(ctx, kind);
        NiaImage image = buildImage(ctx, frame, kind);
        List<NiaAnnotation> annotations = buildAnnotations(sources, image.id());

        return new NiaAnnotationDoc(
                ctx.info(), dataset, ctx.licences(),
                video, ctx.eventAnnotation(), image, annotations, ctx.categories(), TYPE_INSTANCES);
    }

    /** 내부 파이프라인 라벨 목록 → 좁은 입력 목록(순서·null 원소 보존 — 하위 skip 규칙이 동일하게 적용). */
    private static List<AnnotationSource> toSources(List<LsDataLbl> labels) {
        if (labels == null) {
            return null;
        }
        List<AnnotationSource> sources = new ArrayList<>(labels.size());
        for (LsDataLbl lbl : labels) {
            sources.add(AnnotationSource.of(lbl));
        }
        return sources;
    }

    /**
     * kind 별 {@code dataset} 블록(src_path/name)을 조립한다.
     *
     * <p>{@code src_path} — ORIGINAL=원본 raw 영상 경로, DEIDENTIFIED=비식별 영상 경로
     * (proc log DE_IDNTF_FILE_PATH_NM). 비식별 산출물에 원본 경로가 새지 않도록 kind 로 분기한다.
     * deid 경로 미상이면 <b>fail-secure</b> — 원본 절대경로를 넣지 않고 null 로 둔다(CWE-359).
     *
     * <p>{@code name} — <b>데이터셋 이름</b>이다(R9-a, 2026-08-05). 구 구현은 경로 basename 을 넣어
     * 영상 파일명("original"/"deidentified")이 나갔는데, 사업 어노테이션 표준의 이 필드는 데이터셋
     * 이름이다. <b>경로가 아니라 이벤트명 파생</b>이므로 kind 와 무관하게 같은 값이다.
     */
    private NiaDataset buildDataset(VideoExportContext ctx, ExportKind kind) {
        String path = (kind == ExportKind.ORIGINAL)
                ? ctx.meta().getRawFilePathNm()
                : ctx.deidVideoPath();
        return new NiaDataset(ctx.videoId(), datasetName(ctx.meta().getEvntNm()), path, null);
    }

    /**
     * 데이터셋명 — {@code {이벤트명} 데이터셋 구축}. 이벤트명이 없으면 <b>null</b> 이다.
     *
     * <p>null 을 그대로 두는 것은 뷰(SQL {@code m.EVNT_NM || ' 데이터셋 구축'} 는 NULL 전파로 NULL)와
     * 같은 처리이며, 접미사만 남은 " 데이터셋 구축" 을 지어내지 않는다는 뜻이다.
     *
     * <p>⚠ 뷰는 결과를 {@code ::VARCHAR(200)} 로 명시 캐스팅해 <b>이벤트명 193자부터 절단</b>되지만
     * 여기서는 자르지 않는다 — 그 절단은 DB 컬럼 폭(표준도메인 명V200) 제약이지 <b>규칙의 일부가
     * 아니며</b>, JSON 에는 폭 제약이 없다. 뷰의 무절단 표현({@code DATST_EXPLN}, 내용V4000)과 이 값이
     * 일치한다. "뷰와 달라 보인다"는 이유로 여기에 절단을 넣지 말 것.
     */
    private static String datasetName(String evntNm) {
        return (evntNm == null) ? null : evntNm + DATASET_NAME_SUFFIX;
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
        //   요약(2026-08-04 확정): ORIGINAL=<b>정책 상수</b>(N/N/Y) / DEIDENTIFIED=프레임 수동값 우선
        //   (미입력 시 Y/N/N). 원천이 상수인 이유는 <b>프레임 단위 원천 판정 데이터가 존재하지 않기</b>
        //   때문이고, 그럼에도 null 로 두지 않는 이유는 export JSON 이 <b>재적재되는 왕복 자산</b>이라
        //   null 이면 "판정 안 함"과 "유실"이 구분되지 않기 때문이다(사용자 확정 2026-08-04).
        //   video 블록(VideoMetaMapper)은 같은 판정기의 video 계열을 쓰며 원천에 <b>관제 인입값</b>을
        //   싣는다 — 두 블록의 원천값은 같아 보여도 <b>출처가 다르다</b>(관제 판정 vs 정책 상수).
        //   관제가 N 을 보내면 video=N / image=Y 로 갈리는데, 이는 모순이 아니라 입도가 다른 사실이다.
        //   ⚠ 원천 분기는 프레임 수동값을 <b>읽지 않는다</b>(그 값은 비식별 축 판정이다) — 판정기 내부
        //   규약이며 여기서 재유도하지 않는다. 파생영상은 srcPrivacy=NONE 이라 두 블록 모두 null 이다.
        SourcePrivacyMeta srcPrivacy = ctx.srcPrivacy();
        String anonymity = ExportPrivacyPolicy.resolveImageAnonymity(kind, src.getAnonyInclYn(), srcPrivacy);
        String pseudonymity = ExportPrivacyPolicy.resolveImagePseudonymity(kind, src.getPsdoInclYn(), srcPrivacy);
        String privacyIncluded =
                ExportPrivacyPolicy.resolveImagePrivacyIncluded(kind, src.getPrvcInclYn(), srcPrivacy);

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

    private List<NiaAnnotation> buildAnnotations(List<AnnotationSource> labels, Integer imageId) {
        List<NiaAnnotation> result = new ArrayList<>();
        if (labels == null) {
            return result;
        }
        int skipped = 0;
        for (AnnotationSource lbl : labels) {
            if (lbl == null) {
                continue;
            }
            try {
                result.add(labelMapper.toAnnotation(lbl, imageId));
            } catch (CustomException e) {
                // 방어(CWE-20 fail-secure): malformed 라벨 1건은 문서 전체를 깨지 않고 skip.
                // 좌표 원문/PII 미노출 — 식별자만 로깅.
                skipped++;
                log.warn("[NiaJsonBuilder] annotation skipped lblSn={}", lbl.id());
            }
        }
        if (skipped > 0) {
            log.warn("[NiaJsonBuilder] malformed annotations skipped count={}", skipped);
        }
        return result;
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
            JsonNode eventAnnotation,
            /** 원천 축 개인정보 입력(관제 인입값 + 원천 영상 존재 여부). 파생·부재면 {@link SourcePrivacyMeta#NONE}. */
            SourcePrivacyMeta srcPrivacy,
            /** 관제 인입 이벤트 식별자({@code LS_DATA_INGEST.EVNT_ID}) — {@code video.event_id} 조달처. 미송신이면 null. */
            String ingestEvntId,
            /**
             * {@code video.vd_description} — {@link VlmDescriptionPolicy} 가 판정한 VLM 서술.
             * 원천 부재면 null(빈 문자열 아님). [req: R10]
             */
            String vdDescription
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
