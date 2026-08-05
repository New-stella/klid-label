package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/**
 * 산출 폴더 <b>총 바이트</b> 산출기 (V173, {@code @req R4}) —
 * {@code LS_DATASET_EXPORT.DATA_ETBL_CPCT}(데이터구축용량)의 값 원천.
 *
 * <p>관제 {@code dataset_versions.data_etbl_cpct} 는 "이 학습데이터 버전이 차지하는 용량"이므로 집계
 * 대상은 <b>버전 폴더</b>({@code {영상루트}/v{n}}) 하위 전체다. 영상 루트 전체가 아니다 — 거기엔 다른
 * 버전({@code v1..v{n-1}})과 비식별 영상({@code deid/})이 형제로 들어 있어, 합치면 버전당 용량이 아니라
 * 영상 누적 용량이 된다.
 *
 * <h3>실패는 null 이다 — 예외를 전파하지 않는다</h3>
 * <p>용량은 산출물의 <b>부수 정보</b>다. 계산이 실패했다고 export 를 실패시키면(= 검수 승인 산출물이
 * 안 나가면) 부수 정보 때문에 본체를 잃는다. 그래서 경로 가드 거부·I/O 실패는 모두 {@code null} 로
 * 종결하고 WARN 만 남긴다({@code DATA_ETBL_CPCT} 는 nullable — "미산출"이 표현 가능하다).
 *
 * <h3>경로 안전 (CWE-22/59/367)</h3>
 * <p>{@code DatasetExportService.purgeThisRunVersionDir} 와 <b>같은 가드</b>를 쓴다 —
 * ①{@link VideoArtifactRootResolver#resolveUnder}(세그먼트 검증 + 실경로 세그먼트 정확 일치)
 * ②{@link VideoArtifactRootResolver#resolveRealPathUnder}(순회 직전 실경로 재확인).
 * <b>②가 돌려준 실경로로 순회한다</b> — lexical 경로로 검증하고 lexical 경로로 순회하면 검증~순회
 * 사이에 심링크를 바꿔치기해 저장소 밖(또는 원본 트리)을 훑게 만들 수 있다.
 *
 * <p>사용자 입력은 이 경로에 <b>섞이지 않는다</b>: base 는 호출자가 넘긴 영상 루트이고,
 * 버전 세그먼트는 정수({@code version})로만 조립한다.
 *
 * <h3>⚠ base 는 <b>stale</b> 하다 — 계산 시점에 재검증하지 않는다 (의도된 판단, 확장 시 반드시 재검토)</h3>
 * <p>{@code verifiedVideoRoot} 는 <b>파일 쓰기 <i>이전</i></b>에 {@code DatasetExportService} 가
 * 한 번 검증해 둔 값이다. 같은 폴더를 다루는 {@code purgeThisRunVersionDir}·{@code DatasetExportWriter}
 * 는 매번 {@code pathResolver.resolve*} 를 <b>재호출</b>해 고정 allowlist 판정을 현재 파일시스템 상태로
 * 다시 돌리는데, <b>이 계산기만 그러지 않는다</b>. 즉 검증 시점과 사용 시점 사이에 base 자체가
 * 바뀌었다면 그 변화는 여기서 감지되지 않는다.
 *
 * <p><b>그럼에도 지금은 안전하다고 판단한 근거</b> — 셋 다 성립할 때만 유효하다:
 * <ol>
 *   <li>{@link VideoArtifactRootResolver#resolveUnder} 의 <b>exact-segment 판정</b>은 매 호출마다
 *       실경로를 다시 접어 비교하므로, base 하위 세그먼트 교체는 그대로 걸린다.</li>
 *   <li>여기서 새어 나갈 수 있는 것은 <b>집계 바이트 수 1개</b>뿐이다 — 파일 내용도, 경로 문자열도
 *       반환하거나 로깅하지 않는다.</li>
 *   <li>이 값이 적재되는 {@code DATA_ETBL_CPCT} 는 <b>데이터마트 뷰에 노출되지 않는다</b>.</li>
 * </ol>
 * <p>또한 base 를 갈아치우려면 이미 NAS 쓰기 권한이 필요한데, 그 전제라면 산출물 자체를 조작하는
 * 훨씬 큰 공격이 가능하다(그래서 LOW 판정).
 *
 * <p><b>확장 시 규칙</b>: 이 클래스가 바이트 수를 넘어 <b>파일 내용·경로·파일명 등 더 민감한 값</b>을
 * 다루도록 바뀌면 위 근거 ②③이 무너진다. 그때는 {@code verifiedVideoRoot} 를 인자로 받지 말고
 * <b>{@code pathResolver.resolveVideoRoot} 재호출</b>로 base 를 다시 도출하도록 바꿔야 한다.
 * 이 stale base 패턴을 다른 곳에 <b>그대로 복제하지 말 것</b> — 여기서만 성립하는 조건부 판단이다.
 *
 * <p>로그에는 경로 원문을 남기지 않는다(CWE-359/117) — 버전 번호와 실패 유형만 남긴다.
 */
@Component
public class DatasetExportFolderSizeCalculator {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportFolderSizeCalculator.class);

    /**
     * 이 버전의 산출 폴더 총 바이트를 계산한다.
     *
     * @param verifiedVideoRoot 영상 루트({@code DatasetExportPathResolver#resolveVideoRoot} 결과).
     *                          자기참조 rubber-stamp 를 피하려 <b>집계 대상과 별개로</b> 받는다.
     *                          ⚠ <b>파일 쓰기 이전에 검증된 stale 값</b>이며 여기서 재검증하지 않는다 —
     *                          허용 조건과 확장 시 규칙은 클래스 javadoc 참조.
     * @param version           산출 버전(≥1)
     * @return 총 바이트. 폴더 부재·경로 가드 거부·I/O 실패면 {@code null}(export 는 성공으로 종결)
     */
    public Long calculate(Path verifiedVideoRoot, int version) {
        if (verifiedVideoRoot == null || version < 1) {
            return null;
        }
        Path realVersionDir;
        try {
            Path versionDir = VideoArtifactRootResolver.resolveUnder(verifiedVideoRoot, "v" + version);
            if (!Files.exists(versionDir)) {
                return null; // 산출물이 없다 = 집계 대상 없음(비정상은 상위 outcome 이 이미 표현한다).
            }
            // 검증이 돌려준 <실경로>로 순회한다(TOCTOU — lexical 경로 재사용 금지).
            realVersionDir = VideoArtifactRootResolver.resolveRealPathUnder(versionDir, verifiedVideoRoot);
        } catch (RuntimeException e) {
            log.warn("[DatasetExport] output capacity skipped — path guard rejected version={} cause={}",
                    version, e.getClass().getSimpleName());
            return null;
        }
        return sumRegularFileBytes(realVersionDir, version);
    }

    /**
     * 하위 <b>정규 파일</b>의 바이트 합. 심링크는 따라가지 않으며 합산 대상도 아니다 —
     * 링크를 따라가면 폴더 밖(다른 버전·원본 트리)의 용량이 이 버전 용량으로 잡힌다.
     *
     * <p>부분 합계는 관제에 <b>틀린 수치</b>로 나가므로, 중간에 I/O 가 하나라도 깨지면 전체를
     * {@code null}(미산출)로 종결한다(fail-closed).
     */
    private Long sumRegularFileBytes(Path root, int version) {
        // Files.walk 는 기본적으로 심링크를 따라가지 않는다(FOLLOW_LINKS 미지정).
        try (Stream<Path> walk = Files.walk(root)) {
            long total = 0L;
            for (Path path : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes attrs =
                        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isRegularFile()) {
                    total += attrs.size();
                }
            }
            return total;
        } catch (IOException | RuntimeException e) {
            // RuntimeException 까지 잡는다 — UncheckedIOException·SecurityException 등이 새어 나가면
            // 호출부(DatasetExportService)의 바깥 catch 가 이를 <산출 실패>로 오분류해 export 를 FAILED
            // 로 마감한다. "용량 때문에 학습데이터 산출이 실패하면 안 된다"는 계약을 여기서 닫는다.
            log.warn("[DatasetExport] output capacity skipped — walk failed version={} cause={}",
                    version, e.getClass().getSimpleName());
            return null;
        }
    }
}
