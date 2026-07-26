# ADR-0003: Somente BRL nesta versão

## Status

Aceito

## Contexto

O contrato pede valor com código de moeda ISO 4217, mas o payload de abertura de contas
não traz moeda e todos os exemplos do desafio usam BRL. Misturar moedas em um saldo único
seria incorreto; conversão cambial está claramente fora do escopo.

## Decisão

Toda conta nasce em BRL. Transações em outra moeda são rejeitadas com 422 e Problem
Details (`unsupported-currency`). O código de moeda trafega no contrato e é persistido
por conta e por transação, deixando o caminho aberto para multi-moeda.

## Consequências

- Nenhum risco de somar moedas distintas, por dois motivos independentes: só existe uma
  moeda no enum, e o saldo é alterado por aritmética no banco (`UPDATE ... balance ±
  :amount`), nunca somando dois `Money` em memória.
- Evolução para multi-moeda custa mais do que trocar o enum, e o que existe hoje NÃO é
  meio caminho andado. `Money.plus`/`minus` recusam operar moedas diferentes, mas o fluxo
  de autorização não passa por eles: a comparação que faltaria é entre a moeda da
  transação e a da conta, e ela precisaria ser criada em `AuthorizationExecutor`, antes
  de aplicar crédito ou débito. Some-se a isso saldo por (conta, moeda), a moeda na
  mensagem de abertura de conta e um status HTTP para a divergência.
