package scholarchain.storage

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}
import java.util.zip.{CRC32, GZIPInputStream, GZIPOutputStream}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scholarchain.core.Canonical
import scholarchain.storage.fake.*
import scholarchain.storage.load.*
import scholarchain.storage.model.SourceBounds

/**
 * SE-P7-08: the production entry point [[OpeningLoad]] over the real fixtures (or temporary copies
 * of them), with in-memory stores. Fixture files are only read; every change is made to a copy in a
 * temporary directory ([[Fixtures.withTemp]]).
 */
object OpeningFixture {

  val BenchmarkFile = "benchmark-pairs-dev.v1.json"

  /** The pinned SHA-256 of the benchmark fixture (human copy record, 2026-11-20). */
  val BenchmarkSha256 = "c37e3db9b844351b651234d1e5ec6aa437a962c313bd5effed9c96c7866bae10"

  lazy val benchmarkDir: Path =
    Paths.get(getClass.getResource("/fixtures/phase06-benchmark-pairs").toURI)

  /** Test configuration values (not AWS limits). */
  val benchmarkBounds: SourceBounds = SourceBounds.of(5, 1024L * 1024, 1).toOption.get
  val benchmarkMaxBytes: Long = 64L * 1024

  /** Test vector dimension (configuration value; real models are Phase 8). */
  val Dimension = 8

  /**
   * The opening-load vector limits: TestData.limits (test values that match S3 Vectors limits only
   * as remembered, UNVERIFIED: aws-docs MCP unavailable on 2026-11-20) with the ScholarChain
   * per-value cap raised from 256 to 1024 bytes. Under the 256-byte cap two work-summary rows
   * (their author-ID lists) are rejected as oversize metadata (VectorLoadSuite asserts it); the cap
   * guards against text in metadata and is not an AWS limit, while the filterable budget stays
   * 2048.
   */
  val openingLimits: scholarchain.storage.model.VectorLimits =
    TestData.limits.copy(maxValueBytes = 1024)

  /** Test vector configuration (every value a test configuration value). */
  def vectorConfig(
      batchSize: Int = 50,
      maxAttempts: Int = 4,
      maxRejected: Int = 5,
      limits: scholarchain.storage.model.VectorLimits = openingLimits,
      dimension: Int = Dimension,
      policy: scholarchain.storage.model.EmbeddingPolicy =
        scholarchain.storage.model.EmbeddingPolicy.SyntheticTestOnly,
      maxKeyBytes: Int = 512
  ): VectorLoadConfig =
    VectorLoadConfig
      .of(
        limits,
        scholarchain.storage.model.VectorIndexes.all.map(i =>
          VectorIndexSettings(i.name, dimension, scholarchain.storage.model.DistanceMetric.Cosine)
        ),
        batchSize,
        RetryPolicy.of(maxAttempts, 100L, 1600L).toOption.get,
        maxRejected,
        64 * 1024,
        maxKeyBytes,
        policy
      )
      .fold(e => throw new AssertionError(e.mkString("; ")), identity)

  /**
   * SE-P7-13 item 1: the same test values as [[RunSettingsFixture.parquetBounds]], as the
   * parquet-free configuration type a caller outside storage uses.
   */
  val parquetLimits: ParquetReadLimits =
    ParquetReadLimits.of(1024 * 1024, 8L * 1024 * 1024, 100000L, 8 * 1024 * 1024).toOption.get

  lazy val config: OpeningLoadConfig = OpeningLoadConfig(
    RunSettingsFixture.settings,
    parquetLimits,
    UpstreamFixture.upstreamBounds,
    benchmarkMaxBytes,
    KeyValueLoadLimits.of(400 * 1024).toOption.get,
    TestData.layout,
    vectorConfig()
  )

  /** The five indexes as Terraform would create them (every non-filterable key declared). */
  def indexInfos(
      dimension: Int = Dimension
  ): Vector[(scholarchain.storage.model.VectorIndexSpec, scholarchain.storage.model.IndexInfo)] =
    scholarchain.storage.model.VectorIndexes.all.map(i =>
      i -> scholarchain.storage.model.IndexInfo(
        i.name,
        dimension,
        scholarchain.storage.model.DistanceMetric.Cosine,
        scholarchain.storage.model.MetadataField.nonFilterableFields.map(_.key)
      )
    )

  /** A graph store whose writes are counted (CR-P7-10 n1). */
  final class RecordedGraph(val store: InMemoryGraphStore)
      extends scholarchain.storage.port.GraphReader
      with scholarchain.storage.port.GraphWriter {
    val writer = new RecordingGraphWriter(store)
    def run(q: scholarchain.storage.model.BoundQuery) = store.run(q)
    def storedVertex(id: String) = store.storedVertex(id)
    def storedEdge(id: String) = store.storedEdge(id)
    def write(w: scholarchain.storage.model.BoundGraphWrite) = writer.write(w)
    def vertexIds = store.vertexIds
    def edgeIds = store.edgeIds
  }

  def sources(
      run: Path = Fixtures.runDir(Fixtures.run1Root),
      loads: Path = Fixtures.loadDir(Fixtures.run1Root),
      grouping: Path = UpstreamFixture.groupingDir,
      curated: Path = UpstreamFixture.curatedDir,
      benchmark: Path = benchmarkDir
  ): OpeningLoadSources =
    OpeningLoadSources(
      Fixtures.source(run),
      Fixtures.source(loads, Fixtures.loadBounds),
      Fixtures.source(grouping, UpstreamFixture.groupingBounds),
      Fixtures.source(curated, UpstreamFixture.curatedBounds),
      Fixtures.source(benchmark, benchmarkBounds),
      BenchmarkFile
    )

  /** Counts embedding calls and texts (CR-P7-11 m4); delegates everything else. */
  final class CountingEmbedder(underlying: scholarchain.storage.port.Embedder)
      extends scholarchain.storage.port.Embedder {
    private var n = 0
    private var t = 0L
    def calls: Int = n
    def texts: Long = t
    def modelVersion: String = underlying.modelVersion
    def dimension: Int = underlying.dimension
    def embed(xs: Vector[String]) = {
      n += 1
      t += xs.size
      underlying.embed(xs)
    }
  }

  /** In-memory stores with recording wrappers (write counters only the wrappers can change). */
  final class World {
    val graph = new RecordedGraph(new InMemoryGraphStore)
    val kvStore = new InMemoryKeyValueStore(1000)
    val kv = new RecordingKeyValueStore(kvStore)
    val ledgerStore = new InMemoryKeyValueStore(1000)
    val ledger = new RecordingKeyValueStore(ledgerStore)
    val objectStore = new InMemoryObjectStore(1000)
    val objects = new RecordingObjectStore(objectStore)
    val vectorStore = new InMemoryVectorStore(indexInfos(), openingLimits)
    val vectors = new RecordingVectorStore(vectorStore)
    val embedder = new CountingEmbedder(new SyntheticTestEmbedding(Dimension))
    val clock = new ManualClock(1_700_000_000_000L)
    val sleeper = new RecordingSleeper(clock)
    val jitter = new FractionJitter(0.5)
    def targets(
        keyValues: scholarchain.storage.port.KeyValueStore = kv,
        ledgerStore: scholarchain.storage.port.KeyValueStore = ledger,
        vectorStore: scholarchain.storage.port.VectorStore = vectors
    ): OpeningLoadTargets =
      OpeningLoadTargets(
        graph,
        keyValues,
        ledgerStore,
        objects,
        vectorStore,
        embedder,
        clock,
        sleeper,
        jitter
      )

    /** Vectors in all five indexes. */
    def vectorCount: Int =
      scholarchain.storage.model.VectorIndexes.all.map(i => vectorStore.count(i.name)).sum

    /**
     * Every conditional write or put attempted on any store of this world: key-value, ledger,
     * object, graph (CR-P7-10 n1) and vector records.
     */
    def writeCalls: Int =
      kv.counts.calls + ledger.counts.calls + objects.counts.calls + graph.writer.counts.calls +
        vectors.counts.calls + vectors.batches

    /** Writes to the data stores only (not the RunLedger). */
    def dataWriteCalls: Int =
      kv.counts.calls + objects.counts.calls + graph.writer.counts.calls + vectors.batches
  }

  def run(w: World, s: OpeningLoadSources = sources()): OpeningLoadResult =
    OpeningLoad.run(s, config, w.targets())

  // ------------------------------------------------------------------ temporary copies

  def copyTree(src: Path, dst: Path): Unit =
    Using.resource(Files.walk(src)) { s =>
      s.iterator().asScala.foreach { p =>
        val target = dst.resolve(src.relativize(p).toString)
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
        else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES)
        else throw new AssertionError(s"fixture entry is not a regular file: $p")
      }
    }

  def copyOf(src: Path, tmp: Path, name: String): Path = {
    val dst = tmp.resolve(name)
    copyTree(src, dst)
    dst
  }

  /** Flips one bit of the byte at `offset` of a copied file. */
  def flipByte(file: Path, offset: Int): Unit = {
    val b = Files.readAllBytes(file)
    b(offset) = (b(offset) ^ 0x01).toByte
    Files.write(file, b)
    ()
  }

  /**
   * A Hadoop ChecksumFileSystem sidecar of `data`: `crc\0`, bytes per checksum, CRC32 per chunk.
   */
  def crcSidecar(data: Array[Byte], perChecksum: Int = 512): Array[Byte] = {
    val chunks = (data.length + perChecksum - 1) / perChecksum
    val bb = ByteBuffer.allocate(8 + 4 * chunks)
    bb.put(Array('c'.toByte, 'r'.toByte, 'c'.toByte, 0.toByte))
    bb.putInt(perChecksum)
    (0 until chunks).foreach { i =>
      val c = new CRC32
      val from = i * perChecksum
      c.update(data, from, math.min(perChecksum, data.length - from))
      bb.putInt(c.getValue.toInt)
    }
    bb.array()
  }

  /** Writes `data` to a copied curated file and rewrites its sidecar to match. */
  def writeCurated(dir: Path, rel: String, data: Array[Byte]): Unit = {
    Files.write(dir.resolve(rel), data)
    Files.write(dir.resolve(load.HadoopFiles.sidecarOf(rel)), crcSidecar(data))
    ()
  }

  def gunzip(b: Array[Byte]): Array[Byte] =
    Using.resource(new GZIPInputStream(new ByteArrayInputStream(b)))(_.readAllBytes())

  def gzip(b: Array[Byte]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    Using.resource(new GZIPOutputStream(out))(_.write(b))
    out.toByteArray
  }

  def utf8(b: Array[Byte]): String = new String(b, StandardCharsets.UTF_8)

  /**
   * In a copied grouping run: replaces the first occurrence of `from` in one part (same byte count
   * not required) and rewrites that part's `_parts.json` entry (SHA-256 and byte count), so the
   * grouping reader accepts the copy and only its content differs.
   */
  def resealGrouping(dir: Path, rel: String, from: String, to: String): Unit = {
    val file = dir.resolve(rel)
    val before = Files.readAllBytes(file)
    val text = utf8(before)
    val i = text.indexOf(from)
    if (i < 0) throw new AssertionError(s"$from not in $rel")
    val after = (text.substring(0, i) + to + text.substring(i + from.length))
      .getBytes(StandardCharsets.UTF_8)
    Files.write(file, after)
    val parts = dir.resolve("_parts.json")
    val pm = utf8(Files.readAllBytes(parts))
      .replace(Canonical.sha256Hex(before), Canonical.sha256Hex(after))
    val withBytes =
      if (before.length == after.length) pm
      else pm.replace(s""""byteCount":${before.length},""", s""""byteCount":${after.length},""")
    Files.write(parts, withBytes.getBytes(StandardCharsets.UTF_8))
    ()
  }
}
