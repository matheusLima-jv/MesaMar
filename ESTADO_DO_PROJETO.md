# Estado atual do MesaMar

Atualizado em 2026-10-06.

## 1. Regras de trabalho (valem sempre)

- **Um checkpoint por vez, e só avança com aprovação explícita do Matheus.** Ao fim de cada checkpoint: CI verde, relatório em `docs/relatorios/` no formato da seção B4 do [`docs/MESAMAR_PROMPT.md`](docs/MESAMAR_PROMPT.md) e itens novos no roteiro manual [`docs/testar-localmente.md`](docs/testar-localmente.md). **Pare e peça aprovação.**
- **Os testes manuais ficam para a entrega final:** o Matheus aprova com base no relatório e no CI. Por isso os testes automatizados precisam ser bons.
- **Não mergear na `main` sem ele pedir.** Uma branch por entrega, com PR para `main`.
- **Commits pequenos em Conventional Commits**, mensagens em português (é o que o histórico usa), com as linhas de coautoria da sessão. Rode `./gradlew ktlintFormat` antes de commitar.
- **Idioma:** interface e documentação em português do Brasil. Identificadores de código seguem o que já existe (domínio em português: `barraca`, `comanda`, `pedido`…). Use imports, não nomes completos no meio do código.
- **Isolamento entre barracas:** nenhuma URL traz id de barraca; todo endpoint novo ganha teste de isolamento.
- **Dinheiro em centavos (`Long`)**, nunca `Double`.
- **Segredos só no `.env`** (inclusive VAPID). Mantenha `.env.example` e `deploy/prod.env.example` atualizados.
- **Nunca** altere firewall (UFW), SSH, DNS nem Cloudflare. Produza arquivos e um passo a passo em [`docs/deploy.md`](docs/deploy.md); o Matheus executa.
- **Não invente requisitos.** O que for fácil de desfazer, decida e registre em ADR (`docs/adr/NNNN-…md`); o que for difícil de desfazer, pergunte. Proibido inventar depoimentos, números de clientes ou logos.
- **Estilo de resposta ao Matheus:** resposta direta primeiro, depois o porquê; seções curtas; números e trade-offs; quando ele pedir opinião, escolha um lado.
- Testes de backend precisam de Docker (Testcontainers). Para Gradle no Windows: JDK 21 em `C:/Program Files/Amazon Corretto/jdk21.0.12_8`.

O escopo completo (problema, personas, domínio, fases e portões) está em [`docs/MESAMAR_PROMPT.md`](docs/MESAMAR_PROMPT.md). O histórico antigo de fases e aprovações está em [estado anterior](docs/relatorios/estado-antes-revisao-81-110.md).

## 2. Onde o trabalho está

- **Na `main`:** Fases 0 a 5 e 7, Fase 6.1 (PR #7), revisão 61–110 com a fila offline 6.2 (PR #8), revisão 111–116 com o checkpoint 6.3 e banco V20 (PR #22) e as correções da auditoria geral com banco V21 (PR #27).
- **Correções da auditoria geral (na `main` pelo PR #27):** [relatório](docs/relatorios/correcoes-auditoria-geral.md): dez bugs corrigidos, recusa de novas ações offline de outro dia e revisão/cancelamento de pedidos ainda não pagos. Banco passa para **V21**, com filas separadas por autor e idade da ação mais antiga. Decisões: [ADR 0022](docs/adr/0022-correcoes-auditoria-geral.md).
- **Branch `correcoes-revisao-externa` (PR #28), fora da `main`:** o PR #28 foi mergeado na branch `correcoes-auditoria-geral` **depois** de o PR #27 já ter entrado na `main`, então estes dois commits não chegaram à `main`. A `correcoes-terceira-revisao` parte deles. [Correções da revisão externa](docs/relatorios/correcoes-revisao-externa.md): perfil `prod` deixa de exigir V19 fixo (o app não iniciaria), troca de PIN não desfaz desativação, pagamento incerto não é esquecido em sessão vencida/limite e celular com relógio errado volta a lançar ([ADR 0023](docs/adr/0023-idade-da-acao-no-aparelho.md)). Três achados P3 registrados para depois.
- **Branch `correcoes-terceira-revisao`, a partir da anterior:** [correções da terceira revisão externa](docs/relatorios/correcoes-terceira-revisao.md). Dos 13 achados, 10 procedem, 2 procedem em parte e 1 não é bug. Principais: o último login podia desfazer uma troca de senha; a sessão passa a valer pela credencial conferida, e não pela hora do login; o pagamento repetido mostrava outro valor; uma recusa vista na tela travava a fila do aparelho; o Turnstile não tinha prazo. [ADR 0024](docs/adr/0024-sessao-pela-credencial-e-recusa-vista.md).
- **Próximo passo:** aprovação do Matheus para a `correcoes-terceira-revisao`, que leva para a `main` também as correções da revisão externa; depois, preparação do piloto ([lista de execução](docs/preparacao-piloto.md)).

| Área | Disponível |
|---|---|
| Cliente | Check-in por QR, cardápio, carrinho, pedidos, extrato, Pix e recuperação de envio com o mesmo identificador |
| Equipe | Clientes, lançamento de pedidos (com aviso e registro de exceção), fila, pagamentos, revisão/cancelamento de pedidos não pagos, Continuar conta após pagamento parcial, avisos, PIN e sessão por barraca |
| Offline (6.2/6.3) | Check-ins e lançamentos guardados em IndexedDB antes do envio, ordem, reabertura, reenvio idempotente com espera crescente, interrupção em conflito, indicador de sincronização e última sincronização de cada aparelho no painel |
| Painel | Contas por convite, barraca, equipe e aparelhos, guarda-sóis, cardápio, regras e relatório |
| Banco | V18: integridade, concorrência, autoria/histórico e outbox; V19: medições e saúde operacional; V20: sincronização dos aparelhos; V21: sincronia por autor e idade das pendências; execução e migração com contas distintas |
| Operação | Backup consistente com hashes dos valores e totais financeiros, trava, cópia S3 criptografada, restauração em banco separado e resgate em falha |
| Entrega | Imagem única testada/publicada, digest, manifesto de compatibilidade, inventário de dependências e checagens de segredos |

Regras alteradas afetam os próximos pedidos das contas existentes; pedidos já gravados preservam seus preços. Ações offline não confirmam pagamento nem avanço da fila. Novas ações criadas em outro dia são recusadas após atualizar o app; reenvios já confirmados permanecem idempotentes. QR criado offline só pode ser usado pelo cliente depois da sincronização.

Metas aprovadas: perda máxima de 24 horas (RPO), retorno em até 2 horas (RTO), cópia externa em S3 compatível a cada backup. A rotina proposta é a cada 12 horas. A retenção padrão de snapshots locais/externos é 14 dias; dados offline da equipe expiram em 3 dias; a lista de aparelhos guarda 30 dias.

## 3. Pendências que dependem de ambiente externo

Nenhuma foi marcada como comprovada sem evidência:

- S3/Restic reais: endpoint, bucket e credenciais fora do Git, cron, retenção de versões e recuperação a partir do S3.
- Os dois monitores externos (disponibilidade e backup atrasado), com recebimento de cada alerta comprovado.
- Ensaio de perda total, com RPO e RTO medidos.
- Safari offline, instalação, push, Pix, VoiceOver/TalkBack e zoom em aparelhos físicos.
- SMTP e suporte reais, adiados por orientação do usuário (exemplos fictícios em [preparação do piloto](docs/preparacao-piloto.md)).
- Publicação e implantação, só com autorização.

Leia também [deploy](docs/deploy.md), [testar localmente](docs/testar-localmente.md) e os relatórios [61–80](docs/relatorios/revisao-61-80.md), [81–110](docs/relatorios/revisao-81-110.md), [111–115](docs/relatorios/revisao-111-115.md) e [116](docs/relatorios/revisao-116.md).

A VPS, firewall, SSH, DNS e Cloudflare não foram alterados. Segredos, `.env`, chave Restic e credenciais S3 permanecem fora do Git.
