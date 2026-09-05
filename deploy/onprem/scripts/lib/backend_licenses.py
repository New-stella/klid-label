#!/usr/bin/env python3
"""backend 반입물(WAR/JAR)의 제3자 라이선스 인벤토리 생성.

왜 파이썬인가
-------------
라이선스 이름의 주 조달처가 Maven POM 의 ``<licenses>`` 인데, 이 프로젝트 의존성의
36%(160개 중 57개)는 자기 POM 에 ``<licenses>`` 가 없고 **부모 POM 에서 상속**한다.
부모 체인을 따라가는 일은 셸로 하면 금방 무너진다(주석 제거·재귀·XML 중첩).
실측: 자기 POM 만 보면 103/160, 부모 체인까지 따라가면 **160/160** 이 해석된다.

무엇을 하고 무엇을 안 하나
--------------------------
* 한다  — WAR/JAR 안에 실제로 실린 jar 목록을 읽고, 각 jar 의 GAV 를 lockfile 로
          맞춰 Gradle 캐시의 POM 에서 라이선스 **이름**을 읽는다.
* 한다  — 해석 실패분을 UNRESOLVED 로 남긴다.
* 안 한다 — 라이선스 **전문**을 여기서 만들지 않는다. 전문은 jar 안의
          ``META-INF/NOTICE``·``META-INF/LICENSE`` 를 셸이 그대로 복사한다
          (요약·재작성 금지 — 요약한 NOTICE 는 Apache-2.0 §4(d) 를 못 채운다).
* 안 한다 — 이름을 추정하지 않는다. 못 찾으면 빈칸으로 두고 UNRESOLVED 로 센다.

Gradle 캐시 의존성에 대해
-------------------------
POM 은 ``~/.gradle/caches/modules-2/files-2.1`` 에서 읽는다. 이 스크립트는
``10-build-backend.sh`` 가 gradle 빌드를 <끝낸 직후> 도는 자리라 캐시가 반드시
채워져 있다. 캐시가 없으면 실패시키지 않고 UNRESOLVED 로 떨어뜨린다 —
라이선스 인벤토리 때문에 반입물 빌드를 못 만들게 하는 것은 과하다.
"""
from __future__ import annotations

import argparse
import glob
import os
import pathlib
import re
import sys
import zipfile

# jar 파일명에서 (artifactId, version) 을 뽑는다. 버전은 "숫자로 시작하는 첫 토큰"부터.
#   예) hibernate-core-6.5.2.Final.jar -> ("hibernate-core", "6.5.2.Final")
#       listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar 도 같은 규칙으로 잡힌다.
_JAR_RE = re.compile(r"^(?P<a>.+?)-(?P<v>\d[^-]*(?:-[^-]+)*?)\.jar$")


def parse_jar_name(name: str):
    m = _JAR_RE.match(name)
    if not m:
        return name[:-4] if name.endswith(".jar") else name, ""
    return m.group("a"), m.group("v")


def read_lockfile(path: str, configuration: str) -> dict:
    """lockfile 에서 (artifactId, version) -> groupId 를 만든다.

    jar 파일명에는 groupId 가 없어 캐시 경로를 만들 수 없다. lockfile 이 그 빠진
    축을 채워 준다. 같은 artifactId 가 여러 group 에 있을 수 있으므로 (a, v) 로 건다.
    """
    out = {}
    if not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            coord, confs = line.split("=", 1)
            if configuration not in confs:
                continue
            parts = coord.split(":")
            if len(parts) != 3:
                continue
            g, a, v = parts
            out[(a, v)] = g
    return out


def _strip_comments(text: str) -> str:
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


class PomResolver:
    def __init__(self, cache_root: str):
        self.cache_root = cache_root
        self._memo: dict = {}

    def _pom_path(self, g, a, v):
        hits = glob.glob(os.path.join(self.cache_root, g, a, v, "*", f"{a}-{v}.pom"))
        return hits[0] if hits else None

    def find_group(self, a, v):
        """groupId 를 모를 때 Gradle 캐시 레이아웃에서 역으로 찾는다.

        캐시 경로가 ``<group>/<artifact>/<version>/<hash>/<artifact>-<version>.pom`` 라
        group 자리에 와일드카드를 두면 유일하게 결정된다. lockfile 이 못 채우는 두 경우를
        여기서 흡수한다 — ①빌드 산출물이 lockfile 보다 낡아 버전이 어긋난 경우
        ②Gradle 플러그인이 lockfile 없이 끼워 넣은 jar(spring-boot-jarmode-tools 등).
        """
        if not v:
            return ""
        hits = glob.glob(os.path.join(self.cache_root, "*", a, v, "*", f"{a}-{v}.pom"))
        if not hits:
            return ""
        # <cache>/<group>/<artifact>/<version>/<hash>/<file>  → 뒤에서 4번째가 group
        return pathlib.PurePath(hits[0]).parts[-5]

    def licenses(self, g, a, v, depth=0):
        """POM 의 <licenses><name> 목록. 없으면 <parent> 를 따라 올라간다."""
        key = (g, a, v)
        if key in self._memo:
            return self._memo[key]
        result = None
        if depth <= 8:
            path = self._pom_path(g, a, v)
            if path:
                try:
                    with open(path, encoding="utf-8", errors="replace") as fh:
                        raw = _strip_comments(fh.read())
                except OSError:
                    raw = ""
                block = re.search(r"<licenses>(.*?)</licenses>", raw, re.S)
                if block:
                    names = [n.strip() for n in re.findall(r"<name>(.*?)</name>", block.group(1), re.S)]
                    names = [re.sub(r"\s+", " ", n) for n in names if n.strip()]
                    if names:
                        result = names
                if result is None:
                    par = re.search(r"<parent>(.*?)</parent>", raw, re.S)
                    if par:
                        body = par.group(1)
                        pg = re.search(r"<groupId>(.*?)</groupId>", body)
                        pa = re.search(r"<artifactId>(.*?)</artifactId>", body)
                        pv = re.search(r"<version>(.*?)</version>", body)
                        if pg and pa and pv:
                            result = self.licenses(
                                pg.group(1).strip(), pa.group(1).strip(), pv.group(1).strip(), depth + 1
                            )
        self._memo[key] = result
        return result


def list_bundled_jars(archive: str):
    """WAR 의 WEB-INF/lib 또는 bootJar 의 BOOT-INF/lib 에 실린 jar 이름 목록."""
    names = []
    with zipfile.ZipFile(archive) as zf:
        for n in zf.namelist():
            if n.endswith(".jar") and (n.startswith("WEB-INF/lib/") or n.startswith("BOOT-INF/lib/")):
                names.append(os.path.basename(n))
    return sorted(set(names))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--archive", required=True, help="api.war 또는 klid-backend.jar")
    ap.add_argument("--lockfile", required=True, help="backend/gradle.lockfile")
    ap.add_argument("--configuration", default="productionRuntimeClasspath")
    ap.add_argument("--gradle-cache", default=os.path.expanduser("~/.gradle/caches/modules-2/files-2.1"))
    ap.add_argument(
        "--out-tsv",
        required=True,
        help="jar파일명<TAB>artifact<TAB>version<TAB>license 로 출력",
    )
    args = ap.parse_args()

    try:
        jars = list_bundled_jars(args.archive)
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"[backend-licenses] 아카이브를 읽지 못했습니다: {args.archive} ({exc})", file=sys.stderr)
        return 2

    gav = read_lockfile(args.lockfile, args.configuration)
    resolver = PomResolver(args.gradle_cache)
    cache_ok = os.path.isdir(args.gradle_cache)

    resolved = unresolved = 0
    with open(args.out_tsv, "w", encoding="utf-8") as out:
        for jar in jars:
            a, v = parse_jar_name(jar)
            g = gav.get((a, v), "")
            if not g and cache_ok:
                # ①classifier 가 파일명에 붙어 버전이 어긋나는 경우(netty-…-linux-x86_64,
                #   querydsl-jpa-5.1.0-jakarta)를 뒤에서부터 한 토막씩 떼며 다시 맞춘다.
                trimmed = v
                while "-" in trimmed and not g:
                    trimmed = trimmed.rsplit("-", 1)[0]
                    g = gav.get((a, trimmed), "")
                    if g:
                        v = trimmed
            if not g and cache_ok:
                # ②lockfile 로도 안 되면 캐시 레이아웃에서 group 을 역추적한다.
                g = resolver.find_group(a, v)
            names = resolver.licenses(g, a, v) if (g and cache_ok) else None
            lic = "; ".join(names) if names else ""
            if lic:
                resolved += 1
            else:
                unresolved += 1
            # 첫 열은 <원본 jar 파일명>이다. 호출하는 셸이 이 표를 jar 파일명으로 되짚기
            # 때문이며, artifact/version 으로 다시 조립하게 두면 classifier 가 붙은 파일
            # (querydsl-jpa-5.1.0-jakarta.jar)에서 조립 결과가 어긋나 조용히 미해석이 된다.
            out.write(f"{jar}\t{a}\t{v}\t{lic}\n")

    print(f"[backend-licenses] jars={len(jars)} resolved={resolved} unresolved={unresolved}", file=sys.stderr)
    if not cache_ok:
        print(
            f"[backend-licenses] ⚠ Gradle 캐시를 찾지 못해 라이선스 이름을 한 건도 해석하지 못했습니다: "
            f"{args.gradle_cache}",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
