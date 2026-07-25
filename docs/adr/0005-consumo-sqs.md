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
- **Mensagem malformada sai do fluxo na primeira tentativa e vai para a DLQ**, com log e
  métrica (`authorizer.sqs.messages.invalid`), sem derrubar o lote. Devolvê-la à fila
  seria redelivery infinito: o defeito está no conteúdo, e a tentativa seguinte falharia
  igual. O redrive do SQS não cobre este caso porque ele só move depois de
  `maxReceiveCount` falhas do consumidor — aqui a mensagem é reconhecida como
  irrecuperável de imediato, então a cópia é publicada explicitamente. Falha ao publicar
  não derruba o lote: as mensagens boas que vieram junto não devem ser reprocessadas por
  causa de uma que já se sabe perdida.
- **Falha de infraestrutura devolve o lote à fila** e conta como tentativa: depois de
  `maxReceiveCount` a própria fila move a mensagem para a DLQ. Este é o caminho para o
  banco fora do ar, e é o que impede uma mensagem de circular para sempre consumindo
  poll e log.
- **Idempotência no destino**: redelivery e duplicatas são inofensivos porque o insert
  ignora contas existentes, nunca sobrescrevendo saldo.
- **Fila inexistente derruba o startup (`queueNotFoundStrategy: FAIL`)**: o default da
  lib (CREATE) criaria silenciosamente uma fila vazia diante de um nome errado em
  produção, e o consumidor ficaria ouvindo o lugar errado para sempre com health check
  verde. Fail-fast transforma erro de configuração em falha visível de deploy. O perfil
  `local` mantém CREATE por conveniência com LocalStack.

- **DLQ com `maxReceiveCount: 5`**, provisionada junto com a fila. A política de redrive
  é atributo da FILA, não da aplicação: no compose ela nasce no hook de inicialização do
  LocalStack (`localstack/init-queues.sh`), e em produção viria do IaC. Provisionar
  explicitamente também evita que a fila seja criada implicitamente pelo primeiro
  consumidor — o que daria uma fila sem redrive, com a DLQ existindo mas nunca recebendo
  nada.

## Em produção (não implementado aqui)

- Alarme de profundidade da DLQ e de idade da mensagem mais antiga (consumo atrasado =
  contas indisponíveis para transacionar).
- Reprocessamento assistido a partir da DLQ depois de corrigir a origem; hoje as
  mensagens ficam lá para inspeção, sem automação de retorno.

## Consequências

- Contas podem levar alguns segundos entre a publicação e a disponibilidade; inerente à
  integração assíncrona definida pelo desafio.
- Se a mesma conta chegar duas vezes com dados diferentes, vale a primeira (contrato de
  unicidade do id pertence ao sistema de abertura de contas).
