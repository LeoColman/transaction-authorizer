# ADR-0004: Mapeamento HTTP do resultado da autorização

## Status

Aceito

## Contexto

O desafio define os resultados APROVADO/RECUSADO e o envelope de resposta, mas não o
mapeamento HTTP. Recusa por saldo insuficiente é resultado de negócio legítimo, não erro
do cliente nem do servidor, e o cliente precisa distinguir recusa de falha.

## Decisão

| Cenário | HTTP | Corpo |
|---|---|---|
| Aprovada (`SUCCEEDED`) | 200 | Envelope do desafio |
| Recusada por saldo (`FAILED`) | 422 | Envelope do desafio, saldo intacto |
| Crédito que excederia o teto de saldo (`FAILED`) | 422 | Envelope do desafio, saldo intacto |
| Conta inexistente | 404 | Problem Details (RFC 9457) |
| Conta desabilitada | 422 | Problem Details |
| Moeda não suportada | 422 | Problem Details |
| Mesmo `transactionId` com payload divergente | 409 | Problem Details |
| Payload inválido | 400 | Problem Details |
| `Accept` sem `application/json` | 406 | Problem Details do framework (rejeitado ANTES de executar a autorização) |
| Corpo maior que o limite (16 KiB) | 413 | Problem Details (rejeitado ANTES de executar a autorização) |
| Corpo grande **sem `Content-Length`** em requisição rejeitada por content-type/método/Accept | 415/405/406 | O status da rejeição, sem 413: ninguém abre o corpo, que é descartado pelo container |
| Método/rota/content-type inválidos | 405/404/415 | Problem Details do framework |
| Pool de conexões esgotado / timeout no banco | 503 + `Retry-After` | Problem Details |

Rejeições antes do controller têm dois níveis. O filtro de tamanho da aplicação
(`RequestSizeLimitFilter`) roda na cadeia de servlet e responde Problem Details
próprio (413). Já rejeições no nível do container/conector, que nem chegam ao MVC,
respondem no formato do Boot/Tomcat: TRACE bloqueado e timeout de requisição
incompleta (408) respondem o formato de erro padrão do Boot; a valve de erro do
host é substituída por uma sem corpo (nenhuma página HTML nem versão de servidor);
e falhas de parse no próprio conector (ex.: `%00` no path) respondem a página
mínima do Tomcat, que não passa por valve alguma mas também não identifica versão
de servidor. O 408 é contido por `server.tomcat.connection-timeout` (10s), para
que um corpo que nunca chega não prenda conexão e thread por minutos. É um teto de
inatividade, não de duração total: um cliente que envia bytes devagar mantém a
conexão aberta, e limitar isso (taxa mínima, duração máxima) cabe ao API Gateway
da arquitetura proposta.

- **O teto de corpo é decidido pelo `Content-Length`, quando ele existe.** O filtro
  roda antes do MVC, então um corpo declarado acima do teto responde 413 mesmo que
  o content-type esteja errado — o tamanho é motivo suficiente de recusa, e o
  Tomcat encerra a conexão anunciando `Connection: close`.
- **Sem `Content-Length`, o teto só vale para quem lê o corpo.** Requisição chunked
  que o MVC rejeita antes disso (content-type, método ou `Accept` incompatíveis)
  tem o corpo descartado pelo container, no limite padrão dele, e a conexão é
  preservada. Alinhar esse limite ao teto da aplicação foi testado e revertido:
  acima dele o Tomcat encerra a conexão, mas a resposta de erro já está comitada e
  não há como anunciar `Connection: close` — a requisição seguinte do cliente se
  perderia em silêncio. Descartar alguns KB extras custa menos, e esse corpo nunca
  chega à memória da aplicação.
- **Toda recusa vem do UPDATE condicional, e nenhuma vira 5xx.** O débito é
  condicionado ao piso zero e o crédito ao teto da faixa suportada
  (`NUMERIC(19,2)`, 17 dígitos inteiros). Sem a condição no crédito, um saldo
  perto de 10^17 faria o Postgres responder `numeric field overflow` e a
  requisição viraria 500; pior, como o rollback não grava nada, a retentativa com
  o mesmo `transactionId` estouraria para sempre, sem nunca alcançar estado
  terminal. Zero linhas afetadas é resultado de negócio (`FAILED` gravado, 422,
  saldo intacto), então a retentativa replica o resultado em vez de repetir a
  falha.
- **503 separa saturação de defeito**: esgotamento de pool e timeout de consulta são
  transitórios e ocorrem antes de qualquer escrita, então a retentativa é segura e o
  cliente é informado disso (`Retry-After`). Respondê-los como 500 misturaria falta de
  capacidade com bug de aplicação no mesmo alarme de 5xx.
- Recusa usa **422 com o envelope completo** (não Problem Details): o resultado é
  distinguível tanto pelo status HTTP (clientes/gateways não retentam 4xx) quanto pelo
  campo `status` (clientes que só olham o corpo).
- **Validação estrutural precede o conflito de idempotência**: retentativa com o mesmo
  `transactionId` cujo payload divergente é também estruturalmente inválido (moeda
  desconhecida, tipo inexistente, valor fora do formato) responde o erro de validação
  (400/422), não 409. O 409 sinaliza payload válido que diverge do original; payload
  inválido nem chega a ser comparável.
- Retentativas idempotentes devolvem o mesmo status e corpo originais, com header
  `X-Idempotent-Replay: true`.
- Conta inexistente é 404 (e não 422): o recurso referenciado não existe e o envelope
  exigiria um saldo que não há. É a ramificação "Transação recusada" do fluxo do desafio
  para conta inexistente, sinalizada da forma padrão do HTTP.

## Alternativas consideradas

- **200 para tudo** com `status` no corpo: comum em autorizadores legados (ISO 8583
  sobre HTTP), mas esconde recusas de gateways, métricas e retries padrão.
- **Problem Details para recusa**: perderia o envelope com saldo atual que o desafio
  exige na resposta.
