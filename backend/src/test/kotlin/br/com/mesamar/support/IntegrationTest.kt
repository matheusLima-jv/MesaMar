package br.com.mesamar.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Anotação padrão dos testes de integração: sobe a aplicação inteira numa porta
 * aleatória, com perfil `test` e PostgreSQL via Testcontainers.
 * Todas as classes que a usam compartilham o mesmo contexto (e o mesmo container).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
