package br.com.mesamar.common

import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import tools.jackson.databind.ObjectMapper

data class ErroApi(
    val erro: String,
    val mensagem: String,
    val campos: Map<String, String> = emptyMap(),
    val temporario: Boolean = false,
)

/** Contrato aditivo: preserva campos específicos (como produtos indisponíveis) dos endpoints antigos. */
@RestControllerAdvice(basePackages = ["br.com.mesamar.staff", "br.com.mesamar.cliente", "br.com.mesamar.sinal"])
class ErrosApi(
    private val json: ObjectMapper,
    private val servletResponse: HttpServletResponse,
) : ResponseBodyAdvice<Any> {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DataIntegrityViolationException::class, OptimisticLockingFailureException::class)
    fun conflito(erro: Exception): ResponseEntity<ErroApi> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErroApi("conflito", Restricoes.mensagem(erro)))

    @ExceptionHandler(TransientDataAccessException::class)
    fun temporario(): ResponseEntity<ErroApi> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(
                "Retry-After",
                "3",
            ).body(ErroApi("temporario", "A operação está ocupada. Espere um pouco e tente de novo.", temporario = true))

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, IllegalArgumentException::class)
    fun formato(): ResponseEntity<ErroApi> = ResponseEntity.badRequest().body(ErroApi("validacao", "Confira os dados enviados."))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validacao(erro: MethodArgumentNotValidException): ResponseEntity<ErroApi> =
        ResponseEntity.badRequest().body(
            ErroApi(
                "validacao",
                "Confira os campos marcados.",
                erro.bindingResult.fieldErrors.associate {
                    it.field to
                        (it.defaultMessage ?: "Valor inválido.")
                },
            ),
        )

    @ExceptionHandler(Exception::class)
    fun inesperado(erro: Exception): ResponseEntity<ErroApi> {
        if (erro is org.springframework.web.ErrorResponse && erro.statusCode.is4xxClientError) {
            val status = erro.statusCode.value()
            val codigo =
                when (status) {
                    401, 403 -> "sessao-expirada"
                    404 -> "nao-encontrado"
                    409 -> "conflito"
                    else -> "validacao"
                }
            return ResponseEntity.status(erro.statusCode).body(ErroApi(codigo, "Confira a operação e os dados enviados."))
        }
        log.error("Erro na API: {}", erro.javaClass.simpleName)
        return ResponseEntity
            .status(
                HttpStatus.INTERNAL_SERVER_ERROR,
            ).body(ErroApi("temporario", "Não foi possível concluir agora. Tente de novo.", temporario = true))
    }

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val status = (response as? ServletServerHttpResponse)?.servletResponse?.status ?: servletResponse.status
        if (status < 400 || body == null || body is String) return body
        val dados =
            json
                .convertValue(body, Map::class.java)
                .entries
                .associate { it.key.toString() to it.value }
                .toMutableMap()
        dados.putIfAbsent("erro", if (status >= 500) "temporario" else "validacao")
        dados.putIfAbsent("mensagem", "Não foi possível concluir a operação.")
        dados.putIfAbsent("campos", emptyMap<String, String>())
        dados["temporario"] = status == 429 || status >= 500
        return dados
    }
}
