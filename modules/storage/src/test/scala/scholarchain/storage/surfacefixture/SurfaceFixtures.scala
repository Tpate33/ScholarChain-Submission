package scholarchain.storage.surfacefixture

import org.apache.hadoop.conf.Configuration
import org.apache.parquet.io.InputFile

import scholarchain.storage.parquet.ParquetReadBounds

/**
 * SE-P7-14 (CR-P7-16 m2): test-only classes, in a `scholarchain.storage` package so the surface
 * walker of `OpeningLoadSurfaceSuite` descends into them, each hiding a forbidden type where an
 * erased-type walk does not see it. Never used except by that walker; nothing here is called.
 */
final case class InOption(value: Option[ParquetReadBounds])

final case class InVector(value: Vector[InputFile])

final case class InMap(value: Map[String, Vector[Configuration]])

final case class InFunction(value: String => Either[String, InputFile])

/** A forbidden type only in a public method's return type. */
final class InMethodReturn {
  def open(path: String): Either[String, Option[InputFile]] = Left(path)
}

/** A forbidden type only in a public method's parameter (inside a function type). */
final class InMethodParameter {
  def use(f: Vector[ParquetReadBounds] => Unit): Unit = ()
}

/** Reached only through another storage class's field (the walk is recursive). */
final case class Nested(inner: InOption)

/** Wrappers of allowed types only: the walker reports nothing. */
final case class Clean(value: Option[Vector[Map[String, Int => String]]]) {
  def get(k: String): Either[String, Option[Vector[String]]] = Left(k)
}
