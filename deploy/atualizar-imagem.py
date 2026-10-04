"""Prepara uma .env com o digest escolhido, sem interpretar segredos como código."""
import os
import pathlib
import re
import sys
import tempfile


def preparar(caminho, imagem):
    if not re.fullmatch(r"ghcr.io/[a-z0-9_/.-]+@sha256:[a-f0-9]{64}", imagem):
        raise ValueError("Imagem exige digest validado")
    caminho = pathlib.Path(caminho).resolve(strict=True)
    original = caminho.read_text(encoding="utf-8")
    linhas = original.splitlines(keepends=True)
    novo = []
    encontrou = False
    for linha in linhas:
        if re.match(r"^\s*(?:export\s+)?MESAMAR_IMAGE\s*=", linha):
            if not encontrou:
                novo.append(f"MESAMAR_IMAGE={imagem}\n")
            encontrou = True
        else:
            novo.append(linha)
    if not encontrou:
        if novo and not novo[-1].endswith("\n"):
            novo[-1] += "\n"
        novo.append(f"MESAMAR_IMAGE={imagem}\n")
    fd, temporario = tempfile.mkstemp(prefix=".imagem-", dir=caminho.parent)
    try:
        if hasattr(os, "fchmod"):
            os.fchmod(fd, 0o600)
        else:
            os.chmod(temporario, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as arquivo:
            arquivo.write("".join(novo))
            arquivo.flush()
            os.fsync(arquivo.fileno())
    except BaseException:
        os.unlink(temporario)
        raise
    return temporario


if __name__ == "__main__":
    try:
        print(preparar(sys.argv[1], sys.argv[2]))
    except Exception:
        raise SystemExit("Não foi possível preparar a configuração de imagem. Confira arquivo e permissões da .env.")
