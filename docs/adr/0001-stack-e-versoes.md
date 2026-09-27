# ADR 0001 — Stack e versões da Fase 0

- **Status:** aceito
- **Data:** 2026-09-27

## Contexto

O plano (`docs/MESAMAR_PROMPT.md`, seção B2) pede Kotlin + Spring Boot na última versão estável (4.x), JDK 21, Gradle Kotlin DSL com version catalog, PostgreSQL + Flyway e dois fronts em TypeScript + Vite servidos pelo próprio Spring.

## Decisão

| Item | Versão | Observação |
|---|---|---|
| Spring Boot | **4.1.1** | Versão "current" em spring.io na data desta decisão. Não foi necessário cair para 3.5.x. |
| Kotlin | **2.2.21** | Linha 2.2 é o baseline do Spring Boot 4. O plugin do Spring Boot alinha o `kotlin.version` do BOM à versão do plugin Kotlin. |
| JDK | 21 (toolchain) | LTS. Spring Boot 4.1 aceita de 17 a 26. |
| Gradle | 8.14.3 (wrapper) | Spring Boot 4.1 exige 8.14+ ou 9.x. |
| Testcontainers | gerenciado pelo BOM do Boot (linha 2.x) | Módulos com o prefixo `testcontainers-` e pacote `org.testcontainers.postgresql`. |
| Vite / TypeScript | 8.x / 5.9 | TypeScript 7 (reescrita nativa) foi evitado por enquanto por estabilidade do ecossistema. |

Pontos específicos do Spring Boot 4 que afetam o código:

- Starters modulares: `spring-boot-starter-webmvc` (no lugar do antigo `-web`) e `spring-boot-starter-flyway` (a autoconfiguração do Flyway saiu do núcleo).
- Jackson 3 (`tools.jackson.module:jackson-module-kotlin`).
- Anotações de teste do MVC ficam em `spring-boot-webmvc-test`.

### Build dos fronts integrado ao Gradle

Tarefas `Exec` simples (`npm ci` e `npm run build`) em vez de um plugin Node para o Gradle: menos uma dependência, e o comportamento é o mesmo que o desenvolvedor roda à mão. O `dist/` de cada front é copiado para `static/staff/` e `static/cliente/` dentro do JAR.

No Docker, os fronts são buildados num estágio Node separado e o Gradle recebe `-PskipFrontendBuild`, que pula o `npm` mas mantém a cópia do `dist/`.

## Consequências

- Quem roda `./gradlew build` precisa de Node 22 instalado (ou usar o Docker).
- A atualização para Kotlin 2.3 e JDK 25 fica para depois do piloto, com ADR próprio.
