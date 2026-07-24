# ADR-0001: PostgreSQL como banco de dados

## Status

Aceito

## Contexto

O autorizador mantém saldo de contas sob duas invariantes inegociáveis: saldo nunca
negativo e nenhuma atualização perdida sob concorrência. O desafio pede alta volumetria,
consistência e justificativa de tradeoffs.

## Decisão

PostgreSQL, acessado com SQL explícito (JdbcClient) e migrações Flyway.

## Motivadores

- **Consistência é o requisito dominante**: autorização de saldo é o caso clássico de
  ACID. Um `UPDATE` condicional atômico decide débito no próprio banco, sem janela de
  corrida (ver ADR-0002). No teorema CAP, priorizamos CP: melhor recusar em partição do
  que aprovar duas vezes o mesmo dinheiro.
- **Transação multi-tabela**: alterar saldo e registrar a autorização precisam ser
  atômicos. Em Postgres é uma transação; em bancos BASE seria uma saga/complexidade.
- **Volumetria**: o ponto quente é 1 linha por conta; UPDATEs de linha única com índice
  PK sustentam dezenas de milhares de TPS em hardware modesto. Escala de leitura via
  réplicas; escala de escrita via particionamento por `account_id` (chave natural: toda
  operação é por conta), com Citus/particionamento nativo quando necessário.
- **Operação madura**: RDS Multi-AZ, failover automático, tooling e conhecimento de
  equipe abundantes em ambiente bancário.

## Alternativas consideradas

- **DynamoDB**: conditional writes dariam a mesma invariante e escala horizontal
  "infinita". Rejeitado nesta fase: transação entre tabelas é mais limitada (TransactWrite
  com custos), modelagem de consultas futuras (extrato) menos flexível e consistência
  forte custa o dobro de RCUs. Seria o próximo candidato se a escala de escrita superasse
  o particionamento de Postgres.
- **MongoDB**: transações multi-documento existem, mas sem vantagem sobre Postgres aqui
  e com consistência histórica mais fraca. Sem motivador.

## Consequências

- Escrita é verticalmente limitada por instância até particionar; aceitável e endereçado
  no plano de escala.
- CHECK constraints (`balance >= 0`) ficam como defesa em profundidade da invariante.
