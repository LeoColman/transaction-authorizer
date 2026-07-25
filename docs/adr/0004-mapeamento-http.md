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
| Conta inexistente | 404 | Problem Details (RFC 9457) |
| Conta desabilitada | 422 | Problem Details |
| Moeda não suportada | 422 | Problem Details |
| Mesmo `transactionId` com payload divergente | 409 | Problem Details |
| Payload inválido | 400 | Problem Details |
| `Accept` sem `application/json` | 406 | Problem Details (rejeitado ANTES de executar a autorização) |
| Método/rota/content-type inválidos | 405/404/415 | Problem Details do framework |

Exceção conhecida: requisições rejeitadas antes do DispatcherServlet não passam pelo
MVC e não respondem Problem Details. TRACE bloqueado responde o formato de erro
padrão do Boot; a valve de erro do host é substituída por uma sem corpo (nenhuma
página HTML nem versão de servidor); e falhas de parse no próprio conector (ex.:
`%00` no path) respondem a página mínima do Tomcat, que não passa por valve alguma
mas também não identifica versão de servidor.

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
