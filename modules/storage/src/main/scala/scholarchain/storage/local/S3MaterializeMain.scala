package scholarchain.storage.local

import java.nio.file.{Files, LinkOption, Paths}
import scala.util.control.NonFatal
import scholarchain.storage.aws.StorageClients
import scholarchain.storage.model.StoreError

/**
 * Default is local validation only. --execute must equal the manifest's runId. A manifest can be
 * passed literally by an ECS bootstrap without AWS CLI or a network fetch.
 */
object S3MaterializeMain {
  val MaxManifestBytes = 4 * 1024 * 1024

  def run(args: Array[String]): Either[StoreError, String] = {
    def refused(s: String) = Left(StoreError.Refused("materialize CLI: " + s))
    try {
      if (args.length % 2 != 0) refused("arguments must be flag/value pairs")
      else {
        val pairs = args.grouped(2).map(a => a(0) -> a(1)).toVector
        val opts = pairs.toMap
        val allowed = Set("--manifest-json", "--manifest-file", "--root", "--execute")
        if (
          pairs.size != opts.size || !opts.keySet.subsetOf(allowed) ||
          !opts.contains("--root") || opts
            .contains("--manifest-json") == opts.contains("--manifest-file")
        )
          refused("require exactly one manifest source and --root; optional --execute runId")
        else {
          val json = opts.get("--manifest-json") match {
            case Some(s) => s
            case None =>
              val p = Paths.get(opts("--manifest-file"))
              require(
                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) &&
                  !Files.isSymbolicLink(p) && Files.size(p) <= MaxManifestBytes,
                "manifest file must be a bounded regular file"
              )
              Files.readString(p)
          }
          if (json.getBytes("UTF-8").length > MaxManifestBytes) refused("manifest too large")
          else {
            val m = upickle.default.read[MaterializeManifest](json)
            val root = Paths.get(opts("--root"))
            S3Materialize.validate(m, root).flatMap { _ =>
              opts.get("--execute") match {
                case None =>
                  Right(s"validated-only: ${m.entries.size} files; no AWS client or writes")
                case Some(runId) if runId != m.runId =>
                  refused("execution confirmation differs from runId")
                case Some(_) =>
                  for {
                    client <- S3Materialize.clientSettings(m)
                    readerSettings <- S3Materialize.readerSettings(m)
                    clients <- StorageClients.open()
                    result <-
                      try {
                        clients.objectReader(client, readerSettings).flatMap { reader =>
                          S3Materialize
                            .materialize(m, root, reader)
                            .map(n => s"materialized: $n files")
                        }
                      } finally clients.close()
                  } yield result
              }
            }
          }
        }
      }
    } catch {
      case NonFatal(e) => refused("invalid input or local operation: " + e.getClass.getSimpleName)
    }
  }

  def main(args: Array[String]): Unit =
    run(args).fold(e => throw new IllegalArgumentException(e.message), println)
}
