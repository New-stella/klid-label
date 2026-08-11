package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 영상(rawSn) 산출 입력 상태의 콘텐츠 해시(SHA-256 hex)를 계산하는 순수 컴포넌트.
 *
 * <p>Phase 4 — 검수 승인 시 학습데이터 파일 산출의 <b>무수정 재승인 멱등 판정 키</b>로 쓰인다.
 * 직전 SUCCEEDED export 가 기록한 해시와 동일하면 재산출을 skip 한다(중복 버전 생성 방지).
 *
 * <h3>불변식 — "산출 JSON 이 달라지면 해시도 달라진다"</h3>
 * 해시 입력은 라벨뿐 아니라 <b>산출 JSON(NIA COCO 확장)에 직렬화되는 모든 입력</b>을 포함한다.
 * 라벨만 해시하면 프레임 설명(frmExpln)·개인정보 메타(prvcTypeCd/prvcYn)·해상도(width/height) 등의
 * 정정 재승인이 멱등 skip 되어 산출물이 stale 로 고착되므로, 아래 3개 섹션 전체를 반영한다.
 * <ol>
 *   <li><b>라벨</b>: 식별+내용 필드(lblSn·srcSn·labelId·lblTypeCd·labelNm·pointCn·trackId)</li>
 *   <li><b>프레임</b>: {@code buildImage} 가 읽는 필드(srcSn·frameNo·frmExpln·shtDt·srcFilePathNm·deidFilePath)</li>
 *   <li><b>영상 메타</b>: {@code VideoMetaMapper}/{@code buildImage} 가 읽는 메타 필드(개인정보·해상도·좌표·이벤트 등),
 *       {@code VideoMetaMapper} 와 동일하게 {@code meta→raw} 폴백을 적용한 필드는 폴백 후 값을 반영</li>
 * </ol>
 * 개인정보 3필드는 <b>{@code ORIGINAL}=판정 안 함(null) / {@code DEIDENTIFIED}=수동값 우선 + 기본상수</b>로
 * 산출된다({@code ExportPrivacyPolicy}, 2026-08-03 확정). kind 는 해시 입력에 넣지 않는다 — 두 벌
 * (orgnl/deid)이 <b>같은 트리거로 함께 재산출</b>되므로 kind 로 해시를 가를 필요가 없기 때문이다. 대신
 * <b>값이 달라질 수 있는 원천</b>인 <b>프레임 수동값</b>(LS_DATA_SRC, image 블록 원천)과 <b>영상 단위
 * 수동값</b>(LS_DATA_RAW, video 블록 원천 — {@link #appendVideoPrivacyManual})을 모두 반영한다
 * (그래야 이 3필드만 정정한 재승인/재산출이 멱등 skip 으로 stale 고착되지 않는다).
 * 영상 메타의 {@code prvcTypeCd}/{@code prvcYn} 은 이제 export 개인정보 3필드의 입력이 아니지만,
 * 기존 해시 안정성을 위해 입력에서 빼지 않는다(빼면 전 영상 해시가 바뀌어 전량 재산출된다).
 *
 * <p><b>촬영환경 3필드(weather/time_of_day/season) 폴백 방향 주의(E, 문서화 전용)</b>:
 * {@code appendVideoMeta} 는 이 3필드를 <b>meta 단독</b>({@code meta.getWthrNm/getDayNgtCd/getSesnCd})으로
 * 읽는데, {@code VideoMetaMapper} 는 <b>raw(수동값) → meta</b> 순으로 읽어 폴백 방향이 반대다. 이는
 * <b>의도된 허용</b>이다 — 승인 후 촬영환경 수정은 항상 <b>재동결(materialize)</b>로 meta(동결 스냅샷)를
 * 최신 수동값으로 갱신하므로, 산출 시점의 meta 3필드는 raw 수동값과 이미 일치한다. 따라서 meta 단독 읽기가
 * mapper 의 raw-우선 결과와 어긋나지 않아 해시(멱등 판정)와 산출 JSON 이 정합한다(전파 구현 불필요).
 *
 * <h3>결정성(determinism)</h3>
 * <ul>
 *   <li><b>순서 독립</b>: 라벨은 lblSn, 프레임은 srcSn 오름차순으로 정렬 후 계산한다(조회 정렬 변화에 안정).</li>
 *   <li>필드 구분자로 개행이 아닌 제어문자(RS/US/GS)를 써서 값 내부 문자와의 충돌·로그 인젝션 표면을 없앤다.</li>
 *   <li>{@code LocalDate.now()} 등 시간 종속 값은 입력에 포함하지 않아 날짜가 바뀌어도 해시가 유지된다.</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>SHA-256 사용(MD5/SHA-1 금지 — 프로젝트 암호 정책).</li>
 *   <li>라벨/경로/개인정보 값은 해시 입력으로만 쓰이고 로그로 출력하지 않는다(CWE-359/117).</li>
 * </ul>
 */
@Component
public class LabelContentHasher {

    /** 필드 구분자 (Unit Separator, U+001F) — 값 내부 출현 가능성이 없는 제어문자. */
    private static final char FIELD_SEP = '\u001F';
    /** 레코드 구분자 (Record Separator, U+001E). */
    private static final char RECORD_SEP = '\u001E';
    /** 섹션 구분자 (Group Separator, U+001D) — 라벨/프레임/영상메타 섹션 경계. */
    private static final char SECTION_SEP = '\u001D';
    /** 영상 단위 개인정보 수동값 블록 마커 — 값이 있을 때만 붙는 조건부 블록의 모호성 제거용. */
    private static final String VIDEO_PRIVACY_MARKER = "VPRV";
    /** 원천 축(관제 인입) 개인정보 블록 마커 — 위와 같은 조건부 블록 규약. */
    private static final String SOURCE_PRIVACY_MARKER = "SPRV";
    /** VLM 서술({@code video.vd_description}, @req R10) 블록 마커 — 위와 같은 조건부 블록 규약. */
    private static final String VD_DESCRIPTION_MARKER = "VDSC";
    /** 프레임 폐기(@req R4) 블록 마커 — 위와 같은 조건부 블록 규약. */
    private static final String FRAME_DISCARD_MARKER = "FDSC";

    /**
     * 산출 입력 상태의 콘텐츠 해시(SHA-256 hex)를 계산한다. 모든 입력이 비어도 고정 해시를 반환.
     *
     * @param labels 영상 전체 라벨(순서 무관)
     * @param frames 영상 전체 프레임(순서 무관) — 산출 JSON 의 image 필드 원천
     * @param meta   활성 영상 메타 스냅샷(산출 JSON 의 video 필드 1차 원천, null 허용)
     * @param raw    원시 영상(메타 null 필드 폴백, null 허용)
     * @return 64자 소문자 hex
     */
    public String hash(List<LsDataLbl> labels, List<LsDataSrc> frames,
                       LsDatasetVideoMeta meta, LsDataRaw raw) {
        return hash(labels, frames, meta, raw, SourcePrivacyMeta.NONE);
    }

    /**
     * 산출 입력 상태의 콘텐츠 해시(SHA-256 hex) — <b>원천 축 개인정보 포함</b>(산출 경로가 쓰는 정본).
     *
     * @param srcPrivacy 원천 축 입력(관제 인입값). 파생영상·부재면 {@link SourcePrivacyMeta#NONE}
     */
    public String hash(List<LsDataLbl> labels, List<LsDataSrc> frames,
                       LsDatasetVideoMeta meta, LsDataRaw raw, SourcePrivacyMeta srcPrivacy) {
        return hash(labels, frames, meta, raw, srcPrivacy, null);
    }

    /**
     * 산출 입력 상태의 콘텐츠 해시(SHA-256 hex) — <b>VLM 서술 포함</b>(산출 경로가 쓰는 정본).
     *
     * @param vdDescription {@code video.vd_description} 값({@code VlmDescriptionPolicy} 판정 결과).
     *                      원천 부재면 {@code null}. [req: R10]
     */
    public String hash(List<LsDataLbl> labels, List<LsDataSrc> frames,
                       LsDatasetVideoMeta meta, LsDataRaw raw, SourcePrivacyMeta srcPrivacy,
                       String vdDescription) {
        return hash(labels, frames, meta, raw, srcPrivacy, vdDescription, null);
    }

    /**
     * 산출 입력 상태의 콘텐츠 해시(SHA-256 hex) — <b>프레임 폐기 축 포함</b>(산출 경로가 쓰는 정본).
     *
     * @param discardedSrcSns 이 영상에서 폐기된 프레임 SRC_SN 목록. 없거나 비면 폐기 블록을 붙이지 않는다.
     *                        [req: R4]
     */
    public String hash(List<LsDataLbl> labels, List<LsDataSrc> frames,
                       LsDatasetVideoMeta meta, LsDataRaw raw, SourcePrivacyMeta srcPrivacy,
                       String vdDescription, List<Long> discardedSrcSns) {
        StringBuilder sb = new StringBuilder(256);
        appendLabels(sb, labels);
        sb.append(SECTION_SEP);
        appendFrames(sb, frames);
        sb.append(SECTION_SEP);
        appendVideoMeta(sb, meta, raw);
        appendSourcePrivacy(sb, srcPrivacy);
        appendVdDescription(sb, vdDescription);
        appendDiscardedFrames(sb, discardedSrcSns);
        return sha256Hex(sb.toString());
    }

    /**
     * 프레임 폐기 축(@req R4) — 폐기는 산출물의 <b>구성</b>을 바꾸므로 해시에 편입한다.
     *
     * <p>편입하지 않으면 <b>폐기했는데 산출이 멱등 skip 되어</b> 이미지 2벌·JSON 이 옛 구성으로 고착된다
     * (저장은 바뀌었는데 파일은 그대로인 이 클래스의 대표 결함 형태). 산출 입력인 {@code frames} 자체가
     * 이미 폐기분을 걸러 오므로 레코드 소멸만으로도 해시는 달라지지만, 그건 <b>간접</b> 신호라 폐기 축이
     * 코드에 드러나지 않는다 — 조회가 바뀌어도 이 축이 조용히 빠지지 않도록 명시적으로 붙인다.
     *
     * <p><b>하위호환 — 폐기 프레임이 하나도 없으면 아무것도 append 하지 않는다</b>
     * ({@link #appendVideoPrivacyManual}·{@link #appendSourcePrivacy}·{@link #appendVdDescription} 와
     * 동일 규약): 무조건 붙이면 폐기가 존재하지 않던 <b>기존 승인분 전 영상</b>의 해시가 달라져 무의미한
     * 전량 재산출이 일어난다.
     *
     * <p><b>결정성</b>: 조회 정렬에 의존하지 않도록 오름차순 정렬 후 계산한다(이 클래스의 순서 독립 규약).
     * 식별자만 싣는다(PII 없음 — CWE-359).
     */
    private static void appendDiscardedFrames(StringBuilder sb, List<Long> discardedSrcSns) {
        if (discardedSrcSns == null || discardedSrcSns.isEmpty()) {
            return;
        }
        List<Long> sorted = new ArrayList<>(discardedSrcSns);
        sorted.sort(Comparator.nullsLast(Comparator.naturalOrder()));
        sb.append(SECTION_SEP);
        append(sb, FRAME_DISCARD_MARKER);
        for (Long srcSn : sorted) {
            append(sb, srcSn);
        }
    }

    /**
     * {@code video.vd_description}(@req R10) — 산출 JSON 의 {@code video} 블록 값이므로 해시에 편입한다.
     *
     * <h3>⚠ 현재 배포 형상에서 이 블록은 아무것도 게이트하지 않는다 (정직한 한계)</h3>
     * <p>해시가 실제로 게이트하는 유일한 지점은 {@code DatasetExportService.export} 의
     * {@code if (!forceRegenerate && prep.isUnchangedFromLastExport())} 인데,
     * <b>{@code forceRegenerate=false} 로 진입하는 프로덕션 경로가 지금은 없다</b> —
     * 유일한 후보 {@code DatasetExportBridge.onReExport} 가 소비하는 {@code DatasetReExportEvent} 는
     * <b>발행처가 0건인 휴면 리스너</b>이고(그 클래스 javadoc 이 스스로 밝힌다), 승인·수정·회수 등 나머지
     * 재산출 경로는 전부 {@code force=true} 다. 즉 이 블록을 지워도 <b>오늘 당장은 증상이 없다.</b>
     *
     * <p>그럼에도 <b>유지</b>하는 이유는 정합이다 — {@code force=false} 경로가 되살아나는 순간(재동결형
     * 재산출 도입 등) 이 블록이 없으면 서술이 바뀌어도 멱등 skip 되어 <b>저장은 바뀌었는데 export 파일은
     * 옛 서술로 고착</b>된다. 값이 바뀌면 해시도 바뀌어야 한다는 것이 이 클래스의 계약이다.
     *
     * <p>따라서 <b>무변경 재생성 억제는 이 해시가 아니라 호출부가 책임진다</b> —
     * {@code MetaService.update} 의 "값이 실제로 바뀐 항목이 있을 때만 {@code exportRegenerated=true}"
     * 가드와 {@code VlmResultService.applyResults} 의 {@code changed} 가드가 그것이다(CWE-770).
     *
     * <p><b>하위호환 — 값이 없으면 아무것도 append 하지 않는다</b>({@link #appendVideoPrivacyManual}·
     * {@link #appendSourcePrivacy} 와 동일 규약): 무조건 붙이면 서술 원천이 없는 기존 승인분 전량의
     * 해시가 달라져 무의미한 재산출이 일어난다. 값이 있는 영상은 해시가 바뀌는데, 그 영상들은
     * <b>산출 JSON 내용이 실제로 달라지므로</b>(구 산출물의 {@code vd_description} 은 항상 null) 다음
     * 재동결에서 갱신되는 것이 옳다.
     *
     * <p>서술 원문은 해시 입력으로만 쓰고 로그로 출력하지 않는다(CWE-359/117).
     */
    private static void appendVdDescription(StringBuilder sb, String vdDescription) {
        String value = blankToNull(vdDescription);
        if (value == null) {
            return;
        }
        sb.append(SECTION_SEP);
        append(sb, VD_DESCRIPTION_MARKER);
        append(sb, value);
    }

    /**
     * 원천 축 개인정보 3필드(관제 인입, V166/V170) — 산출 JSON 의 {@code video} 블록 <b>ORIGINAL</b>
     * 값 원천이므로 해시에 편입한다(2026-08-04 원천 축 전환). 빠지면 관제가 판정을 정정해 재송신해도
     * 재승인이 멱등 skip 되어 <b>저장은 바뀌었는데 export 파일은 옛 값으로 고착</b>된다.
     *
     * <p><b>하위호환 — 값이 하나도 없으면 아무것도 append 하지 않는다</b>({@link #appendVideoPrivacyManual}
     * 와 동일 규약): 무조건 붙이면 이 변경 이전에 승인된(내용 무변경) 전 영상의 해시가 달라져 전량
     * 재산출된다. {@code image} 블록 원천값은 <b>정책 상수</b>라 영상별로 달라지지 않으므로 해시 입력이
     * 아니다(상수를 바꾸면 그때는 전량 재산출이 <b>의도된</b> 동작이다).
     */
    private static void appendSourcePrivacy(StringBuilder sb, SourcePrivacyMeta source) {
        if (source == null || !source.sourceExists()) {
            return;
        }
        String anony = blankToNull(source.anonyInclYn());
        String psdo = blankToNull(source.psdoInclYn());
        String prvc = blankToNull(source.prvcInclYn());
        if (anony == null && psdo == null && prvc == null) {
            return;
        }
        sb.append(SECTION_SEP);
        append(sb, SOURCE_PRIVACY_MARKER);
        append(sb, anony);
        append(sb, psdo);
        append(sb, prvc);
    }

    private static void appendLabels(StringBuilder sb, List<LsDataLbl> labels) {
        if (labels == null || labels.isEmpty()) {
            return;
        }
        List<LsDataLbl> sorted = new ArrayList<>(labels);
        sorted.sort(Comparator.comparing(LsDataLbl::getLblSn,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (LsDataLbl l : sorted) {
            if (l == null) {
                continue;
            }
            append(sb, l.getLblSn());
            append(sb, l.getSrcSn());
            append(sb, l.getLabelId());
            append(sb, l.getLblTypeCd());
            append(sb, l.getLabelNm());
            append(sb, l.getPointCn());
            append(sb, l.getTrackId());
            sb.append(RECORD_SEP);
        }
    }

    private static void appendFrames(StringBuilder sb, List<LsDataSrc> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        List<LsDataSrc> sorted = new ArrayList<>(frames);
        sorted.sort(Comparator.comparing(LsDataSrc::getSrcSn,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (LsDataSrc f : sorted) {
            if (f == null) {
                continue;
            }
            append(sb, f.getSrcSn());
            append(sb, f.getFrameNo());
            // A-7/S11 — VDO_FRM_NO 는 산출 JSON 의 frame_num 원천이다. 해시에서 빠지면 이 값만 나중에
            // 백필됐을 때 재동결(멱등 skip) 경로가 재산출하지 않아 frame_num 이 옛 값으로 고착된다.
            append(sb, f.getVideoFrameNo());
            append(sb, f.getFrmExpln());
            append(sb, f.getShtDt());
            append(sb, f.getSrcFilePathNm());
            append(sb, f.getDeidFilePath());
            // 프레임 개인정보 3필드(익명/가명/개인정보 포함여부) — DEIDENTIFIED 산출 JSON 의 image 블록에
            // <b>수동값 우선</b>으로 실리므로(ExportPrivacyPolicy, 2026-08-03 정책 반전), 이 3필드만 정정한
            // 재승인/재산출이 멱등 skip 으로 stale 고착되지 않도록 해시 입력에 편입한다
            // (ORIGINAL 은 null 이지만 두 벌이 같은 트리거로 함께 재산출되므로 문제없다).
            // blank 정규화는 영상 축(appendVideoPrivacyManual)과 <동일 기준>이다 — 아래 메서드 주석의
            //   "모든 경로가 같은 기준" 단언을 실제로 성립시킨다(2026-08-03 DEV_FIX 2차).
            //   ★기존 승인분 해시 불변: 코드가 만들 수 있는 값은 null / 'Y' / 'N' 뿐이고
            //   (LsDataSrc.normalizeYn 이 생성·수정 전 경로에서 blank→null, 리셋 벌크 UPDATE 는 null),
            //   그 셋에 대해 blankToNull 은 항등이다. 달라지는 것은 <직접 SQL 로만 생길 수 있는>
            //   공백 문자열 행뿐인데, 그 행은 산출 JSON 이 이미 기본상수라 해시만 어긋나 있던 경우다.
            append(sb, blankToNull(f.getAnonyInclYn()));
            append(sb, blankToNull(f.getPsdoInclYn()));
            append(sb, blankToNull(f.getPrvcInclYn()));
            sb.append(RECORD_SEP);
        }
    }

    /**
     * 영상 메타 섹션 — {@code VideoMetaMapper}/{@code buildImage} 가 산출 JSON 으로 직렬화하는 메타 필드.
     * {@code VideoMetaMapper} 가 {@code meta→raw} 폴백을 쓰는 필드는 동일하게 폴백 후 값을 반영한다.
     */
    private static void appendVideoMeta(StringBuilder sb, LsDatasetVideoMeta meta, LsDataRaw raw) {
        if (meta == null) {
            return;
        }
        // meta→raw 폴백 필드 (VideoMetaMapper 와 동일)
        append(sb, firstNonNull(meta.getRawFilePathNm(), raw == null ? null : raw.getRawFilePathNm()));
        append(sb, firstNonNull(meta.getShtDt(), raw == null ? null : raw.getShtDt()));
        append(sb, firstNonNull(meta.getVdoLenSec(), raw == null ? null : raw.getDurationSec()));
        append(sb, firstNonNull(meta.getPrvcTypeCd(), raw == null ? null : raw.getPrvcTypeCd()));
        append(sb, firstNonNull(meta.getPrvcYn(), raw == null ? null : raw.getPrvcYn()));
        // meta 전용 필드
        append(sb, meta.getVdoWdth());
        append(sb, meta.getVdoHgt());
        append(sb, meta.getFileFmt());
        append(sb, meta.getFileSz());
        append(sb, meta.getSidoNm());
        append(sb, meta.getSggNm());
        append(sb, asStr(meta.getFps()));
        append(sb, asStr(meta.getAsprtRt()));
        append(sb, meta.getResl());
        append(sb, meta.getBitRt());
        append(sb, meta.getWthrNm());
        append(sb, asStr(meta.getWgs84Lat()));
        append(sb, asStr(meta.getWgs84Lot()));
        append(sb, meta.getCctvNm());
        append(sb, meta.getEvntTypeCd());
        append(sb, meta.getEvntNm());
        append(sb, meta.getDayNgtCd());
        append(sb, meta.getSesnCd());
        // 동결 event_annotation — 산출 JSON 최상위 event_annotation 으로 직렬화되므로 해시에 반영해,
        // event_annotation 만 바뀐 재승인(동결본 변경)이 멱등 skip 으로 stale 고착되지 않게 한다.
        append(sb, meta.getEvntAnnoCn());
        appendVideoPrivacyManual(sb, raw);
        sb.append(RECORD_SEP);
    }

    /**
     * 영상 단위 개인정보 수동값(V163, {@code LS_DATA_RAW.*_INCL_YN}) — 산출 JSON 의 {@code video} 블록
     * 개인정보 3필드 원천이므로 해시에 편입한다. 빠지면 이 3필드만 정정한 재승인/재산출이 멱등 skip 되어
     * 저장은 됐는데 export 파일은 옛 값으로 고착된다.
     *
     * <p><b>하위호환 — 값이 하나도 없으면 아무것도 append 하지 않는다</b>: 무조건 3필드를 붙이면 V163
     * 이전에 승인된(내용 무변경) 모든 영상의 해시가 달라져 전량 재산출된다. 값이 있을 때만 블록 마커
     * ({@link #VIDEO_PRIVACY_MARKER})와 함께 붙여, 미입력 영상은 기존 해시를 그대로 유지시킨다
     * (마커가 있어 "값 있는 블록"과 "없는 블록"이 모호해지지 않는다 — 선례: 스냅샷 WTHR_NM 키 조건부 포함).
     */
    private static void appendVideoPrivacyManual(StringBuilder sb, LsDataRaw raw) {
        if (raw == null) {
            return;
        }
        // blank 정규화 — 나머지 3경로(LsDataRaw.normalizeYn · VideoPrivacyMetaService.validate ·
        //   ExportPrivacyPolicy.resolve)가 모두 blank 를 "미입력"으로 다루므로 해시도 같은 기준이어야 한다.
        //   (CHAR(1) 공백 패딩이 남은 레거시 행에서 == null 만 보면 "값 있음"으로 오판해, 산출 JSON 은
        //    기본상수로 동일한데 해시만 달라져 무의미한 전량 재산출이 일어난다.)
        String anony = blankToNull(raw.getAnonyInclYn());
        String psdo = blankToNull(raw.getPsdoInclYn());
        String prvc = blankToNull(raw.getPrvcInclYn());
        if (anony == null && psdo == null && prvc == null) {
            return;
        }
        append(sb, VIDEO_PRIVACY_MARKER);
        append(sb, anony);
        append(sb, psdo);
        append(sb, prvc);
    }

    /**
     * blank(공백만) → null. 미입력 판정 기준을 저장·판정 경로와 동일하게 맞춘다.
     * <b>영상 축·프레임 축 모두</b> 이 함수를 통과시킨다(축마다 기준이 다르면 같은 산출 JSON 에 다른
     * 해시가 나온다 — 2026-08-03 DEV_FIX 2차로 프레임 축을 합류시켰다).
     */
    private static String blankToNull(String yn) {
        return (yn == null || yn.isBlank()) ? null : yn.trim();
    }

    private static void append(StringBuilder sb, Object value) {
        sb.append(value == null ? "" : value.toString()).append(FIELD_SEP);
    }

    /** BigDecimal 은 스케일 표기를 결정적으로 고정(직렬화와 동일한 toPlainString). */
    private static String asStr(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
