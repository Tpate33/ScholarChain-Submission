package scholarchain.spark.store

import java.net.{URI, URISyntaxException}
import java.nio.file.{InvalidPathException, Path, Paths}

import org.apache.hadoop.fs.{Path => HPath}

/**
 * An absolute location in one run store (SP-P6-04): a root (`scheme` and, except for the local file
 * system, an `authority` such as an HDFS name node or an S3 bucket) and the path segments below it.
 * Pure syntax: building, resolving or comparing a location never touches a file system.
 *
 * Local locations (`file` scheme) print as the plain local path and convert to the same
 * `java.nio.file.Path` and `file:` URI as before (so local messages and Spark URIs are unchanged);
 * every other location prints as its URI. Locations built from user input ([[StorePath.parse]]) are
 * normalized: no empty, `.` or `..` segment, no query, fragment, user info or percent escape.
 */
final class StorePath private (
    val scheme: String,
    val authority: Option[String],
    val segments: Vector[String]
) extends Serializable {

  def isLocal: Boolean = scheme == StorePath.FileScheme

  /** The absolute path below the root, `/`-separated (`/` for the root itself). */
  def path: String = if (segments.isEmpty) "/" else segments.mkString("/", "/", "")

  /** The last segment ("" for the root). */
  def name: String = segments.lastOption.getOrElse("")

  /** As `java.nio.file.Path.getFileName.toString` ("" for the root). */
  def getFileName: String = name

  def parent: Option[StorePath] =
    Option.when(segments.nonEmpty)(new StorePath(scheme, authority, segments.init))

  /** As `java.nio.file.Path.getParent`: null for the root. */
  def getParent: StorePath = parent.orNull

  /** The location `relative` (`/`-separated segments) below this one. */
  def resolve(relative: String): StorePath =
    new StorePath(scheme, authority, segments ++ relative.split('/').filter(_.nonEmpty))

  /** The location `n` beside this one. */
  def resolveSibling(n: String): StorePath = parent.getOrElse(this).resolve(n)

  /** Same root and every segment of `other` is a leading segment of this location. */
  def startsWith(other: StorePath): Boolean =
    other != null && scheme == other.scheme && authority == other.authority &&
      segments.startsWith(other.segments)

  /** The relative path of this location below `ancestor`, when it lies at or below it. */
  def relativeTo(ancestor: StorePath): Option[String] =
    Option.when(startsWith(ancestor))(segments.drop(ancestor.segments.size).mkString("/"))

  /** The local path of a `file` location; refused (IllegalStateException) for any other scheme. */
  def localPath: Path =
    if (isLocal) Paths.get(path)
    else throw new IllegalStateException(s"$this is not a local location")

  /** The Hadoop form of the URI: no trailing slash, `file:/...` for local locations. */
  def hadoopUri: URI = new URI(scheme, authority.orNull, path, null, null)

  def hadoopPath: HPath = new HPath(hadoopUri)

  /**
   * The URI Spark is given. Local: `java.nio.file.Path.toUri` (the form used before SP-P6-04); any
   * other scheme: [[hadoopUri]].
   */
  def toUri: URI = if (isLocal) localPath.toUri else hadoopUri

  override def toString: String = if (isLocal) path else hadoopUri.toString

  override def equals(o: Any): Boolean = o match {
    case p: StorePath => p.scheme == scheme && p.authority == authority && p.segments == segments
    case _ => false
  }

  override def hashCode: Int = (scheme, authority, segments).##
}

object StorePath {

  val FileScheme = "file"

  private val SchemeText = "^[a-z][a-z0-9+.-]*$".r

  /**
   * The location of an absolute local path, segment by segment as given (relative paths are made
   * absolute first). Used for the local `java.nio.file.Path` APIs; it keeps the path's own
   * segments, so a converted path maps back to the same local path.
   */
  def local(p: Path): StorePath = {
    val abs = p.toAbsolutePath
    val names = (0 until abs.getNameCount).map(i => abs.getName(i).toString).toVector
    new StorePath(FileScheme, None, names)
  }

  private def segmentProblem(s: String): Option[String] =
    if (s.isEmpty) Some("has an empty segment")
    else if (s == "." || s == "..") Some("has a '.' or '..' segment")
    else None

  /**
   * A location from configuration or arguments: an absolute, normalized local path (`/...`), or a
   * hierarchical URI `scheme://authority/path` (`file:/path` or `file:///path` for local paths).
   * One trailing slash of a URI path is ignored. Refused: a relative path, whitespace, control
   * characters, backslashes, percent escapes, a query, a fragment, user info, a port-only or
   * missing authority (except `file`), a `file` authority other than empty, and empty, `.` or `..`
   * segments.
   */
  def parse(text: String): Either[String, StorePath] =
    if (text == null || text.isEmpty) Left("the location is empty")
    else if (text.exists(c => c.isWhitespace || c.isControl || c == '\\'))
      Left(s"'$text' contains whitespace, a control character or a backslash")
    else if (text.contains('%')) Left(s"'$text' contains a percent escape")
    else if (text.startsWith("/")) localText(text)
    else
      (try Right(new URI(text))
      catch { case e: URISyntaxException => Left(s"'$text' is not a URI: ${e.getMessage}") })
        .flatMap { u =>
          val scheme = Option(u.getScheme).getOrElse("")
          if (!SchemeText.matches(scheme))
            Left(s"'$text' is neither an absolute local path nor a URI with a lowercase scheme")
          else if (u.isOpaque) Left(s"'$text' is not a hierarchical URI (scheme:/path)")
          else if (u.getRawQuery != null || u.getRawFragment != null)
            Left(s"'$text' has a query or a fragment")
          else if (u.getRawUserInfo != null) Left(s"'$text' has user info")
          else {
            val rawPath = Option(u.getRawPath).filter(_.nonEmpty).getOrElse("/")
            val p = if (rawPath.length > 1 && rawPath.endsWith("/")) rawPath.init else rawPath
            val authority = Option(u.getRawAuthority).filter(_.nonEmpty)
            if (!p.startsWith("/")) Left(s"'$text' has no absolute path")
            else if (scheme == FileScheme && authority.nonEmpty)
              Left(s"'$text': a file URI has no authority (file:/path or file:///path)")
            else if (scheme != FileScheme && authority.isEmpty)
              Left(s"'$text' has no authority (bucket or name node)")
            else {
              val segs = p.split('/').toVector.drop(1).filter(_ => p != "/")
              segs.flatMap(segmentProblem).headOption match {
                case Some(why) => Left(s"'$text' $why")
                case None => Right(new StorePath(scheme, authority, segs))
              }
            }
          }
        }

  private def localText(text: String): Either[String, StorePath] =
    (try Right(Paths.get(text))
    catch { case e: InvalidPathException => Left(s"'$text' is not a local path: $e") })
      .flatMap { p =>
        if (p.normalize.toString != text)
          Left(s"'$text' is not an absolute, normalized local path")
        else Right(local(p))
      }
}
