# Offline, recuperação e entrega da imagem validada

Data: 2026-10-03. Complementa a ADR 0019. Esquema resultante: V19.

## Contratos e operações offline

Dados recebidos e restaurados passam por validação em tempo de execução: estrutura, versão, limites, identificadores, duplicatas, validade e inteiros monetários representáveis com segurança em JavaScript. Um cast TypeScript não confirma esses contratos. Resposta interrompida depois dos cabeçalhos ou JSON incompatível mantém o resultado como desconhecido.

A equipe persiste check-ins e lançamentos em IndexedDB antes de enviar. O identificador da operação e o token do check-in são gerados uma vez. A sequência é atribuída numa transação do armazenamento; o processamento usa exclusão entre abas quando Web Locks está disponível e idempotência no servidor. Há no máximo 200 operações por contexto, válidas por três dias. Cache e ações pertencem à combinação barraca/autor. Ao trocar a sessão, callbacks e respostas da geração anterior deixam de atualizar a tela.

Reenvio respeita a ordem: conflito ou falha desconhecida bloqueia a cabeça da fila; 408/429/falhas transitórias preservam a ação para nova tentativa. Uma recusa exige revisão ou descarte explícito. Pagamento e avanço da fila exigem confirmação do servidor. Cadastro local tem indicação de sincronização; seu QR só funciona para o cliente após a confirmação.

Suspender a página encerra timers/SSE sem revogar a sessão restaurável. Logout remove a sessão local imediatamente e grava a necessidade de revogação no servidor; reabrir sem rede mostra login e preserva essa revogação. Uma nova autenticação aguarda a revogação anterior. A confirmação do servidor prevalece sobre cache.

## Backup externo e recuperação

Metas aprovadas pelo usuário: RPO de 24 horas e RTO de duas horas; destino S3 compatível. A rotina proposta roda a cada 12 horas e copia cada backup validado, cifrado pelo Restic. Cron e configuração do bucket precisam ser instalados no ambiente real. A retenção padrão é 14 dias, validada antes de qualquer rotação.

Compose é a fonte única de configuração. Banco/administrador vêm do container em execução; arquivos de segredo não são executados como shell. Backup/restauração compartilham uma trava e usam nomes exclusivos. Dump, hashes de todas as colunas e totais financeiros vêm do mesmo snapshot PostgreSQL.

A rotina restaura o dump num banco separado antes de considerar sucesso; valida arquivos, conteúdo e totais antes de parar produção. Uma troca mantém o banco anterior como resgate. Falha na ativação tenta recolocá-lo; interrupção abrupta exige o procedimento manual documentado. O ensaio externo mede o download e a restauração, mas o RTO completo inclui reconstruir o host e verificar o negócio.

A retenção Restic é aplicada ao conjunto da tag `mesamar`, com `--group-by ''`, para não manter grupos antigos ao mudar host, caminhos ou RPO. Prune é seguido de verificação. Lifecycle por idade não deve excluir objetos correntes, pois snapshots recentes podem referenciar chunks antigos. Limite versões não correntes e multipart no provedor. Chaves de recuperação precisam existir fora da VPS.

Disponibilidade e ausência de backup têm checks externos separados. O marcador de sucesso só avança após copiar e verificar o repositório. O monitor externo precisa alertar também quando a VPS deixa de enviar heartbeats.

## Diagnóstico e entrega

Request IDs, métricas de requisição/latência/erro, conexões SSE e pool, e últimos resultados das tarefas internas permitem correlacionar falhas sem registrar tokens ou dados pessoais. A medição de sinal inclui amostras malsucedidas e métricas reais de abertura, pedido, fila e pagamento, com participação voluntária.

A CI constrói a imagem uma vez, executa E2E e análise de vulnerabilidades sobre ela, e publica o mesmo ID por commit/digest. O manifesto da imagem declara os esquemas de origem e execução. Nesta versão: banco vazio ou origem V17–V19, migração até V19, execução apenas V19. A implantação bloqueia manifestos incompatíveis e para o app antes de migrar.

Rollback exige compatibilidade declarada da imagem anterior com o esquema atual, ou recuperação do backup anterior junto da imagem correspondente. Não há pressuposto de que toda migração futura seja aditiva. Actions e bases Docker têm referências por SHA/digest; Wrapper tem checksum; dependências, imagem e segredos são verificados regularmente.

## Limites da validação

Testes locais usam PostgreSQL real descartável e repositório Restic cifrado local. Acesso, lifecycle e recuperação do S3 real, recebimento de alertas e RTO da perda total dependem das credenciais e infraestrutura externas.

Playwright WebKit verifica interface e transporte no iPhone emulado, mas o suporte a service workers desse runner é limitado ao Chromium. A reabertura offline do Safari, instalação, push, Pix e leitores de tela exigem aparelhos reais. Captura de screenshots WebKit injeta um estilo bloqueado pela CSP; nesse projeto mantemos o diagnóstico por traces DOM sem screenshots, preservando a CSP da aplicação.

Referências: [retenção Restic](https://restic.readthedocs.io/en/stable/060_forget.html), [S3 no Restic](https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html), [service workers no Playwright](https://playwright.dev/docs/service-workers).
