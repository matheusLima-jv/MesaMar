# ADR 0021 — Última sincronização dos aparelhos, reenvio com espera crescente e IndexedDB sem Dexie

- **Status:** proposto (aguarda aprovação do Matheus)
- **Data:** 2026-10-03. Fecha o checkpoint 6.3. Esquema resultante: V20.

## Contexto

A Fase 6 pede que o dono veja a hora da última sincronização de cada aparelho, que o reenvio use espera crescente e que a fila offline use Dexie. A fila offline (item 110, ADR 0020) foi construída com IndexedDB direto, reenviava a cada 15 s fixos e não informava nada ao servidor. Um celular esquecido sem sinal podia guardar pedidos por horas sem que ninguém percebesse; pedido que não chega à fila nem à conta é o tipo de "conta errada" que reprova o Portão B.

## Decisão

1. **Informe por aparelho.** O app cria um identificador aleatório por aparelho (guardado no próprio aparelho, sem dado pessoal) e, ao fim de cada rodada de sincronia que alcançou o servidor, envia `POST /api/staff/sincronizacao` com o número de ações pendentes e recusadas da sessão. Barraca e autor vêm da sessão; a URL não leva id. O servidor guarda uma linha por barraca e aparelho (V20, `sincronizacao_aparelho`), com a família do aparelho (Android, iPhone/iPad, computador), nunca o User-Agent completo.
2. **Frequência.** Sem envio e sem mudança na fila, no máximo um informe por minuto; depois de enviar ações, o informe sai na hora. Falha no informe não afeta a fila.
3. **O que o dono vê.** No painel, **Equipe → Aparelhos da equipe**: quem, tipo do aparelho, última sincronização e o que ficou guardado. Ação recusada, ou ação pendente há mais de 15 minutos, aparece primeiro e com "Atenção". Aparelho sem sincronizar há mais de 30 dias sai da lista (retenção).
4. **Reenvio com espera crescente.** Sem falhas, a cada 15 s; a cada rodada que termina com ações pendentes ou sem servidor, a espera dobra até 2 minutos, com variação de ±20% para os aparelhos não voltarem juntos. O evento de sinal voltando zera a espera e tenta na hora.
5. **Limite da fila por sessão.** As 200 ações por aparelho valem por barraca/autor, como a ADR 0020 já dizia; pendências de outra sessão no mesmo aparelho não bloqueiam a atual. Um registro corrompido é descartado sem impedir a próxima ação.
6. **IndexedDB sem Dexie.** A fila usa poucas operações (incluir com sequência, listar, remover, marcar conflito) dentro de transações próprias. Dexie acrescentaria dependência ao app que precisa abrir rápido em rede ruim, sem resolver nada que a implementação atual não resolva. A especificação da Fase 6 citava Dexie; esta ADR registra a troca.

## Convergência entre aparelhos

Lançamentos e check-ins são só de inclusão e têm id gerado no aparelho: o servidor reconhece reenvios e não duplica. Dois aparelhos lançando na mesma comanda criam dois pedidos distintos, cada um uma vez. Mudanças de estado (em preparo, entregue, cancelado, pagamento, fechamento) só acontecem com o servidor confirmando; estados terminais não voltam atrás, então o primeiro evento terminal vence. Coberto pelo E2E `offline-convergencia.spec.ts`.

## Consequências

- Implantação com migração (V19 → V20): backup validado antes, como em [deploy](../deploy.md). O manifesto da imagem passa a declarar destino e execução V20.
- O dono sabe qual celular conferir, mas o servidor não vê o conteúdo das ações que ainda estão no aparelho, só a quantidade.
- Um aparelho que nunca alcança o servidor depois do login não aparece na lista. Isso é aceito: ele também não tem ações de outro aparelho.
