package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.dataset.export.json.VlmDescriptionPolicy;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 한 영상(rawSn)의 <b>NIA 어노테이션 문서 컨텍스트 조달기</b> — 산출 경로와 포털 산출이 <b>같은 빈</b>을
 * 주입해 쓴다.
 *
 * <h3>왜 공유 부품인가</h3>
 * 포털 사용자 작업 데이터 산출물이 검수 승인 학습데이터 산출물과 <b>같은 구조</b>여야 한다고 확정됐다.
 * 구조만 같고 메타 블록 조달을 각자 구현하면 규칙이 두 곳이 되어 한쪽만 갱신될 때 조용히 어긋난다.
 * 그래서 조달은 여기 한 곳이고, 두 경로 모두 이 결과를 받아 프레임 문서를 만든다.
 *
 * <h3>복제하면 반드시 깨지는 지점 둘</h3>
 * <ol>
 *   <li><b>조달 순서</b> — 원천 축 개인정보는 반드시 <b>메타(LS_DATA_META) 로드 뒤</b>에 판정한다.
 *       외부 산출물 이관 영상은 관제 인입 행이 <b>구조적으로 존재하지 않고</b> 그 값이 메타에 원문으로
 *       보관돼 있기 때문이다. 순서를 되돌리면 이관 영상의 원천 3필드가 전부 null 로 산출된다.</li>
 *   <li><b>파생영상 판정</b> — 영상 행이 없거나 파생(증강·해상도)이면 {@link SourcePrivacyMeta#NONE}
 *       이며, 그때 원천 3필드가 두 블록 모두 null 인 것이 <b>정상</b>이다(결손이 아니다).
 *       상수를 대신 싣지 말 것.</li>
 * </ol>
 *
 * <p>모든 조회는 rawSn 단위 1회(N+1 없음)이며 파생/파라미터 바인딩만 쓴다(CWE-89 표면 없음).
 * 로그는 rawSn·건수만 출력한다(CWE-359/117).
 *
 * <p>트랜잭션 경계는 <b>호출자</b>가 소유한다 — 산출 경로는 {@code REQUIRES_NEW} readOnly 안에서,
 * 포털 경로는 자기 조회 트랜잭션 안에서 부른다.
 *
 * @design API-203
 */
@Component
public class NiaExportContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(NiaExportContextAssembler.class);

    private final LsDatasetVideoMetaRepository videoMetaRepository;
    private final VideoRepository videoRepository;
    /** 원천 축 개인정보 3필드 + {@code video.event_id} 조달 — 관제 인입 평면값. 연결 규칙은 IngestSourceLink 소유. */
    private final IngestSourceRepository ingestSourceRepository;
    /** {@code video.vd_description} 조달용 메타(LS_DATA_META). 판정은 {@link VlmDescriptionPolicy}. */
    private final LsDataMetaRepository metaRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final LsLabelRepository labelMasterRepository;
    private final NiaJsonBuilder niaJsonBuilder;
    private final ObjectMapper objectMapper;

    public NiaExportContextAssembler(LsDatasetVideoMetaRepository videoMetaRepository,
                                     VideoRepository videoRepository,
                                     IngestSourceRepository ingestSourceRepository,
                                     LsDataMetaRepository metaRepository,
                                     LsDeidentProcLogRepository deidentProcLogRepository,
                                     LsLabelRepository labelMasterRepository,
                                     NiaJsonBuilder niaJsonBuilder,
                                     ObjectMapper objectMapper) {
        this.videoMetaRepository = videoMetaRepository;
        this.videoRepository = videoRepository;
        this.ingestSourceRepository = ingestSourceRepository;
        this.metaRepository = metaRepository;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.labelMasterRepository = labelMasterRepository;
        this.niaJsonBuilder = niaJsonBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 활성 영상 메타·원시 영상 행까지 직접 조회해 컨텍스트를 조달한다(메타/raw 를 아직 안 읽은 호출자용).
     *
     * <p>활성 메타가 없으면 {@link Optional#empty()} — 검수 승인 동결본이 없다는 뜻이라 같은 구조의
     * 문서를 만들 근거 자체가 없다(fail-secure skip).
     *
     * @param labelMasterIds 이 산출에 쓰인 라벨 마스터 식별자 집합(categories 원천). null·빈 값 허용
     */
    public Optional<NiaExportContext> assemble(long rawSn, Collection<Long> labelMasterIds) {
        List<LsDatasetVideoMeta> metas = videoMetaRepository.findByRawSnAndActiveYn(
                rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        if (metas.isEmpty()) {
            log.warn("[NiaExportContext] no active video meta rawSn={}", rawSn);
            return Optional.empty();
        }
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        return Optional.of(assemble(rawSn, metas.get(0), raw, labelMasterIds));
    }

    /**
     * 이미 로드한 메타/raw 로 컨텍스트를 조달한다 — 산출 경로가 쓰는 정본.
     *
     * @param meta           활성 영상 메타 스냅샷 (필수)
     * @param raw            라이브 원시 영상 행 (null 허용)
     * @param labelMasterIds 이 산출에 쓰인 라벨 마스터 식별자 집합(categories 원천). null·빈 값 허용
     */
    public NiaExportContext assemble(long rawSn, LsDatasetVideoMeta meta, LsDataRaw raw,
                                     Collection<Long> labelMasterIds) {
        // 인입 평면값은 rawSn 단위 1회만 조회해 두 용도로 쓴다(원천 축 개인정보 + video.event_id).
        // ★ 파생영상에서도 조회한다 — event_id 는 부모 인입값이 그대로 유효하다(IngestSourceLink 의
        //   "두 갈래" 규칙: 개인정보 3필드만 원본 한정이고 나머지 인입값은 파생에도 유효).
        IngestSourceRow ingest = ingestSourceRepository.findSourceMeta(rawSn);
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
        //   META_VL ≤2000자로 상한이 있다.
        List<LsDataMeta> dataMetas = metaRepository.findByRawSn(rawSn);
        // ★ 원천 축 조달은 <b>메타 로드 뒤</b>다 — 외부 산출물 이관 영상은 관제 인입 행이 없어 그 값이
        //   메타에 원문 보관돼 있기 때문이다(ADR-048). 순서를 되돌리면 이관 영상의 원천 3필드가
        //   전부 null 로 산출된다.
        SourcePrivacyMeta srcPrivacy = resolveSourcePrivacy(raw, ingest, dataMetas);
        String vdDescription = VlmDescriptionPolicy.resolve(dataMetas);

        List<LsLabel> usedLabels = loadUsedLabels(labelMasterIds);
        // 동결 event_annotation(C2) — 활성 메타 스냅샷의 EVNT_ANNO_CN(승인 시점 동결본)만 사용한다.
        // 라이브 이벤트 어노테이션을 조회하지 않으므로 승인 후 편집분에 오염되지 않는다(멱등).
        JsonNode eventAnnotation = parseEventAnnotation(meta.getEvntAnnoCn(), rawSn);
        // 비식별 영상 경로(DE_IDNTF_FILE_PATH_NM) — 산출 JSON 의 dataset/video 경로 필드가 원본이 아닌
        // 비식별 경로를 참조하도록 rawSn 단위 1회 조회한다(최신 SUCCEEDED procLog, N+1 없음).
        // 미상이면 null → 빌더가 fail-secure(원본 절대경로 미노출, CWE-359).
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .orElse(null);

        // ★ 항상 8인자 경로다 — 축소 오버로드를 쓰면 메타 필드가 조용히 null 이 된다.
        VideoExportContext ctx = niaJsonBuilder.prepareContext(
                meta, raw, usedLabels, eventAnnotation, deidVideoPath, srcPrivacy, ingestEvntId,
                vdDescription);
        return new NiaExportContext(meta, raw, ctx, srcPrivacy, vdDescription, deidVideoPath);
    }

    /** 라벨 식별자 모음 → 중복 없는 순서 보존 집합(마스터 조회 입력). null 원소는 버린다. */
    public static Set<Long> distinctLabelIds(Collection<Long> labelIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (labelIds == null) {
            return ids;
        }
        for (Long id : labelIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 원천 축 개인정보 3필드 조달 — 원본 영상만 관제 인입값을 싣고, 파생영상·영상행 부재는
     * {@link SourcePrivacyMeta#NONE}(두 블록 모두 {@code null}) 이다.
     *
     * <p>인입 행이 아직/영영 없으면 리포지토리가 전 필드 null 인 행을 돌려주는데, 그것은
     * "원천 영상은 있지만 관제가 판정을 안 보냈다"는 뜻이라 {@code ofIngest(null,null,null)} 이
     * 정확하다({@code NONE} 과 달리 {@code image} 블록 상수는 그대로 실린다).
     */
    private static SourcePrivacyMeta resolveSourcePrivacy(LsDataRaw raw, IngestSourceRow ingest,
                                                          List<LsDataMeta> dataMetas) {
        if (raw == null || raw.getOrgnlRawSn() != null) {
            return SourcePrivacyMeta.NONE;
        }
        if (LsDataRaw.SRC_TYPE_IMPORTED.equals(raw.getSrcType())) {
            // 외부 산출물 이관(ADR-048) — 관제 수신 원장을 거치지 않으므로 인입 행이 <b>구조적으로
            //   존재하지 않는다</b>. 그 결손을 "원천 없음"으로 읽으면 image 블록의 원천 상수까지 함께
            //   빠져 파생영상과 구분되지 않으므로, 산출물이 준 원문을 보관한 메타에서 조달한다.
            //   판정 규칙의 소유자는 ExportPrivacyPolicy 한 곳이다(여기서 재유도하지 않는다).
            return ExportPrivacyPolicy.importedSource(
                    metaValue(dataMetas, ExportPrivacyPolicy.IMPORT_SOURCE_ANONYMITY_KEY),
                    metaValue(dataMetas, ExportPrivacyPolicy.IMPORT_SOURCE_PSEUDONYMITY_KEY),
                    metaValue(dataMetas, ExportPrivacyPolicy.IMPORT_SOURCE_PRIVACY_INCLUDED_KEY));
        }
        if (ingest == null) {
            return SourcePrivacyMeta.NONE;
        }
        return SourcePrivacyMeta.ofIngest(
                ingest.getSrcAnonyInclYn(), ingest.getSrcPsdoInclYn(), ingest.getSrcPrvcInclYn());
    }

    /** 메타 열쇠 하나의 값 — {@code (RAW_SN, META_KEY)} 가 유일이라 최대 1건이다. blank 는 미보관. */
    private static String metaValue(List<LsDataMeta> dataMetas, String metaKey) {
        if (dataMetas == null) {
            return null;
        }
        for (LsDataMeta meta : dataMetas) {
            if (meta != null && metaKey.equals(meta.getMetaKey())) {
                String value = meta.getMetaVl();
                return (value == null || value.isBlank()) ? null : value.trim();
            }
        }
        return null;
    }

    /**
     * 동결 event_annotation payload(jsonb 원문 문자열)를 {@link JsonNode} 로 파싱한다 — 각 프레임 문서에
     * 최상위 {@code event} 로 pass-through(키 순서·형태 보존)하기 위함이다.
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
            log.warn("[NiaExportContext] frozen event_annotation parse failed — omitted rawSn={}", rawSn);
            return null;
        }
    }

    /** 참조된 라벨 마스터(categories 원천)를 distinct labelId 로 일괄 로드. */
    private List<LsLabel> loadUsedLabels(Collection<Long> labelMasterIds) {
        Set<Long> ids = distinctLabelIds(labelMasterIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return labelMasterRepository.findAllById(ids);
    }
}
