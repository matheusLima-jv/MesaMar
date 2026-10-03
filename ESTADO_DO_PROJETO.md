# Estado atual do MesaMar

Atualizado em 2026-10-03. Correções 61–110 concluídas neste checkout, com [relatório](docs/relatorios/revisao-81-110.md); matriz E2E completa aprovada (47/47). As correções propostas foram executadas e a documentação foi atualizada. Entregues no PR da branch `revisao-61-110`, que só é mergeado quando o Matheus pedir. Não foi publicado nesta revisão. O registro antigo de fases e aprovações foi arquivado em [estado anterior](docs/relatorios/estado-antes-revisao-81-110.md), para preservar o contexto sem apresentar etapas antigas como pendências atuais.

| Área | Disponível |
|---|---|
| Cliente | Check-in por QR, cardápio, carrinho, pedidos, extrato, Pix e recuperação de envio com o mesmo identificador |
| Equipe | Clientes, lançamento de pedidos, fila, pagamentos, avisos, PIN e sessão por barraca |
| Offline 6.2/6.3 | Check-ins e lançamentos persistidos antes do envio em IndexedDB, contexto de barraca/autor, ordem, reabertura, reenvio idempotente, interrupção em conflito e indicador de sincronização |
| Painel | Contas por convite, barraca, equipe, guarda-sóis, cardápio, regras e relatório |
| Banco | V18: integridade, concorrência, autoria/histórico e outbox; V19: medições e saúde operacional; execução e migração com contas distintas |
| Operação | Backup consistente com hashes dos valores e totais financeiros, trava, cópia S3 criptografada, restauração em banco separado e resgate em falha |
| Entrega | Imagem única testada/publicada, digest, manifesto de compatibilidade, inventário de dependências e checagens de segredos |

Regras alteradas afetam os próximos pedidos das contas existentes; pedidos já gravados preservam seus preços. Ações offline não confirmam pagamento nem avanço da fila. QR criado offline só pode ser usado pelo cliente depois da sincronização.

Metas aprovadas: perda máxima de 24 horas (RPO), retorno em até 2 horas (RTO), cópia externa em S3 compatível a cada backup. A rotina proposta é a cada 12 horas, com margem para falhas antes de exceder o RPO. A retenção padrão de snapshots locais/externos é 14 dias; dados offline da equipe expiram em 3 dias.

Leia [deploy](docs/deploy.md), [testar localmente](docs/testar-localmente.md) e os relatórios [61–80](docs/relatorios/revisao-61-80.md) e [81–110](docs/relatorios/revisao-81-110.md). Os relatórios distinguem testes executados das verificações que exigem credenciais S3, monitor externo e aparelhos reais. A migração foi ensaiada com dados anteriores; um banco vazio não é a única verificação.

A VPS, firewall, SSH, DNS e Cloudflare não foram alterados. Segredos, arquivo `.env`, chave Restic e credenciais S3 permanecem fora do Git. Não houve merge, push ou implantação nesta revisão.
