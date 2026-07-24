package br.dev.colman.authorizer.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZoneId

@Configuration
class TimeZoneConfig {

    /**
     * Fuso usado apenas na apresentação de datas na API (ISO 8601 com offset,
     * ex.: 2025-07-08T15:57:55-03:00). Armazenamento é sempre UTC.
     */
    @Bean
    fun presentationZone(@Value("\${authorizer.presentation-timezone:America/Sao_Paulo}") zone: String): ZoneId =
        ZoneId.of(zone)
}
