package scholarchain.storage.model

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{Canonical, IdKind, StableId}

/**
 * SE-P8-01 item 3: one unit of embedding work: the vector of one summary input in one index under
 * one embedding model version.
 *
 * @param vectorKey
 *   [[VectorKeys.of]](index, modelVersion, inputId)
 * @param textSha256
 *   SHA-256 of the context text to embed (the skip rule compares it with the stored vector's)
 * @param textS3Key
 *   the ordinary-S3 key of that text (the authoritative copy; never in the manifest itself)
 */
@allowUnknownKeys(false)
final case class EmbeddingWorkItem(
    index: String,
    inputId: String,
    modelVersion: String,
    vectorKey: String,
    textSha256: String,
    textS3Key: String
) derives ReadWriter

/**
 * SE-P8-01 item 3: the deterministic embedding work manifest of one vector load under one model
 * version (schema `scholarchain.storage.embedding-work-manifest.v1`). It is built from the verified
 * summary inputs only (one item per (index, inputId, modelVersion), in index order then vector key
 * by UTF-8 bytes; inputs rejected before any write are not work), stored create-once in ordinary S3
 * at [[S3Layout.embeddingWorkManifest]] with one ArtifactRegistry row. CR-P8-01 m2: it is the
 * RECORDED, content-addressed evidence of the work list a call derived from its verified permit
 * (the ledger totals record its SHA-256); the loader does not read it back as its work list. Equal
 * inputs, configuration and model give byte-identical manifests, so a rerun or resume finds it
 * `AlreadyPresent`; another work list (for example after a configuration change that rejects other
 * inputs) has other bytes and therefore another key (the key holds the SHA-256), so it is stored
 * beside the earlier one; other bytes under a manifest's key are refused as a `Conflict` by the
 * pre-write probe, never overwritten.
 *
 * There is no SQS queue: which items are done is decided by the loader's pre-write probe of the
 * vector store (an intact stored vector with this item's metadata is done), never by a mutable
 * cursor. An SQS-driven design is an AWS item (infra-engineer).
 */
@allowUnknownKeys(false)
final case class EmbeddingWorkManifest(
    schemaVersion: String,
    runId: String,
    loadId: String,
    modelVersion: String,
    dimension: Int,
    items: Vector[EmbeddingWorkItem]
) derives ReadWriter

object EmbeddingWorkManifest {
  val SchemaVersion = "scholarchain.storage.embedding-work-manifest.v1"
  val ContentType = "application/json"

  /** Every problem of a manifest; empty when valid. */
  def problems(m: EmbeddingWorkManifest): Vector[String] =
    if (m == null || m.items == null || m.items.contains(null)) Vector("manifest: missing")
    else {
      val keys = m.items.map(_.vectorKey)
      Vector(
        Option.when(m.schemaVersion != SchemaVersion)(s"schemaVersion: expected $SchemaVersion"),
        StableId.validate(IdKind.Run, m.runId, "runId").left.toOption.map(_.message),
        StableId.validate(IdKind.StoreLoad, m.loadId, "loadId").left.toOption.map(_.message),
        EmbeddingModelVersion.problem(m.modelVersion),
        Option.when(m.dimension < 1)("dimension: below 1"),
        Option.when(keys.distinct.size != keys.size)("items: duplicate vector keys")
      ).flatten ++ m.items.flatMap { i =>
        val expected = VectorIndexes
          .byName(i.index)
          .toRight(StoreError.Refused(s"index '${i.index}' is not a vector index"))
          .flatMap(ix => VectorKeys.of(ix, i.modelVersion, i.inputId))
        Vector(
          expected.left.toOption.map(e => s"${i.vectorKey}: ${e.message}"),
          expected.toOption
            .filter(_ != i.vectorKey)
            .map(k => s"${i.vectorKey}: is not the key $k of its index, model and input"),
          Option.when(i.modelVersion != m.modelVersion)(
            s"${i.vectorKey}: model version differs from the manifest's"
          ),
          Option.when(!Canonical.isHex64(i.textSha256))(s"${i.vectorKey}: textSha256 not hex64"),
          S3Layout.relativeProblem(i.textS3Key).map(p => s"${i.vectorKey}: textS3Key $p")
        ).flatten
      }
    }

  /** The stored bytes: the manifest's compact JSON plus one LF (deterministic). */
  def bytes(m: EmbeddingWorkManifest): Either[StoreError, Bytes] = {
    val p = problems(m)
    if (p.nonEmpty) Left(StoreError.Refused(p.mkString("; ")))
    else Right(Bytes.utf8(upickle.default.write(m) + "\n"))
  }

  def parse(b: Bytes): Either[StoreError, EmbeddingWorkManifest] =
    try {
      val m = upickle.default.read[EmbeddingWorkManifest](b.utf8.stripSuffix("\n"))
      val p = problems(m)
      if (p.isEmpty) Right(m) else Left(StoreError.Corrupt(p.mkString("; ")))
    } catch {
      case e: Exception =>
        Left(StoreError.Corrupt(s"work manifest: undecodable (${e.getClass.getSimpleName})"))
    }
}
