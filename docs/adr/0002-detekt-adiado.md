# ADR 0002 — detekt adiado; apenas ktlint no CI por enquanto

- **Status:** aceito (revisar quando o detekt 2.0 for estável)
- **Data:** 2026-09-27

## Contexto

O plano pede ktlint **e** detekt no CI. Na data desta decisão:

- A última versão estável do detekt é da linha 1.23.x, compilada contra Kotlin 2.0, e não é compatível de forma confiável com projetos em Kotlin 2.2 (que é o baseline do Spring Boot 4).
- A linha que suporta Kotlin 2.2+ (`dev.detekt` 2.0.0) só existe em **alpha** (2.0.0-alpha.6, agosto de 2026).

## Decisão

- **ktlint** (plugin `org.jlleitschuh.gradle.ktlint` 14.2.0) roda no CI e bloqueia o PR.
- **detekt** fica fora até sair a versão 2.0.0 estável. Não vale colocar uma ferramenta alpha como portão obrigatório do CI.

## Consequências

- Perdemos, por ora, as regras de complexidade e code smells do detekt. O risco é baixo com o código ainda pequeno.
- Ação futura: ao sair o detekt 2.0.0 estável, adicionar o plugin `dev.detekt` e um `detekt.yml` baseline.
