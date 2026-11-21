package scholarchain.storage.model

/**
 * Bounds of one observed directory (SE-P7-03): a [[scholarchain.storage.port.RunSource]] refuses a
 * directory that exceeds any of them before the verifier reads a single byte of content. Every
 * value is passed in by the caller (configuration); there is no default.
 *
 * @param maxFiles
 *   the most regular files the directory may hold (marker and manifest files included)
 * @param maxTotalBytes
 *   the most bytes all those files may hold together; also the largest single file read
 * @param maxDepth
 *   the deepest file, in path segments below the directory (1: files directly in it)
 */
final case class SourceBounds private (maxFiles: Int, maxTotalBytes: Long, maxDepth: Int)

object SourceBounds {
  def of(maxFiles: Int, maxTotalBytes: Long, maxDepth: Int): Either[StoreError, SourceBounds] =
    if (maxFiles < 1) Left(StoreError.Refused(s"maxFiles: $maxFiles is below 1"))
    else if (maxTotalBytes < 1)
      Left(StoreError.Refused(s"maxTotalBytes: $maxTotalBytes is below 1"))
    else if (maxDepth < 1) Left(StoreError.Refused(s"maxDepth: $maxDepth is below 1"))
    else Right(new SourceBounds(maxFiles, maxTotalBytes, maxDepth))
}
