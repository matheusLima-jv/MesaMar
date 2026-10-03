# Relatório — correções 61–110

Salvo em **2026-10-03, 14:01 (America/Sao_Paulo / 17:01 UTC)**. Trabalho interrompido por solicitação explícita do usuário. Atualizado em **2026-10-03** após a retomada: a matriz E2E completa foi repetida e aprovou. Este relatório não declara publicação nem aprovação do Matheus.

## Execução das correções propostas

Todas as correções propostas na revisão (itens 61 a 110) foram **executadas** neste lote, e a documentação correspondente foi **atualizada**: README, `ESTADO_DO_PROJETO.md`, [deploy](../deploy.md), [testar localmente](../testar-localmente.md), as ADRs 0019 e 0020 e os relatórios [61–80](revisao-61-80.md) e este. As pendências que dependem de ambiente externo (S3 real, alertas reais, aparelhos físicos, publicação) não são correções de código e estão listadas ao final, sem serem marcadas como comprovadas.

## Situação preservada

Branch preservada: `fase-6-offline`; HEAD anterior às alterações: `88506ab04e7d0da241f520809b5d5e87e5f0e710`. As alterações estão no checkout, incluindo arquivos novos ainda não rastreados. **Não houve commit, push, merge ou deploy.** Não descartar alterações existentes, não executar `git reset --hard` nem `git clean`. A VPS, SSH, firewall, DNS e Cloudflare não foram alterados. Nenhum segredo de produção foi adicionado.

O lote 61–80 está descrito em [revisao-61-80.md](revisao-61-80.md) e [ADR 0019](../adr/0019-retencao-concorrencia-e-entregas.md). O lote seguinte tem decisões na [ADR 0020](../adr/0020-offline-recuperacao-e-entrega-validada.md), procedimentos em [deploy.md](../deploy.md) e comandos em [testar-localmente.md](../testar-localmente.md).

Preferências já aprovadas: **S3 compatível**, backups externos cifrados com Restic, **RPO 24 horas / RTO 2 horas**. Rotina preparada para backup a cada 12 horas, cópia externa a cada backup e retenção padrão de 14 dias. Não perguntar essas preferências novamente. As credenciais e a conta do monitor externo não foram fornecidas.

## Implementações do lote 81–110

| Item | Alteração no checkout |
|---|---|
| 81 | Validadores de contratos compartilhados; armazenamento versionado, limites, duplicatas, validade e cópias imutáveis. Corpo inválido/interrompido não confirma operação. Valores monetários exigem inteiro seguro, sem limite artificial de Int para os totais Long. |
| 82 | README, estado, documentação operacional e textos do painel atualizados; registro antigo de fases arquivado. Regras afetam próximos pedidos das contas existentes. |
| 83 | Nova leitura a cada medição, ausência como null, amostras com falha e telemetria voluntária de abertura/pedido/fila/pagamento. V19, coleta limitada e idempotente. |
| 84 | Inicialização, backup, verificação e recuperação Restic para S3 compatível; credenciais fora do Git. Acesso ao bucket real ainda não ensaiado. |
| 85 | Configuração efetiva do Compose centralizada; banco/administrador lidos do container, sem executar .env como shell. |
| 86 | Retenção inteira de 1–90 dias, sem zeros iniciais, validada antes da rotação; RPO/RTO positivos. |
| 87 | Trava compartilhada, nomes com segundos/UUID e arquivos temporários exclusivos. |
| 88 | Checksums, hashes canônicos de todas as colunas e totais financeiros, obtidos no mesmo snapshot do dump. |
| 89 | Validação em banco separado antes da parada; banco de resgate e recuperação da troca em falha. Procedimento para interrupção abrupta documentado. |
| 90 | Metas aprovadas, ensaios cronometrados e recuperação externa preparados. RTO da perda total depende do host/rede reais. |
| 91 | Checks independentes de disponibilidade e ausência de backup; teste local comprova sinais distintos. Recebimento do alerta real pendente. |
| 92 | Guia de diagnóstico/reinício de container unhealthy e reversão por imagem compatível. |
| 93 | Últimos sucessos/falhas persistidos para anonimização, encerramento, recepção de eventos e envio de notificações. |
| 94 | Request IDs/MDC, métricas protegidas de erro/latência/atividade, conexões SSE e pool, sem corpos/tokens em diagnóstico. |
| 95 | CI constrói uma vez, testa e publica o mesmo ID; imagem por commit/digest, artefato de versão e revisão em actuator/info. |
| 96 | Manifesto de compatibilidade: banco vazio ou origem V17–V19; execução V19. Gate de implantação e plano de rollback com backup quando necessário. |
| 97 | npm audit, inventário de bibliotecas de execução, Trivy, Gitleaks e atualização periódica. Tomcat atualizado para 11.0.26 e Jackson para 3.1.7 após achados corrigíveis. |
| 98 | SHA-256 da distribuição/JAR do Gradle Wrapper; Actions e imagens base identificados por SHA/digest. |
| 99 | ESLint comum e testes unitários de carrinho, armazenamento, contratos, falhas, fila offline, stores e ciclo de vida. |
| 100 | Integração concorrente para convites/e-mail, reset, pagamentos parciais, fechamento forçado, criação de barraca, limite de cadastro, cardápio e reordenação. Corrigida leitura JPA antiga de reset depois de aguardar trava. |
| 101 | Proxy E2E corta corpo depois dos cabeçalhos e commit real; reenvio mantém identificador e cria uma única comanda. |
| 102 | Testes de primeira instalação/reabertura, atualização, quota, revogação e isolamento dos caches cliente/equipe; E2E de reabertura em Chromium. Safari offline físico pendente. |
| 103 | Descarte de respostas de gerações antigas, troca de contexto, renovação de CSRF, logout offline e reabertura com revogação pendente; testes de stores e modais. |
| 104 | Painel dividido em quatro cenários com dados próprios, fixtures e esperas por condição. |
| 105 | HTTP esperado registrado por status/caminho/contexto; falha de rede correlacionada com requestfailed, URL/contexto/tempo. Erros inesperados continuam reprovando. |
| 106 | Fixture acompanha todas as páginas/contextos até o encerramento do cenário. |
| 107 | E2E_ADMIN_PASSWORD ausente provoca falha de configuração; prova negativa executada. |
| 108 | Upgrade com dados V17 → V19 compara valores de contas, pedidos, itens, pagamentos e credenciais. |
| 109 | Chrome/Pixel 7, WebKit/iPhone e desktop estreito; teclado, foco de modal, controles e largura de 190 px. Instalação, push, Pix e leitores de tela em aparelhos reais pendentes. |
| 110 | Check-ins e lançamentos persistidos antes do envio, ordem atômica, token estável, reabertura offline, conflito na cabeça da fila, isolamento de contexto e indicador de sincronização. |

## Verificações concluídas

- **Backend: 475 testes, zero falhas/erros/ignorados**; ktlintCheck e inventário de dependências aprovados. Log: `backend/build/revisao-final-validacao.log`; XMLs em `backend/build/test-results/test`.
- **Frontends: 25 testes unitários** (14 cliente, 11 equipe), ESLint, TypeScript e builds aprovados, inclusive no build da imagem final. Os testes incluem service worker, dados inválidos, interrupção de corpo, quota e encerramento offline da sessão.
- **E2E completo da imagem final: 46 aprovados e uma falha** em 47 cenários. A falha veio da classificação de mensagens WebKit durante falta de rede, após o fluxo funcional completar. Log: `backend/build/e2e-final-validacao.log`.
- A fixture foi corrigida: console e requestfailed podem chegar fora de ordem; a correlação ocorre no encerramento, na mesma URL/contexto e janela de um segundo. **Reexecução isolada do cenário WebKit aprovada**, log `backend/build/webkit-rede-validacao.log`.
- **Matriz E2E completa repetida após a correção da fixture: 47 de 47 aprovados** (7,3 min, imagem final em `localhost:18082`), log `backend/build/e2e-matriz-final.log`.
- **Trivy da imagem final: zero vulnerabilidades HIGH/CRITICAL corrigíveis**, relatório `backend/build/trivy-imagem-final.json`, gerado às 16:59:58 UTC. A primeira tentativa atingiu timeout; a segunda terminou aprovada com cache e timeout de 15 minutos. Vulnerabilidades sem correção disponível não entram nesse gate.
- **Gitleaks: nenhum achado** nas fontes finais e nos 144 commits do histórico. Exceções limitadas a valores exatos de duas fixtures VAPID, por caminho e valor; não são exceções gerais para produção.
- npm audit dos três projetos: nenhum achado na execução desta revisão. Sintaxe YAML/TOML, SHA-256 do Wrapper e `git diff --check` conferidos.
- **Recuperação PostgreSQL real aprovada**: hashes/totais/restrições, retenção inválida recusada, trava concorrente, dump corrompido recusado antes da parada, divergência financeira e retorno do banco anterior após falha injetada. Último ensaio local: 4 segundos, log `backend/build/ensaio-backup-final.log`.
- **Restic cifrado local aprovado**: restauração idêntica, leitura completa, senha incorreta recusada e remoção de snapshot vencido após mudar host/tags. Harness incorporado à CI em `.github/scripts/testar-restic.sh`. Não equivale a testar o S3 real.
- Teste de alertas distintos aprovado: `.github/scripts/testar-alertas.py`. Ausência de credenciais E2E reprovou como esperado: `backend/build/e2e-sem-credenciais.log`.

WebKit do Playwright não oferece o mesmo ciclo de service worker offline que Chromium. O projeto iPhone bloqueia service workers e não executa `offline-reabertura.spec.ts`; interface e transporte continuam sendo testados. Screenshotter WebKit injeta CSS bloqueado pela CSP: capturas foram desativadas apenas nesse projeto, mantendo traces DOM e a CSP da aplicação. Não ignorar genericamente erros de CSP/CORS.

## Ambiente preservado, agora parado

Workspace: `C:\Users\james\OneDrive\Desktop\mesaMar\core\mesamar`.

- Container `mesamar-revisao-81-110`: PostgreSQL descartável, porta local 55432, banco `mesamar`. Foi **parado**, sem remoção de dados.
- Container `mesamar-revisao-app`: app descartável, porta local 18082, mesma imagem final. Foi **parado**.
- Imagem local `mesamar-revisao:81-110`, ID inspecionado: `sha256:54af5d68191ee27668708db887d8af0a48545e5b0ba232dd99c7901b7673db35`. Não é digest publicado no GHCR. Exportação analisada: `backend/build/imagem-validada.tar`.
- Servidor Java antigo da revisão, porta 18081/PID 30068, encerrado. Nenhum teste/scanner ficou em execução ao salvar este checkpoint.
- Relatórios, cache Trivy, dumps e fixtures locais permanecem em diretórios ignorados de build/test-results. Não adicionar esses artefatos ao Git. `deploy/__pycache__` ainda precisa ser removido; não tem código de produto.

## Encerramento

Matriz E2E repetida (47/47) e `deploy/__pycache__` removido. Backend (475 testes), frontends, Trivy, Gitleaks e recuperação já estavam aprovados e não foram repetidos, pois não houve mudança de produto. Containers descartáveis parados ao final.

## Pendentes que dependem de ambiente externo

- Preencher endpoint/bucket e credenciais S3/Restic fora do Git; inicializar o repositório, instalar cron, aplicar proteção/retenção de versões não correntes e recuperar desde o S3 real.
- Configurar os dois checks externos e comprovar recebimento de alertas de indisponibilidade e backup atrasado separadamente.
- Ensaiar perda total com provisionamento, download e validação do negócio; medir RPO real e RTO total de duas horas.
- Validar Safari offline, instalação, push, Pix, VoiceOver/TalkBack e zoom real em aparelhos físicos.
- Executar o workflow de publicação/implantação quando autorizado; nenhuma imagem foi publicada nesta revisão.

Esses pendentes não foram silenciosamente marcados como comprovados. Restam apenas esses pendentes externos; não repetir a pergunta sobre destino ou metas de recuperação.
