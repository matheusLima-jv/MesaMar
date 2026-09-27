// Projeto raiz: apenas declara os plugins (sem aplicar) para que os subprojetos
// usem as mesmas versões do version catalog.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
}
