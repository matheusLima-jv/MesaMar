"""Valida o conteúdo sem interpretar caminhos fornecidos por um manifesto."""
import hashlib
import pathlib
import sys

arquivo = pathlib.Path(sys.argv[1])
nomes = [arquivo.name + s for s in ("", ".tabelas", ".financeiro")]
linhas = pathlib.Path(str(arquivo) + ".sha256").read_text().splitlines()
if len(linhas) != len(nomes):
    raise SystemExit("Manifesto incompleto")
for linha, nome in zip(linhas, nomes):
    esperado, separador, recebido = linha.partition("  ")
    if not separador or recebido != nome or len(esperado) != 64:
        raise SystemExit("Nome/hash inesperado no manifesto")
    with (arquivo.parent / nome).open("rb") as stream:
        calculado = hashlib.file_digest(stream, "sha256").hexdigest()
    if calculado != esperado:
        raise SystemExit("Conteúdo corrompido")
