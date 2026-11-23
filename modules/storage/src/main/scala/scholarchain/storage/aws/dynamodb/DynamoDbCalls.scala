package scholarchain.storage.aws.dynamodb

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  BatchGetItemRequest,
  BatchGetItemResponse,
  DeleteItemRequest,
  DeleteItemResponse,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse,
  QueryRequest,
  QueryResponse
}

/**
 * SE-INT-01: the narrow seam over the SDK `DynamoDbClient`. Exactly five operations exist here:
 * GetItem, PutItem, DeleteItem, Query and BatchGetItem. Scan, BatchWriteItem, UpdateItem and every
 * Transact or PartiQL operation are unreachable from the adapter: it holds only this seam, and
 * [[DynamoDbCalls.of]] captures the client privately without exposing it. (The deployed IAM policy
 * should grant the same five actions only: routing item for infra-engineer.)
 *
 * Implementations must be safe for concurrent use (the SDK client is documented as thread-safe as
 * remembered; UNVERIFIED against AWS documentation on 2026-11-22).
 */
trait DynamoDbCalls {
  def getItem(request: GetItemRequest): GetItemResponse
  def putItem(request: PutItemRequest): PutItemResponse
  def deleteItem(request: DeleteItemRequest): DeleteItemResponse
  def query(request: QueryRequest): QueryResponse
  def batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse
}

object DynamoDbCalls {

  /**
   * The five operations of `client`; nothing else of the client is reachable through the result.
   */
  def of(client: DynamoDbClient): DynamoDbCalls = {
    require(client != null, "client is required")
    new DynamoDbCalls {
      def getItem(request: GetItemRequest): GetItemResponse = client.getItem(request)
      def putItem(request: PutItemRequest): PutItemResponse = client.putItem(request)
      def deleteItem(request: DeleteItemRequest): DeleteItemResponse = client.deleteItem(request)
      def query(request: QueryRequest): QueryResponse = client.query(request)
      def batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse =
        client.batchGetItem(request)
    }
  }
}
