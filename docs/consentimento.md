# Texto de consentimento do check-in

No check-in, a equipe lê (ou mostra) ao cliente o texto abaixo e marca a caixa "O cliente concorda…". O app envia, junto com o check-in, a **versão** do texto que estava na tela, e o servidor a grava em `cliente.consentimento_versao`. Assim, para qualquer cadastro dá para saber **qual texto** foi apresentado, e não só que houve um "sim".

- Quando o texto mudar de sentido (o que é guardado, para quê, por quanto tempo), **crie uma versão nova**: troque `CONSENTIMENTO_VERSAO` em `web-staff/src/consentimento.ts` **e** `CONSENTIMENTO_VERSAO_ATUAL` em `CheckinService.kt` (o teste confere que são o mesmo valor) e acrescente a versão aqui. **Nunca edite o texto de uma versão que já foi usada.**
- `desconhecida`: o check-in veio de um app em cache sem a versão (ou com valor fora do formato). `NULL`: cadastro de antes deste registro existir (até 2026-10-03).

## Versões

### `2026-10-v1` (desde 2026-10-03)

> O cliente concorda em informar o nome (e o WhatsApp) só para identificar a comanda e receber o link.

Complementada pela política de privacidade (`/privacidade`, seção 5), que a equipe pode mostrar ao cliente.

Antes do registro de versão, o texto da tela era o mesmo desta versão.
