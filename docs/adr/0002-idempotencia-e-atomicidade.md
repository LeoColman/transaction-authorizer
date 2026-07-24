# ADR-0002: UPDATE condicional + PK de transactions como mecanismo de consistência

## Status

Aceito

## Contexto

Duas corridas ameaçam o autorizador:

1. Débitos concorrentes na mesma conta podem negativar saldo ou perder atualização.
2. Retentativas (mesmo `transactionId`) podem debitar/creditar duas vezes.

## Decisão

### Atomicidade do saldo

Débito é decidido pelo banco em um único statement:

```sql
UPDATE accounts SET balance = balance - :v
WHERE id = :id AND balance >= :v
RETURNING balance
```

Zero linhas afetadas = recusado. O lock de linha do Postgres serializa débitos e créditos
concorrentes; a condição `balance >= :v` garante a invariante sem leitura prévia
(read-modify-write é proibido no caminho de decisão).

### Idempotência

`transactions.id` (o `transactionId` do path) é PRIMARY KEY. O fluxo, em uma única
transação de banco:

1. Fast-path: se a transação já existe, devolve o resultado gravado (replay).
2. Aplica o UPDATE de saldo.
3. INSERT do resultado. Se violar a PK (corrida entre retentativas), a transação de banco
   inteira sofre rollback, desfazendo a dupla aplicação de saldo, e o serviço devolve o
   resultado gravado pela requisição vencedora.

Recusas também são gravadas: a decisão de um `transactionId` é imutável, e o replay
devolve resposta idêntica byte a byte (timestamp truncado em micros, precisão do
timestamptz).

## Alternativas consideradas

- **Lock otimista com versão** (read, compute, CAS, retry): mais código, mais round-trips
  e degrada sob contenção exatamente onde mais importa (conta quente).
- **SELECT FOR UPDATE**: serializa igual, mas com duas idas ao banco e mais tempo de lock.
- **Serializable isolation**: paga retry de serialização em todo o tráfego para proteger
  um único ponto que o UPDATE condicional já protege.

## Consequências

- O saldo autoritativo vive no banco; a aplicação nunca calcula saldo em memória.
- Provado por testes de integração: 50 débitos concorrentes contra saldo para 10 aprovam
  exatamente 10; N requisições concorrentes com o mesmo id debitam uma vez.
