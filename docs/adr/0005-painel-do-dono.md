# ADR 0005 — Painel do dono: páginas no servidor, convites e uma barraca por conta

- **Status:** aceito
- **Data:** 2026-09-30

## Contexto

A Fase 2 dá ao dono da barraca um lugar para configurar a operação antes de abrir: dados, regras, chave Pix e, nos próximos checkpoints, atendentes, guarda-sóis e cardápio. O plano original previa um PWA (`web-staff`) para tudo o que o dono faz. Mas quase todas essas telas são formulários usados poucas vezes, enquanto a fila de pedidos (Fase 3) é usada em tempo real o dia inteiro.

## Decisão

- **Configuração em páginas Thymeleaf no servidor, em `/painel`.** Mesmo visual da landing, sem JavaScript, e funciona em qualquer celular. O PWA (`web-staff`) fica para a fila de pedidos em tempo real da Fase 3, que é onde ele traz ganho (notificação, som e uso offline).
- **Cadastro só por convite no piloto.** O admin gera o convite no `/admin`, a partir de um lead ou avulso. O link aparece **uma vez**, com botão para mandar pelo WhatsApp. O convite vale 14 dias e serve para uma conta. No banco fica só o **SHA-256** do token.
- **Login do dono separado do admin.** São duas cadeias do Spring Security, cada uma com seu `AuthenticationManager` e **sua própria chave na sessão** (`MESAMAR_ADMIN` e `MESAMAR_PAINEL`). A senha de um não entra no outro, e entrar numa área não derruba o login da outra. Sair de qualquer uma encerra a sessão inteira (comportamento padrão, mais seguro).
- **Senha:** de 10 a 72 caracteres (o limite do BCrypt), sem regras de "símbolo obrigatório". A tela sugere uma frase curta. O hash é BCrypt via `DelegatingPasswordEncoder`, e ele sai do objeto de login logo depois de autenticar.
- **Troca de senha por e-mail.** O link vale 1 hora e uma vez só, e pedir outro invalida os anteriores. A tela responde igual exista ou não a conta, com limite de 5 pedidos por IP a cada 15 minutos. **Sem SMTP configurado, o e-mail vai para o log** (útil em desenvolvimento). Em produção, é preciso configurar o SMTP (`SPRING_MAIL_*`).
- **Uma barraca por dono** (`usuario.barraca_id`) no MVP. Nenhuma URL do painel tem id de barraca: tudo parte do usuário logado. Assim, não existe como tentar abrir a barraca de outro dono.
- **O código da barraca (slug) é gerado uma vez e não muda** quando o nome muda, porque os QR codes impressos vão depender dele.
- **Pix guardado normalizado** (CPF e CNPJ só com dígitos, celular em +55…, e-mail em minúsculas), com nome até 25 e cidade até 15 caracteres, sem acento: são os limites do BR Code. A validação já aceita o **CNPJ alfanumérico**.
- **Dinheiro sempre em centavos (`Long`).** O formulário aceita "150", "150,00" e "1.250,50".

## Consequências

- Um dono com duas barracas precisaria de duas contas até existir a relação N:N (fora do MVP).
- Sem SMTP em produção, o "esqueci a senha" não chega ao dono. É um item do checklist de deploy.
- Quando o piloto abrir para qualquer pessoa, o convite pode virar opcional sem mudar o modelo: basta um cadastro sem `convite`.
