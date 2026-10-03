import datetime as dt
import importlib.util
import pathlib
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

spec = importlib.util.spec_from_file_location("monitorar", pathlib.Path(__file__).parents[2] / "deploy/monitorar.py")
monitorar = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitorar)


class AlertasTest(unittest.TestCase):
    def test_indisponibilidade_e_ausencia_de_backup_sao_distintas(self):
        eventos = []
        status = [200]

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                eventos.append(self.path)
                self.send_response(status[0] if self.path == "/health" else 200)
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}' if self.path == '/health' else b'OK')

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        agora = dt.datetime.now(dt.timezone.utc)
        try:
            with tempfile.TemporaryDirectory() as pasta:
                marca = pathlib.Path(pasta, "ultima-copia-externa.txt")
                base = f"http://127.0.0.1:{server.server_port}"
                config = {"healthUrl": base + "/health", "diretorio": pasta, "rpoSegundos": 86400,
                          "pingDisponibilidade": base + "/servico", "pingBackupMonitor": base + "/backup"}
                marca.write_text(agora.isoformat())
                status[0] = 503
                r = monitorar.verificar(config, agora)
                self.assertFalse(r["disponivel"])
                self.assertTrue(r["backupRecente"])
                self.assertIn("/servico/fail", eventos)
                self.assertIn("/backup", eventos)
                eventos.clear()
                status[0] = 200
                marca.write_text((agora - dt.timedelta(hours=28)).isoformat())
                r = monitorar.verificar(config, agora)
                self.assertTrue(r["disponivel"])
                self.assertFalse(r["backupRecente"])
                self.assertIn("/servico", eventos)
                self.assertIn("/backup/fail", eventos)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
