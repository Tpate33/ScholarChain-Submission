package scholarchain.storage.parquet

import java.io.File
import java.net.{URL, URLClassLoader}

import scala.util.Using

import scholarchain.core.{PartFormat, PartManifests}
import scholarchain.storage.Fixtures.*

/**
 * DU-P7-02 item 9b: the production Parquet read path ([[ParquetRows.read]], with its own pure
 * [[SnappyRaw]] decoder) works WITHOUT snappy-java, aircompressor and zstd-jni on the classpath.
 *
 * The test builds an isolated class loader from `java.class.path` (the forked test JVM's classpath)
 * minus every jar of those three artifacts, with the platform class loader as parent (so nothing
 * from the application class loader leaks in), counter-checks that the codec entry classes are
 * absent there (`ClassNotFoundException`), and runs the production read reflectively in that
 * loader (also as the thread context class loader) over all 29 fixture Parquet parts: footer rows
 * = decoded rows = manifest rows, codec set {SNAPPY}. It stays valid when the build removes the
 * three jars from storage Compile/Runtime and keeps snappy-java on Test only: the jars are removed
 * here whether or not they are present.
 */
class ParquetCodecIsolationSuite extends munit.FunSuite {

  /** File-name prefixes of the native codec jars (the artifacts named by DU-P7-02). */
  private val Excluded = Vector("snappy-java", "aircompressor", "zstd-jni")

  /** Entry classes of those artifacts (package names as published). */
  private val CodecClasses = Vector(
    "org.xerial.snappy.Snappy",
    "io.airlift.compress.snappy.SnappyDecompressor",
    "io.airlift.compress.zstd.ZstdDecompressor",
    "com.github.luben.zstd.Zstd"
  )

  private lazy val classPath: Vector[String] =
    sys.props("java.class.path").split(File.pathSeparator).toVector.filter(_.nonEmpty)

  private def excluded(entry: String): Boolean = {
    val name = new File(entry).getName
    entry.endsWith(".jar") && Excluded.exists(name.startsWith)
  }

  private lazy val parquetParts =
    PartManifests
      .decode(text(runDir(run1Root).resolve("_parts.json")))
      .map(PartManifests.asV2)
      .fold(e => fail(e.toString), identity)
      .files
      .filter(_.format == PartFormat.Parquet)

  private def module(cl: ClassLoader, name: String): (Class[?], AnyRef) = {
    val c = Class.forName(name + "$", true, cl)
    (c, c.getField("MODULE$").get(null))
  }

  private def call(o: AnyRef, method: String): AnyRef =
    o.getClass.getMethod(method).invoke(o)

  test("the classpath is the forked test JVM's (it holds the storage classes)") {
    assert(
      classPath.exists(e => e.contains("storage") && e.endsWith("classes")),
      classPath.take(5).toString
    )
  }

  test(
    "ParquetRows reads all 29 fixture parts in a loader without snappy-java/aircompressor/zstd-jni"
  ) {
    val removed = classPath.filter(excluded)
    val kept = classPath.filterNot(excluded)
    println(
      s"DU-P7-02 9b: ${classPath.size} classpath entries; removed ${removed.size}: " +
        removed.map(e => new File(e).getName).mkString(", ")
    )
    val urls: Array[URL] = kept.map(e => new File(e).toURI.toURL).toArray
    Using.resource(new URLClassLoader(urls, ClassLoader.getPlatformClassLoader)) { cl =>
      // Counter-check: the codec classes are not loadable in the isolated loader.
      CodecClasses.foreach { name =>
        intercept[ClassNotFoundException](Class.forName(name, false, cl))
      }
      val (rowsClass, rows) = module(cl, "scholarchain.storage.parquet.ParquetRows")
      assert(rowsClass.getClassLoader eq cl, "ParquetRows comes from the isolated loader")
      val (bytesClass, bytesObj) = module(cl, "scholarchain.storage.model.Bytes")
      val (boundsClass, boundsObj) = module(cl, "scholarchain.storage.parquet.ParquetReadBounds")
      val boundsEither = boundsClass
        .getMethod("of", classOf[Int], classOf[Long], classOf[Long], classOf[Int])
        .invoke(
          boundsObj,
          Int.box(1024 * 1024),
          Long.box(8L * 1024 * 1024),
          Long.box(100000L),
          Int.box(8 * 1024 * 1024)
        )
      val bounds = call(call(boundsEither, "toOption"), "get")
      val bytesType = Class.forName("scholarchain.storage.model.Bytes", false, cl)
      val readBounds = Class.forName("scholarchain.storage.parquet.ParquetReadBounds", false, cl)
      val read = rowsClass.getMethod("read", classOf[String], bytesType, readBounds)
      val of = bytesClass.getMethod("of", classOf[Array[Byte]])
      val thread = Thread.currentThread
      val before = thread.getContextClassLoader
      thread.setContextClassLoader(cl)
      try {
        assertEquals(parquetParts.size, 29)
        parquetParts.foreach { f =>
          val data = of.invoke(bytesObj, bytes(runDir(run1Root).resolve(f.path)))
          val result = read.invoke(rows, f.path, data, bounds)
          assert(
            call(result, "isRight").asInstanceOf[java.lang.Boolean].booleanValue,
            s"${f.path}: $result"
          )
          val part = call(call(result, "toOption"), "get")
          val footer = call(part, "footer")
          val footerRows = call(footer, "rowCount").asInstanceOf[java.lang.Long].longValue
          val decoded = call(call(part, "rows"), "size").asInstanceOf[java.lang.Integer].intValue
          val codecs = call(footer, "codecSet")
          val codecText =
            codecs.getClass.getMethod("mkString", classOf[String]).invoke(codecs, ",")
          assertEquals(footerRows, f.rowCount, f.path)
          assertEquals(decoded.toLong, f.rowCount, f.path)
          assertEquals(codecText, "SNAPPY", f.path)
          // The decoder classes in use are the isolated loader's own.
          assert(part.getClass.getClassLoader eq cl, f.path)
        }
      } finally thread.setContextClassLoader(before)
    }
  }
}
