package scholarchain.spark.testkit

import java.nio.file.Path

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, count, lit}
import org.apache.spark.sql.types.StructType

import scholarchain.spark.input.RunTree

/**
 * Logical reproducibility of two published datasets (carried forward from sparkIT run 04: equal run
 * IDs, different Parquet bytes, row equivalence not established). Two sets of Parquet parts are
 * logically equal when the StructType read back from the files is equal (field names, order, types
 * and nullability at every level) and the complete row multisets are equal, duplicates included:
 * each side is grouped by every column with its multiplicity, and the grouped rows that are not on
 * the other side are the exact difference (a row whose multiplicity differs appears on both sides
 * with its two counts). Comparison runs in Spark; only the difference (at most `limit` rows per
 * side) and the counts reach the driver.
 *
 * Byte comparison is reported separately ([[Report.sameBytes]]) as a diagnosis only; it is never
 * the reproducibility criterion.
 */
object LogicalReproducibility {

  val MultiplicityColumn = "__multiplicity"

  final case class Report(
      schemaA: StructType,
      schemaB: StructType,
      rowsA: Long,
      rowsB: Long,
      onlyInA: Vector[String],
      onlyInB: Vector[String],
      sameBytes: Boolean
  ) {
    def sameSchema: Boolean = schemaA == schemaB
    def equal: Boolean = sameSchema && rowsA == rowsB && onlyInA.isEmpty && onlyInB.isEmpty

    /** The exact difference, for a failure message. */
    def describe: String =
      if (equal) s"logically equal: $rowsA rows, schema ${schemaA.simpleString}"
      else
        Vector(
          if (sameSchema) ""
          else s"schema A ${schemaA.simpleString} != schema B ${schemaB.simpleString}",
          s"rows A $rowsA, rows B $rowsB",
          s"only in A (row, multiplicity): ${onlyInA.mkString("; ")}",
          s"only in B (row, multiplicity): ${onlyInB.mkString("; ")}"
        ).filter(_.nonEmpty).mkString("\n")
  }

  /** Every row of `df` with its multiplicity (grouped by every column). */
  def multiset(df: DataFrame): DataFrame =
    df.groupBy(df.columns.toSeq.map(col)*).agg(count(lit(1)).as(MultiplicityColumn))

  private def bytesOf(parts: Vector[Path]): Vector[String] =
    parts.map(p =>
      RunTree.observe(p.getParent, p.getFileName.toString).map(_.sha256).fold(identity, identity)
    )

  def compareFrames(
      a: DataFrame,
      b: DataFrame,
      limit: Int = 20
  ): (Long, Long, Vector[String], Vector[String]) = {
    val ma = multiset(a)
    val mb = multiset(b)
    def show(df: DataFrame) = df.limit(limit).collect().toVector.map(_.toString)
    (a.count(), b.count(), show(ma.except(mb)), show(mb.except(ma)))
  }

  private def report(a: Vector[Path], b: Vector[Path], fa: DataFrame, fb: DataFrame, limit: Int) = {
    val (na, nb, onlyA, onlyB) =
      if (fa.schema == fb.schema) compareFrames(fa, fb, limit)
      else (fa.count(), fb.count(), Vector.empty, Vector.empty)
    Report(fa.schema, fb.schema, na, nb, onlyA, onlyB, bytesOf(a) == bytesOf(b))
  }

  /** Compares the Parquet parts `a` and `b` (each read back with the schema of its files). */
  def compare(spark: SparkSession, a: Vector[Path], b: Vector[Path], limit: Int = 20): Report =
    report(
      a,
      b,
      spark.read.parquet(a.map(_.toUri.toString)*),
      spark.read.parquet(b.map(_.toUri.toString)*),
      limit
    )

  /**
   * The rows of CSV parts (SP-P6-03 step 2B, the Neptune bulk-load files): every part's first line
   * is its header (the column names; all columns read as strings, no inference), quoted fields may
   * hold LF, and a doubled double quote is a quote (the `NeptuneCsv` quoting rule).
   */
  def readCsv(spark: SparkSession, parts: Vector[Path]): DataFrame =
    spark.read
      .option("header", "true")
      .option("multiLine", "true")
      .option("quote", "\"")
      .option("escape", "\"")
      .option("inferSchema", "false")
      .csv(parts.map(_.toUri.toString)*)

  /**
   * Compares the CSV parts `a` and `b` as row multisets (header columns equal, every row with its
   * multiplicity), as [[compare]] does for Parquet; bytes again a diagnosis only.
   */
  def compareCsv(spark: SparkSession, a: Vector[Path], b: Vector[Path], limit: Int = 20): Report =
    report(a, b, readCsv(spark, a), readCsv(spark, b), limit)
}
