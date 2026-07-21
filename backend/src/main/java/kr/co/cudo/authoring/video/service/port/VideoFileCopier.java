package kr.co.cudo.authoring.video.service.port;

import java.nio.file.Path;

/**
 * 영상 파일 복사 포트 — Phase 2 (해상도 파생영상).
 *
 * <p>원본 비식별 비디오를 파생영상 경로로 복사한다. 경로 검증(CWE-22 normalize+base)은 호출 측
 * 서비스가 책임지고, 본 포트는 검증된 경로에 대한 물리 복사/존재확인만 수행한다(테스트 격리 목적 분리).
 * 운영 구현은 {@code NioVideoFileCopier}(java.nio Files.copy).
 */
public interface VideoFileCopier {

    /** 검증된 src 파일이 존재하는지 확인한다. */
    boolean exists(Path src);

    /** src 를 dst 로 복사한다(상위 디렉토리 생성 포함, 기존 파일 덮어쓰기). */
    void copy(Path src, Path dst);
}
