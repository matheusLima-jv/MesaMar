"""Dois sinais independentes: serviço disponível e cópia externa recente. Sem URLs/segredos nos logs."""
import datetime as dt
import json
import os
import pathlib
import shlex
import subprocess
import urllib.request


def ping(url, sucesso):
    if not url:
        raise RuntimeError("Monitor externo não configurado")
    destino = url.rstrip("/") + ("" if sucesso else "/fail")
    with urllib.request.urlopen(destino, timeout=10) as resposta:
        return resposta.status == 200 and resposta.read(128).strip() == b"OK"


def verificar(config, agora=None):
    agora = agora or dt.datetime.now(dt.timezone.utc)
    disponivel = False
    try:
        with urllib.request.urlopen(config["healthUrl"], timeout=8) as resposta:
            disponivel = resposta.status == 200 and json.load(resposta).get("status") == "UP"
    except Exception:
        pass
    recente = False
    try:
        marca = pathlib.Path(config["diretorio"], "ultima-copia-externa.txt").read_text().strip()
        ultimo = dt.datetime.fromisoformat(marca.replace("Z", "+00:00"))
        idade = (agora - ultimo).total_seconds()
        recente = 0 <= idade <= int(config["rpoSegundos"]) + int(config.get("toleranciaSegundos", 0))
    except Exception:
        pass
    entregues = []
    for chave, ok in (("pingDisponibilidade", disponivel), ("pingBackupMonitor", recente)):
        try:
            entregues.append(ping(config.get(chave, ""), ok))
        except Exception:
            entregues.append(False)
    return {"disponivel": disponivel, "backupRecente": recente, "sinaisEntregues": all(entregues)}


if __name__ == "__main__":
    comando = shlex.split(os.environ.get("MESAMAR_COMPOSE", "docker compose"))
    for variavel, opcao in (("MESAMAR_COMPOSE_FILE", "-f"), ("MESAMAR_ENV_FILE", "--env-file")):
        if os.environ.get(variavel):
            comando += [opcao, os.environ[variavel]]
    resultado = subprocess.run(comando + ["config", "--format", "json"], capture_output=True, check=True)
    config = json.loads(resultado.stdout)["x-operacao"]
    estado = verificar(config)
    print(json.dumps(estado))
    raise SystemExit(0 if all(estado.values()) else 1)
