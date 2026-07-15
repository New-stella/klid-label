package kr.co.cudo.authoring.dataset.export;

import java.nio.file.Path;

/**
 * 한 영상·한 {@link ExportKind}·한 버전에 대한 파일 산출 결과 집계.
 *
 * @param kind       산출 종류(원본/비식별)
 * @param version    산출 버전
 * @param writtenCnt 이미지+JSON 페어를 정상 기록한 프레임 수
 * @param skippedCnt 원천 이미지 부재 등으로 건너뛴 프레임 수(부분성공)
 * @param dir        산출 디렉토리(절대경로)
 */
public record ExportResult(ExportKind kind, int version, int writtenCnt, int skippedCnt, Path dir) {
}
