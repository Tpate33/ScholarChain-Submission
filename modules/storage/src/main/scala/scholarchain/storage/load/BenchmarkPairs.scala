package scholarchain.storage.load

import scholarchain.core.{BenchmarkPairList, Json}
import scholarchain.storage.port.RunSource

/**
 * The analysis run's benchmark pair list, read and checked by [[BenchmarkPairsReader.read]]
 * (SE-P7-08 item 1). A plain class with a `private[load]` constructor: the only way to one is the
 * reader, so no caller can supply a list that makes paths-run-chain pass.
 *
 * What the reader establishes is the list's form only (one strict UTF-8 line of canonical JSON that
 * decodes through the core [[BenchmarkPairList]] contract). Its binding to the analysis run (the
 * run manifest's `benchmarkPairsDigest` equals [[digest]], `provenance.runId` is the grouping run,
 * every pair names identified researchers of that run) is checked by the verifier, which holds the
 * run manifest and the grouping run ([[ParquetChecks]], check `benchmark-pairs`).
 *
 * @param fileSha256
 *   SHA-256 of the file bytes read (reported; the binding is by [[digest]])
 */
final class VerifiedBenchmarkPairs private[load] (
    val list: BenchmarkPairList,
    val fileSha256: String
) {

  /** [[BenchmarkPairList.digest]] (core): the value the analysis manifest records. */
  def digest: String = BenchmarkPairList.digest(list)

  override def toString: String =
    s"VerifiedBenchmarkPairs(${list.listVersion}, ${list.pairs.size} pairs, " +
      s"file sha256 $fileSha256, digest $digest)"
}

/**
 * Reader of the benchmark pair list (`scholarchain.benchmark-pairs.v1`) through a [[RunSource]]
 * (bounded, never following links). The rules are the analysis job's own (`spark` BenchmarkPairs,
 * restated here because storage does not import spark): the file text is exactly the canonical JSON
 * of the decoded list (`Json.write`) plus one LF, so the file bytes and
 * [[BenchmarkPairList.digest]] describe the same list. Every failure is a refusal; nothing is
 * repaired.
 */
object BenchmarkPairsReader {

  /**
   * Reads `fileName` from `src`: it must be a listed regular file of at most `maxBytes` bytes (at
   * most `maxBytes + 1` bytes are read), strict UTF-8, exactly one LF-terminated line, decoding
   * through the core contract, and in canonical form.
   */
  def read(
      src: RunSource,
      fileName: String,
      maxBytes: Long
  ): Either[Vector[String], VerifiedBenchmarkPairs] =
    if (src == null || fileName == null || fileName.isEmpty)
      Left(Vector("benchmark pairs: source and file name are required"))
    else if (maxBytes < 1)
      Left(Vector(s"benchmark pairs: maxBytes $maxBytes is below 1"))
    else
      for {
        files <- src.listFiles().left.map(e => Vector(s"benchmark pairs: ${e.message}"))
        _ <-
          if (files.contains(fileName)) Right(())
          else Left(Vector(s"benchmark pairs: $fileName is not a file of the source"))
        b <- src.readAtMost(fileName, maxBytes).left.map(e => Vector(s"$fileName: ${e.message}"))
        lines <- HadoopFiles.lines(fileName, b.toArray, 1).left.map(e => Vector(e.message))
        line <- lines match {
          case Vector(l) => Right(l)
          case _ => Left(Vector(s"$fileName: must be one line of canonical JSON ending in LF"))
        }
        list <- Json
          .decode[BenchmarkPairList](line)
          .left
          .map(_.map(e => s"$fileName: ${e.message}"))
        _ <-
          if (Json.write(list) == line) Right(())
          else
            Left(
              Vector(
                s"$fileName: not in canonical JSON form (Json.write of the decoded record), so its " +
                  "bytes and its digest would describe different text"
              )
            )
      } yield new VerifiedBenchmarkPairs(list, b.sha256)
}
