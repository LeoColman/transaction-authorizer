#!/bin/sh
# Provisiona as filas antes de a aplicação e o gerador subirem.
#
# Sem isto, a fila nasceria implicitamente (o gerador cria se não achar, e o
# perfil local usa queueNotFoundStrategy=CREATE) e sairia SEM redrive policy:
# a DLQ existiria mas nada chegaria nela. maxReceiveCount é atributo da FILA,
# não da aplicação, então é aqui que ele mora — em produção, no IaC.
set -e

REGION="${AWS_DEFAULT_REGION:-sa-east-1}"
QUEUE="${QUEUE_NAME:-conta-bancaria-criada}"
DLQ="${QUEUE}-dlq"
MAX_RECEIVE="${SQS_MAX_RECEIVE_COUNT:-5}"

awslocal sqs create-queue --queue-name "$DLQ" --region "$REGION"

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$DLQ" --region "$REGION" --output text)" \
  --attribute-names QueueArn --region "$REGION" --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name "$QUEUE" --region "$REGION" --attributes "$(cat <<JSON
{
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"${MAX_RECEIVE}\"}"
}
JSON
)"

echo "[init] Filas prontas: $QUEUE (maxReceiveCount=$MAX_RECEIVE) -> $DLQ"
