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

`transactions.id` (o `transactionId` do path) é PRIMARY KEY. O fluxo:

1. Fast-path (leitura avulsa, fora de transação): se a transação já existe, devolve o
   resultado gravado (replay).
2. Em uma única transação de banco: UPDATE de saldo e INSERT do resultado. Se o INSERT
   violar a PK (corrida entre retentativas), a transação inteira sofre rollback,
   desfazendo a aplicação de saldo, e o serviço devolve o resultado gravado pela
   requisição vencedora.

Recusas também são gravadas: a decisão de um `transactionId` é imutável, e o replay
devolve resposta idêntica byte a byte (timestamp truncado em micros, precisão do
timestamptz).

Replay só vale para payload idêntico (conta, tipo, valor e moeda). Mesmo
`transactionId` com payload divergente indica bug do cliente na geração de ids;
responder o resultado original em silêncio esconderia o bug, então a resposta é
409 (conflito de idempotência) sem alterar saldo.

## Alternativas consideradas

- **Lock otimista com versão** (read, compute, CAS, retry): mais código, mais round-trips
  e degrada sob contenção exatamente onde mais importa (conta quente).
- **SELECT FOR UPDATE**: serializa igual, mas com duas idas ao banco e mais tempo de lock.
- **Serializable isolation**: paga retry de serialização em todo o tráfego para proteger
  um único ponto que o UPDATE condicional já protege.

## Consequências

- O `transactionId` é único no serviço inteiro, não por conta: ele identifica o recurso no
  path (`POST /transactions/{transactionId}`), então o mesmo id não pode designar
  transações diferentes em contas diferentes. A consequência prática é que um id já
  gravado responde 409 para qualquer outra conta, e por isso quem gera os ids precisa de
  valores imprevisíveis (UUID aleatório, como o contrato define). Com ids sequenciais ou
  derivados de dado público, um cliente poderia ocupar antecipadamente o id que outro
  usaria e negar-lhe a transação — o 409 estaria correto, e a operação legítima perdida
  do mesmo jeito.
- **A imutabilidade de `transactions` é garantida pela aplicação, não pelo banco**: não
  existe caminho de código que faça UPDATE ou DELETE ali, só INSERT e leitura. O banco
  não impõe isso — no compose a aplicação conecta como superusuário, então uma sessão
  `psql` reescreveria o histórico sem deixar rastro. Em produção o controle seria de
  infraestrutura: role da aplicação com INSERT e SELECT em `transactions` e sem UPDATE
  nem DELETE, o que transforma a disciplina de código em garantia verificável.
- O saldo autoritativo vive no banco; a aplicação nunca calcula saldo em memória.
- Provado por testes de integração: 50 débitos concorrentes contra saldo para 10 aprovam
  exatamente 10; N requisições concorrentes com o mesmo id debitam uma vez.
