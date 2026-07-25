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

## Alternativas consideradas

### Coroutines Kotlin (WebFlux ou controllers `suspend`)

A escolha idiomática em Kotlin seria coroutines. Rejeitada porque elas não atacam o
gargalo desta aplicação, que é **JDBC bloqueante**:

- **Coroutine que chama código bloqueante bloqueia a thread carrier.** Para não
  bloquear seria preciso despachar cada consulta para `Dispatchers.IO`, um pool de
  threads de plataforma com teto — exatamente o limite que se queria remover. A
  thread virtual desmonta da carrier ao bloquear em I/O, então o mesmo código
  sequencial escala sem `suspend`, sem dispatcher e sem anotação nenhuma.
- **Coroutines só pagariam seu custo com driver reativo (R2DBC)**, e o preço cairia
  justamente sobre o que garante a correção: SQL explícito com `@Transactional`
  declarativo (ADR-0002) e Flyway. Trocar por `TransactionalOperator` e propagação
  de contexto reativo adiciona atrito sem fortalecer a invariante — quem a garante
  é o `UPDATE` condicional no banco, não a camada de acesso.
- **Stack trace.** Exceção em código suspenso ou reativo chega fragmentada; com
  virtual threads permanece linear, do controller ao statement. Em plantão de
  sistema financeiro, isso vale mais do que parece.
- **Onde coroutines brilham, aqui não existe**: fan-out concorrente, streaming e
  cancelamento estruturado. Cada requisição é linear — recebe, dois statements,
  responde.

Não são excludentes: coroutines rodam sobre virtual threads sem problema. A decisão
é que, neste desenho, elas adicionariam vocabulário sem adicionar capacidade.

### WebFlux com stack reativo completo

Entregaria throughput equivalente ao custo de contaminar todo o código com tipos
reativos, além de exigir R2DBC pelos motivos acima. Virtual threads entregam o
mesmo benefício de concorrência mantendo o código legível por qualquer pessoa da
squad.

## Consequências

- Sem entidades JPA, mapeamento é manual (RowMappers); aceitável no tamanho do modelo.
- Virtual threads exigem atenção a pinning (blocos synchronized longos); o código não
  usa synchronized e o driver Postgres JDBC é compatível.
- O ganho depende de o I/O ser realmente bloqueante e barato de desmontar: medido em
  carga, 567 mil requisições a ~7.5k req/s com p99 de 45 ms e pico de 1,2 GiB de heap.
