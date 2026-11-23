package scholarchain.storage.aws.s3vectors

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.model.{QueryOutputVector, QueryVectorsResponse}

import scholarchain.storage.model.{
  MetaValue,
  MetadataField,
  StoreError,
  VectorFilter,
  VectorFilters,
  VectorIndexSpec,
  VectorIndexes,
  VectorMetadata
}

/**
 * SE-INT-05 (CR-INT-07-1): `In` on a list field (researcherIds) as the documented `$or` of `$eq`
 * (Q15), with the exact filter shapes rag's SemanticContextAgent emits, equivalence with
 * [[VectorFilters.matches]], the 100-constraint cap and the value-size bound.
 */
class S3VectorsFilterSuite extends munit.FunSuite {
  import S3VectorsFilterSuite.*
  import MetadataField.*

  private val R = VectorIndexes.ResearcherProfile
  private val P = VectorIndexes.PaperAbstract

  test("researcher-profile-v1: the exact SemanticContextAgent filter document") {
    val f = researcherShape(Vector(IdA, IdB), 2020, 2021)
    val expected = and(
      or(isEq("researcherIds", str(IdA)), isEq("researcherIds", str(IdB))),
      and(cmp("windowStart", "$gte", num(2020)), cmp("windowStart", "$lte", num(2020))),
      and(cmp("windowEnd", "$gte", num(2021)), cmp("windowEnd", "$lte", num(2021)))
    )
    assertEquals(VectorFilterDocuments.of(R, f), Right(expected))
    // and it is what reaches the QueryVectors request
    val stub = new S3VectorsReaderSuite.Stub
    stub.query =
      QueryVectorsResponse.builder().vectors(java.util.List.of[QueryOutputVector]()).build()
    val settings = S3VectorsReaderSettings.of(S3VectorsReaderSuite.VectorBucket, 3, 4).toOption.get
    assertEquals(
      S3VectorsReader.of(stub, settings).query(R.name, Vector(1f), 3, Some(f)),
      Right(Vector())
    )
    assertEquals(stub.queries.head.filter(), expected)
  }

  test("researcher-profile-v1: one researcher is a plain $eq inside the $and") {
    val f = researcherShape(Vector(IdA), 2019, 2019)
    assertEquals(
      VectorFilterDocuments.of(R, f),
      Right(
        and(
          isEq("researcherIds", str(IdA)),
          and(cmp("windowStart", "$gte", num(2019)), cmp("windowStart", "$lte", num(2019))),
          and(cmp("windowEnd", "$gte", num(2019)), cmp("windowEnd", "$lte", num(2019)))
        )
      )
    )
  }

  test("paper-abstract-v1: the exact SemanticContextAgent filter document (no $and wrapper)") {
    assertEquals(
      VectorFilterDocuments.of(P, paperShape(Vector(IdA, IdB, IdC))),
      Right(
        or(
          isEq("researcherIds", str(IdA)),
          isEq("researcherIds", str(IdB)),
          isEq("researcherIds", str(IdC))
        )
      )
    )
    assertEquals(
      VectorFilterDocuments.of(P, paperShape(Vector(IdA))),
      Right(isEq("researcherIds", str(IdA)))
    )
  }

  test("equivalence with VectorFilters.matches on stub metadata, array-contains semantics") {
    val pool = Vector(IdA, IdB, IdC, IdD)
    val lists: Vector[Option[Vector[String]]] =
      None +: (0 to pool.size).toVector.flatMap(n => pool.combinations(n).map(Some(_)).toVector)
    val years: Vector[Option[Long]] = Vector(None, Some(2019L), Some(2020L), Some(2021L))
    val metadata = for {
      ids <- lists
      ws <- years
      we <- years
    } yield VectorMetadata(
      SortedMap.from(
        ids.map(v => ResearcherIds.key -> MetaValue.StrList(v)).toVector ++
          ws.map(v => WindowStart.key -> MetaValue.Num(v)).toVector ++
          we.map(v => WindowEnd.key -> MetaValue.Num(v)).toVector
      )
    )
    val wanted = (1 to 3).toVector.flatMap(n => pool.combinations(n).toVector)
    val filters: Vector[(VectorIndexSpec, VectorFilter)] =
      wanted.flatMap(ids => Vector(R -> researcherShape(ids, 2020, 2021), P -> paperShape(ids)))
    var agreeTrue = 0
    for ((spec, f) <- filters) {
      val d = VectorFilterDocuments.of(spec, f).toOption.get
      for (md <- metadata) {
        val expected = VectorFilters.matches(f, md)
        assertEquals(Q15.eval(d, md), expected, s"$f on $md")
        if (expected) agreeTrue += 1
      }
    }
    assert(agreeTrue > 0, "the sample must contain matching metadata")
    // a vector naming several researchers matches a filter naming one of them (any element)
    val many = VectorMetadata(SortedMap(ResearcherIds.key -> MetaValue.StrList(Vector(IdC, IdA))))
    val d = VectorFilterDocuments.of(P, paperShape(Vector(IdB, IdA))).toOption.get
    assert(Q15.eval(d, many) && VectorFilters.matches(paperShape(Vector(IdB, IdA)), many))
    val none = VectorMetadata(SortedMap(ResearcherIds.key -> MetaValue.StrList(Vector(IdC, IdD))))
    assert(!Q15.eval(d, none) && !VectorFilters.matches(paperShape(Vector(IdB, IdA)), none))
  }

  test("constraint cap: exactly 100 accepted, 101 Refused (value-free), counted over the tree") {
    // researcher-profile shape: $and 1 + $or 1 + n $eq + two ranges of 3 = n + 8
    val atCap = ids(92)
    assertEquals(
      VectorFilterDocuments.constraintCount(R, researcherShape(atCap, 2020, 2020)),
      Right(100)
    )
    assert(VectorFilterDocuments.of(R, researcherShape(atCap, 2020, 2020)).isRight)
    val over = ids(93)
    val refused = VectorFilterDocuments.of(R, researcherShape(over, 2020, 2020))
    assertRefusedValueFree(refused, over)
    assert(refused.left.exists(_.message.contains("101 filter constraints")), refused.toString)
    // paper-abstract shape: $or 1 + n $eq
    assertEquals(VectorFilterDocuments.constraintCount(P, paperShape(ids(99))), Right(100))
    assert(VectorFilterDocuments.of(P, paperShape(ids(99))).isRight)
    assertRefusedValueFree(VectorFilterDocuments.of(P, paperShape(ids(100))), ids(100))
    // a string-field $in counts one per value
    val subjects = (1 to 101).toVector.map(i => s"researcher:local:s$i")
    assert(VectorFilterDocuments.of(R, VectorFilter.In(SubjectId, subjects.take(100))).isRight)
    assertRefusedValueFree(
      VectorFilterDocuments.of(R, VectorFilter.In(SubjectId, subjects)),
      subjects
    )
    // refused before any call
    val stub = new S3VectorsReaderSuite.Stub
    val settings = S3VectorsReaderSettings.of(S3VectorsReaderSuite.VectorBucket, 3, 4).toOption.get
    val got =
      S3VectorsReader.of(stub, settings).query(P.name, Vector(1f), 1, Some(paperShape(ids(100))))
    assert(got.left.exists(_.isInstanceOf[StoreError.Refused]), got.toString)
    assertEquals(stub.calls, 0)
  }

  test("empty and oversized values: refused, consistent with matches; non-empty list In accepted") {
    // control: a non-empty list-field In is translated (refused by the SE-INT-04 code)
    assert(VectorFilterDocuments.of(P, VectorFilter.In(ResearcherIds, Vector(IdA))).isRight)
    // an empty set matches nothing under matches, and is refused (never an empty $or or $in)
    val empty = VectorFilter.In(ResearcherIds, Vector())
    val anyMd = VectorMetadata(SortedMap(ResearcherIds.key -> MetaValue.StrList(Vector(IdA))))
    assert(!VectorFilters.matches(empty, anyMd))
    val e = VectorFilterDocuments.of(P, empty)
    assert(
      e.left.exists(x =>
        x.isInstanceOf[StoreError.Refused] && x.message.contains("empty value set")
      ),
      e.toString
    )
    assert(VectorFilterDocuments.of(P, VectorFilter.In(ResearcherIds, Vector(IdA, ""))).isLeft)
    // value size (Q17 CLASSIC filterable 2 KB): 2048 bytes accepted, 2049 refused, value-free
    val ok = "r" * VectorFilterDocuments.MaxValueBytes
    val long = "q" * (VectorFilterDocuments.MaxValueBytes + 1)
    assert(VectorFilterDocuments.of(P, VectorFilter.In(ResearcherIds, Vector(IdA, ok))).isRight)
    assertRefusedValueFree(
      VectorFilterDocuments.of(P, VectorFilter.In(ResearcherIds, Vector(IdA, long))),
      Vector(long)
    )
    assert(VectorFilterDocuments.of(R, VectorFilter.Eq(SubjectId, ok)).isRight)
    assertRefusedValueFree(
      VectorFilterDocuments.of(R, VectorFilter.Eq(SubjectId, long)),
      Vector(long)
    )
    // multi-byte: 683 three-byte characters are 2049 bytes
    assert(VectorFilterDocuments.of(R, VectorFilter.Eq(SubjectId, "€" * 683)).isLeft)
    assert(VectorFilterDocuments.of(R, VectorFilter.Eq(SubjectId, "€" * 682)).isRight)
  }

  private def assertRefusedValueFree(e: Either[StoreError, ?], values: Vector[String]): Unit = {
    assert(e.left.exists(_.isInstanceOf[StoreError.Refused]), e.toString)
    val m = e.left.toOption.get.message
    assert(!values.exists(v => m.contains(v)), m)
  }
}

object S3VectorsFilterSuite {
  import MetadataField.*

  val IdA = "researcher:orcid:0000-0002-1825-0097"
  val IdB = "researcher:orcid:0000-0001-5109-3700"
  val IdC = "researcher:dblp:homepages-00-0001"
  val IdD = "researcher:dblp:homepages-00-0002"

  def ids(n: Int): Vector[String] = (1 to n).toVector.map(i => s"researcher:dblp:homepages-cap-$i")

  /** SemanticContextAgent.wanted (rag, lines 98-128): researcher-profile-v1. */
  def researcherShape(ids: Vector[String], start: Int, end: Int): VectorFilter =
    VectorFilter.And(
      Vector(
        VectorFilter.In(ResearcherIds, ids),
        VectorFilter.Range(WindowStart, start.toLong, start.toLong),
        VectorFilter.Range(WindowEnd, end.toLong, end.toLong)
      )
    )

  /** SemanticContextAgent.wanted: paper-abstract-v1 with named ids (`and` of one is the filter). */
  def paperShape(ids: Vector[String]): VectorFilter = VectorFilter.In(ResearcherIds, ids)

  def str(s: String): Document = Document.fromString(s)
  def num(n: Long): Document = Document.fromNumber(n)
  def cmp(field: String, op: String, v: Document): Document =
    Document.fromMap(Map(field -> Document.fromMap(Map(op -> v).asJava)).asJava)
  def isEq(field: String, v: Document): Document = cmp(field, "$eq", v)
  def and(ds: Document*): Document =
    Document.fromMap(Map("$and" -> Document.fromList(ds.asJava)).asJava)
  def or(ds: Document*): Document =
    Document.fromMap(Map("$or" -> Document.fromList(ds.asJava)).asJava)

  /**
   * A test model of the documented S3 Vectors filter semantics (Q15, CONFIRMED): `$eq` on an array
   * is true when any element matches; `$in` on a string is membership; `$gte`/`$lte` compare
   * numbers; `$and`/`$or` combine. A missing field matches nothing (as `matches` assumes; P).
   */
  object Q15 {
    def eval(d: Document, md: VectorMetadata): Boolean = {
      val m = d.asMap().asScala
      require(m.size == 1, s"one operator or field per object: $d")
      val (k, v) = m.head
      k match {
        case "$and" => v.asList().asScala.forall(eval(_, md))
        case "$or" => v.asList().asScala.exists(eval(_, md))
        case field =>
          val ops = v.asMap().asScala
          require(ops.size == 1, s"one comparison per field object: $d")
          val (op, arg) = ops.head
          (op, md.values.get(field)) match {
            case (_, None) => false
            case ("$eq", Some(MetaValue.Str(s))) => s == arg.asString()
            case ("$eq", Some(MetaValue.StrList(xs))) => xs.contains(arg.asString())
            case ("$in", Some(MetaValue.Str(s))) =>
              arg.asList().asScala.exists(_.asString() == s)
            case ("$gte", Some(MetaValue.Num(n))) => n >= arg.asNumber().longValue()
            case ("$lte", Some(MetaValue.Num(n))) => n <= arg.asNumber().longValue()
            case other => throw new IllegalArgumentException(s"not modelled: $other")
          }
      }
    }
  }
}
