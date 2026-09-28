package br.com.mesamar.common

/** Praias do Guarujá atendidas no piloto. A ordem é a exibida nos formulários. */
enum class Praia(
    val label: String,
) {
    ENSEADA("Enseada"),
    PITANGUEIRAS("Pitangueiras"),
    ASTURIAS("Astúrias"),
    TOMBO("Tombo"),
    PERNAMBUCO("Pernambuco"),
    GUAIUBA("Guaiúba"),
    OUTRA("Outra praia"),
    ;

    companion object {
        fun fromCode(code: String?): Praia? = entries.firstOrNull { it.name == code }
    }
}
