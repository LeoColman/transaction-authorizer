# ADR-0005: Consumo da fila de abertura de contas

## Status

Aceito

## Contexto

Fila SQS standard: entrega at-least-once, possivelmente fora de ordem, 100.000 mensagens
no cenário do desafio e abertura de contas 24/7. O consumo não pode perder conta nem
travar em mensagem venenosa.

## Decisão

- **Lote de 10 mensagens por poll** (máximo do SQS) com concorrência configurável
  (`SQS_MAX_CONCURRENT_MESSAGES`, padrão 100) e inserção em lote no banco
  (`ON CONFLICT DO NOTHING`), drenando as 100 mil mensagens rapidamente.
- **Ack ON_SUCCESS**: falha de infraestrutura (banco fora) lança exceção, o lote não é
  ackado e volta após o visibility timeout. Esse é o retry natural do SQS; o SDK AWS usa
  retry adaptativo com backoff exponencial e full jitter nas chamadas.
- **Mensagem malformada é descartada individualmente** com log e métrica
  (`authorizer.sqs.messages.invalid`), sem derrubar o lote. Redelivery infinito de uma
  mensagem venenosa não registra conta nenhuma; descartar com rastro é o menor dano.
- **Idempotência no destino**: redelivery e duplicatas são inofensivos porque o insert
  ignora contas existentes, nunca sobrescrevendo saldo.

## Em produção (não implementado aqui)

- **DLQ com `maxReceiveCount: 5`** e alarme de profundidade: mensagens venenosas e lotes
  repetidamente falhos ganham triagem manual em vez de descarte.
- Alarme de idade da mensagem mais antiga (consumo atrasado = contas indisponíveis para
  transacionar).

## Consequências

- Contas podem levar alguns segundos entre a publicação e a disponibilidade; inerente à
  integração assíncrona definida pelo desafio.
- Se a mesma conta chegar duas vezes com dados diferentes, vale a primeira (contrato de
  unicidade do id pertence ao sistema de abertura de contas).
