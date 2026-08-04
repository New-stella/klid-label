package kr.co.cudo.authoring.common.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 저장소 <b>서브트리(subtree) 격리 정책</b> — 원본(raw) 산출물과 비식별(deid) 산출물이 섞이지 않도록
 * "어떤 상대 경로 규약이 어느 성격의 산출물인가"를 한 곳에서 정의한다.
 *
 * <h3>왜 base 검사만으로는 부족한가 (CWE-359 / CWE-668)</h3>
 * <p>운영 환경은 {@code STORAGE_RAW_PATH} 와 {@code STORAGE_DEIDENTIFIED_PATH} 를 <b>같은 경로</b>
 * ({@code /nas-storage})로 설정한다(CLAUDE.md 데이터마트 절). 이때 {@code resolved.startsWith(deidBase)}
 * 검사는 {@code frames/raw/...}(원본 프레임)도 그대로 통과시키므로, "비식별 전용" 검증이 사실상 무력화된다
 * (fail-open). 따라서 비식별 산출물 판정은 <b>base 포함 검사 + 비식별 전용 서브트리 접두 검사</b>를
 * 함께 수행해야 한다.
 *
 * <h3>규약</h3>
 * <ul>
 *   <li>{@code frames/raw/{rawSn}/…} — 원본 프레임 전용({@code FfmpegFrameExtractor})</li>
 *   <li>{@code frames/deid/{rawSn}/…} — 비식별 프레임 전용({@code DeidentFrameAttacher}, 해상도 파생 프레임)</li>
 *   <li>{@code videos/…} — 비식별 <b>영상</b> 전용({@code DeidentifyStep}/{@code KpstDeidentService},
 *       해상도 파생 영상). 원본 영상은 관제 NAS 절대경로라 저장소 base 하위에 존재하지 않는다.</li>
 * </ul>
 *
 * <h3>판정기 단일화 (H-2 / H-4)</h3>
 * <p>{@link #isDeidentifiedArtifact(Path, Path)} 등은 순수 경로 계산이지만, 실제 "이 DB 경로가 비식별
 * 산출물인가" 판정은 <b>파일시스템 상태까지</b> 봐야 한다(존재·정규파일·심링크 실경로). 그 판정을
 * {@link #verifyDeidentifiedFile(Path, String)} <b>한 메서드</b>로 모아 서빙 경로({@code FrameSource})와
 * 이 판정이 필요한 다른 경로가 <b>literally 같은 코드</b>를 쓰게 한다 — SQL 로 근사한 감사는 코드 판정과
 * 동치가 아니어서 "영향 없음" 주장을 입증하지 못했다(H-4). (이 메서드를 함께 쓰던 해상도 저장소 이관
 * 백필의 감사 경로는 2026-07-30 백필 제거와 함께 사라졌다.)
 *
 * <p><b>심링크 우회 차단(CWE-59, H-2)</b>: 두 base 가 같은 디렉토리인 운영(prd, {@code /nas-storage})에서는
 * {@code frames/deid/**} 안의 심링크가 {@code frames/raw/**} 를 가리켜도 ①lexical 서브트리 검사와
 * ②{@code realResolved.startsWith(realBase)} 검사를 <b>모두</b> 통과한다. 따라서 서브트리 판정을
 * <b>실경로({@code toRealPath})</b> 에 적용해야 원본 프레임이 비식별 벌로 새는 것을 막을 수 있다.
 */
public final class StorageSubtreePolicy {

    /** 프레임 산출물 최상위 세그먼트. */
    public static final String SEG_FRAMES = "frames";
    /** 비식별 영상 산출물 최상위 세그먼트. */
    public static final String SEG_VIDEOS = "videos";
    /** 원본 프레임 종류 세그먼트. */
    public static final String SEG_RAW = "raw";
    /** 비식별 프레임 종류 세그먼트. */
    public static final String SEG_DEID = "deid";

    /** 해상도 파생 영상의 비식별 저장 서브트리 세그먼트 — {@code videos/resolution/{parentRawSn}/…}. */
    public static final String SEG_RESOLUTION = "resolution";

    /** 증강 파생 영상의 비식별 저장 서브트리 세그먼트 — {@code videos/augment/{parentRawSn}/…}. */
    public static final String SEG_AUGMENT = "augment";

    private StorageSubtreePolicy() {
    }

    /** 비식별 프레임 디렉토리 상대경로 — {@code frames/deid/{rawSn}}. */
    public static String deidFramesDir(long rawSn) {
        return SEG_FRAMES + "/" + SEG_DEID + "/" + rawSn;
    }

    /**
     * 해상도 파생 영상 파일 상대경로 —
     * {@code videos/resolution/{parentRawSn}/{derivativeRawSn}/{preset}.mp4}.
     *
     * <p>비식별 영상 규약({@code videos/}) 하위이되, 실제 비식별 영상 디렉토리
     * ({@code videos/{rawSn}/})와 겹치지 않도록 {@code resolution} 세그먼트로 분리한다 — 비식별
     * 결과 회수 시 디렉토리 스캔 폴백({@code KpstDeidentService})이 파생 영상을 원본 비식별본으로
     * 오인해 회수하는 것을 막는다.
     *
     * <p><b>A-6 (경로 키에 파생 RAW_SN 포함)</b>: 구 규약은 {@code (부모, 프리셋)} 만으로 키잉해
     * <b>같은 (부모, 프리셋) 파생이 복수 존재</b>할 때(실측: 구 스킴 파일 1개를 rawSn 5건이 공유) ①확정
     * 시 {@code REPLACE_EXISTING} 복사가 서로를 덮어쓰고 ②한 파생의 실패 cleanup 이 <b>다른 파생이
     * 참조 중인 공유 파일</b>을 삭제했다. 파생 RAW_SN 을 키에 넣어 파생 1건 = 파일 1개로 분리한다.
     *
     * @param parentRawSn     원본(부모) RAW_SN — 그룹 디렉토리
     * @param derivativeRawSn 파생 RAW_SN — 파생별 고유 키
     * @param presetName      해상도 프리셋 코드(RESL_1080P/RESL_720P/RESL_480P)
     */
    public static String resolutionVideoFile(long parentRawSn, long derivativeRawSn, String presetName) {
        return SEG_VIDEOS + "/" + SEG_RESOLUTION + "/" + parentRawSn + "/" + derivativeRawSn
                + "/" + presetName + ".mp4";
    }

    /**
     * 증강 파생 영상 파일 상대경로 —
     * {@code videos/augment/{parentRawSn}/{derivativeRawSn}/{augTypeCd}.mp4}.
     *
     * <p>{@link #resolutionVideoFile(long, long, String)} 과 <b>동일 규약</b>이며 종류 세그먼트만 다르다
     * (해상도 파생과 증강 파생이 서로 다르게 동작하지 않도록 규약을 하나로 유지한다). 증강 결과 영상은
     * 외부가 만들어 주지 않고 <b>부모의 비식별 영상을 복사</b>한 것이므로 비식별 영상 규약({@code videos/})
     * 하위이되, 실제 비식별 영상 디렉토리({@code videos/{rawSn}/})와 겹치지 않도록 {@code augment}
     * 세그먼트로 분리한다 — 비식별 결과 회수 시 디렉토리 스캔 폴백({@code KpstDeidentService})이 파생
     * 영상을 원본 비식별본으로 오인해 회수하는 것을 막는다.
     *
     * <p>경로 키에 <b>파생 RAW_SN</b> 을 포함해 파생 1건 = 파일 1개로 분리한다(A-6 와 동일 이유 —
     * 재요청/중복 파생이 서로의 파일을 덮어쓰거나 cleanup 이 남의 파일을 지우는 실패 클래스 차단).
     *
     * @param parentRawSn     원본(부모) RAW_SN — 그룹 디렉토리
     * @param derivativeRawSn 파생 RAW_SN — 파생별 고유 키
     * @param augTypeCd       증강 유형 코드(WINTER/NIGHT/RAIN)
     */
    public static String augmentVideoFile(long parentRawSn, long derivativeRawSn, String augTypeCd) {
        return SEG_VIDEOS + "/" + SEG_AUGMENT + "/" + parentRawSn + "/" + derivativeRawSn
                + "/" + augTypeCd + ".mp4";
    }

    /**
     * 비식별 산출물 서브트리 여부 — {@code frames/deid/**} 또는 {@code videos/**} 하위인가.
     *
     * <p>두 base 가 동일 문자열이어도 {@code frames/raw/**} 는 이 검사에서 거부된다.
     *
     * @param base     비식별 저장소 base (정규화된 절대경로)
     * @param resolved 검증 대상 (정규화된 절대경로)
     */
    public static boolean isDeidentifiedArtifact(Path base, Path resolved) {
        Path rel = relativeUnder(base, resolved);
        return rel != null
                && (startsWithSegments(rel, SEG_FRAMES, SEG_DEID) || startsWithSegments(rel, SEG_VIDEOS));
    }

    /** 원본 프레임 서브트리 여부 — {@code frames/raw/**} 하위인가. */
    public static boolean isRawFrameArtifact(Path base, Path resolved) {
        Path rel = relativeUnder(base, resolved);
        return rel != null && startsWithSegments(rel, SEG_FRAMES, SEG_RAW);
    }

    /**
     * 비식별 산출물 파일 판정 결과 코드 — 감사(rawSn 별 위반 집계)에서 사유로 그대로 쓴다.
     * 경로 원문은 담지 않는다(CWE-209).
     */
    public enum Verdict {
        /** 비식별 산출물로 인정(실경로 기준 서브트리 + 존재하는 정규파일). */
        OK,
        /** 경로 값이 null/공백 — 결측(코드가 {@code isBlank} 로 취급하는 것과 동일). */
        BLANK,
        /** lexical 정규화 결과가 base 밖(CWE-22 traversal 포함). */
        OUTSIDE_BASE,
        /** 파일 부재. */
        MISSING,
        /** 정규 파일이 아님(디렉토리 등). */
        NOT_REGULAR_FILE,
        /** 실경로 해석 실패(권한·깨진 심링크 등). */
        REALPATH_FAILED,
        /** 실경로가 비식별 전용 서브트리({@code frames/deid}·{@code videos}) 밖 — 심링크 우회 포함. */
        OUTSIDE_DEID_SUBTREE
    }

    /**
     * 판정 결과 — 통과 시 서빙/복사에 쓸 경로를 함께 담는다.
     *
     * <p><b>A-1 (TOCTOU, CWE-367)</b>: {@link Verdict#OK} 의 {@code path} 는 판정에 사용한
     * <b>실경로({@code toRealPath})</b>다. lexical 경로를 돌려주면 "실경로로 판정하고 lexical 경로로 연다"가
     * 되어, 판정~open 사이(DB 조회·로깅 수 ms)에 심링크를 {@code ../raw/{n}/frame-0.jpg} 로 바꾸면
     * <b>원본 픽셀이 비식별본으로 서빙·export</b> 된다. 소비측이 이 경로를 그대로 열면 그 창이 닫힌다.
     *
     * @param verdict 판정 코드
     * @param path    {@link Verdict#OK} 일 때만 non-null (심링크까지 해석한 정규화 절대 <b>실경로</b>)
     */
    public record Verification(Verdict verdict, Path path) {

        public boolean ok() {
            return verdict == Verdict.OK;
        }
    }

    /**
     * DB 에 저장된 경로 문자열이 <b>실제로</b> 비식별 산출물인지 판정한다 — 서빙(FrameSource)과
     * 감사(백필)가 공유하는 <b>단일 판정기</b>.
     *
     * <p>순서: ①blank ②lexical 정규화 + base 포함(CWE-22) ③존재 + 정규파일 ④{@code toRealPath} 실경로
     * ⑤<b>실경로 기준</b> 비식별 서브트리(CWE-59 심링크 우회 차단). 어느 단계든 실패하면 fail-secure 로
     * 사유 코드를 반환한다(예외 없음 — 호출측이 skip/집계 판단).
     *
     * @param base      비식별 저장소 base (정규화 절대경로)
     * @param pathValue DB 경로 문자열(절대/상대 모두 허용 — 상대는 base 기준 해석)
     */
    public static Verification verifyDeidentifiedFile(Path base, String pathValue) {
        if (base == null || pathValue == null || pathValue.isBlank()) {
            return new Verification(Verdict.BLANK, null);
        }
        Path resolved;
        try {
            Path candidate = Paths.get(pathValue);
            resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        } catch (RuntimeException e) {
            return new Verification(Verdict.OUTSIDE_BASE, null); // 해석 불가 경로 = fail-secure
        }
        if (!resolved.startsWith(base)) {
            return new Verification(Verdict.OUTSIDE_BASE, null);
        }
        if (!Files.exists(resolved)) {
            return new Verification(Verdict.MISSING, null);
        }
        if (!Files.isRegularFile(resolved)) {
            return new Verification(Verdict.NOT_REGULAR_FILE, null);
        }
        Path realResolved;
        Path realBase;
        try {
            realResolved = resolved.toRealPath();
            realBase = base.toRealPath();
        } catch (IOException e) {
            return new Verification(Verdict.REALPATH_FAILED, null);
        }
        // H-2 — 서브트리 판정을 <b>실경로</b>에 적용한다. lexical 경로에만 적용하면 base 내부 심링크
        // (frames/deid/x.jpg → frames/raw/x.jpg)가 두 base 동일 환경에서 전 검사를 통과한다.
        if (!isDeidentifiedArtifact(realBase, realResolved)) {
            return new Verification(Verdict.OUTSIDE_DEID_SUBTREE, null);
        }
        // A-1 — <b>판정한 그 경로</b>(실경로)를 돌려준다. lexical {@code resolved} 를 돌려주면 소비측이
        // 검증 이후 다시 심링크를 따라가므로 검증과 사용 대상이 달라진다(TOCTOU, CWE-367).
        return new Verification(Verdict.OK, realResolved);
    }

    /**
     * <b>세그먼트 시퀀스 정확 일치</b> 판정 (CWE-59/706) — {@code realTarget} 의 실경로가
     * {@code realBase} 아래 <b>정확히</b> {@code segments} 위치인가.
     *
     * <h3>왜 {@code startsWith} 로는 부족한가 (B-ISSUE-41 CRITICAL)</h3>
     * <p>{@code realTarget.startsWith(realBase)} 는 "base 밖으로 탈출했는가"만 본다. 그런데 운영은
     * {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH}({@code /nas-storage})가 <b>정상 형상</b>
     * 이라, 원본 산출물({@code frames/raw/**})과 비식별 산출물이 <b>같은 base 안에</b> 있다. 이때
     * 중간 디렉터리 자체({@code videos/{rawSn}} 등 — 외부 비식별 벤더가 공유 마운트로 직접 쓰는 신뢰
     * 경계 지점)를 <b>같은 base 안의</b> 원본 서브트리를 가리키는 심링크로 바꾸면, target 과 base 가
     * 모두 그 링크를 거쳐 접히므로 {@code startsWith} 가 <b>자기참조(rubber-stamp)</b>가 되어 항상
     * 참이 된다 — 마스킹 전 픽셀이 "비식별본" 으로 200 서빙된다(CWE-359).
     *
     * <p>따라서 판정 축은 "탈출 여부"가 아니라 <b>"실경로가 기대한 그 자리인가"</b> 여야 한다. 이는
     * {@link #verifyDeidentifiedFile} 이 이미 채택한 축("실경로에 서브트리 세그먼트 판정을 적용")과
     * 동일한 철학이며, 여기서는 접두가 아니라 <b>길이까지 포함한 정확 일치</b>를 요구한다(중간 세그먼트
     * 하나라도 다른 실위치로 접히면 거부).
     *
     * @param realBase  기준 base 의 <b>실경로</b>
     * @param realTarget 검증 대상의 <b>실경로</b>
     * @param segments  base 로부터 기대하는 세그먼트 시퀀스(빈 목록이면 {@code realTarget == realBase})
     */
    public static boolean isExactSegmentPath(Path realBase, Path realTarget, List<String> segments) {
        if (realBase == null || realTarget == null || segments == null) {
            return false;
        }
        if (segments.isEmpty()) {
            // 빈 path 의 getNameCount() 가 1 인 JDK 특성 때문에 relativize 로는 판정할 수 없다.
            return realTarget.equals(realBase);
        }
        Path rel = relativeUnder(realBase, realTarget);
        if (rel == null || rel.getNameCount() != segments.size()) {
            return false;
        }
        return matchesPrefix(rel, segments);
    }

    /** base 하위면 상대경로, 아니면 null. */
    private static Path relativeUnder(Path base, Path resolved) {
        if (base == null || resolved == null || !resolved.startsWith(base)) {
            return null;
        }
        return base.relativize(resolved);
    }

    /** 상대경로의 선두 세그먼트들이 기대값과 일치하는가(문자열 접두가 아닌 <b>세그먼트</b> 단위 비교). */
    private static boolean startsWithSegments(Path relative, String... segments) {
        if (relative.getNameCount() <= segments.length) {
            return false; // 최소한 서브트리 하위에 파일/디렉토리 1개는 더 있어야 한다.
        }
        return matchesPrefix(relative, List.of(segments));
    }

    /** 세그먼트 비교 원시연산 — 접두 판정({@link #startsWithSegments})과 정확 일치 판정이 공유한다. */
    private static boolean matchesPrefix(Path relative, List<String> segments) {
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(relative.getName(i).toString())) {
                return false;
            }
        }
        return true;
    }
}
