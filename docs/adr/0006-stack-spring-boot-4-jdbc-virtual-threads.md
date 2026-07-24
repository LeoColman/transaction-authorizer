# ADR-0006: Spring Boot 4, JdbcClient sem JPA, MVC com virtual threads

## Status

Aceito

## Contexto

Aplicação de missão crítica com I/O dominante (banco + fila), squad precisa de
produtividade e o mercado bancário de stack auditável.

## Decisão

- **Spring Boot 4.0 (Spring Framework 7)**: linha atual com suporte OSS ativo;
  actuator, health probes, graceful shutdown e Testcontainers de primeira classe.
- **JdbcClient + Flyway, sem JPA**: o coração do sistema são 3 statements SQL onde o
  próprio SQL é a garantia de correção (ADR-0002). ORM esconderia exatamente o que
  precisa estar visível (dirty checking, flush timing, cache de 1º nível) e não paga seu
  custo em um modelo de 2 tabelas.
- **WebMVC + virtual threads** (`spring.threads.virtual.enabled=true`): concorrência de
  I/O alta com código bloqueante simples e stack traces legíveis. WebFlux daria o mesmo
  throughput ao custo de complexidade reativa em todo o código; virtual threads entregam
  o benefício sem o custo.
- **Kotest + MockK + Testcontainers + Gatling**: expressividade Kotlin nos testes,
  infraestrutura real (Postgres/LocalStack) nos testes de integração, carga como código
  no mesmo repositório.

## Consequências

- Sem entidades JPA, mapeamento é manual (RowMappers); aceitável no tamanho do modelo.
- Virtual threads exigem atenção a pinning (blocos synchronized longos); o código não
  usa synchronized e o driver Postgres JDBC é compatível.
