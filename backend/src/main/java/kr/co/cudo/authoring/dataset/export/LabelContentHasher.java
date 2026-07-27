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
 * anonymity 는 {@link ExportKind} 별 결정적(orgnl=N/deid=Y)이라 해시에 kind 를 넣을 필요가 없다(두 벌이
 * 같은 트리거로 함께 재산출됨). 단 pseudonymity/privacy_included 의 원천(prvcTypeCd/prvcYn)은 반드시 반영한다.
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
        StringBuilder sb = new StringBuilder(256);
        appendLabels(sb, labels);
        sb.append(SECTION_SEP);
        appendFrames(sb, frames);
        sb.append(SECTION_SEP);
        appendVideoMeta(sb, meta, raw);
        return sha256Hex(sb.toString());
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
            // 개인정보 3필드(익명/가명/개인정보 포함여부) — pseudonymity/privacy_included 는 buildImage 가
            // 산출 JSON 에 수동 우선 반영하므로, 이 3필드만 정정한 재승인이 멱등 skip 으로 stale 고착되지
            // 않도록 해시 입력에 편입한다(#2). anonymity 는 export 미반영이나 저장·표시 정합을 위해 함께 반영.
            append(sb, f.getAnonyInclYn());
            append(sb, f.getPsdoInclYn());
            append(sb, f.getPrvcInclYn());
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
        sb.append(RECORD_SEP);
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
