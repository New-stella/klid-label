package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 학습데이터 export JSON 의 {@code video.vd_description} <b>단일 조달 판정기</b>. [req: R10]
 *
 * <p>어노테이션 포맷 정의: <i>"이벤트에 대한 VLM 이 출력하는 간단한 상황묘사 내용"</i>.
 * 구 구현은 {@code VideoMetaMapper} 에서 <b>항상 {@code null}</b> 을 하드코딩했다(데이터 출처 부재).
 * 외부 VLM {@code verify} 콜백이 서술 전문을 {@code LS_DATA_META} 에 적재하면서 원천이 생겼다.
 *
 * <h3>조달 우선순위 (사용자 확정 — 이 클래스가 유일한 소유자다. 복제 금지)</h3>
 * <ol>
 *   <li>{@link VlmResultService#META_KEY_DESCRIPTION}({@code vlm.description}) 이 있으면 <b>그 값</b></li>
 *   <li>없고 {@link #MANUAL_TIMESERIES_META_KEY}({@code manual-timeseries}, <b>사람이 직접 쓴 전문</b>)이
 *       있으면 <b>그 값</b></li>
 *   <li>없고 <b>보존된 레거시 구간 행</b>(구 describe 산출물, metaKey {@code {start_sec}-{end_sec}})만
 *       있으면 <b>{@code start_sec} 오름차순</b>으로 이어붙인 값</li>
 *   <li>모두 없으면 <b>{@code null}</b></li>
 * </ol>
 *
 * <h3>왜 수동 전문이 레거시보다 앞인가</h3>
 * {@code manual-timeseries} 는 <b>사람이 직접 쓴 전문</b>이고 레거시 구간은 <b>폐기된 describe 자동
 * 산출물</b>이다. FE 는 편집 가능한 항목이 하나도 없을 때(메타 0건 <b>또는 레거시 구간뿐</b>) 이 수동
 * 슬롯을 띄우므로, 레거시를 앞에 두면 사람이 방금 쓴 전문이 무시되고 <b>편집조차 불가능한 옛 구간
 * 이어붙임</b>이 대신 나간다(조용한 손실).
 *
 * <p>{@code vlm.description} 이 더 앞인 이유: FE 는 그 키가 있으면 수동 슬롯을 <b>아예 띄우지 않으므로</b>
 * 정상 상태에서 둘은 공존하지 않는다. 공존한다면 과거 데이터이며 그때는 현행 편집 대상인
 * {@code vlm.description} 이 최신이다.
 *
 * <h3>지어내지 않는다 (fail-safe)</h3>
 * 원천이 없으면 {@code null} 이며 <b>빈 문자열을 넣지 않는다</b> — {@code ""} 는 "판정했는데 내용이
 * 없다"는 거짓 사실이고, {@link NiaVideo} 는 {@code ALWAYS} 포함이라 키는 어차피 유지된다.
 * 공백뿐인 값은 프로젝트 공통 규약대로 <b>미입력</b>으로 다룬다.
 *
 * <h3>정렬은 반드시 숫자다 (문자열 정렬 함정)</h3>
 * metaKey 를 문자열로 정렬하면 구간이 10개를 넘는 순간 {@code "10-18" < "8-16"} 이 되어 서술 순서가
 * 뒤집힌다(같은 함정을 FE 시계열 패널이 겪었다 — 카탈로그 회차 17). 그래서 키를 파싱해
 * ({@code start_sec}, {@code end_sec}) <b>숫자 오름차순</b>으로 정렬하고, 동률이면 키 자체로 3차
 * 정렬해 결정성을 확보한다(입력 순서에 의존하면 같은 데이터가 다른 산출물을 만든다).
 *
 * <h3>구간형이 아닌 키는 조용히 무시한다 (CWE-20)</h3>
 * 같은 {@code LS_DATA_META} 에 기술메타({@code video.*})·화면 전용(과거 적재분 {@code vlm.accuracy})·비규격 키가
 * 섞여 있다. 정규식에 걸리지 않으면 <b>구간</b> 조달 대상이 아니며 <b>예외를 던지지 않는다</b> —
 * export 는 이 값 하나 때문에 깨지면 안 된다. ({@link #MANUAL_TIMESERIES_META_KEY} 는 무시 대상이
 * 아니라 <b>우선순위 2</b> 로 별도 조달된다.)
 *
 * <h3>절단하지 않는다</h3>
 * 이어붙인 결과가 길어도 <b>자르지 않는다</b>(사일런트 손실 금지 — JSON 에는 길이 제약이 없다).
 * 과대 길이는 관측 로그로만 남기며 <b>내용·식별자는 로그에 싣지 않는다</b>(CWE-209/359).
 */
public final class VlmDescriptionPolicy {

    private static final Logger log = LoggerFactory.getLogger(VlmDescriptionPolicy.class);

    /**
     * <b>사람이 직접 쓴 전문</b>의 metaKey — BE 단일 원천(리터럴 복제 금지, 가드
     * {@code VlmDescriptionPolicySingleSourceGuardTest}).
     *
     * <p>FE 시계열 패널({@code TimeseriesSidePanel})은 <b>편집 가능한 항목이 하나도 없을 때</b>
     * (메타 0건 또는 레거시 구간뿐) 이 키로 신규 등록 슬롯을 띄운다 — 즉 사람이 그 칸에 상황묘사
     * 전문을 직접 입력한다. FE 미러는 {@code features/auto/metaKeys.ts} 의
     * {@code MANUAL_TIMESERIES_META_KEY} 이며 언어 경계라 값이 같아야 한다.
     */
    public static final String MANUAL_TIMESERIES_META_KEY = "manual-timeseries";

    /**
     * 레거시 구간 키 규격 — 구 describe 콜백이 {@code startSec + "-" + endSec} 로 만들던 형태.
     *
     * <p>자릿수를 10 으로 제한해 {@code Long.parseLong} 오버플로를 구조적으로 배제한다(CWE-20).
     * 앵커 고정 + 백트래킹 없는 단순 패턴이라 ReDoS 표면이 없다.
     */
    private static final Pattern LEGACY_SEGMENT_KEY = Pattern.compile("^(\\d{1,10})-(\\d{1,10})$");

    /** 구간 이어붙임 구분자 — 문장이 서로 붙지 않도록 개행으로 명확히 가른다. */
    private static final String SEGMENT_DELIMITER = "\n";

    /** 관측 임계(문자). 넘어도 <b>절단하지 않고</b> 길이만 남긴다. */
    private static final int OBSERVE_LENGTH_THRESHOLD = 100_000;

    private VlmDescriptionPolicy() {
    }

    /**
     * 영상 1건의 메타 집합에서 {@code video.vd_description} 값을 조달한다.
     *
     * @param metas 그 영상({@code rawSn})의 {@code LS_DATA_META} 전체(순서 무관, null·null 원소 허용).
     *              <b>파생영상은 자기 rawSn 의 메타를 넘긴다</b> — 부모 폴백은 두지 않는다(없으면 null).
     * @return 조달된 서술, 원천이 없으면 {@code null}(빈 문자열이 아니다)
     */
    public static String resolve(Collection<LsDataMeta> metas) {
        if (metas == null || metas.isEmpty()) {
            return null;
        }
        String verified = firstValueOf(metas, VlmResultService.META_KEY_DESCRIPTION);
        if (verified != null) {
            return observed(verified);
        }
        String manual = firstValueOf(metas, MANUAL_TIMESERIES_META_KEY);
        if (manual != null) {
            return observed(manual);
        }
        return observed(joinLegacySegments(metas));
    }

    /**
     * 이 metaKey 가 {@code vd_description} 조달에 <b>참여하는가</b> — 즉 이 키의 값이 바뀌면 export
     * 산출물이 달라지는가.
     *
     * <p>승인 후 수정 경로가 {@code TaskModifiedEvent(exportRegenerated=true)} 로 재산출을 걸어야
     * 하는지 판정하는 데 쓴다("저장은 됐는데 산출물이 안 바뀐다" 축 차단). 판정 규칙을 호출부에
     * 복제하지 말 것 — 규칙이 늘면 그쪽만 뒤처진다.
     */
    public static boolean participates(String metaKey) {
        return VlmResultService.META_KEY_DESCRIPTION.equals(metaKey)
                || MANUAL_TIMESERIES_META_KEY.equals(metaKey)
                || isLegacySegmentKey(metaKey);
    }

    /** 지정 키 중 값이 실재하는 첫 행((rawSn, metaKey) 는 UK 라 최대 1건). blank 는 미입력. */
    private static String firstValueOf(Collection<LsDataMeta> metas, String metaKey) {
        for (LsDataMeta m : metas) {
            if (m == null || !metaKey.equals(m.getMetaKey())) {
                continue;
            }
            String value = normalize(m.getMetaVl());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 레거시 구간 행을 {@code start_sec} 숫자 오름차순으로 이어붙인다. 대상 0건이면 null. */
    private static String joinLegacySegments(Collection<LsDataMeta> metas) {
        List<Segment> segments = new ArrayList<>();
        for (LsDataMeta m : metas) {
            if (m == null) {
                continue;
            }
            Matcher matcher = matchSegment(m.getMetaKey());
            if (matcher == null) {
                continue;
            }
            String value = normalize(m.getMetaVl());
            if (value == null) {
                continue;
            }
            segments.add(new Segment(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    m.getMetaKey(),
                    value));
        }
        if (segments.isEmpty()) {
            return null;
        }
        segments.sort(Comparator.comparingLong(Segment::startSec)
                .thenComparingLong(Segment::endSec)
                .thenComparing(Segment::metaKey));
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            if (sb.length() > 0) {
                sb.append(SEGMENT_DELIMITER);
            }
            sb.append(s.description());
        }
        return sb.toString();
    }

    private static boolean isLegacySegmentKey(String metaKey) {
        return matchSegment(metaKey) != null;
    }

    /** 구간 키 매칭 — 형식 불일치는 {@code null}(예외 없음). */
    private static Matcher matchSegment(String metaKey) {
        if (metaKey == null) {
            return null;
        }
        Matcher matcher = LEGACY_SEGMENT_KEY.matcher(metaKey);
        return matcher.matches() ? matcher : null;
    }

    /** blank(공백만)를 미입력({@code null})으로 정규화. 프로젝트 공통 기준. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * 과대 길이 관측 — <b>절단하지 않는다</b>. 서술 원문·rawSn 을 남기지 않아 로그로 PII 가 새지
     * 않게 한다(CWE-209/359).
     */
    private static String observed(String resolved) {
        if (resolved != null && resolved.length() > OBSERVE_LENGTH_THRESHOLD) {
            log.warn("[Export] vd_description unusually long — not truncated length={}", resolved.length());
        }
        return resolved;
    }

    /** 정렬용 파싱 결과(불변). */
    private record Segment(long startSec, long endSec, String metaKey, String description) {
    }
}
