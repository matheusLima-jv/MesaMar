"""Testes locais: somente arquivos temporários, sem Compose ou configuração real."""
import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("imagem", pathlib.Path(__file__).resolve().parents[2] / "deploy/atualizar-imagem.py")
imagem = importlib.util.module_from_spec(spec)
spec.loader.exec_module(imagem)
DIGEST = "ghcr.io/teste/mesamar@sha256:" + "a" * 64


class PersistenciaImagemTest(unittest.TestCase):
    def test_substitui_duplicatas_e_preserva_segredos_sem_executar(self):
        with tempfile.TemporaryDirectory() as pasta:
            env = pathlib.Path(pasta) / "config.env"
            segredo = "SEGREDO_FICTICIO='$(nao_executar) `$literal`'\n"
            env.write_text(segredo + "MESAMAR_IMAGE=antiga\nexport MESAMAR_IMAGE=outra\n", encoding="utf-8")
            novo = pathlib.Path(imagem.preparar(env, DIGEST))
            self.assertEqual(segredo + f"MESAMAR_IMAGE={DIGEST}\n", novo.read_text(encoding="utf-8"))
            self.assertIn("MESAMAR_IMAGE=antiga", env.read_text(encoding="utf-8"))

    def test_adiciona_chave_ausente_e_quebra_de_linha(self):
        with tempfile.TemporaryDirectory() as pasta:
            env = pathlib.Path(pasta) / "config.env"
            env.write_text("DADO_FICTICIO=123", encoding="utf-8")
            novo = pathlib.Path(imagem.preparar(env, DIGEST))
            self.assertEqual(f"DADO_FICTICIO=123\nMESAMAR_IMAGE={DIGEST}\n", novo.read_text(encoding="utf-8"))

    def test_recusa_tag_mutavel_antes_de_criar_arquivo(self):
        with tempfile.TemporaryDirectory() as pasta:
            env = pathlib.Path(pasta) / "config.env"
            env.write_text("DADO_FICTICIO=123", encoding="utf-8")
            with self.assertRaises(ValueError):
                imagem.preparar(env, "ghcr.io/teste/mesamar:latest")
            self.assertEqual([env], list(pathlib.Path(pasta).iterdir()))


if __name__ == "__main__":
    unittest.main()
