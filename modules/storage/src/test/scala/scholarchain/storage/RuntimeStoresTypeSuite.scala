package scholarchain.storage

import scholarchain.storage.fake.{InMemoryKeyValueStore, InMemoryObjectStore, InMemoryVectorStore}
import scholarchain.storage.model.*
import scholarchain.storage.model.ItemCodecs.given
import scholarchain.storage.port.*
import TestData.*

/**
 * RuntimeStores type test (SE-P7-01; README L1378, role law 4): runtime composition reaches only
 * readers and the three runtime-table writers.
 */
class RuntimeStoresTypeSuite extends munit.FunSuite {

  private val noGraph: GraphReader = (_: BoundQuery) => Right(Vector.empty)

  private def compose(kv: InMemoryKeyValueStore = new InMemoryKeyValueStore(10)) = {
    val objects = new InMemoryObjectStore(10)
    val vectors = new InMemoryVectorStore(Vector.empty, limits)
    RuntimeStores(objects, kv, kv, noGraph, vectors, vectors, EmbeddingPolicy.RealModelsOnly)
  }

  test("public members are only read facades and the three runtime-table writers") {
    val allowed: Set[Class[?]] = Set(
      classOf[ObjectReader],
      classOf[KeyValueReader],
      classOf[GraphReader],
      classOf[VectorReader],
      classOf[IndexInspector],
      classOf[QuerySessionWriter],
      classOf[EntityLinkCacheWriter],
      classOf[EvidenceBundleWriter]
    )
    val members = classOf[RuntimeStores].getDeclaredMethods.filter(m =>
      java.lang.reflect.Modifier.isPublic(m.getModifiers) &&
        !java.lang.reflect.Modifier.isStatic(m.getModifiers) && // companion forwarders
        m.getParameterCount == 0
    )
    assertEquals(members.map(_.getReturnType).toSet[Class[?]], allowed)
    val forbidden: Vector[Class[?]] = Vector(
      classOf[ObjectWriter],
      classOf[KeyValueWriter],
      classOf[VectorWriter],
      classOf[GraphLoader],
      classOf[GraphWriter],
      classOf[ObjectStore],
      classOf[KeyValueStore],
      classOf[VectorStore]
    )
    members.foreach { m =>
      forbidden.foreach(f =>
        assert(!f.isAssignableFrom(m.getReturnType), s"${m.getName}: ${f.getSimpleName}")
      )
    }
  }

  test("a concrete store passed in cannot be cast back to its write side") {
    val s = compose()
    assert(!s.objects.isInstanceOf[ObjectWriter])
    assert(!s.keyValues.isInstanceOf[KeyValueWriter])
    assert(!s.vectors.isInstanceOf[VectorWriter])
    assert(!s.vectorIndexes.isInstanceOf[VectorWriter])
    assert(!s.graph.isInstanceOf[GraphLoader])
  }

  test("B5: the in-memory graph store (reader and writer) reaches the runtime as a reader only") {
    val graph = new scholarchain.storage.fake.InMemoryGraphStore
    assert(graph.isInstanceOf[GraphWriter], "control: the store itself is also a writer")
    val objects = new InMemoryObjectStore(10)
    val kv = new InMemoryKeyValueStore(10)
    val vectors = new InMemoryVectorStore(Vector.empty, limits)
    val s = RuntimeStores(objects, kv, kv, graph, vectors, vectors, EmbeddingPolicy.RealModelsOnly)
    assert(!s.graph.isInstanceOf[GraphWriter], "the facade cannot be cast back to the writer")
    assert(!s.graph.isInstanceOf[scholarchain.storage.fake.InMemoryGraphStore])
    assert(compileErrors("(??? : RuntimeStores).graph.write(???)").nonEmpty)
    assert(compileErrors("val w: GraphWriter = (??? : RuntimeStores).graph").nonEmpty)
    assert(compileErrors("(??? : RuntimeStores).graph.run(???)").isEmpty, "control: reads compile")
  }

  test("loader-only writes do not compile against RuntimeStores") {
    assert(compileErrors("(??? : RuntimeStores).objects.putCreateOnce(???, ???, ???)").nonEmpty)
    assert(compileErrors("(??? : RuntimeStores).keyValues.putIfAbsent(???, ???)").nonEmpty)
    assert(compileErrors("(??? : RuntimeStores).vectors.putVectors(???, ???)").nonEmpty)
    assert(compileErrors("(??? : RuntimeStores).graph.submit(???)").nonEmpty)
    assert(compileErrors("(??? : RuntimeStores).querySessions.putIfAbsent(???, ???)").nonEmpty)
    assert(
      compileErrors("new QuerySessionWriter(???, ???)").nonEmpty,
      "writers come from the factory"
    )
    assert(compileErrors("(??? : RuntimeStores).objects.get(???)").isEmpty, "reads compile")
  }

  test("the runtime writers write only their own tables") {
    val kv = new InMemoryKeyValueStore(10)
    val s = compose(kv)
    assertEquals(s.querySessions.putSession(session()), Right(PutOutcome.Written))
    assertEquals(s.querySessions.putStep(step(0)), Right(PutOutcome.Written))
    assertEquals(s.entityLinks.put(link()), Right(PutOutcome.Written))
    val b = bundle("s3://b/a", "s3://b/b")
    assertEquals(s.evidenceBundles.put(b), Right(PutOutcome.Written))
    assertEquals(s.evidenceBundles.put(b), Right(PutOutcome.AlreadyPresent), "idempotent retry")
    assertEquals(kv.itemCount(TableRole.QuerySession), 2)
    assertEquals(kv.itemCount(TableRole.EntityLinkCache), 1)
    assertEquals(kv.itemCount(TableRole.EvidenceBundle), 3, "two item rows and the bundle row")
    TableRole.values
      .filterNot(_.runtimeWritable)
      .foreach(t => assertEquals(kv.itemCount(t), 0, t.tag))
    assertEquals(
      RuntimeStores.WritableTables,
      Set(TableRole.QuerySession, TableRole.EntityLinkCache, TableRole.EvidenceBundle)
    )
  }

  test("evidence items resolve through the byEvidence index after a bundle write") {
    val kv = new InMemoryKeyValueStore(10)
    val b = bundle("s3://b/a")
    compose(kv).evidenceBundles.put(b)
    val ev = b.items.head.evidenceId
    val p = TableKeys.EvidenceBundle.byEvidence(ev).toOption.get
    val rows = kv
      .query(TableRole.EvidenceBundle, KeyQuery(Some("byEvidence"), p, SortKeyCondition.All, 10))
      .toOption
      .get
    assertEquals(
      rows.map(ItemCodecs.EvidenceItemRowCodec.decode(_).map(_.bundleId)),
      Vector(Right(b.bundleId))
    )
    assertEquals(
      summon[ItemCodec[scholarchain.core.EvidenceBundle]].table.role,
      TableRole.EvidenceBundle
    )
  }
}
