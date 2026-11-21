package scholarchain.outside

import java.lang.reflect.Modifier

import scala.collection.mutable

import scholarchain.storage.{OpeningFixture, RunSettingsFixture, TestData, UpstreamFixture}
import scholarchain.storage.load.{
  KeyValueLoadLimits,
  OpeningLoad,
  OpeningLoadConfig,
  OpeningLoadReport,
  OpeningLoadResult,
  OpeningLoadSources,
  OpeningLoadTargets,
  ParquetReadLimits,
  Rejection,
  StoreLoadOutcome
}
import scholarchain.storage.model.{EmbeddingPolicy, StoreError, VectorFilter, VectorRecord}
import scholarchain.storage.port.{RuntimeStores, VectorStore}

/**
 * SE-P7-13 item 1 (handoff to Phases 9-11). Code outside package `scholarchain.storage` (this
 * suite's package, standing in for rag, services and tests code) configures and runs the opening
 * load and obtains a loaded [[RuntimeStores]] without naming any type of the reader package
 * `scholarchain.storage.parquet` (which `verifyRagSourceImports` forbids in rag sources), and the
 * public entry types hold no field of that package, of Parquet or of Hadoop.
 *
 * Running the load still needs the Parquet and Hadoop jars on the CALLER's classpath (they are
 * Optional dependencies of storage); storage tests have them. Without them the load refuses every
 * store (`reader-classpath`, see `OpeningLoadSuite`).
 *
 * CR-P7-15 N1: on both public construction paths of a runtime view (`OpeningLoad.run` and
 * `RuntimeStores.apply`), a stored vector whose floats were changed is refused by
 * `RuntimeStores.vectors` (query and get), and the view cannot be built any other way.
 */
class OpeningLoadSurfaceSuite extends munit.FunSuite {

  private val Forbidden =
    Vector("scholarchain.storage.parquet.", "org.apache.parquet.", "org.apache.hadoop.")

  /**
   * SE-P7-14 (CR-P7-16 m2): every forbidden type reachable from `roots`, as `"<where>: <type>"`.
   *
   * The walk uses GENERIC types (`java.lang.reflect.Type`), so a forbidden type is found inside any
   * type argument (Option, Vector, Map, Either, function types, nested arguments), wildcard and
   * type-variable bound, and array component. For every class of a `scholarchain.storage.` package
   * it reaches, it inspects: every declared non-static, non-synthetic field (private ones too;
   * Scala 3 emits no generic signature for a private field, so a wrapped type there is found
   * through the public accessor or constructor instead); every public constructor's parameters;
   * every public method's return, parameter and exception types (inherited ones included); the
   * generic superclass and interfaces; and the companion object's class (`<name>$`), so
   * `ParquetReadLimits.of` is covered. Classes outside `scholarchain.storage.` are checked by name
   * only (their members are library surface). A type the walker does not know is reported (fail
   * closed).
   *
   * Public methods whose name contains `$` are skipped: Scala emits them for expanded names of
   * private members, default-argument getters and lambdas, and Scala code outside the class cannot
   * name them. Qualified-private members (`private[storage]`) are public in bytecode and ARE
   * reported; the test below lists them exactly and proves each inaccessible from here.
   */
  private def forbiddenReachable(roots: Vector[Class[?]]): Vector[String] = {
    import java.lang.reflect.{GenericArrayType, ParameterizedType, Type, TypeVariable, WildcardType}
    val seenClasses = mutable.Set.empty[Class[?]]
    val seenTypes = mutable.Set.empty[Type]
    val bad = mutable.LinkedHashSet.empty[String]
    def visitType(t: Type, via: String): Unit = t match {
      case null => ()
      case c: Class[?] => visitClass(c, via)
      // Parameterized, array and wildcard types are finite trees: walked at every occurrence, so
      // each place that names a forbidden type is reported. Only a type variable's bounds can be
      // cyclic (F-bounds), so each variable is expanded once.
      case p: ParameterizedType =>
        visitType(p.getRawType, via)
        visitType(p.getOwnerType, via)
        p.getActualTypeArguments.foreach(visitType(_, via))
      case a: GenericArrayType => visitType(a.getGenericComponentType, via)
      case w: WildcardType =>
        (w.getUpperBounds ++ w.getLowerBounds).foreach(visitType(_, via))
      case v: TypeVariable[?] =>
        if (seenTypes.add(v)) v.getBounds.foreach(visitType(_, via))
      case other => bad += s"$via: unknown reflected type ${other.getTypeName}"
    }
    def visitClass(c0: Class[?], via: String): Unit = {
      var c: Class[?] = c0
      while (c.isArray) c = c.getComponentType
      val n = c.getName
      if (Forbidden.exists(n.startsWith)) bad += s"$via: $n"
      else if (n.startsWith("scholarchain.storage.") && seenClasses.add(c)) {
        c.getDeclaredFields
          .filterNot(f => Modifier.isStatic(f.getModifiers) || f.isSynthetic)
          .foreach(f => visitType(f.getGenericType, s"$n.${f.getName}"))
        c.getConstructors.foreach(k =>
          k.getGenericParameterTypes.foreach(visitType(_, s"$n(constructor)"))
        )
        c.getMethods
          .filterNot(m => m.isSynthetic || m.isBridge || m.getName.contains('$'))
          .foreach { m =>
            val at = s"$n.${m.getName}"
            visitType(m.getGenericReturnType, at)
            m.getGenericParameterTypes.foreach(visitType(_, at))
            m.getGenericExceptionTypes.foreach(visitType(_, at))
          }
        visitType(c.getGenericSuperclass, s"$n(extends)")
        c.getGenericInterfaces.foreach(visitType(_, s"$n(extends)"))
        if (!n.endsWith("$"))
          try visitClass(Class.forName(n + "$", false, c.getClassLoader), s"$n(companion)")
          catch { case _: ClassNotFoundException => () }
      }
    }
    roots.foreach(r => visitClass(r, "root"))
    bad.toVector
  }

  /** The public entry surface of the opening load (CR-P7-16 m2) and the runtime ports. */
  private val EntryRoots: Vector[Class[?]] = {
    import scholarchain.storage.port.*
    Vector(
      OpeningLoad.getClass,
      classOf[OpeningLoadSources],
      classOf[OpeningLoadConfig],
      classOf[OpeningLoadTargets],
      classOf[OpeningLoadResult],
      classOf[OpeningLoadReport],
      classOf[StoreLoadOutcome],
      classOf[Rejection],
      classOf[ParquetReadLimits],
      classOf[KeyValueLoadLimits],
      classOf[scholarchain.storage.load.UpstreamBounds],
      classOf[scholarchain.storage.load.VectorLoadConfig],
      classOf[scholarchain.storage.model.S3Layout],
      classOf[RuntimeStores],
      classOf[GraphReader],
      classOf[GraphWriter],
      classOf[KeyValueReader],
      classOf[KeyValueWriter],
      classOf[KeyValueStore],
      classOf[ObjectReader],
      classOf[ObjectStore],
      classOf[VectorReader],
      classOf[VectorStore],
      classOf[IndexInspector],
      classOf[RunSource],
      classOf[Embedder],
      classOf[Clock],
      classOf[Sleeper],
      classOf[Jitter]
    )
  }

  /**
   * The only findings on the entry surface: members that are `private[storage]` in Scala (public in
   * bytecode). Each is proven inaccessible from this package below.
   */
  private val QualifiedPrivate: Vector[String] = Vector(
    "scholarchain.storage.load.ParquetReadLimits.bounds: scholarchain.storage.parquet.ParquetReadBounds"
  )

  test("item 1, m2: the entry surface reaches no parquet-package, Parquet or Hadoop type") {
    val found = forbiddenReachable(EntryRoots)
    assertEquals(found, QualifiedPrivate, found.mkString("\n"))
    // Each finding is not nameable outside storage (its only use is inside storage).
    assert(compileErrors("ParquetReadLimits.of(1, 2L, 3L, 4).toOption.get.bounds").nonEmpty)
    assert(compileErrors("ParquetReadLimits.of(1, 2L, 3L, 4).toOption.get").isEmpty, "control")
    // The one entry method's signature reaches exactly the same (through OpeningLoadConfig).
    val run = OpeningLoad.getClass.getMethods.filter(_.getName == "run").toVector
    assertEquals(run.size, 1)
    assertEquals(
      forbiddenReachable(
        (run.head.getParameterTypes.toVector :+ run.head.getReturnType).map(c => c: Class[?])
      ),
      QualifiedPrivate
    )
  }

  test("m2: the walker finds forbidden types wrapped in type arguments and method signatures") {
    import scholarchain.storage.surfacefixture.*
    def at(c: Class[?]): Vector[String] = forbiddenReachable(Vector(c))
    val bounds = "scholarchain.storage.parquet.ParquetReadBounds"
    val input = "org.apache.parquet.io.InputFile"
    def has(found: Vector[String], where: String, tpe: String): Unit =
      assert(found.contains(s"$where: $tpe"), s"expected $where: $tpe in\n${found.mkString("\n")}")
    has(at(classOf[InOption]), "scholarchain.storage.surfacefixture.InOption.value", bounds)
    has(at(classOf[InVector]), "scholarchain.storage.surfacefixture.InVector.value", input)
    has(
      at(classOf[InMap]),
      "scholarchain.storage.surfacefixture.InMap.value",
      "org.apache.hadoop.conf.Configuration"
    )
    has(at(classOf[InFunction]), "scholarchain.storage.surfacefixture.InFunction.value", input)
    // Constructor parameters and accessors are reported too (Option-wrapped).
    has(at(classOf[InOption]), "scholarchain.storage.surfacefixture.InOption(constructor)", bounds)
    // Companion apply (InOption.apply(Option[ParquetReadBounds])).
    has(at(classOf[InOption]), "scholarchain.storage.surfacefixture.InOption$.apply", bounds)
    // Method signatures only (no field holds the type).
    assertEquals(
      at(classOf[InMethodReturn]),
      Vector(s"scholarchain.storage.surfacefixture.InMethodReturn.open: $input")
    )
    assertEquals(
      at(classOf[InMethodParameter]),
      Vector(s"scholarchain.storage.surfacefixture.InMethodParameter.use: $bounds")
    )
    // Recursive: reached through another storage class.
    has(at(classOf[Nested]), "scholarchain.storage.surfacefixture.InOption.value", bounds)
    // Control: wrappers of allowed types only.
    assertEquals(at(classOf[Clean]), Vector.empty)
    // Control: the erased walk the review found insufficient sees none of these.
    Vector(classOf[InOption], classOf[InVector], classOf[InMap], classOf[InFunction]).foreach { c =>
      val erased = c.getDeclaredFields.map(_.getType.getName).toVector
      assert(!erased.exists(n => Forbidden.exists(n.startsWith)), s"${c.getName}: $erased")
    }
  }

  test("item 1: ParquetReadLimits validates like the reader's bounds; no default exists") {
    assert(ParquetReadLimits.of(0, 1L, 0L, 1).isLeft)
    assert(ParquetReadLimits.of(1, 0L, 0L, 1).isLeft)
    assert(ParquetReadLimits.of(1, 1L, -1L, 1).isLeft)
    assert(ParquetReadLimits.of(1, 1L, 0L, 0).isLeft)
    assertEquals(
      ParquetReadLimits.of(1, 2L, 3L, 4).map(l => (l.maxFooterBytes, l.maxPartBytes)),
      Right((1, 2L))
    )
    assert(compileErrors("new ParquetReadLimits(1, 2L, 3L, 4)").nonEmpty, "private constructor")
    assert(compileErrors("ParquetReadLimits.of(1, 2L, 3L, 4).toOption.get.bounds").nonEmpty)
  }

  /** A vector store whose GetVectors results have their first float changed while `on`. */
  private final class Corrupting(u: VectorStore) extends VectorStore {
    var on = false
    def describe(i: String) = u.describe(i)
    def putVectors(i: String, rs: Vector[VectorRecord]) = u.putVectors(i, rs)
    def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) = u.query(i, v, k, f)
    def getVectors(i: String, ks: Vector[String]) =
      u.getVectors(i, ks)
        .map(vs => if (!on) vs else vs.map(r => r.copy(data = r.data.updated(0, r.data(0) + 1f))))
  }

  /** The caller's own configuration: plain values, no parquet-package type named. */
  private lazy val callerConfig: OpeningLoadConfig = OpeningLoadConfig(
    RunSettingsFixture.settings,
    ParquetReadLimits
      .of(1024 * 1024, 8L * 1024 * 1024, 100000L, 8 * 1024 * 1024)
      .fold(e => fail(e.toString), identity),
    UpstreamFixture.upstreamBounds,
    OpeningFixture.benchmarkMaxBytes,
    KeyValueLoadLimits.of(400 * 1024).fold(e => fail(e.toString), identity),
    TestData.layout,
    OpeningFixture.vectorConfig()
  )

  private lazy val loaded: (OpeningFixture.World, Corrupting, OpeningLoadResult) = {
    val w = new OpeningFixture.World
    val c = new Corrupting(w.vectors)
    val res = OpeningLoad.run(OpeningFixture.sources(), callerConfig, w.targets(vectorStore = c))
    (w, c, res)
  }

  test("item 1: a caller outside storage runs the full fixture load and gets a runtime view") {
    val (_, _, res) = loaded
    assert(res.report.complete, res.report.stores.map(s => s.target.tag -> s.status).toString)
    assertEquals(res.report.rejections, Vector.empty)
    assert(res.runtime.isDefined)
  }

  private val Index = "researcher-profile-v1"
  private val Query = Vector.fill(OpeningFixture.Dimension)(0.5f)

  // SE-P8-03: queries name their model version (the load's synthetic one).
  private val Model = "synthetic-test-only.v1"

  private def assertRefusesCorrupted(rt: RuntimeStores, c: Corrupting, path: String): Unit = {
    c.on = false
    val ms =
      rt.vectorsFor(Model).query(Index, Query, 3, None).fold(e => fail(s"$path: $e"), identity)
    assertEquals(ms.size, 3, path)
    assert(rt.vectors.getVectors(Index, ms.map(_.key)).isRight, path)
    c.on = true
    try {
      rt.vectorsFor(Model).query(Index, Query, 3, None) match {
        case Left(StoreError.Corrupt(d)) => assert(d.contains("do not match its dataSha256"), d)
        case other => fail(s"$path: query served a corrupted vector: $other")
      }
      rt.vectors.getVectors(Index, ms.map(_.key)) match {
        case Left(StoreError.Corrupt(d)) => assert(d.contains("do not match its dataSha256"), d)
        case other => fail(s"$path: get served a corrupted vector: $other")
      }
    } finally c.on = false
  }

  test("CR-P7-15 N1: the view from OpeningLoad.run refuses a corrupted vector (query and get)") {
    val (_, c, res) = loaded
    assertRefusesCorrupted(res.runtime.getOrElse(fail("no runtime view")), c, "OpeningLoad.run")
  }

  test(
    "CR-P7-15 N1: the view from RuntimeStores.apply refuses a corrupted vector (both policies)"
  ) {
    val (w, c, _) = loaded
    val test =
      RuntimeStores(w.objects, w.kv, w.kv, w.graph, c, c, EmbeddingPolicy.SyntheticTestOnly)
    assertRefusesCorrupted(test, c, "RuntimeStores.apply")
    // Under the production policy the synthetic vectors are refused whether or not corrupted.
    val prod = RuntimeStores(w.objects, w.kv, w.kv, w.graph, c, c, EmbeddingPolicy.RealModelsOnly)
    assert(prod.vectorsFor(Model).query(Index, Query, 3, None).isLeft)
  }

  test("CR-P7-15 N1: no other public way builds a runtime view or an unchecked vector reader") {
    assert(compileErrors("new RuntimeStores(???, ???, ???, ???, ???, ???, ???, ???)").nonEmpty)
    assert(compileErrors("scholarchain.storage.port.VectorReader.readOnly(???)").nonEmpty)
    assert(
      compileErrors(
        "RuntimeStores(???, ???, ???, ???, ???, ???, scholarchain.storage.model.EmbeddingPolicy.RealModelsOnly)"
      ).isEmpty,
      "control: the public factory compiles"
    )
  }
}
