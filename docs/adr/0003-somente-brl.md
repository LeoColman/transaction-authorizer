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

- Nenhum risco de somar moedas distintas.
- Evolução para multi-moeda: saldo por (conta, moeda) e validação contra a moeda da
  conta; o modelo `Money` do domínio já impede operações entre moedas diferentes.
