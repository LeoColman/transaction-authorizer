#!/usr/bin/env python3
"""Compara os números publicados no README com os relatórios recém-gerados.

Existe porque essa divergência já aconteceu quatro vezes: cobertura duas vezes e
mutação duas vezes. Cada correção foi manual, e a próxima mudança de escopo
recomeçaria o ciclo. Aqui o número medido é a fonte da verdade e o README é
cobrado contra ele.

O escopo do "núcleo" NÃO é escrito à mão: sai do targetClasses do Pitest no
build.gradle.kts, para que ampliar a mutação recalcule o número em vez de deixar
o texto para trás — que foi exatamente como ele se desatualizou da última vez.

Uso: veja scripts/check-numbers.sh, que gera os relatórios e chama este script.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def line_coverage(report: Path, class_filter=None) -> float:
    """Cobertura de linhas do relatório Kover, opcionalmente restrita a classes."""
    root = ET.parse(report).getroot()
    missed = covered = 0
    if class_filter is None:
        for counter in root.findall("counter"):
            if counter.get("type") == "LINE":
                missed += int(counter.get("missed"))
                covered += int(counter.get("covered"))
    else:
        for package in root.findall("package"):
            for klass in package.findall("class"):
                if not class_filter(klass.get("name")):
                    continue
                for counter in klass.findall("counter"):
                    if counter.get("type") == "LINE":
                        missed += int(counter.get("missed"))
                        covered += int(counter.get("covered"))
    total = missed + covered
    if total == 0:
        raise SystemExit(f"Relatório sem linhas contabilizadas: {report}")
    return 100 * covered / total


def pitest_scope(build_file: Path):
    """Monta o filtro de classes a partir do targetClasses do Pitest."""
    build = build_file.read_text()
    block = re.search(r"targetClasses\s*=\s*setOf\((.*?)\)", build, re.S)
    if not block:
        raise SystemExit("Não achei targetClasses no build.gradle.kts")
    globs = re.findall(r'"([^"]+)"', block.group(1))
    if not globs:
        raise SystemExit("targetClasses vazio; o escopo do núcleo ficaria indefinido")
    patterns = [re.compile("^" + g.replace(".", "/").replace("*", ".*") + "$") for g in globs]
    return lambda name: any(p.match(name) for p in patterns), globs


def mutation_score(report: Path):
    """Usa `detected`, que é o critério do próprio Pitest.

    Contar só `status == KILLED` subestima: mutante que morre por timeout ou
    erro de memória também é detectado. A diferença aparece justamente em
    máquina lenta — no CI, vários mutantes que morrem rápido localmente caem em
    TIMED_OUT, e o número despencava sem que a suíte tivesse piorado.
    """
    mutations = ET.parse(report).getroot().findall("mutation")
    detected = sum(1 for m in mutations if m.get("detected") == "true")
    return detected, len(mutations)


def smoke_scenarios(source: Path) -> int:
    return len(re.findall(r"^\s*test\(", source.read_text(), re.M))


def rounded(value: float) -> str:
    """Uma casa decimal, como os badges publicam."""
    return f"{value:.1f}"


# Tolerância que marca "isto é um piso": qualquer medição acima dele serve.
FLOOR = float("inf")


class Verdict:
    def __init__(self, tag: str, failed: bool, reason: str = ""):
        self.tag, self.failed, self.reason = tag, failed, reason


def compare(measured: float, published: float, tolerance: float) -> Verdict:
    """Publicar menos que o medido é conservador; publicar mais é mentira."""
    # Piso: medir acima é o esperado e nunca é defasagem, mas medir ABAIXO
    # continua sendo promessa não cumprida.
    if tolerance == FLOOR:
        if measured < published:
            return Verdict("PROMETE DEMAIS", True, "a medição não alcança o piso publicado")
        return Verdict("ok", False)
    if measured < published - tolerance:
        return Verdict("PROMETE DEMAIS", True, "o README anuncia mais do que a medição sustenta")
    # Defasado para baixo além de um ponto deixa de ser conservadorismo e vira
    # desatualização: o trabalho feito não aparece.
    if measured > published + max(tolerance, 1.0):
        return Verdict("DEFASADO", True, "a medição melhorou e o README ficou para trás")
    return Verdict("ok", False)


def find(pattern: str, text: str, what: str) -> str:
    match = re.search(pattern, text)
    if not match:
        raise SystemExit(f"Não encontrei no README: {what} (padrão {pattern!r})")
    return match.group(1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    parser.add_argument("--build-file", type=Path, default=Path("build.gradle.kts"))
    parser.add_argument("--kover-total", type=Path, required=True)
    parser.add_argument("--kover-unit", type=Path, required=True)
    parser.add_argument("--kover-integration", type=Path, required=True)
    parser.add_argument("--pitest", type=Path, required=True)
    parser.add_argument(
        "--smoke-source",
        type=Path,
        default=Path("src/smokeTest/kotlin/br/dev/colman/authorizer/smoke/SmokeTest.kt"),
    )
    args = parser.parse_args()

    readme = args.readme.read_text()
    in_scope, globs = pitest_scope(args.build_file)

    total = line_coverage(args.kover_total)
    unit = line_coverage(args.kover_unit)
    integration = line_coverage(args.kover_integration)
    core = line_coverage(args.kover_unit, in_scope)
    killed, mutants = mutation_score(args.pitest)
    mutation_pct = 100 * killed / mutants
    scenarios = smoke_scenarios(args.smoke_source)

    # (o que é, valor medido, valor publicado)
    #
    # A comparação não é de igualdade. O que não pode acontecer é o README
    # prometer MAIS do que a medição entrega; publicar um número conservador é
    # inofensivo. Isso também absorve o ruído real do Pitest, que mata um
    # mutante a mais ou a menos entre execuções por causa de timeout — com
    # igualdade exata, o build quebraria sozinho de vez em quando.
    checks = [
        ("badge de cobertura total", rounded(total),
         find(r"cobertura%20total-([\d.]+)%25", readme, "badge de cobertura total"), 0.15),
        ("tabela: cobertura total", rounded(total).replace(".", ","),
         find(r"\*\*Total \(unit \+ integração\)\*\* \| \*\*([\d,]+)%\*\*", readme, "tabela total"), 0.15),
        ("badge de cobertura unitária", rounded(unit),
         find(r"testes%20unit%C3%A1rios-([\d.]+)%25", readme, "badge unitário"), 0.15),
        ("tabela: cobertura unitária", rounded(unit).replace(".", ","),
         find(r"\| Unitários \| ([\d,]+)%", readme, "tabela unitários"), 0.15),
        ("badge de cobertura de integração", rounded(integration),
         find(r"integra%C3%A7%C3%A3o-([\d.]+)%25", readme, "badge integração"), 0.15),
        ("tabela: cobertura de integração", rounded(integration).replace(".", ","),
         find(r"\| Integração \| ([\d,]+)%", readme, "tabela integração"), 0.15),
        ("cobertura unitária no escopo do Pitest", rounded(core).replace(".", ","),
         find(r"suíte unitária cobre \*\*([\d,]+)%\*\*", readme, "cobertura do núcleo"), 0.15),
        # Piso, não medição pontual: o score de mutação depende da máquina
        # (timeouts em runner lento), então publicar o valor exato de uma
        # execução tornaria o gate instável. O README anuncia o mínimo, e o
        # gate cobra que a medição atual o sustente.
        ("piso de mutação no badge", str(round(mutation_pct)),
         find(r"mutantes%20mortos-(\d+)%25%2B", readme, "badge de mutação"), FLOOR),
        ("piso de mutação no texto", str(round(mutation_pct)),
         find(r"pelo menos \*\*(\d+)%\*\* dos mutantes", readme, "piso de mutação"), FLOOR),
        ("mutantes gerados", str(mutants),
         find(r"(\d+) mutantes gerados", readme, "mutantes gerados"), 0.0),
        ("cenários de fumaça", str(scenarios),
         find(r"fuma%C3%A7a-(\d+)%2F\d+%20cen%C3%A1rios", readme, "badge de fumaça"), 0.0),
    ]

    print(f"escopo do núcleo (do Pitest): {', '.join(globs)}")
    problems = []
    for what, measured, published, tolerance in checks:
        verdict = compare(float(measured.replace(",", ".")), float(published.replace(",", ".")), tolerance)
        print(f"  [{verdict.tag}] {what}: medido {measured}, README {published}")
        if verdict.failed:
            problems.append(f"{what}: README diz {published}, medição diz {measured} ({verdict.reason})")

    if problems:
        print(f"\n{len(problems)} número(s) publicados não se sustentam:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("\nOs números publicados se sustentam nos relatórios.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
