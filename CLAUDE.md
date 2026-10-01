# MesaMar: instruções para o Claude Code

**Antes de qualquer tarefa, leia `ESTADO_DO_PROJETO.md`** (na raiz). Ele diz onde o trabalho parou, o que está verificado e o que não está, o próximo passo exato e as armadilhas já conhecidas.

Resumo das regras (detalhes no `ESTADO_DO_PROJETO.md`, seção 1, e no `docs/MESAMAR_PROMPT.md`):

- Trabalhe **um checkpoint por vez**. Ao terminar: CI verde, relatório em `docs/relatorios/fase-N.md` e roteiro manual em `docs/testar-localmente.md`. Depois **pare e peça aprovação ao Matheus**.
- Não faça merge na `main` sem ele pedir. A Fase 2 está na branch `fase-2-contas` (PR #3).
- Interface e documentação em português do Brasil. Commits em Conventional Commits.
- Isolamento entre barracas: nenhuma URL traz id de barraca, e todo endpoint novo ganha teste de isolamento.
- Dinheiro em centavos (`Long`). Segredos só no `.env`. Não mexa em firewall, SSH nem Cloudflare.
- Rode `./gradlew ktlintFormat` antes de commitar. Testes de backend precisam de Docker (Testcontainers).
